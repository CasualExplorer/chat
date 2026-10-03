# Behavior inventory: the Go terminal app

This is the reference for what the Android app must do. It was written from
the Go source at the repository root (`main.go`, `provider.go`,
`anthropic.go`, `openai.go`, `ui.go`, `messages.go`, `dialog.go`,
`layout.go`, `history.go`, `keys.go`, `markdown_stream.go`), the Go tests
(`request_test.go`, `provider_test.go`, `ui_test.go`,
`markdown_stream_test.go`), `README.md`, and the two SDK versions pinned in
`go.mod` (`anthropic-sdk-go v1.78.0` and `openai-go/v3 v3.68.0`).

> **What kind of app this is.** `chat` is a client for two LLM APIs, the
> **Anthropic Messages API** and the **OpenAI Responses API**. There is no
> chat server, socket, username or room. Each reply is one HTTPS request
> whose response is a stream of server-sent events (SSE). The "protocol" in
> CLAUDE.md therefore means these two request/response formats. They are
> covered in sections 3 and 4.

---

## 1. Startup and configuration

| Input | Default | Notes |
|---|---|---|
| `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` | none | Read by the SDKs. A missing key only fails when a request is sent (as a 401). |
| `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL` | the official API hosts | Honored implicitly by both SDKs (`client.go` in each). The app doesn't mention them. |
| `-provider` | `openai` | `anthropic` or `openai`. Anything else: message on stderr, exit code 2. |
| `-anthropic-model` | `claude-sonnet-5-5` | |
| `-openai-model` | `gpt-5.6-luna` | |
| `-effort` | `medium` | One of `low`, `medium`, `high`, `xhigh`, `max`. Seeds both providers. |
| `-anthropic-effort`, `-openai-effort` | `-effort` | Per-provider override. An unknown value exits with code 2. |

- Both providers are always built, in the fixed order `[Anthropic, OpenAI]`.
  One of them is *active*. The conversation is shared and carries over when
  the user switches provider.
- At startup the app lists each provider's models in the background, with a
  15 s timeout (`modelsTimeout`). A failure is silent, and the model picker
  falls back to a built-in list (section 7).
- **Persistence: none.** Conversations live in memory only. Nothing is
  written to disk, and OpenAI requests send `store: false`. Quitting loses
  the conversation. This is a deliberate choice, stated in the README.

## 2. Conversation model (`provider.go`)

- **Turn** = `{role: user|assistant, text, native replay payload}`. An
  assistant turn also keeps what its provider returned, in that provider's
  own format: the Anthropic message param, or the list of OpenAI input items.
  The same provider gets this payload back unchanged on later turns. The
  other provider gets only `text`.
- **StreamEvent**: exactly one of
  - `Delta` (reply text),
  - `Thinking` (reasoning-summary text),
  - `Done` (the final Turn, plus an optional `Note` and `Usage`),
  - `Err`.

  The stream closes after `Done` or `Err`.
- **Usage**:
  - `Input`: tokens sent, including tokens read from or written to the cache.
  - `Output`: tokens generated, including reasoning.
  - `Context`: how big the conversation the model saw is, including the reply.
- **Provider capabilities**:
  - Every provider has `Name`, `Model` and `Stream(ctx, history)`.
  - Optional: `SetModel`/`SetEffort` (never called while a reply streams),
    `Effort()` (the effort actually sent), and `ListModels`.
- **Effort levels**, lowest first: `low, medium, high, xhigh, max`.

## 3. Anthropic protocol (`anthropic.go`)

### Request

`POST {base}/v1/messages?beta=true`, streaming, through the SDK's **beta**
Messages API. Model listing uses the non-beta `GET /v1/models`.

