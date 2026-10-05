// Command chat is a terminal chat client for the OpenAI and Anthropic APIs.
// API keys are read from OPENAI_API_KEY and ANTHROPIC_API_KEY.
package main

import (
	"cmp"
	"flag"
	"fmt"
	"os"
	"slices"

	tea "charm.land/bubbletea/v2"
)

// version is set at release time: -ldflags "-X main.version=1.0.0" (the tag without "cli-v").
var version = "dev"

func main() {
	providerName := flag.String("provider", "openai", `provider to start with: "anthropic" or "openai" (Ctrl+L switches in the app)`)
	anthropicModel := flag.String("anthropic-model", "claude-sonnet-5-5", "Anthropic model ID")
	openaiModel := flag.String("openai-model", "gpt-5.6-luna", "OpenAI model ID")
	effort := flag.String("effort", "medium", "reasoning effort for both providers: low, medium, high, xhigh or max")
	anthropicEffort := flag.String("anthropic-effort", "", "reasoning effort for Anthropic (default: -effort)")
	openaiEffort := flag.String("openai-effort", "", "reasoning effort for OpenAI (default: -effort)")
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Println("chat", version)
		return
	}

	// Each provider keeps its own effort, which starts as -effort unless
	// its own flag sets it.
	providerEfforts := []string{cmp.Or(*anthropicEffort, *effort), cmp.Or(*openaiEffort, *effort)}
	for _, e := range providerEfforts {
		if !slices.Contains(efforts, e) {
			fmt.Fprintf(os.Stderr, "unknown effort %q: use low, medium, high, xhigh or max\n", e)
			os.Exit(2)
		}
	}

	providers := []Provider{
		newAnthropicProvider(*anthropicModel, providerEfforts[0]),
		newOpenAIProvider(*openaiModel, providerEfforts[1]),
	}
	active := -1
	switch *providerName {
	case "anthropic":
		active = 0
	case "openai":
		active = 1
	}
	if active < 0 {
		fmt.Fprintf(os.Stderr, "unknown provider %q: use \"anthropic\" or \"openai\"\n", *providerName)
		os.Exit(2)
	}

	m := newModel(providers, active)
	m.effort = providerEfforts
	// The filter merges bursts of mouse events, so a fast wheel spin can't
	// queue up ahead of key presses.
	if _, err := tea.NewProgram(m, tea.WithFilter(newInputFilter().filter)).Run(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}
