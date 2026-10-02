package main

import (
	"context"
	"encoding/json"
	"errors"
	"regexp"
	"slices"
	"strings"
	"sync"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/shared/constant"
)

// anthropicMaxTokens is the most output a reply may use, before any lower
// limit the model reports.
const anthropicMaxTokens = 64000

type anthropicProvider struct {
	client anthropic.Client
	model  string
	effort anthropic.BetaOutputConfigEffort

	// What ListModels learned about each model, which runs while replies
	// may be streaming.
	mu     sync.Mutex
	limits map[string]anthropicLimits
}

// anthropicLimits is what a model accepts of the request Stream sends.
type anthropicLimits struct {
	maxTokens int64    // most output tokens; 0 if unknown
	efforts   []string // the effort levels it supports, lowest first
}

// newAnthropicProvider reads ANTHROPIC_API_KEY from the environment.
func newAnthropicProvider(model, effort string) *anthropicProvider {
	return &anthropicProvider{client: anthropic.NewClient(), model: model, effort: anthropic.BetaOutputConfigEffort(effort)}
}

func (p *anthropicProvider) Name() string  { return "Anthropic" }
func (p *anthropicProvider) Model() string { return p.model }

func (p *anthropicProvider) SetModel(model string) { p.model = model }
func (p *anthropicProvider) SetEffort(effort string) {
	p.effort = anthropic.BetaOutputConfigEffort(effort)
}

// ListModels lists the models from Claude 4.6 on that support what every
// request asks for: adaptive thinking, an effort level and compaction. It
// also records each one's output limit and effort levels for Stream, since
// not every model supports every level.
func (p *anthropicProvider) ListModels(ctx context.Context) ([]ModelInfo, error) {
	var models []ModelInfo
	limits := make(map[string]anthropicLimits)
	pager := p.client.Models.ListAutoPaging(ctx, anthropic.ModelListParams{Limit: anthropic.Int(100)})
	for pager.Next() {
		m := pager.Current()
		if !anthropicSupportedVersion(m.ID) || !anthropicChatModel(m.Capabilities) {
			continue
		}
		models = append(models, ModelInfo{ID: m.ID, ContextWindow: m.MaxInputTokens})
		limits[m.ID] = anthropicLimits{maxTokens: m.MaxTokens, efforts: anthropicEfforts(m.Capabilities.Effort)}
	}
	if err := pager.Err(); err != nil {
		return nil, err
	}
	p.mu.Lock()
	p.limits = limits
	p.mu.Unlock()
	return models, nil
}

// anthropicVersion matches the version in a model ID: "claude-opus-4-6" is
// 4.6, and "claude-opus-4-20250514" is 4.0 followed by a date.
var anthropicVersion = regexp.MustCompile(`^claude-[a-z]+-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?$`)

func anthropicSupportedVersion(id string) bool {
	v := anthropicVersion.FindStringSubmatch(id)
	return v != nil && versionAtLeast(v[1], v[2], 4, 6)
}

func anthropicChatModel(c anthropic.ModelCapabilities) bool {
	return c.Thinking.Types.Adaptive.Supported && c.Effort.Supported &&
		c.ContextManagement.Compact20260112.Supported && len(anthropicEfforts(c.Effort)) > 0
}

// anthropicEfforts lists the effort levels a model supports, lowest first.
func anthropicEfforts(e anthropic.EffortCapability) []string {
	// In the order of efforts.
	supported := []bool{e.Low.Supported, e.Medium.Supported, e.High.Supported, e.Xhigh.Supported, e.Max.Supported}
	var levels []string
	for i, ok := range supported {
		if ok {
			levels = append(levels, efforts[i])
		}
	}
	return levels
}

