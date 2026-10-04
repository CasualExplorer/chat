# Android audit: `chat` vs Now in Android, Material 3 and the app quality checklist

Branch audited: `ccr-0a2d7a4a-iytgpn` (HEAD `acc64ce`). All paths below are relative to `android/`.
Reference: Now in Android, cloned at `a49ed25` (2026-09-22), plus the m3.material.io and developer.android.com/quality guidance.
This audit is read-only. The only file it adds is this report.

## 1. Stack summary

- **Modules:** `:app` (Android, Compose, Hilt) and `:core` (a plain Kotlin/JVM library holding the provider protocols, the chat session and the markdown parser). There are two modules and no `build-logic`.
- **Build:** AGP 9.4 with built-in Kotlin, Kotlin 2.4.20, Gradle 9.8, KSP, a version catalog (`gradle/libs.versions.toml`) and Spotless/ktlint. compileSdk is 37, targetSdk 36, minSdk 26. R8 and resource shrinking are on for release builds.
- **UI:** Jetpack Compose with Material 3 (BOM 2026.09.00), `material3-adaptive` for window size classes, Navigation 3 with ViewModels scoped to back-stack entries, a splash screen, edge-to-edge, and dynamic colour.
- **Architecture:** Route/Screen split with a stateless `…Screen(uiState, callbacks)`, `@HiltViewModel`s that expose a `StateFlow<UiState>`, and repositories behind interfaces (`ChatRepository`, `SettingsRepository`, `NetworkMonitor`). The process-lifetime `ChatSession` in `:core` owns the conversation state.
- **Data:** Room (one DB, version 1, schema exported), Preferences DataStore with API keys encrypted by AES-GCM under an Android Keystore key, OkHttp with hand-written SSE parsing and `org.json`.
- **Tests:** JVM tests in `:core` (protocol, retry, session, markdown). Robolectric ViewModel, repository and Room tests in `:app`, using fakes rather than mocks. Roborazzi screenshots cover light, dark, 2× font, RTL, foldable and tablet, and the Accessibility Test Framework runs on them. CI runs spotless, lint, screenshots, unit tests, and debug and release builds.

## 2. Priority definitions

- **Critical:** data loss, a security breach, or a crash for most users on the main path.
- **High:** a crash or a broken feature on a realistic but non-default path, or a real security weakness.
- **Medium:** a measurable quality gap against NiA or the checklist that users or maintainers will hit.
- **Low:** polish, hardening, or future-proofing.

---

## Critical

**None found.** The main path (start, add a key, chat, stream, stop or retry, switch chats) is handled carefully, and failures inside a streaming reply are caught (`core/.../ChatSession.kt:360-389`).

---

## High

### H1. Exceptions in fire-and-forget coroutines crash the process
**What's wrong:** Many `launch` calls run on `@ApplicationScope` (`SupervisorJob()` with no `CoroutineExceptionHandler`) or on `viewModelScope`, and none of them catch anything. Any exception they throw is uncaught, and an uncaught exception kills the app:
- `app/.../ui/SettingsViewModel.kt:70-86`: `setApiKey` runs `KeystoreCipher.encrypt` (`app/.../data/KeyCipher.kt:30-35`) inside `dataStore.edit`. `encrypt` doesn't catch `GeneralSecurityException`/`ProviderException`, even though `decrypt` does (lines 44-48). Keystore failures are well known on some OEM devices, so typing an API key during onboarding can crash the app. DataStore `IOException`s on write aren't caught either.
- `app/.../data/ChatRepository.kt:222-231`: `modelSelected` and `effortSelected` write to DataStore with no `try`.
- `core/.../ChatSession.kt:496`: `scope.launch { store.deleteConversation(id) }` has no `try`, so a disk-full or SQLite error here crashes the app.
- `core/.../ChatSession.kt:229-236`: the `opening` job (`recoverInterrupted()`, `latestConversationId()`) runs at startup. If the DB can't be read, the app crashes on every launch.

**How NiA does it:** `NiaPreferencesDataSource` wraps every DataStore write in `try { … } catch (ioException: IOException)` (`core/datastore/.../NiaPreferencesDataSource.kt:71, 88, 134, 184`). Flows exposed to the UI go through `asResult()`, which `.catch { emit(Result.Error(it)) }` (`core/common/.../result/Result.kt:30-32`). This app already applies the same idea inside `stream()` and in `tryStore`.