| Field | Value |
|---|---|
| `model` | The active model. |
| `max_tokens` | `64000`, lowered to the model's `max_tokens` if listing reported one. |
| `messages` | The history, converted as described below. |
| `output_config.effort` | The effort, lowered to what the model supports (see below). |
| `thinking` | `{type: "adaptive", display: "summarized"}`. Thinking is always on. |
| `cache_control` | `{type: "ephemeral"}` at the top level (automatic prompt caching). |
| `context_management.edits` | `[{type: "compact_20260112"}]`. The server compacts the conversation once input passes its default of 150k tokens. |
| `fallbacks` | `"default"`. If a safety classifier declines, the server retries on the recommended fallback model. |
| `anthropic-beta` header | `server-side-fallback-2026-07-01,compact-2026-01-12` |
| Auth header | `x-api-key`, plus `anthropic-version: 2023-06-01` (both set by the SDK). |

**Converting the history.** For each turn, in order:
- If it has an Anthropic payload, send that payload verbatim.
- Otherwise, if it is a user turn, send a user text block.
- Otherwise, if it is an assistant turn with non-empty text, send an
  assistant text block.
- Otherwise skip it. This covers an empty assistant turn from the other
  provider, which the API would reject. The API merges the consecutive user
  turns that skipping leaves behind.

### Stream handling

- `content_block_start` of type `thinking`: if thinking was already
  streamed, emit `Thinking("\n\n")` first. This separates summary blocks.
- `text_delta` becomes `Delta`.
- `thinking_delta` with non-empty text becomes `Thinking`.
- Every event is accumulated into the full message. An accumulation error,
  or a stream error, becomes `Err`.

### After the stream ends

1. If the message contains a `compaction` block, add the note "Earlier
   messages were summarized to fit the context window."
2. Act on the stop reason:
   - `refusal`: emit `Err("Claude declined this request")`, with
     `" (<category>)"` appended when `stop_details.category` is set.
   - `max_tokens`: add the note "Reply cut off: reached the max_tokens limit."
   - `model_context_window_exceeded`: add the note "Reply cut off: the
     conversation filled the model's context window."
3. Emit `Done`:
   - `text` is the concatenation of the text blocks.
   - The notes are joined with spaces.
   - The replay payload is `replayParam(message)`: content is echoed
     unchanged, because thinking blocks must be. The one exception: if there
     is a `fallback` block, drop every `fallback` block, and drop the
     `thinking`, `redacted_thinking` and `tool_use` blocks that come before
     the *last* fallback marker. Compaction blocks are kept.
   - **Usage**:
     - `Input` = `input_tokens` + `cache_read_input_tokens` + `cache_creation_input_tokens`.
     - `Output` = `output_tokens`.
     - `Context` comes from the last entry in `usage.iterations` whose type
       is `message` or `fallback_message` (its input fields plus its
       output). If there is no such entry, `Context` = `Input` + `Output`.

### Listing models

`GET /v1/models`, auto-paged with `limit=100`. A model is kept if:
- its ID matches `^claude-[a-z]+-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?$` with a
  version of at least 4.6, and
- its capabilities include adaptive thinking, effort, and
  `context_management.compact_20260112`, with at least one effort level.

For each kept model the app records:
- `ContextWindow` = `max_input_tokens`,
- `max_tokens`,
- the supported effort levels.

**Effort downgrade.** When the model is listed and doesn't support the
chosen effort, the app sends the highest supported level *below* the chosen
one. If there is none, it sends the model's lowest level. The UI shows the
effort actually sent.

## 4. OpenAI protocol (`openai.go`)

### Request

`POST {base}/v1/responses`, streaming, with `Authorization: Bearer <key>`.

| Field | Value |
|---|---|
| `model` | The active model. |
| `input` | The history, converted as described below. |
| `store` | `false` |
| `include` | `["reasoning.encrypted_content"]` |
| `reasoning` | `{effort, summary: "auto"}` |
| `prompt_cache_key` | `"chat-" + rand.Text()`, created once per provider instance (that is, per app run) |
| `context_management` | `[{type: "compaction", compact_threshold: 200000}]` |

**Converting the history.** For each turn:
- If it has OpenAI items, send them.
- Otherwise, if it is a user turn, send an easy-input message with role
  user.
