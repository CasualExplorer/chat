package main

import (
	"context"
	"encoding/json"
	"slices"
	"testing"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/openai/openai-go/v3"
	"github.com/openai/openai-go/v3/responses"
)

func TestReplyTextIncludesRefusal(t *testing.T) {
	var response responses.Response
	err := json.Unmarshal([]byte(`{"output": [
		{"type": "reasoning", "id": "rs_1", "summary": []},
		{"type": "message", "id": "msg_1", "role": "assistant", "status": "completed", "content": [
			{"type": "refusal", "refusal": "I can't help with that."}
		]}
	]}`), &response)
	if err != nil {
		t.Fatal(err)
	}
	if got := replyText(&response); got != "I can't help with that." {
		t.Fatalf("replyText = %q", got)
	}
}

func TestEmptyAssistantTurnIsSkipped(t *testing.T) {
	history := []Turn{
		{Role: RoleUser, Text: "one"},
		{Role: RoleAssistant}, // e.g. a reply cut off before any text
		{Role: RoleUser, Text: "two"},
	}
	if got := anthropicMessages(history); len(got) != 2 {
		t.Fatalf("anthropicMessages sent %d messages, want 2: %+v", len(got), got)
	}
	if got := openaiInput(history); len(got) != 2 {
		t.Fatalf("openaiInput sent %d items, want 2: %+v", len(got), got)
	}
}

func TestReplayParamDropsDeclinedModelsBlocksBeforeFallback(t *testing.T) {
	var message anthropic.BetaMessage
	err := json.Unmarshal([]byte(`{"role": "assistant", "content": [
		{"type": "thinking", "thinking": "declined", "signature": "s1"},
		{"type": "text", "text": "Before. "},
		{"type": "fallback"},
		{"type": "thinking", "thinking": "fallback model", "signature": "s2"},
		{"type": "text", "text": "After."}
	]}`), &message)
	if err != nil {
		t.Fatal(err)
	}

	var got []string
	for _, block := range replayParam(message).Content {
		switch {
		case block.OfText != nil:
			got = append(got, "text:"+block.OfText.Text)
		case block.OfThinking != nil:
			got = append(got, "thinking:"+block.OfThinking.Thinking)
		default:
			t.Fatalf("unexpected block kept: %+v", block)
		}
	}
	want := []string{"text:Before. ", "thinking:fallback model", "text:After."}
	if !slices.Equal(got, want) {
		t.Fatalf("replayed blocks = %q, want %q", got, want)
	}
}

func TestReplayParamKeepsCompactionBlock(t *testing.T) {
	var message anthropic.BetaMessage
	err := json.Unmarshal([]byte(`{"role": "assistant", "content": [
		{"type": "compaction", "content": "summary", "encrypted_content": "enc", "signature": "sig"},
		{"type": "text", "text": "Reply."}
	]}`), &message)
	if err != nil {
		t.Fatal(err)
	}
	content := replayParam(message).Content
	if len(content) != 2 || content[0].OfCompaction == nil || content[0].OfCompaction.Signature.Value != "sig" {
		t.Fatalf("compaction block not replayed verbatim: %+v", content)
	}
}

func TestOpenAIInputStartsAtLatestCompaction(t *testing.T) {
	var response responses.Response
	err := json.Unmarshal([]byte(`{"output": [
		{"type": "compaction", "id": "cmp_1", "encrypted_content": "enc"},
		{"type": "message", "id": "msg_1", "role": "assistant", "status": "completed", "content": [
			{"type": "output_text", "text": "Reply.", "annotations": []}
		]}
	]}`), &response)
	if err != nil {
		t.Fatal(err)
	}
	history := []Turn{
		{Role: RoleUser, Text: "old"},
		{Role: RoleAssistant, Text: "old reply"},
		{Role: RoleUser, Text: "long"},
		{Role: RoleAssistant, Text: "Reply.", openaiItems: replayItems(&response)},
		{Role: RoleUser, Text: "next"},
	}

	input := openaiInput(history)
	if len(input) != 3 {
		t.Fatalf("openaiInput sent %d items, want compaction, reply and next: %+v", len(input), input)
	}
	if c := input[0].OfCompaction; c == nil || c.EncryptedContent != "enc" || c.ID.Value != "cmp_1" {
		t.Fatalf("first item is not the compaction item: %+v", input[0])
	}
}