**Fix:**
1. Catch `GeneralSecurityException`/`ProviderException` in `KeystoreCipher.encrypt`. When encryption fails, either keep the key in memory and show "Couldn't store the key securely", or regenerate the key and retry once. Don't let it throw.
2. Wrap the settings writes and `store.deleteConversation` in `try/catch` (rethrowing `CancellationException`, e.g. with the existing `rethrowIfCancellation()`), and report failures through the existing snackbar `userMessage`.
3. As a backstop, give the application scope a handler: `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(…) })` in `app/.../di/CoroutinesModule.kt:53`. Keep this as a safety net, not as the real handling.
4. If `opening` fails, start with an empty chat and show an error instead of crashing.

### H2. A corrupt settings file means the app can never start
**What's wrong:** `app/.../di/DataModule.kt:56-63` creates the Preferences DataStore without a `corruptionHandler`, and `DataStoreSettingsRepository.settings` (`app/.../data/SettingsRepository.kt:61-79`) has no `.catch`. If `user_settings.preferences_pb` is ever corrupted (for example by a partial write on power loss), `dataStore.data` throws `CorruptionException`. `MainActivityViewModel.uiState` (`app/.../MainActivityViewModel.kt:24-26`) then fails in `viewModelScope`. The launch screen waits for that value (`MainActivity.kt:23`), so the app crashes on every launch until the user clears its data.

**How NiA and the guideline do it:** NiA throws `CorruptionException` from its serializer. The DataStore guidance is to install `ReplaceFileCorruptionHandler` so a corrupt file falls back to defaults.

**Fix:** `PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, …)`. Also add `.catch { if (it is IOException) emit(emptyPreferences()) else throw it }` before the `map` in `settings`. Users lose their stored keys, but the app opens on Settings, which is far better than a crash loop.

### H3. `http://` servers pass validation but can never work, and would leak the API key if they did
**What's wrong:** `normalizeBaseUrl` (`app/.../data/UserSettings.kt:84-91`) accepts `http://`, and the UI says it's fine (`res/values/strings.xml:101`: "Enter an http:// or https:// address"). The app targets API 36 and has no `network_security_config`, so the platform blocks cleartext. OkHttp checks `NetworkSecurityPolicy` and throws `UnknownServiceException: CLEARTEXT communication … not permitted`. A user who configures a local proxy or gateway gets a confusing error on every request. If someone "fixes" this by setting `usesCleartextTraffic="true"`, the `x-api-key` or `Authorization: Bearer` header would travel in plaintext.

**Guideline:** the app quality and security checklist says to use HTTPS for all network traffic, and to allow cleartext only for specific hosts through a network security config.

**Fix:** pick one of these.
- (a) Accept only `https://` in `normalizeBaseUrl` and update `server_invalid`.
- (b) If local gateways matter, add `res/xml/network_security_config.xml` that permits cleartext only for `localhost`, `127.0.0.1` and `10.0.2.2`. Then reject `http://` for any other host in `normalizeBaseUrl`, with an explicit error string.

Either way, add a unit test for the rule.

---

## Medium

### M1. User-visible error text is hard-coded English in `:core` and stored in the database
**What's wrong:** `describeError` (`core/.../Provider.kt:177-192`) builds sentences such as "(check the … API key in Settings)" and "Cancelled.", and `INTERRUPTED` (`core/.../ChatSession.kt:144`) is another English sentence. These strings are written to `messages.failure` (`app/.../data/db/ChatDatabase.kt:56`) and shown as-is (`app/.../ui/MessageViews.kt:230, 431`). Everything else in the UI comes from `strings.xml`, so these errors can't be translated. Once stored, they also stay in the language they were written in.

**How NiA does it:** NiA keeps all UI text in resources, and `:core` modules carry data (error types), not prose.

**Fix:** store a structured failure: kind (`Api`, `Network`, `Interrupted`, `Storage`), `status`, `type`, `detail` and `requestId`, in a few columns or as JSON. This needs Room version 2 with an `AutoMigration`; see L9. Then format the message in the UI with string resources. Keep the provider's `detail` verbatim, since it comes from the server.

### M2. No Baseline Profile or `profileinstaller`
**What's wrong:** `app/build.gradle.kts` has neither the `androidx.baselineprofile` plugin nor `androidx.profileinstaller`. Compose code runs interpreted or JIT-compiled until ART compiles it, which hurts cold start and early scrolling, and the message list scrolls heavily.

