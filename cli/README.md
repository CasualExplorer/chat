# chat

A terminal chat client for the OpenAI and Anthropic APIs, built with Bubble Tea and Glamour.

## Setup

Set your API keys in the environment:

```sh
OPENAI_API_KEY=...
ANTHROPIC_API_KEY=...
```

Build and run (Go 1.27+):

```sh
go build -ldflags "-s -w" -o chat.exe .   # -s -w strips debug info for a smaller binary
./chat.exe                          # starts on OpenAI (gpt-5.6-luna)
./chat.exe -provider openai         # starts on OpenAI (gpt-6-astra)
./chat.exe -anthropic-model claude-sonnet-5-5 -openai-model gpt-6.1-sol
./chat.exe -effort high             # reasoning effort for both: low, medium (default), high, xhigh, max
./chat.exe -anthropic-effort max -openai-effort high   # or one per provider
```

Each provider keeps its own reasoning effort. "Select Reasoning Effort" in the command palette changes the active provider's, and switching provider brings back the other's.

## Keys

Tab moves focus between the input and the conversation.

| Key | Action |
|---|---|
| Ctrl+P | Command palette |
| Ctrl+L | Switch model or provider (the conversation carries over). Lists the models each API serves from Claude 4.6 and GPT-5.6 on. A reasoning effort the model doesn't support is lowered to the nearest level it does, for Claude models |
| Ctrl+N | New chat |
| Ctrl+G | Show all keys |
| Esc, twice | Stop the reply that is streaming (the first press asks for a second within 2 seconds). Esc also closes a dialog |
| Ctrl+End | Jump to the newest message and follow it |
| Ctrl+C | Quit (asks first; press twice to skip) |

In the input:

| Key | Action |
|---|---|
| Enter | Send |
| Shift+Enter / Alt+Enter / Ctrl+J | New line |
| Up / Down | Step through messages sent this session |
| Ctrl+O | Edit the message in `$VISUAL` or `$EDITOR` (Notepad on Windows, `vi` elsewhere) |
| PgUp / PgDn | Scroll the conversation |
| Shift+arrows | Select text (Ctrl+Shift+arrows by word) |
| Ctrl+Shift+A / C / X | Select all / copy / cut the selected text |
| Ctrl+Shift+V | Paste the clipboard's text, for terminals whose own paste doesn't reach the app (Ctrl+V works too) |

In the conversation:

| Key | Action |
|---|---|
| ↑ / ↓ or k / j | Scroll a line. When the selected message leaves the view, the selection moves to one still in it |
| Shift+↑ / Shift+↓ or K / J | Select the previous / next message |
| b / f, PgUp / PgDn | Page up / down, selecting the message at that edge |
| u / d | Half page up / down, likewise |
| g / G, Home / End | Top / bottom |
| Space / Enter | Expand or collapse the selected reply's thinking |
| c / y | Copy the selection, or else the selected message's markdown |
| Esc | Clear the selection, or go back to the input |

The mouse wheel scrolls. Drag to select text, double-click for a word, triple-click for a line; the selection is copied when you let go (through the terminal with OSC 52 and to the system clipboard). Click a reply's thinking box to expand it. In the input, click to move the cursor and drag to select (then Ctrl+Shift+C / X). Hold Shift while dragging to use the terminal's own selection instead.

## Look

The interface follows [Crush](https://github.com/charmbracelet/crush): its dark Charmtone palette, message styling, spinner, `:::` input prompt and layout. Terminals at least 120×30 get a sidebar with the working directory, git branch, model and providers; smaller ones get a one-line header instead. Once a reply arrives, the sidebar shows how full the context window is ("12% (24.5K)", with a warning past 80%; just the token count for OpenAI, whose API doesn't report context windows) and each reply's footer shows the tokens it sent and generated. The conversation is a lazily rendered list of messages (`list/`, `chat.go`, `messages.go`): finished messages are rendered once and cached, and a streaming reply only re-renders the paragraph still arriving (`markdown_stream.go`). Long thinking summaries show their last lines until expanded. Code blocks are highlighted in the palette's exact colours (`xchroma/`). Styles live in one `Styles` value built from a palette (`styles.go`), and dialogs (`dialog.go`) draw over the UI. Scrolling redraws from a cache of recent frames (`framecache.go`), and bursts of wheel events are merged into one scroll.

Crush features left out on purpose: saved sessions, themes, `/` and `@` completions, attachments, desktop notifications, the details panel and Toggle Sidebar command for small terminals, scrolling the sidebar, Ctrl+Z to suspend (Windows has no job control), the terminal progress bar and a window title with the directory, the mouse, transparency and thinking toggles, and setting API keys in the app.

The adapted code (`list/`, `xchroma/`, `styles.go`, `spinner.go`, `logo.go`, `layout.go`, `chat.go`, `messages.go`, `markdown_stream.go`, `dialog.go`, `keys.go`, `history.go`, `framecache.go`) is Copyright 2025-2026 Charmbracelet, Inc., used under [FSL-1.1-MIT](https://github.com/charmbracelet/crush/blob/main/LICENSE.md), which permits use other than in a competing commercial product. The logo spells this app's name rather than Crush's, which is Charm's trademark.

While a model reasons, a summary of its reasoning streams in a muted box above the reply (Anthropic `thinking.display: "summarized"`, OpenAI `reasoning.summary: "auto"`). Summaries are display-only: they're not counted as reply text.

A reply that fails or is stopped stays in the chat, ending in an ERROR banner with the reason, or "Canceled". Any text that arrived is kept and sent with later turns, so the model sees what it said. If none arrived, the message is left out of later requests and put back in the input to resend.

Conversations are kept in memory only. Nothing is saved to disk, and OpenAI requests use `store: false`.

Long conversations are compacted server-side: Anthropic summarizes earlier turns once the input passes 150k tokens, and OpenAI does the same past 200k. A note under the reply says when this happened. Anthropic requests use automatic prompt caching, and OpenAI requests share one `prompt_cache_key` per session, so earlier turns are read from the cache.