- Otherwise, if it is an assistant turn with non-empty text, send an
  easy-input message with role assistant.
- Otherwise skip it.

Then drop every item before the **latest** `compaction` item.

### Stream handling

| Event | Effect |
|---|---|
| `response.output_text.delta`, `response.refusal.delta` | `Delta`. Refusals are shown like ordinary text. |
| `response.reasoning_summary_part.added` | `Thinking("\n\n")` if a summary was already streamed. |
| `response.reasoning_summary_text.delta` | `Thinking` |
| `response.completed` | Becomes the final response. |
| `response.incomplete` | Becomes the final response. Note: "Reply cut off: <reason>." The reason is omitted if empty, and the period is always there. |
| `response.failed` | `Err("OpenAI response failed: <error.message or error.code>")` |
| `error` | `Err("OpenAI stream error: <message>")` |
| Stream ends without a final response | `Err("OpenAI stream ended without a completed response")` |

### After the stream ends

- If there is a `compaction` output item, prefix the note with "Earlier
  messages were summarized to fit the context window."
- `Done`:
  - `text` is the concatenation of `output_text` and `refusal` parts of the
    `message` items.
  - Replay items are the `message`, `reasoning` (with encrypted content) and
    `compaction` (`id`, `encrypted_content`) items.
  - Usage: `Input` = `input_tokens`, `Output` = `output_tokens`,
    `Context` = `total_tokens`.

### Listing models

`GET /v1/models`. A model is kept if:
- its ID matches `^gpt-(\d+)(?:\.(\d+))?(?:-|$)` with a version of at least 5.6,
- it isn't a dated snapshot (`-\d{4}(-\d{2}-\d{2})?$`),
- its shutdown date, if any, hasn't passed, and
- its ID contains none of `audio, realtime, transcribe, tts, image, search,
  instruct, embedding`.

The list is sorted by `created`, newest first. The API reports no context
window and no effort levels, so the UI shows only a token count.

## 5. Cross-cutting behavior

- **Coalescing (`coalesce`, 33 ms).** `Delta` events that arrive within one
  window are merged, and so are `Thinking` events. A switch between the two
  kinds flushes first, so order is preserved. `Done` and `Err` flush pending
  text and are passed on at once. After cancellation nothing more is
  delivered. Purpose: one redraw per window, not one per token.
- **Error text (`describeError`)**, shown in the reply's error banner:
  - Cancelled: `Cancelled.`
  - OpenAI API error: `OpenAI API error <status>: <message>`, plus
    ` (check OPENAI_API_KEY)` on 401, plus ` [request <x-request-id>]`.
  - Anthropic API error: `Anthropic API error <status> (<type>)`, plus
    `: <error.message from body>`, plus ` (check ANTHROPIC_API_KEY)` on 401,
    plus ` [request <id>]`.
  - Anything else: `err.Error()`.