**How NiA does it:** `app/build.gradle.kts:26, 102` applies `baselineprofile` and depends on `profileinstaller`. A `:benchmarks` module generates the profiles, and a nightly workflow (`NightlyBaselineProfiles.yaml`) refreshes them.

**Fix:** add `implementation(libs.androidx.profileinstaller)` now. It's cheap, and it lets the Compose libraries' own profiles apply to sideloaded and CI builds. Then add a small `:baselineprofile` module with a generator that covers startup, typing and sending, and scrolling a long conversation, and apply the plugin to `:app`.

### M3. The Keystore and other device-only code has no on-device tests
**What's wrong:** all `:app` tests run on Robolectric, which has no `AndroidKeyStore`. `SettingsRepositoryTest` therefore swaps in `FakeCipher` (line 28), so `KeystoreCipher` and the `EncryptedSharedPreferences` migration (`SettingsRepository.kt:179-200`) have never run in a test. There's no `src/androidTest` at all.

**How NiA does it:** NiA runs `connectedDemoDebugAndroidTest` on an emulator in CI (`.github/workflows/Build.yaml:247`) and produces a combined coverage report (line 257).

**Fix:** add `src/androidTest` with:
- a `KeystoreCipher` round-trip test, including undecryptable input, which should return `null`;
- a `LegacySecretsMigration.forDevice` test against a real `EncryptedSharedPreferences` file;
- a Room DAO smoke test.

Run them in CI with an emulator action. Optionally add Jacoco coverage as NiA does.

### M4. The conversation query reloads every message, including large replay blobs, about once a second while streaming
**What's wrong:** `ChatDao.messages()` (`app/.../data/db/ChatDatabase.kt:65-66`) runs `SELECT *`, so every UI emission loads `anthropicReplay` and `openaiReplay`. These are the full JSON of each reply, including encrypted reasoning, and the UI never uses them. While a reply streams, `ChatSession` saves a checkpoint every `CHECKPOINT_MS = 1000` (`core/.../ChatSession.kt:354-357`). Each save invalidates the table, so the whole conversation, blobs included, is queried again and mapped about once a second. For long chats this wastes I/O and memory. A row bigger than the CursorWindow limit would throw `SQLiteBlobTooBigException` when read. I haven't seen that happen; it's a risk given unbounded blob size.

**Guideline:** the performance checklist and Room guidance say to query only the columns the screen needs.

**Fix:** add a slim projection for the UI, e.g. `@Query("SELECT id, conversationId, role, text, … FROM messages WHERE …")` returning a `MessageSummary`. Load the replay columns only in `turns()` and `stream()`, which build the request.

### M5. Settings has no large-screen layout
**What's wrong:** the chat is limited to `MAX_CONTENT_WIDTH = 840.dp` (`app/.../ui/ChatScreen.kt:131, 274`), and the tablet screenshot shows it working. `SettingsScreen` (`app/.../ui/SettingsScreen.kt:115-123`) has no width limit, so on a 1280dp tablet the text fields and switch rows stretch across the whole screen. The screenshot tests don't capture Settings at tablet width.

**Guideline:** Material 3's large-screen guidance limits text fields and lists to a readable width. The large-screen quality tier expects layouts that adapt.

**Fix:** wrap the column the same way the chat is wrapped: a `Box(contentAlignment = TopCenter)` around `Column(Modifier.widthIn(max = 640.dp))`. Add a `w1280dp` Roborazzi case for Settings.

---

## Low

### L1. The window theme ignores the in-app Light/Dark choice
`res/values/themes.xml:5` and `values-night/themes.xml:3` follow the system's night mode. When the user picks Light or Dark in the app, the window background shows the wrong colour for a moment on cold start, during the predictive-back peek, and while Compose is first laid out. NiA has the same limitation. **Fix:** on API 31+, call `UiModeManager.setApplicationNightMode(…)` whenever the theme setting changes, so the system's splash and window follow the app's choice. Alternatively, use `AppCompatDelegate.setDefaultNightMode`, which needs the AppCompat dependency.

