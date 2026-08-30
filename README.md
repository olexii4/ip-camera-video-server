# IP Camera Video Server

**Give your old phones a second life as security cameras.**

Old Android phones with cracked screens, worn batteries, or outdated specs are usually thrown away — but their cameras still work perfectly. This project turns any such phone into a self-hosted IP camera with a built-in web server, using both the front and rear cameras as independent streams.

No cloud, no subscription, no account. The phone runs its own HTTP server. You connect to it from any browser on your network.

---

## What it does

- **Streams live video** from the front camera, rear camera, and optionally a USB OTG camera — each as an independent low-latency MJPEG stream (640×480, ~70ms delay)
- **Browser-based web UI** at `http://<phone-ip>:8080` — login, start/stop individual camera streams, view live video, browse and download recordings, all without installing anything on the client
- **Records continuously** in 15-minute MP4 segments with optional microphone audio to the phone's storage; configurable file count and total size cap (default 30 GB), automatic rotation
- **Recording only runs while someone is watching** — no wasted storage when the camera stream is idle
- **Live WebSocket status** — the browser page reflects server state in real time; disconnects automatically redirect to the login screen
- **Sends an SMS** when the phone's public IP address changes, so you can always find it
- **Serves recorded files over FTP or FTPS** for easy bulk download; FTPS uses a hardware-backed certificate from Android Keystore
- **Single-session enforcement** — a new login revokes all other active sessions
- **Requires no screen interaction** after setup — designed for phones with broken displays

---

## Target hardware

Primary target: **Huawei P20 Lite** (Android 8.0+, dual front/rear camera).

Works on any Android 8.0+ phone. Dual-camera phones give you two independent streams; single-camera phones give you one. USB OTG support adds a third stream from a UVC webcam.

---

## Install

