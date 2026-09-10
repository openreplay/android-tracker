# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository purpose

OpenReplay Android Tracker — an Android SDK (`:tracker`) for session replay + analytics, plus a sample app (`:app`) that demonstrates integration. The tracker is published as `com.openreplay.tracker:openreplay` (see `tracker/build.gradle.kts` for the current version). Jitpack publishes via `./gradlew clean build :tracker:publishToMavenLocal` (see `jitpack.yml`); use the same target locally before testing a consumer.

## Build / run

- Toolchain: Kotlin 2.0, AGP 8.5.1, JDK 17 (`jitpack.yml`). Tracker `minSdk=21`, app `minSdk=24`, both `compileSdk=34`. Keep the tracker minSdk at 21 — it’s an intentional library/app split.
- Common commands (run from repo root):
  - `./gradlew :app:assembleDebug` — build the sample app
  - `./gradlew :tracker:assembleRelease` — build the AAR
  - `./gradlew :tracker:publishToMavenLocal` — publish locally for consumer testing
  - `./gradlew :tracker:test` / `:tracker:connectedAndroidTest` — unit and instrumented tests
  - Single JUnit test: `./gradlew :tracker:test --tests "com.openreplay.tracker.MyTest.myMethod"`
- Dependencies live in `gradle/libs.versions.toml` (version catalog). Add new libs there, never inline in the module `build.gradle.kts`.

## Credentials (sample app only)

`app/build.gradle.kts` injects `BuildConfig.OR_SERVER_URL` and `BuildConfig.OR_PROJECT_KEY` via a `getLocalProperty` helper that resolves in this order: `local.properties` → Gradle property → env var → default. The tracker library itself takes these as `start()` arguments — don’t bake keys into the library. If `OR_PROJECT_KEY` is empty the app starts but tracking is disabled (warning logged). `local.properties` is gitignored; `local.properties.example` is the template.

## Architecture

The public surface is the `OpenReplay` Kotlin `object` (singleton) in `tracker/src/main/java/com/openreplay/tracker/ORTracker.kt`. Everything below it is internal collaborators reached via that object.

**Session lifecycle** (`ORTracker.kt`):
- `start()` stores app context, initializes `NetworkManager`/`UserDefaults`/`LifecycleManager`, then registers a `ConnectivityManager.NetworkCallback` (or pre-N `BroadcastReceiver`) that calls `startSession()` once connectivity is suitable (respects `OROptions.wifiOnly`, default `false` — WiFi and cellular both record).
- `startSession()` is guarded by `synchronized(sessionLock)` + `@Volatile isSessionStarted` to prevent duplicate starts from repeated network callbacks. It POSTs to `/v1/mobile/start` via `SessionRequest.create()` and, on success, starts the per-feature subsystems gated on `OROptions` flags (`screen`, `logs`, `crashes`, `performances`, `analytics`).
- `coldStart()` is the offline-first variant: sets `bufferingMode = true`, calls `MessageCollector.cycleBuffer()` to keep messages in a rolling in-memory buffer, and waits for `triggerRecording()` (typically driven by `ConditionsManager`) to flush via `MessageCollector.syncBuffers()` and start normal ingestion.
- `pause()` / `resume()` stop and restart the subsystems without tearing down the session, network callbacks, or lifecycle manager. `stop()` is the full teardown — it unregisters network callbacks, lifecycle callbacks, the gesture detector, and resets `isSessionStarted`.
- Crash safety: `checkForLateMessages()` reads `cacheDir/lateMessages.dat` on startup and POSTs to `/v1/mobile/late`. `MessageCollector` writes buffered bytes there so a crash in the prior run is recoverable on next launch.

**Managers** (`tracker/.../managers/`):
- `NetworkManager` — single HTTP client; endpoints are `/v1/mobile/{start,i,late,images,conditions}` with gzip. Initialized once with the app context.
- `MessageCollector` — batches binary `ORMessage`s up to `maxMessagesSize`, schedules flushes on a `ScheduledExecutorService`, supports `pause/resume/cycleBuffer/syncBuffers`. This is also the path that backs `lateMessages.dat`.
- `ScreenshotManager` — captures the activity window via `PixelCopy` at the FPS/resolution returned by `getCaptureSettings(fps, RecordingQuality)`, encodes JPEG (quality 40/50/60, matching iOS), and batches frames into archives. Archive format follows `framesSupport` from the `/start` response: `true` → gzipped length-prefixed `[ts u64 LE][size u32 LE][jpeg]` records named `<sid>-<lastTs>.gz`, uploaded with a `type=frames` form field; `false` → legacy `.tar.gz` of `<firstTs>_1_<ts>.jpeg`. Encoding/naming lives in `ScreenshotArchiveFormat` (pure JVM, unit-tested). Maintains a list of "sanitized" views and overlays a cross-stripe paint over them before sending.
- `ConditionsManager` — server-driven conditions fetched from `/v1/mobile/conditions`; used with `coldStart`/`triggerRecording`.
- `UserDefaults`, `DebugUtils`, `MessageHandler` — persistence, logging, and the dispatcher for raw `sendMessage(type, msg)`.