### L2. Code syntax colours aren't contrast-checked against dynamic colours
`LightCodeColors` and `DarkCodeColors` (`app/.../ui/Theme.kt:68-86`) are fixed GitHub colours drawn on `surfaceContainerHighest`, which varies with the wallpaper. The Accessibility Test Framework runs only with `dynamicColor = false` (`AccessibilityTest.kt:84`). **I can't verify contrast for every wallpaper.** **Fix:** add an ATF run with a few seeded dynamic schemes, or derive token colours from the scheme with a minimum-contrast adjustment.

### L3. Markdown is parsed and highlighted during composition
`Markdown()` (`app/.../ui/MarkdownView.kt:119-130`) runs `MarkdownParser.parse` and `CodeHighlighter.tokenize` on the main thread the first time a finished message is composed. The LRU cache (lines 108-110) helps, but a long reply scrolled into view, or a cache miss after rotation, can drop frames. No benchmark exists, so **this isn't measured**. **Fix:** parse finished messages off the main thread, for example in the ViewModel on the `Default` dispatcher, and put the parsed blocks in the UI state. That also fits NiA's approach of keeping logic out of composables.

### L4. The data layer exposes screen state and UI-shaped results
`ChatRepository.state` returns `ChatState`, documented as "Everything the chat screen draws" (`app/.../data/ChatRepository.kt:33-34`, `core/.../ChatSession.kt:79-112`). The actions return a `Boolean` that means "refused because a reply is streaming", and the session keeps English refusal sentences that are then thrown away (`ChatRepository.kt:114-121`). In NiA, repositories expose domain models and ViewModels build the `UiState`. This is a reasonable simplification for an app of this size, so it isn't a defect. If more screens are added, return domain types plus a sealed result such as `ActionResult.Busy`, and map them in the ViewModel.

### L5. Every keystroke in an API key field triggers Keystore work and a DataStore write
`SettingsViewModel.setApiKey` (`SettingsViewModel.kt:70-73`) encrypts and writes on every character. Each write makes `settings` emit again, and each emission decrypts both keys (`SettingsRepository.kt:64-65`), even when an unrelated setting changed. This works, but it's wasteful. **Fix:** debounce key and URL writes by about 300 ms in the ViewModel. In the repository, cache the decrypted value by ciphertext, e.g. with `distinctUntilChanged` on the raw preference before calling `decrypt`.

### L6. TalkBack users can't reach "Select text" on their own messages
`UserMessageView` uses `clearAndSetSemantics` (`MessageViews.kt:140-143`), which removes the long-click from `MessageMenu`. TalkBack users get Copy and Share as custom actions but not "Select text". **Fix:** add a third `CustomAccessibilityAction` for Select text, or add it to `MessageActions.accessibilityActions`.

### L7. Device transfer includes conversations, but the manifest comment says backups are off
`AndroidManifest.xml:8-14` sets `allowBackup="false"`. For apps targeting Android 12+, that turns off cloud backup but not device-to-device transfer, which follows `data_extraction_rules.xml`. Those rules exclude only `sharedpref` and `datastore/`, so `chat.db` moves to a new phone. That's probably what you want, but make it deliberate: update the comment and the privacy note (`strings.xml:102`). Or add `<exclude domain="database" path="chat.db"/>` if conversations should never leave the device.

### L8. Leaving the app, including tapping a link, stops the reply
`StopReplyOnLeave` (`app/.../StopReplyOnLeave.kt`) is a documented, valid choice because the app has no foreground service. One side effect: tapping a link in a reply that's still streaming opens the browser, the process stops, and the reply is cancelled. Consider ignoring a stop that the app caused itself (a link tap or share), or warn in the README. This is not a checklist violation.

### L9. No migration testing set up yet
Room is at version 1 with the schema exported (`app/build.gradle.kts:64-68`), which is the right start. M1 will need version 2. NiA declares `AutoMigration`s and tests them (`core/database/.../NiaDatabase.kt:45-49`, `DatabaseMigrations.kt`). **Fix:** when you bump the version, add `autoMigrations = [AutoMigration(1, 2)]` and a `MigrationTestHelper` test in `androidTest` (see M3).

### L10. Release and Play readiness can't be verified from the code
`versionCode = 1` is hard-coded (`app/build.gradle.kts:20`), there's no signing config, and CI builds an unsigned release. No privacy policy link appears in the app. The app sends user content to third-party APIs, so the Play Data safety form and a privacy policy are required. **That can't be verified from the repo.** NiA also runs `checkProdReleaseBadging` and `dependencyGuard` in CI (`Build.yaml:56, 191`). Both are optional but cheap ways to catch unintended permission or dependency changes.

