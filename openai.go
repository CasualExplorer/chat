package main

import (
	"cmp"
	"context"
	"crypto/rand"
	"errors"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/openai/openai-go/v3"
	"github.com/openai/openai-go/v3/responses"
	"github.com/openai/openai-go/v3/shared"
)

// openaiCompactThreshold is the input size, in tokens, at which the API
// compacts the conversation server-side.
const openaiCompactThreshold = 200_000

type openAIProvider struct {
	client   openai.Client
	model    string
	effort   shared.ReasoningEffort
	cacheKey string // routes this session's requests to the same prompt cache
}

// newOpenAIProvider reads OPENAI_API_KEY from the environment.
func newOpenAIProvider(model, effort string) *openAIProvider {
	return &openAIProvider{
		client:   openai.NewClient(),
		model:    model,
		effort:   shared.ReasoningEffort(effort),
		cacheKey: "chat-" + rand.Text(),
	}
}

func (p *openAIProvider) Name() string  { return "OpenAI" }
func (p *openAIProvider) Model() string { return p.model }

func (p *openAIProvider) SetModel(model string)   { p.model = model }
func (p *openAIProvider) SetEffort(effort string) { p.effort = shared.ReasoningEffort(effort) }

// ListModels lists the chat models from GPT-5.6 on, which all reason. The
// API lists every model the key can use, including embedding, audio and
// image models, and doesn't report context windows or effort levels.
func (p *openAIProvider) ListModels(ctx context.Context) ([]ModelInfo, error) {
	var found []openai.Model
	pager := p.client.Models.ListAutoPaging(ctx)
	for pager.Next() {
		if m := pager.Current(); isOpenAIChatModel(m, time.Now()) {
			found = append(found, m)
		}
	}
	if err := pager.Err(); err != nil {
		return nil, err
	}
	slices.SortStableFunc(found, func(a, b openai.Model) int { return cmp.Compare(b.Created, a.Created) })
	models := make([]ModelInfo, len(found))
	for i, m := range found {
		models[i] = ModelInfo{ID: m.ID}
	}
	return models, nil
}

var (
	// The version of a GPT model: "gpt-5.6-sol" is 5.6, "gpt-6-astra" 6.0.
	openAIChatModel = regexp.MustCompile(`^gpt-(\d+)(?:\.(\d+))?(?:-|$)`)
	// Dated snapshots of a model, which the undated alias already covers.
	openAISnapshot = regexp.MustCompile(`-\d{4}(-\d{2}-\d{2})?$`)
	openAINotChat  = []string{"audio", "realtime", "transcribe", "tts", "image", "search", "instruct", "embedding"}
)

func isOpenAIChatModel(m openai.Model, now time.Time) bool {
	v := openAIChatModel.FindStringSubmatch(m.ID)
	if v == nil || !versionAtLeast(v[1], v[2], 5, 6) || openAISnapshot.MatchString(m.ID) {
		return false
	}
	if !m.ShutdownDate.IsZero() && !m.ShutdownDate.After(now) {
		return false
	}
	for _, s := range openAINotChat {
		if strings.Contains(m.ID, s) {
			return false
		}
	}
	return true
}

