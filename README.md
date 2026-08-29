# IP Camera Video Server

An Android application that turns a phone into a self-hosted IP camera server. Streams live video from front and rear cameras over HTTP, protects access with JWT authentication, records local video archives, and notifies you by SMS when the device's public IP changes.

Primary target: **Huawei P20 Lite** (Android 8.0+, minSdk 26).

## Features

- **MJPEG streaming** — front camera (`/stream/front`), rear camera (`/stream/main`), USB OTG camera (`/stream/usb`)
- **JWT authentication** — token issued at `POST /oauth/token`, required on all stream and status endpoints
- **SMS IP notification** — sends a text with the new server URL when the public IP changes
- **Local archive** — continuous 30-minute MP4 segments; configurable file count and total size cap (default 30 GB)
- **Embedded FTP server** — read-only access to the archive directory (default port 2121)
- **Status dashboard** — local IP, active sessions, one-tap URL copy
- **Boot autostart** — optional; toggle in Settings

## Build and install

Requires Android SDK (API 34) and JDK 17 or 21 (`brew install openjdk@17`).

```bash
# Build debug APK
./scripts/build.sh

# Build + install to connected device + set up port forward
./scripts/install.sh
```

Both scripts auto-detect Homebrew JDK 17/21 and the Android SDK path.

### Manual build commands

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:assembleDebug

# Install
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Connecting the phone (USB)

1. **Settings → About phone → tap "Build number" 7 times** (enables Developer Options)
2. **Settings → Developer options → USB debugging → ON**
3. Connect USB cable; tap **Allow** on the phone when prompted

## Connecting to the server

Huawei's firewall blocks incoming WiFi TCP connections by default. Two options:

**Option A — disable the firewall on the phone (persistent):**

Settings → Developer options → Disable WiFi firewall restrictions

Then connect directly via the device's WiFi IP (shown on the Status screen).

**Option B — USB port forward (no phone setting needed):**

```bash
~/Library/Android/sdk/platform-tools/adb forward tcp:8080 tcp:8080
```

Then connect to `http://127.0.0.1:8080` from this machine.

### Default credentials

| Field    | Value   |
|----------|---------|
| Username | `admin` |
| Password | `admin` |

### Get a token

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/oauth/token \
  -d "username=admin&password=admin" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
```

### Watch a stream

Open in any MJPEG-capable browser or VLC:

```
http://127.0.0.1:8080/stream/main
Authorization: Bearer <token>
```

Via curl:

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/stream/main
```

### Check server status

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/status | python3 -m json.tool
```

## HTTP API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/ping` | No | Health check — returns `pong` |
| `POST` | `/oauth/token` | No | Issue JWT; body: `username=&password=` |
| `GET` | `/stream/front` | Bearer | MJPEG stream from front camera |
| `GET` | `/stream/main` | Bearer | MJPEG stream from rear camera |
| `GET` | `/stream/usb` | Bearer | MJPEG stream from USB OTG camera |
| `GET` | `/status` | Bearer | JSON: server status and active sessions |

## Run unit tests

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:test
```

## Architecture overview

All server logic runs in a foreground `Service` (`CameraServerService`) independent of the UI. The embedded Ktor HTTP server handles streaming and auth. Camera2 JPEG frames are exposed as Kotlin `SharedFlow<ByteArray>` — a camera session opens on first subscriber and closes when idle. WorkManager polls the public IP periodically and fires SMS on change. Settings are persisted in Jetpack DataStore.

See [`CLAUDE.md`](CLAUDE.md) for full architectural detail.

## Permissions

The app requests: `CAMERA`, `RECORD_AUDIO`, `SEND_SMS`, `READ_PHONE_STATE`, `INTERNET`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `WAKE_LOCK`. SMS and phone-state permissions are only used for IP-change notifications; the app degrades gracefully if denied.