func TestOpenAIModelListKeepsChatModels(t *testing.T) {
	now := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	cases := map[string]bool{
		"gpt-6-astra":            true,
		"gpt-6.1-sol":            true,
		"gpt-5.6-sol":            true,
		"gpt-10":                 true,
		"gpt-5.5":                false, // before 5.6
		"gpt-5.4-mini":           false,
		"gpt-5":                  false,
		"o3":                     false,
		"gpt-4o-mini":            false, // no reasoning
		"gpt-3.5-turbo":          false,
		"gpt-6-sol-2026-09-01":   false, // a dated snapshot
		"gpt-4-0613":             false,
		"gpt-6-audio-preview":    false,
		"gpt-6-realtime":         false,
		"gpt-6-image":            false,
		"text-embedding-3-small": false,
		"dall-e-3":               false,
		"whisper-1":              false,
		"omni-moderation-latest": false,
	}
	for id, want := range cases {
		if got := isOpenAIChatModel(openai.Model{ID: id}, now); got != want {
			t.Errorf("isOpenAIChatModel(%q) = %v, want %v", id, got, want)
		}
	}
	retired := openai.Model{ID: "gpt-5.6-terra", ShutdownDate: now.AddDate(0, -1, 0)}
	if isOpenAIChatModel(retired, now) {
		t.Error("a model past its shutdown date was kept")
	}
}

func TestAnthropicModelListStartsAt46(t *testing.T) {
	cases := map[string]bool{
		"claude-opus-4-6":            true,
		"claude-sonnet-4-6":          true,
		"claude-opus-5-5":            true,
		"claude-fable-5-1":           true,
		"claude-opus-5":              true,
		"claude-opus-4-10":           true,
		"claude-opus-4-6-20260205":   true,
		"claude-haiku-4-5-20251001":  false,
		"claude-opus-4-1-20250805":   false,
		"claude-opus-4-20250514":     false, // 4.0 with a date
		"claude-3-7-sonnet-20250219": false,
	}
	for id, want := range cases {
		if got := anthropicSupportedVersion(id); got != want {
			t.Errorf("anthropicSupportedVersion(%q) = %v, want %v", id, got, want)
		}
	}
}

func TestAnthropicContextComesFromLastSamplingIteration(t *testing.T) {
	var u anthropic.BetaUsage
	err := json.Unmarshal([]byte(`{
		"input_tokens": 160000, "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0, "output_tokens": 900,
		"iterations": [
			{"type": "compaction", "input_tokens": 155000, "output_tokens": 3000},
			{"type": "message", "input_tokens": 4000, "cache_read_input_tokens": 1000, "cache_creation_input_tokens": 0, "output_tokens": 900}
		]
	}`), &u)
	if err != nil {
		t.Fatal(err)
	}
	got := anthropicUsage(u)
	if got.Input != 160000 || got.Output != 900 || got.Context != 5900 {
		t.Fatalf("usage = %+v", got)
	}

	u.Iterations = nil
	if got := anthropicUsage(u); got.Context != 160900 {
		t.Fatalf("without iterations, context = %d", got.Context)
	}
}

func TestCoalesceMergesTextAndKeepsOrder(t *testing.T) {
	in := make(chan StreamEvent, 8)
	for _, ev := range []StreamEvent{
		{Thinking: "a"}, {Thinking: "b"},
		{Delta: "c"}, {Delta: "d"},
		{Thinking: "e"},
		{Done: &Turn{Text: "cd"}},
	} {
		in <- ev
	}
	close(in)

	var got []string
	for ev := range coalesce(context.Background(), in, time.Hour) {
		switch {
		case ev.Thinking != "":
			got = append(got, "thinking:"+ev.Thinking)
		case ev.Delta != "":
			got = append(got, "delta:"+ev.Delta)
		case ev.Done != nil:
			got = append(got, "done")
		}
	}
	want := []string{"thinking:ab", "delta:cd", "thinking:e", "done"}
	if !slices.Equal(got, want) {
		t.Fatalf("coalesce = %q, want %q", got, want)
	}
}

func TestCoalesceFlushesAfterWindow(t *testing.T) {
	in := make(chan StreamEvent)
	out := coalesce(context.Background(), in, time.Millisecond)
	in <- StreamEvent{Delta: "x"}
	select {
	case ev := <-out:
		if ev.Delta != "x" {
			t.Fatalf("got %+v", ev)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("pending text not sent once the window passed")
	}
	close(in)
	if _, ok := <-out; ok {
		t.Fatal("out not closed after in")
	}
}

func TestCoalesceStopsDeliveringOnceCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	in := make(chan StreamEvent)
	out := coalesce(ctx, in, time.Hour)
	cancel()
	in <- StreamEvent{Err: context.Canceled} // nobody reads it; must not block
	close(in)
	for ev := range out {
		if ev.Err != nil {
			// send may still win the race with ctx.Done; either is fine.
			continue
		}
		t.Fatalf("unexpected event %+v", ev)
	}
}
