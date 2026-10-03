# Project: Native Android chat app (refactor of existing port)

## Context
This repo contains:
- The original Go terminal chat app (source of truth for behavior and
  protocol).
- A working but non-idiomatic Android port of it (the base to refactor).

The port works, but it feels like a terminal app wrapped in Android, not a
native app. Goal: make it feel like a polished, idiomatic native Android
chat app built the way Google recommends. Do NOT start from scratch. Do
NOT translate Go patterns literally. Keep what works, replace what is
non-idiomatic.

## Reference material (consult, don't guess)
1. Now in Android (official Google reference app):
   https://github.com/android/nowinandroid
   Clone it into a scratch folder outside this repo (e.g. /tmp/nia), or
   read it via GitHub if cloning is blocked. Use it as the reference for:
   - Architecture (UI / domain / data layers, offline-first, repositories
     exposing Flow, single source of truth)
   - Compose structure: stateless composables, state hoisting, UI state
     classes, previews
   - Material 3 theming (dynamic color, light/dark, typography, shapes)
   - Navigation Compose setup, edge-to-edge, window size classes
   - Hilt DI, Room, DataStore usage
   - Gradle setup: version catalogs, Kotlin DSL, convention plugins
   - Testing: unit tests, Compose UI tests, screenshot tests
   Do NOT copy its full multi-module structure. This is a small app: use a
   simple structure (a few modules, or packages by layer) unless a
   module split clearly helps.
2. Google's Guide to App Architecture and Android Developers docs
   (developer.android.com): always check current docs and API versions
   instead of relying on memory.
3. Material Design 3 guidelines (m3.material.io) for components, theming
   and chat-appropriate layout.
4. Compose performance and stability guidance (stable keys, avoiding
   unnecessary recomposition, derivedStateOf, remember).
5. Android accessibility and large-screen (adaptive layout) guidance.

## Target stack
- Kotlin (latest stable), Jetpack Compose, Material 3
- Navigation Compose
- ViewModel + StateFlow, Kotlin Coroutines + Flow
- Hilt for dependency injection
- Room for message history, DataStore for settings
- WorkManager or a foreground service only if background connectivity
  truly requires it
- Gradle Kotlin DSL + version catalog (libs.versions.toml)
- Latest stable AGP, compileSdk/targetSdk per current Play requirements

## Chat UX requirements
- Messages in LazyColumn (stable keys, reverse layout or correct
  auto-scroll to newest), message bubbles, timestamps, sender grouping,
  date separators
- Input bar that rides above the keyboard (imePadding / WindowInsets),
  send button state, multiline input
- Edge-to-edge, proper system bar insets, predictive back
- Connection state in the UI (connecting, connected, reconnecting,
  disconnected) driven by a Flow from the data layer; auto-reconnect with
  backoff
- Survives rotation and process death; history persisted in Room
- Notifications (channels, reply action) when backgrounded, with the
  POST_NOTIFICATIONS runtime permission handled correctly
- Settings screen (server/host, username, theme) backed by DataStore
- Material You dynamic color, light and dark themes
- Adaptive layout using window size classes (phone, tablet, foldable)
- Accessibility: content descriptions, 48dp touch targets, font scaling,
  TalkBack-friendly semantics
- No terminal-style rendering (monospace text dump, manual scrolling
  buffers, command-style input) anywhere in the UI

## Architecture rules
- Unidirectional data flow: UI observes immutable UiState from the
  ViewModel, sends events up
- Networking/protocol lives in the data layer behind a repository
  interface; the UI never touches sockets directly
- Replace Go-style patterns (raw threads, channels, global state,
  polling loops) with coroutines, Flow, and structured concurrency
- Lifecycle-aware collection (collectAsStateWithLifecycle)
- Keep protocol behavior identical to the Go app unless I approve a change

## Environment constraints
- I work entirely from my phone. There is no local Android Studio or
  emulator.
- Verification happens through GitHub Actions. CI must run:
  ./gradlew lint test, screenshot tests (Roborazzi or Paparazzi),
  assembleDebug, and upload the APK and screenshots as artifacts.
- CI must stay green. If a step breaks CI, fix it before moving on.
- Commit in small, working steps so I can install each APK artifact and
  give feedback.

## Process (follow in order, do not skip ahead)
1. Behavior inventory: read the Go source and write docs/behavior.md
   (protocol, message formats, commands, edge cases).
2. Audit: review the existing Android code against the references above
   and write docs/audit.md with: what to keep, what to replace, and a
   prioritized refactor plan. Stop and wait for my approval.
3. Refactor in this order, one step per commit/CI run:
   a. Project setup: version catalog, Hilt, CI improvements
   b. Data layer: repository, networking, Room, DataStore
   c. UI state and ViewModels
   d. Compose UI and Material 3 theme
   e. Lifecycle, reconnection, background behavior, notifications
   f. Adaptive layout, accessibility, polish
4. Add tests with each step (unit tests for ViewModels and repositories,
   Compose UI tests, screenshot tests).
5. After each step, summarize what changed, what the CI result was, and
   what I should check on my phone.

## Rules
- Ask before adding big dependencies or changing the protocol.
- Don't use gomobile or wrap Go code unless I explicitly approve it.
- Prefer official Google/AndroidX libraries.
- Don't invent APIs: check developer.android.com when unsure.
- Keep the code simple and readable; this is a small app.