- **Retries (from the SDKs, not the app's code).** Both SDKs default to
  **2 retries**. They retry:
  - connection errors,
  - HTTP 408, 409, 429 and ≥500,
  - and whatever `x-should-retry: true/false` says, which overrides the
    status.

  The delay honors `retry-after-ms` / `retry-after`. Otherwise it is
  `0.5 s × 2^n`, capped at 8 s, minus up to 25 % jitter. Retries only cover
  getting the response. Once a 200 stream is being read, a dropped
  connection is an `Err` and is not retried. The Android port currently has
  **no** retries (see audit).
- **Timeouts.** Model listing: 15 s. Requests: no app-level timeout; the
  user cancels.

## 6. Send → stream → finish lifecycle (`ui.go`, `messages.go`)

### Submit

1. The input is trimmed. If it is empty, or a reply is already streaming,
   nothing happens.
2. A user turn is appended to the history. The UI appends a user message and
   a *pending* assistant message, labeled with the provider and model at
   send time.
3. The chat jumps to the newest message and follows it.
4. The raw input is remembered (`sentInput`) and added to the prompt
   history. The input is cleared and any status is cleared.
5. A copy of the history is streamed, through `coalesce`.

### While streaming

- The pending reply shows a spinner, with elapsed time, until its first
  reply text arrives. The label is "Thinking" once thinking has started.
- Thinking text streams into a muted box above the reply.
- "Thought for X" is fixed when the first reply text, `Done` or a failure
  arrives.

### On Done

- The final Turn (with its replay payload) is appended to the history.
- The reply's final text replaces the streamed text.
- The note and usage are stored, and elapsed time is recorded.
- The global "last usage" is updated if the reply reported usage.

### On failure (`fail`)

- The reply stays in the chat:
  - **Cancelled**: shows "Canceled".
  - Otherwise: an **ERROR** banner with `describeError`.
- If any non-blank text had arrived, it is appended to the history as an
  assistant turn (text only), so the model sees what it said.
- If no text had arrived, the user turn is **removed from the history** and
  the sent text is **put back in the input**, but only if the input is empty.
- A stream that closes without `Done` or `Err` while the reply is still
  pending counts as cancelled.

### Cancelling

- Press Esc twice within **2 s** while streaming. The first press only arms
  the cancel, so a stray Esc can't lose a reply.
- Quitting also cancels.

### How a reply can look

- Reply text, then optionally a note, then the footer.
- "(no text in this reply)" when it completed with no text.
- "Canceled", or an ERROR banner. Failed replies have no footer.

## 7. Actions (command palette and shortcuts)

The command palette (Ctrl+P) has a fuzzy filter and these entries:
- Switch Model
- Select Reasoning Effort
- New Chat
- Open External Editor
- Copy Last Reply ("There is no reply to copy yet." when there is none)
- Toggle Help
- Quit

**Switch Model** (also Ctrl+L):
- Lists each provider in order. For each one: its current model first, then
  the models it listed, or, if listing failed or hasn't finished, the
  built-in list.
  - Built-in list for Anthropic: `claude-opus-5-5`, `claude-sonnet-5-5`.
  - Built-in list for OpenAI: `gpt-6-astra`, `gpt-6.1-sol`.
- The active model is pre-selected and labeled "current · Provider".
- Choosing a model sets it on that provider and makes that provider active.
  The conversation carries over.
- Last usage is cleared if the model or the provider changed.

**Select Reasoning Effort**:
- Picks the *active* provider's effort, shown as Low, Medium, High,
  X-High or Max. The current one is marked.
- Each provider remembers its own effort.

**New Chat** (Ctrl+N): clears the history, the messages and the usage, and
returns to the start screen.

**Guards while streaming.** These actions are refused with a warning:
- "Wait for the reply to finish (or press Esc) before switching model."
- "… before changing reasoning effort."
- "… before starting a new chat."

**Quit** (Ctrl+C): asks for confirmation (default "Nope"). Pressing Ctrl+C
twice skips the question.

**Prompt history** (Up/Down in the input):
- Holds the messages sent this session, newest first. A message equal to
  the newest one isn't added again.
- Up shows an older message once the cursor is at the very start of the
  input. Down shows a newer one once the cursor is at the very end. Stepping
  past the newest brings back the draft that was there before.
- Editing leaves the history and keeps the edit as the draft. Esc returns to
  the draft.

**Status line.** Errors and warnings stay until replaced. Info messages,
such as "Copied to clipboard.", clear after 5 s.

## 8. Display semantics to preserve (content, not terminal look)

- **Thinking summary.**
  - Shown above the reply.
  - When collapsed and longer than 11 rendered lines, it shows only the
    **last 10 lines**, behind "… (N lines hidden)". Tapping toggles it.
  - "Thought for 2.3s" appears once the reply starts.
- **Reply footer**, on completed, non-failed replies:
  `◇ <model> via <Provider> in <elapsed> · <in> in · <out> out`.
  - Elapsed time is Go's duration format rounded to 100 ms (`2.3s`,
    `1m2.3s`).
  - Token counts are formatted as `845`, `12.4K`, `1.2M`, and `.0` is
    dropped (`12K`).
