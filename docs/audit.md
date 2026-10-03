# Audit: the Android port (`android/`)

Reviewed against:
- Now in Android ([`android/nowinandroid`](https://github.com/android/nowinandroid),
  cloned 2026-10-03),
- developer.android.com (versions and policies checked on 2026-10-03; sources
  at the end),
- the CLAUDE.md requirements,
- `docs/behavior.md`.

**Status: waiting for approval.** Nothing in `android/` has been changed.
Section 5 lists the decisions I need from you before step 3a.

---

## 1. Summary

The port is **functionally faithful and the protocol layer is good**:
- `core/` is a clean, Android-free Kotlin port of the Go protocol code. It
  has 62 JVM tests that port the Go request, provider and markdown tests.
- Streaming, replay payloads, compaction, effort downgrade and the
  fail/restore rules all match the Go app.

The **app layer is a terminal app in a Compose window**:
- It uses a monospace font everywhere and a hard-coded dark Crush palette.
- It has a `:::` prompt, a scrambled-glyph spinner, `[tap to expand]` text
  hints, an `ERROR` tag and a `copy` text link.
- Messages are drawn with a left border instead of as bubbles.
- It has none of the target stack: no DI, no repository, no Room, no
  DataStore, no navigation library, no app tests, no lint or screenshot
  tests in CI.

The build is also behind current versions: it targets SDK 35, and Play has
required 36 since 2026-08-31.

**Mismatch with CLAUDE.md.** CLAUDE.md describes a socket chat client
(server/host, username, connecting and reconnecting, incoming-message
notifications). This app is a streaming HTTPS client for two LLM APIs.
Section 4 maps each of those requirements to what it can mean here.

## 2. What to keep

| Keep | Why | Changes needed |
|---|---|---|
| `core/.../AnthropicProvider.kt`, `OpenAIProvider.kt` | Request bodies, SSE event handling, replay rules and model filters match the Go app and are tested against a local HTTP server (`RequestTest.kt`). | Fix the small parity gaps in section 3.4. Make the providers read settings from a source instead of `var` fields. |
| `core/.../Provider.kt` (`StreamEvent`, `Usage`, `coalesce`, `describeError`, `EFFORTS`) | Already idiomatic: a cold `Flow`, cancellation through the collector. | Keep. `coalesce` should use an injectable clock or `TimeSource` so it can be tested with virtual time. |
| `core/.../Http.kt` `readSse` | Small, correct, and tested. | Keep the parser. Rework the transport (section 3.2). |
| `ChatSession` send/fail/cancel logic | It carries the subtle behavior: partial text kept in the history, the user turn removed and the input restored, the cancel path. | Move it into a `ChatRepository` in the data layer. Split the UI-facing message model from the persisted model. Keep its tests. |
| `PromptHistory` | Only matters with a hardware keyboard, but correct. | Keep. Own it in the ViewModel, not the UI. |
| `core/.../markdown/*` (parser, `StreamingMarkdown`, highlighter) | The stable-prefix cache keeps block instances stable, so Compose skips unchanged blocks. This is good for performance. | Keep the logic. Restyle the renderer with Material typography and colors. |
| Immutable `ChatState` and message data classes | They already follow UDF. | Rename to `ChatUiState`, add `@Immutable`, and use `ImmutableList` or stable wrappers for lists. |
| `collectAsStateWithLifecycle`, `enableEdgeToEdge()`, `Scaffold` + `consumeWindowInsets` + `imePadding()` | All correct. | Keep. |
| `LazyColumn(reverseLayout = true)` with `key = { it.id }` | This is the correct auto-scroll approach. | Keep, and add `contentType`. |
| Encrypted key storage, backup exclusion (`data_extraction_rules.xml`) | API keys must stay on the device. | Keep the intent. Replace the deprecated library (section 3.1). |
| Hardware-keyboard Enter to send, Shift+Enter for a new line | Matches the Go app, and is good on tablets and ChromeOS. | Keep. |

## 3. What to replace

### 3.1 Build and CI

| Finding | Where | Reference / fix |
|---|---|---|
| `compileSdk`/`targetSdk` 35 | `app/build.gradle.kts:11,16` | Play requires **API 36** for new apps and updates since **2026-08-31**. NIA uses 36. Move to 36. |
| AGP 8.11.1, Gradle 8.14.3, Kotlin 2.2.0, Compose BOM 2025.06.01 | `gradle/libs.versions.toml`, `gradle-wrapper.properties` | Latest stable versions: **AGP 9.4.0** (needs Gradle ≥ 9.6.0), **Kotlin 2.4.20**, **Compose BOM 2026.09.00** (Material 3 1.4.0). AGP 9 has built-in Kotlin, so the `kotlin-android` plugin goes away. Exact versions of Hilt, KSP, Room, DataStore and Roborazzi will be pinned from their release pages in step 3a. |
| `androidx.security:security-crypto:1.1.0-alpha06` | `libs.versions.toml`, `Settings.kt:68` | **Deprecated** since 1.1.0 ("in favour of existing platform APIs and direct use of Android Keystore"). Replace it with a small Keystore AES-GCM cipher wrapping DataStore. |
| No Hilt, Room, DataStore or navigation library | build files | Required by the CLAUDE.md target stack. |
| CI runs only `:core:test` and `assembleDebug` | `.github/workflows/android.yml:38-42` | CLAUDE.md requires `./gradlew lint test`, screenshot tests, and uploading both the APK and the screenshots. Upload test and lint reports on every run, not only on failure. |
| No app-module tests, previews or screenshot tests | `app/` | Add ViewModel unit tests (Turbine), Robolectric + Roborazzi screenshot tests (as NIA does), and Compose UI tests. |
| Hard-coded UI strings | all of `ui/` | Move them to `strings.xml`. Lint will flag them, and it is needed for TalkBack labels and i18n. |

### 3.2 Architecture

| Finding | Where | Fix |
|---|---|---|
| `ChatViewModel` is an `AndroidViewModel` that builds `Settings`, both providers and `ChatSession` itself | `ChatViewModel.kt:20-32` | `@HiltViewModel` with injected `ChatRepository` and `SettingsRepository` interfaces. No `Application` in the ViewModel. |
| The UI reaches into the ViewModel's collaborators: `vm.prompts`, `vm.settings`, `vm.hasKey()` | `ChatScreen.kt:149,370-406`, `SettingsScreen.kt:60-79` | Screens take a `UiState` plus event lambdas, and stay stateless (NIA pattern: a `Route` composable wraps a stateless `Screen`). Whether a key exists becomes part of the UI state. |
| Settings use synchronous `SharedPreferences`, read through `var` getters | `Settings.kt:16-55` | Preferences DataStore behind `SettingsRepository` exposing `Flow<UserSettings>`. Model and effort changes reach the providers through that flow. Today the defaults only apply after a restart. |
| `ChatSession` owns a `MutableStateFlow`, a mutable `history` and a scope, and needs a single-threaded dispatcher | `ChatSession.kt:130-151` | The repository owns the conversation, with Room as the single source of truth (section 5). The in-flight stream runs in an app-scoped `CoroutineScope` injected by Hilt, so it survives configuration changes and leaving the screen. Use a `Mutex` instead of relying on the single-thread assumption. |
| A global `requests` scope plus a raw `thread {}` to disconnect | `Http.kt:114,143` | This is a Go-style workaround. Use OkHttp (`Call.cancel()` is thread-safe and unblocks reads) with `okhttp-sse` or the existing parser, or keep `HttpURLConnection` with an injected scope and `runInterruptible`. **Recommendation: OkHttp** (a new dependency; see section 5). |
| `restoredInput` and `notices` are `SharedFlow`s with `replay = 0` | `ChatSession.kt:140`, `ChatViewModel.kt:42` | Events emitted while nobody collects are **dropped**. Example: a reply fails while Settings is open, and the message is never put back in the input. Fix: model them as state (`inputDraft`, `userMessage: String?`) that the UI consumes, as the architecture guide recommends for UI events. |
| Navigation is a `rememberSaveable` boolean | `MainActivity.kt:31-36` | Use a navigation library (decision in section 5) with a typed Chat and Settings back stack, which gets predictive back for free. |
| Conversation lost on process death | `ChatViewModel.kt` (by design, as in Go) | Room persistence (decision in section 5). The input draft goes in `SavedStateHandle`. |
| `expanded` thinking state is in a plain `remember` map | `ChatScreen.kt:111` | Move it to UI state, or `rememberSaveable`. |

### 3.3 UI: terminal-style elements (CLAUDE.md: "no terminal-style rendering")

| Finding | Where | Native replacement |
|---|---|---|
| `FontFamily.Monospace` for every style | `Theme.kt:81`, `ChatScreen.kt:91-92`, `MessageViews.kt`, `SettingsScreen.kt` | The Material 3 type scale with the default font. Monospace only in code blocks and inline code. |
| Hard-coded Crush "Pantera" palette, dark only, `Palette.*` used directly | `Theme.kt:20-76,88` and every UI file | `dynamicLightColorScheme`/`dynamicDarkColorScheme` on Android 12+, with a brand fallback scheme. Theme setting: system, light or dark. All UI code reads `MaterialTheme.colorScheme`. This also removes the FSL-licensed palette. |
| `:::` / ` > ` prompt, `BasicTextField`, "Ready…" placeholder | `ChatScreen.kt:397-425` | An M3 `OutlinedTextField` or a filled text field in a rounded input bar. Multiline (up to about 6 lines, then scroll), "Message" placeholder, `FilledIconButton` Send that is disabled when blank, and Stop while streaming. |
| User messages as a 2 dp left border; replies as plain text | `MessageViews.kt:71` | Bubbles: the user's on the end side in `primaryContainer`, the assistant's on the start side in `surfaceContainer` (or full width for long markdown). Rounded shapes that change with grouping. |
| No timestamps, date separators or grouping | — | Add `createdAt` to messages. Show time in the footer, sticky date headers ("Today" and so on), and tighter spacing between consecutive messages from the same sender. |
| Scrambled-glyph gradient spinner | `MessageViews.kt:209` | A typing indicator: three animated dots, or `LinearProgressIndicator`, with "Thinking…" and elapsed time. |
| "[tap to expand]" / "[tap to collapse]" text | `MessageViews.kt:137,141` | An expandable card with a chevron icon button and `stateDescription` ("Collapsed"/"Expanded"). |
| `ERROR` tag | `MessageViews.kt:161` | An error-colored message with an icon, plus a **Retry** action. Retry is new UX; its protocol effect is the same as resending. |
| "copy" text link, under 48 dp | `MessageViews.kt:189` | Long-press menu (Copy, Copy markdown, Share) and/or an `IconButton` with a content description. |
| Effort "button" is a 12 sp `Text` with `clickable` | `ChatScreen.kt:227` | A touch target under 48 dp. Use an `AssistChip` or `FilterChip`, or a top-bar menu item. |
| Model title is clickable text | `ChatScreen.kt:220` | A `TopAppBar` title with a dropdown affordance (`ExposedDropdownMenu` or bottom sheet), with `Role.Button` semantics. |
| Bottom-sheet rows are custom `Row`s with a filled `Primary` selection | `ChatScreen.kt:515-538` | `ListItem` with radio or check trailing content and `selectable(role = Role.RadioButton)`. |
| `LocalClipboardManager` (deprecated) | `ChatScreen.kt:113` | `LocalClipboard` (suspend `setClipEntry`). |
| No previews | all of `ui/` | Previews for every stateless screen and component, in light, dark and large font, which double as Roborazzi screenshot tests. |
| No window size classes | — | `currentWindowAdaptiveInfo()`. On expanded width, cap the message column width and use a list-detail layout (conversation list and chat) if conversations are persisted. |
| No notifications | — | See section 4. |
| Predictive back not tested | manifest | With `targetSdk 36`, predictive back is the default. Use `BackHandler` or the navigation library's back handling, not `onBackPressed`. |

### 3.4 Small protocol parity gaps (Kotlin vs Go)

| Gap | Go | Kotlin | Proposal |
|---|---|---|---|
| Retries | The SDKs retry twice: connection errors, 408/409/429/5xx, `x-should-retry`, `retry-after`, 0.5 s·2ⁿ backoff capped at 8 s with jitter | None | Port this exact policy into the transport, so that **only** the request before the stream starts is retried. This restores parity rather than changing the protocol. |
| Anthropic messages URL | `/v1/messages?beta=true` | `/v1/messages` (`AnthropicProvider.kt:125`) | Add `?beta=true` to match. |
| Base URL | `ANTHROPIC_BASE_URL` / `OPENAI_BASE_URL` env vars | Hard-coded defaults (constructor parameter only) | Optional "Server" setting per provider (section 4). |
| 401 hint | "(check ANTHROPIC_API_KEY)" | "(check the Anthropic API key in Settings)" | Keep the Android wording. The env var means nothing on a phone. |
| Model picker | Only listed or known models | Also accepts a typed model ID (`ChatScreen.kt:466`) | Keep. It's a useful Android addition and doesn't change the protocol. Calling it out for your approval. |

## 4. Mapping the CLAUDE.md chat requirements to this app

| CLAUDE.md says | What it means here | Proposal |
|---|---|---|
| Connection state (connecting, connected, reconnecting, disconnected) from a Flow | There is no persistent connection. There is per-reply request state, plus device connectivity. | `ReplyState` = Idle, Sending, Retrying(attempt, in), Thinking, Streaming, Failed, Cancelled, from the repository. Plus `NetworkMonitor` (`ConnectivityManager.registerDefaultNetworkCallback` → `Flow<Boolean>`, as in NIA) driving an "Offline" banner and disabling Send. |
| Auto-reconnect with backoff | Retrying a request that hasn't started streaming | The SDK-equivalent retry policy (section 3.4). Optionally: when the network comes back, offer **Retry** on a reply that failed for connectivity. Never automatically resend a stream that was cut off mid-reply. That would be a protocol change, because the partial text is already in the history. |
| Settings: server/host | Base URL | Optional per-provider base URL (default: official). **Needs approval.** |
| Settings: username | Nothing | Not applicable. Settings instead holds API keys, start provider, models, efforts and theme. |
| Settings: theme | — | System, light or dark, plus a dynamic color toggle, in DataStore. |
| Messages survive process death; history in Room | Go keeps nothing on disk | **Needs approval** (section 5). |
| Notifications with a reply action when backgrounded | No incoming messages exist. The useful case is a reply that finishes or fails while the app is in the background. | A "Replies" notification channel. On completion or failure while backgrounded, post a notification with the reply preview and a **Reply** action (`RemoteInput`), which sends a follow-up through the repository. Request `POST_NOTIFICATIONS` at a contextual moment (the first send), with a rationale. |
| WorkManager or foreground service only if needed | A reasoning reply can run for minutes. Android may kill a backgrounded process. | **Needs approval:** (a) accept that the reply may be lost if the process dies (it is marked "Interrupted" in Room on next launch), or (b) run a foreground service while a reply streams and the app is backgrounded. Its type would be `dataSync` or `shortService`; the right type needs checking against current FGS policy before step 3e. |

## 5. Decisions needed before step 3a

1. **Persist conversations in Room?** CLAUDE.md requires it, but the Go app
   deliberately keeps nothing on disk.
   - Proposal: yes, with a conversation list ("New chat" starts a new
     conversation) and a "Delete" action.
   - Store the replay payloads (Anthropic thinking signatures, OpenAI
     encrypted reasoning) so continuing a chat after a restart behaves
     exactly like Go within one run.
   - Alternative: store text only, and accept that the model loses its
     reasoning context after a restart.
2. **Retries:** port the SDKs' 2-retry policy. I count this as parity, not
   a change.
3. **Base URL ("server") setting:** add it, or leave it out?
4. **Background replies:** foreground service, or accept loss on process
   death (see section 4)?
5. **Drop the Crush look entirely?** That means Material 3, the system font,
   bubbles, and dynamic color, with monospace only in code. It also removes
   the FSL-licensed palette and spinner code.
6. **Navigation library.** CLAUDE.md says Navigation Compose. **Navigation 3
   (1.2.0 stable, 2026-09-23)** is now the recommended Compose navigation
   library, and Now in Android has moved to it. Proposal: Navigation 3.
7. **New dependencies** (CLAUDE.md asks me to check first):
   - Hilt + KSP
   - Room
   - DataStore Preferences
   - Navigation 3
   - Material 3 Adaptive
   - OkHttp (transport)
   - Roborazzi + Robolectric (screenshots)
   - Turbine (Flow tests)

   All are official AndroidX/Google or the testing libraries Now in Android
   uses, except OkHttp (Square, also used by NIA). JSON stays on
   `org.json`, so no serialization library is added.
8. **Typed model IDs** in the picker: keep this Android-only extra?

## 6. Prioritized refactor plan

### Module layout

Keep it small:
- `:core`, pure JVM: the protocol, the SSE parser, markdown, and their
  tests. It gains the transport and retry code.
- `:app`, organized by layer:
  - `data/`: repositories, Room, DataStore, the network monitor, key
    encryption.
  - `ui/`: theme, chat, settings, components.
  - `di/`
  - `notifications/`

No `domain/` layer unless logic is shared between ViewModels. No convention
plugins: with two modules, a version catalog alone is enough, and convention
plugins add a `build-logic` build to maintain.

Each step is one commit, and CI must be green before the next one starts.

### a. Project setup (no behavior change)

Changes:
- Version catalog bumps (AGP 9.4, Gradle 9.6, Kotlin 2.4.20, BOM
  2026.09.00, compile/target 36).
- Hilt application class and `@AndroidEntryPoint` activity.
- Lint config with a baseline.
- Roborazzi + Robolectric setup with one screenshot test of the current
  chat screen.
- CI runs `./gradlew lint test verifyRoborazziDebug assembleDebug` (record
  on first run) and uploads the APK, screenshots and reports.

Tests: existing core tests, plus the first screenshot.

**Phone check:** the app installs and behaves exactly as before.

### b. Data layer

Changes:
- `SettingsRepository`: DataStore, plus Keystore-encrypted API keys, with a
  one-time migration from the old SharedPreferences and
  EncryptedSharedPreferences.
- `ConversationRepository`: Room tables `conversation` and `message`, with
  the replay payload as a JSON column.
- `ChatRepository`: wraps the providers, runs the stream in an app scope,
  and writes every change to Room as the single source of truth.
- Transport with retries, plus the parity fixes from section 3.4.
- `NetworkMonitor`.

Tests:
- Repository tests with fakes and an in-memory Room.
- Retry policy tests against the local HTTP server.
- Settings migration test.

**Phone check:** keys survive the update, and chats survive force-stop
(if approved).

### c. UI state and ViewModels

Changes:
- `ChatViewModel` and `SettingsViewModel` (`@HiltViewModel`) expose
  `StateFlow<…UiState>` via `stateIn(WhileSubscribed(5_000))`.
- Events become functions.
- The draft goes in `SavedStateHandle`.
- One-off events become state.
- Navigation 3 back stack.

Tests: ViewModel tests with Turbine and a test dispatcher covering send,
stream, fail, restore input, cancel, switch model, the streaming guards and
the effort downgrade display.

**Phone check:** same UI. Rotate during a reply: it keeps streaming. Open
Settings during a failure: the input is restored.

### d. Compose UI and Material 3 theme

Changes:
- Theme (dynamic color, light/dark, type scale, shapes).
- Stateless `ChatScreen` with bubbles, grouping, date headers, timestamps,
  the typing indicator, the expandable thinking card, the error-with-retry
  row, a message context menu, and the input bar.
- Model and effort picker sheets built from `ListItem`s.
- M3 Settings screen, including theme.
- Conversation list.
- Markdown renderer restyled.

Tests:
- Previews plus Roborazzi screenshots for phone, light/dark and font scale
  1.0/2.0.
- Compose UI tests: Send enabled/disabled, Stop while streaming, expanding
  thinking.

**Phone check:** the whole look; light/dark and wallpaper colors.

### e. Lifecycle, reconnection, background, notifications

Changes:
- Offline banner and retry UI driven by `NetworkMonitor` and `ReplyState`.
- Notification channel; reply-finished notification with a `RemoteInput`
  reply; `POST_NOTIFICATIONS` request flow.
- Foreground service (if approved), or marking interrupted replies on the
  next launch.

Tests:
- Notification builder unit tests.
- Permission-flow UI test.
- `ReplyState` transitions under a fake network.

**Phone check:** airplane mode mid-send; background the app during a long
reply; reply from the notification.

### f. Adaptive layout, accessibility, polish

Changes:
- `currentWindowAdaptiveInfo()`: list-detail on expanded widths, a capped
  message width.
- Semantics: merged message nodes reading "You, 10:42: …", heading roles
  for date separators, live region for the streaming state, 48 dp targets
  checked.
- Large-font and RTL screenshots.
- `contentType` and stability audit with the Compose compiler reports.

Tests: tablet and foldable screenshots; accessibility checks via Compose
UI tests (and Roborazzi's ATF checks if they're available in the pinned
version).

**Phone check:** TalkBack walkthrough, largest font size, landscape, and
tablet if you have one.

---

### Sources (checked 2026-10-03)

- Target API requirements: https://developer.android.com/google/play/requirements/target-sdk
- AGP release notes (9.4.0): https://developer.android.com/build/releases/gradle-plugin
- Compose BOM mapping (2026.09.00): https://developer.android.com/develop/ui/compose/bom/bom-mapping
- Navigation 3 releases (1.2.0): https://developer.android.com/jetpack/androidx/releases/navigation3
- security-crypto deprecation: https://developer.android.com/jetpack/androidx/releases/security
- Kotlin 2.4.20: https://kotlinlang.org/docs/whatsnew2420.html
- Now in Android: https://github.com/android/nowinandroid (`gradle/libs.versions.toml`,
  `build-logic/`, `app/.../NiaApp.kt`)
- Go SDK retry behavior: `anthropic-sdk-go@v1.78.0/internal/requestconfig/requestconfig.go`
  (`shouldRetry`, `retryDelay`, `MaxRetries: 2`), `openai-go/v3@v3.68.0` (same defaults)
