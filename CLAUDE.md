# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Before starting any work

Read these project-specific rules and skills first — they override defaults:

- **`.claude/rules/development-conventions.md`** — commit message format, permitted trailers, code style rules. Always follow before making any commit.
- **`.claude/skills/commit.md`** — commit template and rules. Use when creating any commit.
- **`.claude/skills/run.md`** — how to build, install, and run the app on a connected device. Use before running the app.

## What this project is

An Android application (`com.ipcamera.videoserver`) that turns a phone into a self-hosted IP camera server. It streams MJPEG video from on-device cameras over HTTP, protects endpoints with JWT auth, records MP4 archives to device storage, serves recorded files via an embedded FTP server, and sends SMS alerts when the device's public IP changes.

Primary target: Huawei P20 Lite (Android 8.0+, minSdk 26).

## Build and install

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Run JVM unit tests
./gradlew :app:test

# Run a single test class
./gradlew :app:test --tests "com.ipcamera.videoserver.auth.AuthManagerTest"

# Install on connected device
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch on device
~/Library/Android/sdk/platform-tools/adb shell am start -n com.ipcamera.videoserver/.ui.MainActivity

# Filtered logcat
~/Library/Android/sdk/platform-tools/adb logcat -s CameraServerService,WebServer,FtpServer,IpMonitor
```

`adb` is at `~/Library/Android/sdk/platform-tools/adb` — it is not on `$PATH`.

## Architecture

All server-side logic runs inside a single `ForegroundService` (`CameraServerService`) so it survives the UI being closed. The service is the composition root: it reads settings from DataStore, configures `AuthManager` with the persisted JWT secret and BCrypt password hash, then starts `WebServer` and optionally `FtpServer`. `WorkManager` runs `IpMonitor` on an independent periodic schedule (≥15 min, WorkManager's floor).

**Data flow for a video stream request:**

```
HTTP client → WebServer (Ktor CIO)
           → JWT validation (AuthManager)
           → CameraStreamManager.getStream(source)   ← Camera2 callbackFlow
           → multipart/x-mixed-replace MJPEG response
```

`CameraStreamManager` wraps Camera2 in a `callbackFlow` and exposes each camera as a `SharedFlow<ByteArray>` (JPEG frames). Streams are lazy — the Camera2 session only opens when there is at least one subscriber, and closes 5 seconds after the last one leaves (`SharingStarted.WhileSubscribed`).

**Auth lifecycle:**

`AuthManager` must be configured before `WebServer.start()`. The service always calls `authManager.configure(jwtSecret)` then `authManager.setHashedCredentials(username, hash)` during `onCreate`. On first launch the hash is generated from the hardcoded default password `"admin"` and persisted; it can be changed from Settings. The JWT secret is generated once and persisted — rotating it invalidates all existing tokens.

**Archive rotation:**

`enforceRotationByCount` and `enforceRotationBySize` are package-level functions in `ArchiveManager.kt` (not methods), making them trivially unit-testable without Android dependencies. They are called by `ArchiveManager.enforceRotation()`. Default limits: 1440 files, 30 GB.

**FTP server:**

`FtpServer` is a minimal hand-rolled passive-mode FTP server (no library). It only implements `USER`, `PASS`, `PASV`, `LIST`, `RETR`, `SIZE`, `SYST`, `FEAT`, `PWD`, `CWD`, `TYPE`, `NOOP`, `QUIT`. The root is locked to `archiveDir` — `RETR` rejects any path that escapes it via `canonicalPath` check.

**Settings:**

All settings are in `AppSettings` (Jetpack DataStore). Every setting is a `Flow<T>` with a default, paired with a `suspend fun set*()`. Nothing is stored in `SharedPreferences`.

**UI:**

Single `MainActivity` with a bottom-nav `NavHost` (`status`, `settings`, `archive`). All screens receive the single `AppViewModel`. Server start/stop buttons are on the Status screen; they call `startForegroundService` / `stopService` directly. `CameraServerService.serverState` and `.localIp` are `StateFlow` companions so the UI can observe them without binding to the service.

## Commit conventions

Subject line ≤ 50 chars, conventional commits (`feat`, `fix`, `chore`, `refactor`, `test`, `docs`).

Do not add AI explanation comments inside source code.

## Key constraints

- Kotlin only — no Java source files.
- All async via Coroutines + Flow — no RxJava, no `AsyncTask`.
- Hilt for DI everywhere; `@AndroidEntryPoint` on `CameraServerService` and `MainActivity`.
- `IpMonitor` is a `@HiltWorker` using `@AssistedInject` — the WorkManager Hilt integration requires both `hilt-work` and `hilt-work-compiler` KSP dependencies.
- `META-INF/INDEX.LIST` and `META-INF/io.netty.versions.properties` must be excluded in `packaging` (Ktor/Netty conflict).
- WorkManager's minimum periodic interval is 15 minutes; `IpMonitor.schedule` clamps the user-configured value with `coerceAtLeast(15)`.