### L11. No brand colour scheme as a fallback
When dynamic colour is off, or below API 31, `ChatTheme` uses the default baseline `lightColorScheme()`/`darkColorScheme()` (`Theme.kt:44-45`), while the launcher background is `#201F26`. That's acceptable, but Material 3 recommends a scheme generated from a brand seed colour. NiA defines its own scheme in `core/designsystem/.../theme/Color.kt`. **Fix (optional):** generate one with Material Theme Builder from a seed that matches the launcher icon.

---

## Not penalized (valid alternatives to NiA)

- **Two modules instead of NiA's 30+ feature/core modules, and no `build-logic` convention plugins.** At about 7k lines with two screens, that's the right size. Revisit if feature screens multiply.
- **Preferences DataStore instead of Proto DataStore.**
- **`org.json` and a hand-written SSE parser instead of a serialization library or Retrofit.** It's tested (`RequestTest`, `ProviderTest`).
- **Application scope on `Main.immediate`, documented as a single-threaded state owner.** NiA uses `Default`, but the trade-off is explained in `CoroutinesModule.kt:14-18` and blocking work runs on the injected dispatchers.
- **A permanent drawer only at Expanded width (≥840dp) instead of `NavigationSuiteScaffold`.** The app has no top-level destinations, and Material's list-detail pattern shows one pane at Medium width.
- **No WorkManager sync.** Network use is interactive by nature. The app works offline as a reader: the history lives in Room, there's an offline banner, and Send is disabled while offline.

---

## Already done well

- **Following NiA closely where it matters:** Route/Screen split, `hiltViewModel()` scoped to Navigation 3 entries (`ChatApp.kt:59-62`), `collectAsStateWithLifecycle`, `stateIn(WhileSubscribed(5_000))`, immutable `UiState`, interfaces with test fakes (`testing/Test*Repository.kt`), injected dispatchers, `MainDispatcherRule`, and Turbine.
- **Launch:** the splash screen stays up until settings load, as in NiA. System bar styling follows the app's own theme, using the same scrims as NiA (`MainActivity.kt:34-45`).
- **Edge-to-edge and the keyboard:** `Scaffold` padding, `consumeWindowInsets` and `imePadding` are used correctly on both screens. The drawer and bottom sheet handle insets.
- **Material 3:** proper component use (`TopAppBar`, `ModalBottomSheet`, `FilterChip`, `SegmentedButton`, `ListItem`, a toggleable `Switch` row, `AlertDialog` before deleting). Colours come from scheme roles, with `errorContainer` and `onErrorContainer` for failures, and type comes from the M3 scale. Dynamic colour is on by default with a switch to turn it off.
- **Adaptive layout:** window size classes, a readable maximum width, a permanent drawer on tablets, and screenshot tests for phone, foldable and tablet.
- **Accessibility:** headings, merged TalkBack items with custom actions, polite live regions for errors, typing and offline, 48dp targets, text that follows the system font size (screenshots at 2× font), RTL-aware text direction while code stays left-to-right, and ATF checks in CI.
- **Security:** API keys use AES-GCM with a non-exportable Keystore key, are kept out of saved instance state and backups, use password keyboards, and are never logged. Links open only for `http`, `https` and `mailto`. Only the launcher activity is exported. R8 is on.
- **Stability:** streaming failures, cancellation (OkHttp calls are actually cancelled), storage errors during a reply, and replies interrupted by the process dying are all handled and tested.
- **Offline-first reading:** Room is the source of truth, a streaming reply is checkpointed about once a second, and retry follows the SDK rules.
- **Build hygiene:** version catalog, `FAIL_ON_PROJECT_REPOS`, Spotless, `lint { abortOnError = true }`, Room schemas committed, a release build in CI to catch R8 problems, and a Compose stability config for the `:core` types.

## Couldn't verify from the code

- Real-device behaviour of the Keystore, `EncryptedSharedPreferences` migration on actual OEM devices, and cold-start or jank numbers (there are no benchmarks).
- Colour contrast under arbitrary dynamic colour schemes.
- Play Console setup: signing, Data safety form, privacy policy, pre-launch report.
- Foldable posture handling (tabletop or hinge). No code addresses it, and none is required at the "Large screen ready" tier.