func (p *openAIProvider) Stream(ctx context.Context, history []Turn) <-chan StreamEvent {
	out := make(chan StreamEvent)
	model, effort := p.model, p.effort
	go func() {
		defer close(out)

		input := openaiInput(history)

		// The conversation lives in this process, so nothing is stored
		// server-side. Encrypted reasoning items are requested so they can be
		// passed back on the next turn, as OpenAI recommends for stateless use.
		stream := p.client.Responses.NewStreaming(ctx, responses.ResponseNewParams{
			Model:          model,
			Input:          responses.ResponseNewParamsInputUnion{OfInputItemList: input},
			Store:          openai.Bool(false),
			Include:        []responses.ResponseIncludable{responses.ResponseIncludableReasoningEncryptedContent},
			Reasoning:      shared.ReasoningParam{Effort: effort, Summary: shared.ReasoningSummaryAuto},
			PromptCacheKey: openai.String(p.cacheKey),
			// Past the threshold the API compacts the conversation into a
			// compaction item, which replayItems keeps for later turns.
			ContextManagement: []responses.ResponseNewParamsContextManagement{
				{Type: "compaction", CompactThreshold: openai.Int(openaiCompactThreshold)},
			},
		})
		defer stream.Close()

		var final *responses.Response
		var note string
		thought := false // a reasoning summary has been streamed
		for stream.Next() {
			event := stream.Current()
			switch event.Type {
			case "response.output_text.delta", "response.refusal.delta":
				send(ctx, out, StreamEvent{Delta: event.Delta})
			case "response.reasoning_summary_part.added":
				if thought {
					send(ctx, out, StreamEvent{Thinking: "\n\n"})
				}
			case "response.reasoning_summary_text.delta":
				thought = true
				send(ctx, out, StreamEvent{Thinking: event.Delta})
			case "response.completed":
				final = &event.Response
			case "response.incomplete":
				final = &event.Response
				note = "Reply cut off"
				if reason := event.Response.IncompleteDetails.Reason; reason != "" {
					note += ": " + reason
				}
				note += "."
			case "response.failed":
				msg := "OpenAI response failed"
				if e := event.Response.Error; e.Message != "" {
					msg += ": " + e.Message
				} else if e.Code != "" {
					msg += ": " + string(e.Code)
				}
				send(ctx, out, StreamEvent{Err: errors.New(msg)})
				return
			case "error":
				send(ctx, out, StreamEvent{Err: errors.New("OpenAI stream error: " + event.Message)})
				return
			}
		}
		if err := stream.Err(); err != nil {
			send(ctx, out, StreamEvent{Err: err})
			return
		}
		if final == nil {
			send(ctx, out, StreamEvent{Err: errors.New("OpenAI stream ended without a completed response")})
			return
		}

		for _, item := range final.Output {
			if item.Type == "compaction" {
				note = strings.TrimSpace("Earlier messages were summarized to fit the context window. " + note)
			}
		}

		send(ctx, out, StreamEvent{
			Done:  &Turn{Role: RoleAssistant, Text: replyText(final), openaiItems: replayItems(final)},
			Note:  note,
			Usage: &Usage{Input: final.Usage.InputTokens, Output: final.Usage.OutputTokens, Context: final.Usage.TotalTokens},
		})
	}()
	return out
}

// openaiInput converts the conversation into Responses API input items.
// Assistant turns from the other provider that have no text (e.g. a reply cut
// off while thinking) are skipped, since there is nothing to show the model.
// Items before the latest compaction item are dropped: it already carries
// them, and OpenAI recommends pruning them to keep requests small.
func openaiInput(history []Turn) responses.ResponseInputParam {
	input := make(responses.ResponseInputParam, 0, len(history))
	for _, turn := range history {
		switch {
		case turn.openaiItems != nil:
			input = append(input, turn.openaiItems...)
		case turn.Role == RoleUser:
			input = append(input, responses.ResponseInputItemParamOfMessage(turn.Text, responses.EasyInputMessageRoleUser))
		case turn.Text != "":
			input = append(input, responses.ResponseInputItemParamOfMessage(turn.Text, responses.EasyInputMessageRoleAssistant))
		}
	}
	for i := len(input) - 1; i >= 0; i-- {
		if input[i].OfCompaction != nil {
			return input[i:]
		}
	}
	return input
}

// replyText returns the reply's visible text. Unlike Response.OutputText it
// includes refusals, which are streamed to the screen like ordinary text.
func replyText(response *responses.Response) string {
	var text strings.Builder
	for _, item := range response.Output {
		if item.Type != "message" {
			continue
		}
		for _, content := range item.Content {
			switch content.Type {
			case "output_text":
				text.WriteString(content.Text)
			case "refusal":
				text.WriteString(content.Refusal)
			}
		}
	}
	return text.String()
}

// replayItems converts a response's output into input items for later turns,
// keeping reasoning and compaction items (with their encrypted content)
// alongside the message.
func replayItems(response *responses.Response) []responses.ResponseInputItemUnionParam {
	var items []responses.ResponseInputItemUnionParam
	for _, item := range response.Output {
		switch item.Type {
		case "message":
			message := item.AsMessage().ToParam()
			items = append(items, responses.ResponseInputItemUnionParam{OfOutputMessage: &message})
		case "reasoning":
			reasoning := item.AsReasoning().ToParam()
			items = append(items, responses.ResponseInputItemUnionParam{OfReasoning: &reasoning})
		case "compaction":
			compaction := item.AsCompaction()
			items = append(items, responses.ResponseInputItemUnionParam{OfCompaction: &responses.ResponseCompactionItemParam{
				ID:               openai.String(compaction.ID),
				EncryptedContent: compaction.EncryptedContent,
			}})
		}
	}
	return items
}
