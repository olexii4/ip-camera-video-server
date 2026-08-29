# IP Camera Video Server

An Android application that turns a phone into a self-hosted IP camera server. Streams live video from front and rear cameras over HTTP, protects access with JWT authentication, records local video archives, and notifies you by SMS when the device's public IP changes.

Primary target: **Huawei P20 Lite** (Android 8.0+).

## Features

- **MJPEG streaming** — front camera (`/stream/front`), rear camera (`/stream/main`), and USB OTG camera (`/stream/usb`) over HTTP
- **JWT authentication** — token issued at `POST /oauth/token`, required on all stream and status endpoints
- **SMS IP notification** — sends a text with the new server URL when the public IP changes
- **Local archive** — continuous 30-minute MP4 segments; configurable file count and total size cap (default 30 GB)
- **Embedded FTP server** — read-only access to the archive directory on a configurable port (default 2121)
- **Status dashboard** — shows local IP, active sessions, and a one-tap URL copy button
- **Boot autostart** — optional; the server can start automatically on device boot

## Quick start

### Default credentials

On first launch the server starts with:
- Username: `admin`
- Password: `admin`

Change the password in **Settings → Web Server**.

### Connect to the stream

```
POST http://<device-ip>:8080/oauth/token
Content-Type: application/x-www-form-urlencoded

username=admin&password=admin
```

Then open the stream in any MJPEG-capable browser or player:

```
GET http://<device-ip>:8080/stream/main
Authorization: Bearer <access_token>
```

### FTP access

Enable FTP in Settings. Connect with any FTP client to `<device-ip>:2121` using the same credentials. Files are read-only.

## Build

Requires Android SDK (API 34) and JDK 17.

```bash
./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Install on connected device

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Run unit tests

```bash
./gradlew :app:test
```

## Architecture overview

All server logic runs in a foreground `Service` (`CameraServerService`) independent of the UI. The embedded Ktor HTTP server handles streaming and auth. Camera2 JPEG frames are exposed as Kotlin `SharedFlow<ByteArray>` streams — a camera session opens on first subscriber and closes when idle. WorkManager polls the public IP on a periodic schedule and fires SMS notifications on change. Settings are persisted in Jetpack DataStore.

See [`CLAUDE.md`](CLAUDE.md) for full architectural detail.

## HTTP API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/ping` | No | Health check — returns `pong` |
| `POST` | `/oauth/token` | No | Issue JWT; body: `username=&password=` |
| `GET` | `/stream/front` | Bearer | MJPEG stream from front camera |
| `GET` | `/stream/main` | Bearer | MJPEG stream from rear camera |
| `GET` | `/stream/usb` | Bearer | MJPEG stream from USB OTG camera |
| `GET` | `/status` | Bearer | JSON: server status and active sessions |

## Permissions

The app requests `CAMERA`, `RECORD_AUDIO`, `SEND_SMS`, `READ_PHONE_STATE`, `INTERNET`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, and `WAKE_LOCK`. SMS and phone-state permissions are only used for IP-change notifications; the app degrades gracefully if they are denied.