// requestLimits is the max_tokens and effort to send for the current model:
// the defaults, lowered to what the model was listed as supporting. An
// unsupported effort falls back to the highest supported level below it, or
// else the lowest the model has.
func (p *anthropicProvider) requestLimits() (int64, anthropic.BetaOutputConfigEffort) {
	p.mu.Lock()
	lim, ok := p.limits[p.model]
	p.mu.Unlock()
	maxTokens, effort := int64(anthropicMaxTokens), p.effort
	if !ok {
		return maxTokens, effort
	}
	if lim.maxTokens > 0 {
		maxTokens = min(maxTokens, lim.maxTokens)
	}
	if len(lim.efforts) > 0 && !slices.Contains(lim.efforts, string(effort)) {
		want := slices.Index(efforts, string(effort))
		effort = anthropic.BetaOutputConfigEffort(lim.efforts[0])
		for _, e := range lim.efforts {
			if slices.Index(efforts, e) < want {
				effort = anthropic.BetaOutputConfigEffort(e)
			}
		}
	}
	return maxTokens, effort
}

// Effort is the reasoning effort the next request will use.
func (p *anthropicProvider) Effort() string {
	_, effort := p.requestLimits()
	return string(effort)
}

func (p *anthropicProvider) Stream(ctx context.Context, history []Turn) <-chan StreamEvent {
	out := make(chan StreamEvent)
	model := p.model
	maxTokens, effort := p.requestLimits()
	go func() {
		defer close(out)

		messages := anthropicMessages(history)

		stream := p.client.Beta.Messages.NewStreaming(ctx, anthropic.BetaMessageNewParams{
			Model:     model,
			MaxTokens: maxTokens,
			Messages:  messages,
			OutputConfig: anthropic.BetaOutputConfigParam{
				Effort: effort,
			},
			// Thinking is always on; "summarized" returns a readable summary
			// of it (the default, "omitted", streams empty thinking blocks).
			Thinking: anthropic.BetaThinkingConfigParamUnion{
				OfAdaptive: &anthropic.BetaThinkingConfigAdaptiveParam{Display: anthropic.BetaThinkingConfigAdaptiveDisplaySummarized},
			},
			// Cache the conversation so far; the breakpoint moves forward with
			// each turn, so earlier turns are read from the cache.
			CacheControl: anthropic.NewBetaCacheControlEphemeralParam(),
			// Once the input passes the default 150k-token trigger, the API
			// summarizes the earlier turns into a compaction block. Later
			// requests replay the reply as usual and the API drops what came
			// before the block.
			ContextManagement: anthropic.BetaContextManagementConfigParam{
				Edits: []anthropic.BetaContextManagementConfigEditUnionParam{
					{OfCompact20260112: &anthropic.BetaCompact20260112EditParam{}},
				},
			},
			// If a safety classifier declines the request, let the API retry it
			// on Anthropic's recommended fallback model instead of failing.
			Fallbacks: anthropic.BetaFallbacksParamUnion{OfDefault: constant.ValueOf[constant.Default]()},
			Betas: []anthropic.AnthropicBeta{
				anthropic.AnthropicBetaServerSideFallback2026_07_01,
				anthropic.AnthropicBetaCompact2026_01_12,
			},
		})
		defer stream.Close()

		var message anthropic.BetaMessage
		thought := false // a thinking summary has been streamed
		for stream.Next() {
			event := stream.Current()
			if err := message.Accumulate(event); err != nil {
				send(ctx, out, StreamEvent{Err: err})
				return
			}
			switch event := event.AsAny().(type) {
			case anthropic.BetaRawContentBlockStartEvent:
				if event.ContentBlock.Type == "thinking" && thought {
					send(ctx, out, StreamEvent{Thinking: "\n\n"})
				}
			case anthropic.BetaRawContentBlockDeltaEvent:
				switch delta := event.Delta.AsAny().(type) {
				case anthropic.BetaTextDelta:
					send(ctx, out, StreamEvent{Delta: delta.Text})
				case anthropic.BetaThinkingDelta:
					if delta.Thinking != "" {
						thought = true
						send(ctx, out, StreamEvent{Thinking: delta.Thinking})
					}
				}
			}
		}
		if err := stream.Err(); err != nil {
			send(ctx, out, StreamEvent{Err: err})
			return
		}

		var notes []string
		for _, block := range message.Content {
			if block.Type == "compaction" {
				notes = append(notes, "Earlier messages were summarized to fit the context window.")
			}
		}
		switch message.StopReason {
		case anthropic.BetaStopReasonRefusal:
			// Shown as-is in the status line, so it starts with the name.
			msg := "Claude declined this request"
			if category := string(message.StopDetails.Category); category != "" {
				msg += " (" + category + ")"
			}
			send(ctx, out, StreamEvent{Err: errors.New(msg)})
			return
		case anthropic.BetaStopReasonMaxTokens:
			notes = append(notes, "Reply cut off: reached the max_tokens limit.")
		case anthropic.BetaStopReasonModelContextWindowExceeded:
			notes = append(notes, "Reply cut off: the conversation filled the model's context window.")
		}

		replay := replayParam(message)
		usage := anthropicUsage(message.Usage)
		send(ctx, out, StreamEvent{
			Done:  &Turn{Role: RoleAssistant, Text: messageText(message), anthropicMsg: &replay},
			Note:  strings.Join(notes, " "),
			Usage: &usage,
		})
	}()
	return out
}

