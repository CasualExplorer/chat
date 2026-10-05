package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/openai/openai-go/v3"
	"github.com/openai/openai-go/v3/responses"
)

type Role int

const (
	RoleUser Role = iota
	RoleAssistant
)

// Turn is one message in the conversation. Assistant turns also keep the
// producing provider's native output so it can be replayed to that provider
// unchanged (Anthropic thinking blocks, OpenAI reasoning items); the other
// provider receives just Text.
type Turn struct {
	Role Role
	Text string

	anthropicMsg *anthropic.BetaMessageParam
	openaiItems  []responses.ResponseInputItemUnionParam
}

// StreamEvent is sent by a Provider while a reply streams. Exactly one of
// Delta, Thinking, Done or Err is set; the channel is closed after Done or Err.
type StreamEvent struct {
	Delta    string
	Thinking string // reasoning summary text, shown dimmed above the reply
	Done     *Turn
	Note     string // shown under a completed reply, e.g. when it was cut off
	Usage    *Usage // sent with Done
	Err      error
}

// Usage is the tokens a reply used.
type Usage struct {
	Input  int64 // sent, including any read from or written to the cache
	Output int64 // generated, including reasoning
	// Context is the size of the conversation the model saw, with the reply:
	// roughly how full its context window now is.
	Context int64
}

type Provider interface {
	Name() string
	Model() string
	// Stream sends the conversation (ending with the new user turn) and
	// streams the assistant's reply until it completes, fails, or ctx is
	// cancelled.
	Stream(ctx context.Context, history []Turn) <-chan StreamEvent
}

// efforts are the reasoning effort levels both providers accept, lowest
// first.
var efforts = []string{"low", "medium", "high", "xhigh", "max"}

// configurable providers can change model and reasoning effort between
// requests. Neither may be changed while a reply is streaming.
type configurable interface {
	SetModel(model string)
	SetEffort(effort string)
}

// effortReporter providers may send a different effort than the one set,
// when the model doesn't support it. Effort is the one the next request
// uses.
type effortReporter interface {
	Effort() string
}

// ModelInfo is a model a provider serves.
type ModelInfo struct {
	ID            string
	ContextWindow int64 // input tokens the model accepts; 0 if unknown
}

// modelLister providers can list the chat models their API serves, newest
// first.
type modelLister interface {
	ListModels(ctx context.Context) ([]ModelInfo, error)
}

// versionAtLeast reports whether the version major.minor, as matched from a
// model ID (minor may be empty), is at least wantMajor.wantMinor.
func versionAtLeast(major, minor string, wantMajor, wantMinor int) bool {
	maj, err := strconv.Atoi(major)
	if err != nil {
		return false
	}
	mnr, _ := strconv.Atoi(minor) // a missing minor is 0
	return maj > wantMajor || maj == wantMajor && mnr >= wantMinor
}

// send delivers ev unless the request has been cancelled and nobody is
// listening any more.
func send(ctx context.Context, out chan<- StreamEvent, ev StreamEvent) {
	select {
	case out <- ev:
	case <-ctx.Done():
	}
}

// streamDebounce is how long streamed text is gathered before the UI is
// told, as Crush does: one redraw per window rather than one per token.
const streamDebounce = 33 * time.Millisecond

// coalesce merges the Delta and Thinking events that arrive within window
// of each other into one, keeping their order. Done and Err are passed on
// at once, after any text gathered before them. Like send, it stops
// delivering once ctx is cancelled, and it closes out when in closes.
func coalesce(ctx context.Context, in <-chan StreamEvent, window time.Duration) <-chan StreamEvent {
	out := make(chan StreamEvent)
	go func() {
		defer close(out)
		var pending StreamEvent // only Delta or Thinking is set
		var timer <-chan time.Time
		flush := func() {
			if pending.Delta != "" || pending.Thinking != "" {
				send(ctx, out, pending)
			}
			pending, timer = StreamEvent{}, nil
		}
		for {
			select {
			case ev, ok := <-in:
				switch {
				case !ok:
					flush()
					return
				case ev.Delta != "" && pending.Thinking == "":
					pending.Delta += ev.Delta
				case ev.Thinking != "" && pending.Delta == "":
					pending.Thinking += ev.Thinking
				case ev.Delta != "" || ev.Thinking != "":
					flush() // the other kind of text: keep the order
					pending = ev
				default:
					flush()
					send(ctx, out, ev)
					continue
				}
				if timer == nil {
					timer = time.After(window)
				}
			case <-timer:
				flush()
			}
		}
	}()
	return out
}

// describeError turns an SDK error into a short message for the status line.
// It avoids Error() on API errors, which can include raw request diagnostics.
func describeError(err error) string {
	if errors.Is(err, context.Canceled) {
		return "Cancelled."
	}
	if oaErr, ok := errors.AsType[*openai.Error](err); ok {
		msg := fmt.Sprintf("OpenAI API error %d: %s", oaErr.StatusCode, oaErr.Message)
		if oaErr.StatusCode == 401 {
			msg += " (check OPENAI_API_KEY)"
		}
		return withRequestID(msg, oaErr.Response)
	}
	if anErr, ok := errors.AsType[*anthropic.Error](err); ok {
		msg := fmt.Sprintf("Anthropic API error %d (%s)", anErr.StatusCode, anErr.Type())
		if detail := anthropicErrorMessage(anErr); detail != "" {
			msg += ": " + detail
		}
		if anErr.StatusCode == 401 {
			msg += " (check ANTHROPIC_API_KEY)"
		}
		if anErr.RequestID != "" {
			msg += " [request " + anErr.RequestID + "]"
		}
		return msg
	}
	return err.Error()
}

// withRequestID appends the x-request-id header, which support asks for.
func withRequestID(msg string, resp *http.Response) string {
	if resp == nil {
		return msg
	}
	if id := resp.Header.Get("x-request-id"); id != "" {
		msg += " [request " + id + "]"
	}
	return msg
}