**Listeners** (`tracker/.../listeners/`):
- `Analytics` — touch/gesture capture and **automatic EditText discovery**: when an activity is shown, all `EditText`s are auto-wired with `trackTextInput()`. Label resolution order is hint → contentDescription → resource id. Password input types (`TYPE_TEXT_VARIATION_PASSWORD`, `WEB_PASSWORD`, `NUMBER_VARIATION_PASSWORD`) are auto-masked. Opt-out per-view via the `View.excludeFromTracking()` extension; manual override via `View.trackTextInput(label, masked)`.
- `LifecycleManager` — `ActivityLifecycleCallbacks` holding the current activity in a `WeakReference`, tracking a foreground activity count to drive pause/resume, and surviving configuration changes. It also calls `OpenReplay.setupGestureDetectorForActivity()` so each new activity gets its gesture detector wired through `Activity.dispatchTouchEvent` → `OpenReplay.onTouchEvent`.
- `Crash`, `LogListener`, `NetworkListener`, `PerformanceListener` — feature-flagged subsystems started from `startSession()`.

**Models** (`tracker/.../models/`):
- `OROptions` — feature flag bag (`crashes/analytics/performances/logs/screen/wifiOnly/debugLogs/...`) plus `RecordingQuality` and `RecordingFrequency` enums.
- `ORMessage` + `models/script/ORMessages.kt` — binary message format. `ORMessages.kt` is **generated-style** code with a Ruby script (`models/script/messages.rb`) — edit the source-of-truth carefully and keep both in sync.
- `Session` / `SessionRequest` / `SessionResponse` — `/v1/mobile/start` request/response shapes.

**Sanitization — two independent axes (don’t conflate):**
- `view.sanitize()` (extension) → screenshot-only visual masking (cross-stripes), implemented in `ScreenshotManager`.
- `trackTextInput(masked = true)` → input-event-only data masking; the screenshot still shows the field.
- For full privacy, apply both. See README "Screenshot Sanitization" for the canonical example.

**Known platform limitation:** `PixelCopy` only captures the activity's main window, so `AlertDialog`/`BottomSheetDialog`/system dialogs are **not** in screenshots. Interaction events (touches, inputs, custom `OpenReplay.event(...)`) still fire — when working on dialog flows, lean on events rather than expecting visuals.

## Memory-leak hygiene (this codebase cares)

Recent history (`git log`) shows multiple memory-leak fixes. Patterns to follow when editing tracker code:
- Always store `context.applicationContext`; never hold an `Activity` strongly. `LifecycleManager` uses `WeakReference<Activity>` — match that.
- Anything registered in `start()` (network callbacks, broadcast receivers, lifecycle callbacks, gesture detectors) **must** be unregistered in `stop()` and re-checked on re-`start()`. The existing code wraps unregister calls in `try/catch (IllegalArgumentException)` because Android throws when something wasn’t registered — preserve that pattern.
- Background work uses `CoroutineScope(Dispatchers.IO)` plus `ScheduledExecutorService` in `MessageCollector`; don’t introduce new long-lived scopes without a corresponding shutdown in `stop()`.

## Where things are

- `tracker/src/main/java/com/openreplay/tracker/ORTracker.kt` — public API, session orchestration
- `tracker/src/main/java/com/openreplay/tracker/managers/` — network, batching, screenshots, persistence
- `tracker/src/main/java/com/openreplay/tracker/listeners/` — input/touch/lifecycle/crash/log/perf capture
- `tracker/src/main/java/com/openreplay/tracker/models/script/` — wire format (`ORMessages.kt` + `messages.rb` codegen)
- `app/src/main/java/com/openreplay/sampleapp/` — sample integration; `MainActivity.kt` is the configuration entry point

For setup-flow details that won’t fit here (input-tracking semantics, masking rules, screenshot limits) see `README.md` and `SETUP.md` — both are kept current.