- **Context usage** of the active model, after a reply:
  - `12% (24.5K)` when the context window is known, with ⚠ once above 80 %.
  - `24.5K tokens` when it isn't (always the case for OpenAI).
  - Shown with the model, provider and the effort actually sent
    ("Reasoning High").
- **Markdown.**
  - Replies, user messages and thinking are rendered as markdown, with
    syntax-highlighted code blocks. Message text is capped at 120 columns.
  - While streaming, only the trailing part after the last *safe boundary*
    is re-rendered. A safe boundary is a blank line outside any fence, list,
    table, quote or setext header.
  - A finished reply is rendered whole.
- **Copy.** The user can copy the reply's markdown source, or a text
  selection.

## 9. Terminal-only behavior (do not port as-is)

These have no direct Android equivalent, or exist only because of the
terminal. The Android app needs the *intent*, not the mechanism:
- Key bindings, Tab focus switching, and the help bar.
- The chat-list keyboard navigation (j/k, g/G, half pages).
- Mouse drag, double-click and triple-click selection, and OSC 52 clipboard.
- The `$VISUAL`/`$EDITOR` round trip.
- The sidebar or header showing the working directory and git branch.
- The frame cache, wheel coalescing and the resize prewarm.
- The ":::" prompt, the Crush logo, the scrambled-glyph spinner, and the
  Charmtone colors.
- The quit dialog. On Android, the user leaves the app with system back.

## 10. Edge cases (from code and tests)

| Case | Behavior | Test |
|---|---|---|
| The other provider's assistant turn has empty text (e.g. cut off while reasoning) | Skipped when building the request for either API | `TestEmptyAssistantTurnIsSkipped` |
| Anthropic fallback mid-reply | Pre-fallback thinking and tool_use are dropped from the replay, and the fallback markers too | `TestReplayParamDropsDeclinedModelsBlocksBeforeFallback` |
| Anthropic compaction | The compaction block is kept in the replay, and the note is shown | `TestReplayParamKeepsCompactionBlock` |
| OpenAI compaction | Input starts at the latest compaction item | `TestOpenAIInputStartsAtLatestCompaction` |
| OpenAI refusal | Streamed and stored as reply text | `TestReplyTextIncludesRefusal` |
| Anthropic refusal stop reason | Shown as an error, with the category | `anthropic.go` |
| Context after compaction | Taken from the last sampling iteration | `TestAnthropicContextComesFromLastSamplingIteration` |
| Model doesn't support the chosen effort | Lowered as in section 3, and the UI shows the lowered value | `TestAnthropicRequestLimitsEffortFallback` |
| Model limit is lower than 64000 | `max_tokens` is lowered | `TestAnthropicListModelsLimitsRequests` |
| Reply fails after partial text | Text is kept in the history, and an ERROR banner is shown | `TestFailedReplyKeepsItsText` |
| Reply fails with no text | The user turn is removed and the input restored (only if empty) | `TestFailedReplyWithoutTextPutsTheMessageBack` |
| Single Esc | Does not cancel | `TestCancelNeedsASecondEsc` |
| Cancelled stream closes without Err | Treated as cancelled | `ui.go` `handleStreamEvent` |
| Model listing fails or times out | Silent, and the built-in model list is used | `listModels` |
| Switching model or provider | Usage cleared; conversation kept | `TestSwitchingModelClearsUsage`, `TestModelPickerSwitchesProviderAndKeepsConversation` |
| Each provider's effort | Kept independently across switches | `TestEachProviderKeepsItsEffort` |
| Completed reply with empty text | Shows "(no text in this reply)", not the thinking spinner | `TestEmptyReplyIsNotShownAsThinking` |
| Pasted CRLF | Becomes a single newline | `TestPastedCRLFIsOneLineBreak` |
| Stream text burst | Merged per 33 ms window, order kept, nothing delivered after cancel | `TestCoalesce*` |