Download the latest APK from the [Releases page](../../releases) and install it directly on the phone, or build and deploy from source (see [Build from source](#build-from-source)).

### One-time setup on the phone

1. **Enable Developer Options**: Settings → About phone → tap **Build number** 7 times
2. **Enable USB debugging**: Settings → Developer options → USB debugging → ON
3. Connect the phone to your Mac via USB and tap **Allow** when prompted
4. Install from a release APK:
   ```bash
   ~/Library/Android/sdk/platform-tools/adb install ip-camera-video-server.apk
   ```
   Or build and install from source in one step:
   ```bash
   ./scripts/install.sh
   ```

Default credentials: `admin` / `admin` — change in **Settings → Access control**.

### Daily use (no USB needed)

Once installed, the phone runs the server independently:
- Plug the phone into power (charger, USB hub, etc.)
- The server starts automatically on boot (enable in Settings)
- Connect to `http://<phone-ip>:8080` from any browser on your network

To find the phone's IP: the Status screen in the app shows the current local IP.
If the IP changes: the SMS notification feature texts you the new address automatically.

---

## Web interface

Open `http://<phone-ip>:8080` in any browser.

### Cameras tab

Three camera tiles are always shown — **Main**, **Front**, and **USB**:
- Each tile is **350×200 px** in its normal state
- Press **▶ Play** to start streaming that camera (only one camera can be active at a time on most phones)
- Press **■ Stop** to stop the stream
- Click **Expand** to open a full-width panel below the grid with a larger view of the same stream; click **Compress** to close it
- USB tile shows "No USB camera connected" when no OTG device is attached

### Files tab

- List of recorded segments — name, size, date — updated live via WebSocket
- **⬇ Download** button for each file
- **🗑 Delete** button with confirmation

### Login & session management

- Sessions are exclusive: a new login revokes all other active sessions immediately; the displaced browser returns to the login screen with a "Session revoked" message
- **⏏ Logout** button in the header revokes the current session and returns to login

---

## App settings

| Section | Setting | Default |
|---------|---------|---------|
| Web Server | Port | 8080 |
| Web Server | Start on boot | Off |
| Access control | Require login | On |
| Access control | Username | admin |
| Access control | Password | admin |
| SMS notification | Target phone number | — |
| Archive | Record main camera | Off |
| Archive | Record front camera | Off |
| Archive | Record microphone audio | Off |
| Archive | Max files | 1440 |
| Archive | Max storage | 30 GB |
| FTP Server | FTP enabled | Off |
| FTP Server | Use FTPS (encrypted) | Off |
| FTP Server | Port | 2121 (plain) / 2122 (FTPS) |

Changes take effect after **Stop → Start Server**.

---

## Connecting from other devices (LAN access)

Huawei phones block incoming WiFi connections by default. Two options:

**Option A — disable the firewall on the phone (permanent):**

Settings → Developer options → Disable WiFi firewall restrictions

Then connect directly at `http://<phone-ip>:8080` from any device.

**Option B — Mac relay (development / testing only):**

With the USB cable connected:
```bash
./scripts/relay.sh
```
This relays `<mac-ip>:8080` → phone via USB tunnel. Other devices on the same WiFi can connect to the Mac's IP instead of the phone's IP. See `scripts/relay.sh` for details.

---

## FTP access to recordings

Enable in **Settings → FTP Server**. Same credentials as the web UI.

| Mode | Default port | Security |
|------|-------------|----------|
| Plain FTP | 2121 | Unencrypted — LAN only |
| FTPS | 2122 | TLS — encrypted |

When FTPS is enabled, the app generates a self-signed certificate stored in the Android Keystore (hardware-backed, never leaves the device). The **SHA-256 fingerprint** is shown in Settings — enter it in your FTP client (FileZilla: Site Manager → Trust this certificate with fingerprint) to verify the server's identity.

---

## Build from source

### Requirements

- macOS with Android SDK installed
- JDK 17 or 21 (`brew install openjdk@17`)

### Scripts

| Script | Purpose |
|--------|---------|
| `scripts/build.sh` | Compile the debug APK. Auto-detects JDK from Homebrew or Android Studio. |
| `scripts/install.sh` | Build + install on connected phone + configure EMUI background permissions + set up USB port forward. |
| `scripts/relay.sh` | **Dev only.** TCP relay Mac WiFi → USB tunnel, so LAN devices reach the phone while the cable is connected. |

```bash
# Build only
./scripts/build.sh

# Build + deploy to connected phone + open browser tunnel
./scripts/install.sh
```

---

## Architecture

All server logic runs in an Android `ForegroundService` independent of the UI, so it keeps running when the screen is off or broken.

| Component | Technology |
|-----------|-----------|
| HTTP server | Ktor CIO — MJPEG multipart streams, JWT auth |
| Camera | Camera2 API — 640×480 JPEG, single-buffer for low latency |
| Streaming | `SharedFlow<ByteArray>` with `WhileSubscribed(200ms)` — camera closes automatically when last viewer disconnects |
| Real-time updates | WebSocket `/ws` — status + file list pushed every 2 s |
| Archive | MediaRecorder → MP4, 15-min segments, optional AAC audio |
| IP monitoring | WorkManager periodic task + SmsManager |
| Settings | Jetpack DataStore |
| FTP/FTPS | Hand-rolled passive-mode server; TLS via Android Keystore |
| DI | Hilt |
| UI | Jetpack Compose + Navigation |

See [`CLAUDE.md`](CLAUDE.md) for full architectural detail.

---

## Permissions

| Permission | Used for |
|-----------|---------|
| `CAMERA` | Front and rear camera access |
| `RECORD_AUDIO` | Microphone audio in recordings |
| `INTERNET` | HTTP server, public IP polling |
| `ACCESS_WIFI_STATE` | Read local IP address |
| `SEND_SMS` | IP-change notifications |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` | Keep server alive when screen is off |
| `WAKE_LOCK` | Prevent CPU sleep while streaming |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |
