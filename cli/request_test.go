package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"

	"github.com/anthropics/anthropic-sdk-go"
	anthropicoption "github.com/anthropics/anthropic-sdk-go/option"
	"github.com/openai/openai-go/v3"
	openaioption "github.com/openai/openai-go/v3/option"
	"github.com/tidwall/gjson"
)

// captureServer records one request and fails it, so a provider's request can
// be inspected without a real reply.
func captureServer(t *testing.T) (*httptest.Server, *[]byte, *http.Header) {
	t.Helper()
	var body []byte
	var header http.Header
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ = io.ReadAll(r.Body)
		header = r.Header.Clone()
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("request-id", "req_test")   // Anthropic
		w.Header().Set("x-request-id", "req_test") // OpenAI
		w.WriteHeader(http.StatusBadRequest)
		io.WriteString(w, `{"type": "error", "error": {"type": "invalid_request_error", "message": "nope"}}`)
	}))
	t.Cleanup(server.Close)
	return server, &body, &header
}

func drain(events <-chan StreamEvent) (err error) {
	for ev := range events {
		if ev.Err != nil {
			err = ev.Err
		}
	}
	return err
}

func TestAnthropicRequest(t *testing.T) {
	server, body, header := captureServer(t)
	p := newAnthropicProvider("claude-test", "high")
	p.client = anthropic.NewClient(anthropicoption.WithBaseURL(server.URL), anthropicoption.WithAPIKey("k"), anthropicoption.WithMaxRetries(0))

	err := drain(p.Stream(context.Background(), []Turn{{Role: RoleUser, Text: "hi"}}))
	if err == nil {
		t.Fatal("expected the stub's error")
	}
	if got := describeError(err); got != "Anthropic API error 400 (invalid_request_error): nope [request req_test]" {
		t.Errorf("describeError = %q", got)
	}

	for path, want := range map[string]string{
		"cache_control.type":              "ephemeral",
		"context_management.edits.0.type": "compact_20260112",
		"output_config.effort":            "high",
		"thinking.type":                   "adaptive",
		"thinking.display":                "summarized",
		"fallbacks":                       "default",
		"messages.0.content.0.text":       "hi",
	} {
		if got := gjson.GetBytes(*body, path).String(); got != want {
			t.Errorf("%s = %q, want %q", path, got, want)
		}
	}
	if got := strings.Join(header.Values("anthropic-beta"), ","); got != "server-side-fallback-2026-07-01,compact-2026-01-12" {
		t.Errorf("anthropic-beta = %q", got)
	}
}

func TestOpenAIRequest(t *testing.T) {
	server, body, _ := captureServer(t)
	p := newOpenAIProvider("gpt-test", "low")
	p.client = openai.NewClient(openaioption.WithBaseURL(server.URL), openaioption.WithAPIKey("k"), openaioption.WithMaxRetries(0))

	err := drain(p.Stream(context.Background(), []Turn{{Role: RoleUser, Text: "hi"}}))
	if err == nil {
		t.Fatal("expected the stub's error")
	}
	if got := describeError(err); got != "OpenAI API error 400: nope [request req_test]" {
		t.Errorf("describeError = %q", got)
	}

	for path, want := range map[string]string{
		"store":                                  "false",
		"reasoning.effort":                       "low",
		"reasoning.summary":                      "auto",
		"prompt_cache_key":                       p.cacheKey,
		"context_management.0.type":              "compaction",
		"context_management.0.compact_threshold": "200000",
	} {
		if got := gjson.GetBytes(*body, path).String(); got != want {
			t.Errorf("%s = %q, want %q", path, got, want)
		}
	}
}

func TestAnthropicListModelsLimitsRequests(t *testing.T) {
	model := func(id string, maxTokens int, compact bool, efforts ...string) string {
		effort := map[string]any{"supported": len(efforts) > 0}
		for _, e := range []string{"low", "medium", "high", "xhigh", "max"} {
			effort[e] = map[string]bool{"supported": slices.Contains(efforts, e)}
		}
		m := map[string]any{
			"id": id, "type": "model", "display_name": id, "created_at": "2026-01-01T00:00:00Z",
			"max_input_tokens": 200000, "max_tokens": maxTokens,
			"capabilities": map[string]any{
				"thinking":           map[string]any{"supported": true, "types": map[string]any{"adaptive": map[string]bool{"supported": true}}},
				"effort":             effort,
				"context_management": map[string]any{"supported": compact, "compact_20260112": map[string]bool{"supported": compact}},
			},
		}
		b, _ := json.Marshal(m)
		return string(b)
	}
	var body []byte
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/v1/models" {
			io.WriteString(w, `{"has_more": false, "data": [`+
				model("claude-sonnet-4-6", 32000, true, "low", "medium", "high")+","+
				model("claude-opus-4-7", 8192, false)+","+ // no compaction
				model("claude-opus-4-1", 32000, true, "low", "medium", "high")+`]}`) // too old
			return
		}
		body, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusBadRequest)
		io.WriteString(w, `{"type": "error", "error": {"type": "invalid_request_error", "message": "nope"}}`)
	}))
	t.Cleanup(server.Close)

	p := newAnthropicProvider("claude-sonnet-4-6", "max")
	p.client = anthropic.NewClient(anthropicoption.WithBaseURL(server.URL), anthropicoption.WithAPIKey("k"), anthropicoption.WithMaxRetries(0))

	models, err := p.ListModels(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(models) != 1 || models[0].ID != "claude-sonnet-4-6" {
		t.Fatalf("ListModels = %+v, want only claude-sonnet-4-6", models)
	}
	if got := p.Effort(); got != "high" {
		t.Errorf("Effort() = %q, want max lowered to high", got)
	}

	drain(p.Stream(context.Background(), []Turn{{Role: RoleUser, Text: "hi"}}))
	if got := gjson.GetBytes(body, "max_tokens").Int(); got != 32000 {
		t.Errorf("max_tokens = %d, want the model's 32000", got)
	}
	if got := gjson.GetBytes(body, "output_config.effort").String(); got != "high" {
		t.Errorf("effort = %q, want max lowered to high", got)
	}

	// A model that wasn't listed gets the defaults.
	p.SetModel("claude-unlisted")
	drain(p.Stream(context.Background(), []Turn{{Role: RoleUser, Text: "hi"}}))
	if got := gjson.GetBytes(body, "max_tokens").Int(); got != anthropicMaxTokens {
		t.Errorf("max_tokens = %d, want %d", got, anthropicMaxTokens)
	}
	if got := gjson.GetBytes(body, "output_config.effort").String(); got != "max" {
		t.Errorf("effort = %q, want max", got)
	}
}

func TestAnthropicRequestLimitsEffortFallback(t *testing.T) {
	p := newAnthropicProvider("m", "low")
	p.limits = map[string]anthropicLimits{"m": {efforts: []string{"high", "max"}}}
	if _, effort := p.requestLimits(); effort != "high" {
		t.Errorf("low on a model from high up = %q, want its lowest, high", effort)
	}
	p.SetEffort("xhigh")
	if _, effort := p.requestLimits(); effort != "high" {
		t.Errorf("xhigh = %q, want the next lower supported, high", effort)
	}
}