// anthropicUsage totals a reply's tokens. The context size comes from the
// last sampling iteration: after a compaction the top-level counts include
// the closed context, and a compaction iteration's own counts are only the
// cost of summarizing.
func anthropicUsage(u anthropic.BetaUsage) Usage {
	usage := Usage{
		Input:  u.InputTokens + u.CacheReadInputTokens + u.CacheCreationInputTokens,
		Output: u.OutputTokens,
	}
	usage.Context = usage.Input + usage.Output
	for i := len(u.Iterations) - 1; i >= 0; i-- {
		if it := u.Iterations[i]; it.Type == "message" || it.Type == "fallback_message" {
			usage.Context = it.InputTokens + it.CacheReadInputTokens + it.CacheCreationInputTokens + it.OutputTokens
			break
		}
	}
	return usage
}

// anthropicMessages converts the conversation into Messages API params.
// Assistant turns from the other provider that have no text (e.g. a reply cut
// off while reasoning) are skipped: the API rejects empty text blocks, and it
// merges the consecutive user turns that skipping leaves behind.
func anthropicMessages(history []Turn) []anthropic.BetaMessageParam {
	messages := make([]anthropic.BetaMessageParam, 0, len(history))
	for _, turn := range history {
		switch {
		case turn.anthropicMsg != nil:
			messages = append(messages, *turn.anthropicMsg)
		case turn.Role == RoleUser:
			messages = append(messages, anthropic.NewBetaUserMessage(anthropic.NewBetaTextBlock(turn.Text)))
		case turn.Text != "":
			messages = append(messages, anthropic.BetaMessageParam{
				Role:    anthropic.BetaMessageParamRoleAssistant,
				Content: []anthropic.BetaContentBlockParamUnion{anthropic.NewBetaTextBlock(turn.Text)},
			})
		}
	}
	return messages
}

func messageText(message anthropic.BetaMessage) string {
	var text string
	for _, block := range message.Content {
		if b, ok := block.AsAny().(anthropic.BetaTextBlock); ok {
			text += b.Text
		}
	}
	return text
}

// replayParam converts a reply into the form it is sent back in on later
// turns. Content is kept as-is (thinking blocks must be echoed unchanged)
// except after a mid-reply server-side fallback, where blocks the declined
// model produced before the fallback marker other than text must be dropped.
func replayParam(message anthropic.BetaMessage) anthropic.BetaMessageParam {
	param := message.ToParam()
	lastFallback := -1
	for i, block := range message.Content {
		if block.Type == "fallback" {
			lastFallback = i
		}
	}
	if lastFallback < 0 {
		return param
	}
	kept := make([]anthropic.BetaContentBlockParamUnion, 0, len(param.Content))
	for i, block := range param.Content {
		switch message.Content[i].Type {
		case "fallback":
			continue
		case "thinking", "redacted_thinking", "tool_use":
			if i < lastFallback {
				continue
			}
		}
		kept = append(kept, block)
	}
	param.Content = kept
	return param
}

func anthropicErrorMessage(err *anthropic.Error) string {
	var body struct {
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if json.Unmarshal([]byte(err.RawJSON()), &body) != nil {
		return ""
	}
	return body.Error.Message
}
