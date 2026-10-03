# chat for Android

A native Android port of the terminal chat client in the repository root, for the OpenAI and Anthropic APIs. It is written in Kotlin with Jetpack Compose and coroutines/Flow. There are no cross-platform frameworks: requests go through OkHttp, server-sent events are parsed by hand, and JSON is handled with `org.json`. Failed requests are retried the way the Go SDKs do it: twice, for connection errors and 408/409/429/5xx responses, honoring `retry-after` and otherwise backing off from 0.5 s to 8 s. A reply that has started streaming is never retried.

## Build

JDK 21 (Robolectric needs it for SDK 36; the app compiles to Java 17) and the Android SDK (compiles against API 37, targets API 36). The build uses Gradle 9.8, AGP 9.4 with its built-in Kotlin, Hilt and a version catalog (`gradle/libs.versions.toml`).

```sh
cd android
./gradlew lint                    # Android lint
./gradlew test                    # unit tests: core/ on the JVM, app/ on Robolectric
./gradlew verifyRoborazziDebug    # screenshot tests against app/src/test/screenshots
./gradlew recordRoborazziDebug    # re-record those references
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/android.yml`) runs lint, the screenshot tests, the unit tests and the debug build. It uploads the APK (`chat-debug-apk`), the screenshots (`screenshots`) and the lint and test reports (`reports`). When there are no reference screenshots yet, or a manual run sets `record_screenshots`, CI records them and commits them to the branch instead of verifying them.

## Setup

There are no environment variables on Android, so the app opens on Settings until it has an API key. Settings live in Preferences DataStore (`app/.../data/SettingsRepository.kt`). The API keys in it are encrypted with AES-GCM under a key that never leaves the Android Keystore, and backups and device transfers leave the settings out. Settings also holds what the terminal app takes as flags or environment: an optional server for each API (blank for the official one), the provider to start with (OpenAI by default) and each provider's model (`claude-sonnet-5-5`, `gpt-5.6-luna`) and reasoning effort (`medium`). The first start after updating moves the settings and keys from the SharedPreferences used before.

## Use

- **Model bar** (top): tap the model to switch model or provider; the conversation carries over. The picker lists the models each API serves from Claude 4.6 and GPT-5.6 on, and a filter that matches nothing can be used as a model ID. Tap "Reasoning …" to set the active provider's effort. Each provider keeps its own effort, and an effort the model doesn't support is lowered to the nearest level it does, for Claude models. The bar also shows how full the context window is after a reply ("12% (24.5K)", with ⚠ past 80%), or just the token count for OpenAI, whose API doesn't report context windows.
- **+** starts a new chat.
- **Send / Stop**: while a reply streams, Send becomes Stop.
- **Thinking**: the reasoning summary streams into a muted box above the reply (Anthropic `thinking.display: "summarized"`, OpenAI `reasoning.summary: "auto"`). A long one shows its last 10 lines until tapped.
- **Footer**: each finished reply shows "◇ model via Provider in 2.3s · 12.4K in · 845 out" and a **copy** action for its markdown. Text in messages can be selected.
- **Hardware keyboard**: Enter sends (Shift+Enter is a new line), Up/Down at the start/end of the input step through the messages sent this session, and Esc goes back to the draft.

A reply that fails or is stopped stays in the chat, ending in an ERROR banner with the reason, or "Canceled". Any text that arrived is kept and sent with later turns. If none arrived, the message is left out of later requests and put back in the input to resend.

Conversations are saved on the device in a Room database (`app/.../data/db/`), unlike the terminal app, which keeps them in memory. Each reply is saved with what its provider returned (Anthropic thinking blocks, OpenAI encrypted reasoning), so continuing a conversation after a restart sends exactly what the terminal app would within one run. A streaming reply is saved about once a second, and one the app was closed during is marked "Interrupted" on the next start, by the same rules as a failed reply. The app opens the most recent conversation; New chat starts another and keeps the old one. Nothing is stored server-side: OpenAI requests use `store: false`. Long conversations are compacted server-side: Anthropic past its 150k-token default, OpenAI past 200k, with a note under the reply when it happens. Anthropic requests use automatic prompt caching (top-level `cache_control`), and OpenAI requests share one `prompt_cache_key` per session.

## Layout

| Path | What |
|---|---|
| `core/` | Plain Kotlin/JVM library, ported from the Go app, with no Android dependency. |
| `core/.../Provider.kt` | `provider.go`: turns, stream events, usage, `coalesce`, `describeError`. |
| `core/.../AnthropicProvider.kt` | `anthropic.go`: Messages API request, SSE accumulator, replay, model listing and effort limits. |
| `core/.../OpenAIProvider.kt` | `openai.go`: Responses API request, events, replay items, model filter. |
| `core/.../Http.kt` | OkHttp transport, the SDKs' retry policy, the SSE parser and cancellation. |
| `core/.../ChatSession.kt` | Conversation state and the send / fail / switch logic of `ui.go` and `messages.go`, over a `ConversationStore`. |
| `core/.../ConversationStore.kt` | Where conversations are kept: the interface, its records, and an in-memory store for tests. |
| `core/.../PromptHistory.kt` | `history.go`'s prompt history. |
| `core/.../markdown/` | `markdown_stream.go`'s stable-prefix cache over a small block and inline parser, plus the code highlighter. |
| `app/.../data/` | Repositories: the chat (`ChatRepository`, which holds the providers and the session for the life of the process), settings in DataStore, the Room store, the network monitor. |
| `app/.../di/` | Hilt modules. |
| `app/.../ui/` | Compose screens and their ViewModels. `ChatApp.kt` is the Navigation 3 back stack; each screen has a `…Route` that connects its ViewModel and a stateless `…Screen` that takes an immutable UI state and callbacks. |

The markdown renderer caches the parsed blocks of the stable prefix. While a reply streams, only the paragraph still arriving is re-parsed, and the earlier blocks stay the same instances, so Compose skips redrawing them.

The tests port `request_test.go` (against a local HTTP server), `provider_test.go` and `markdown_stream_test.go`. There are also tests for the SSE parsing, the conversation logic and the markdown parser.

## License

The adapted code is Copyright 2025-2026 Charmbracelet, Inc., used under [FSL-1.1-MIT](https://github.com/charmbracelet/crush/blob/main/LICENSE.md), which permits use other than in a competing commercial product. This covers the streaming markdown cache and its tests (`core/.../markdown/StreamingMarkdown.kt`, `MarkdownStreamTest.kt`), the message model (`ChatSession.kt`), the prompt history (`PromptHistory.kt`), and the palette, message styles and spinner (`app/.../ui/Theme.kt`, `MarkdownView.kt`, `MessageViews.kt`). It is adapted from [Crush](https://github.com/charmbracelet/crush) by way of the Go app, and each file says so in its header.
