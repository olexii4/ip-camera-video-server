# IP Camera Video Server

**Give your old phones a second life as security cameras.**

Old Android phones with cracked screens, worn batteries, or outdated specs are usually thrown away — but their cameras still work perfectly. This project turns any such phone into a self-hosted IP camera with a built-in web server, using both the front and rear cameras simultaneously as separate streams.

No cloud, no subscription, no account. The phone runs its own HTTP server. You connect to it from any browser on your network.

---

## What it does

- **Streams live video** from the front camera, rear camera, and optionally a USB OTG camera — each as an independent MJPEG stream
- **Hosts a web UI** at `http://<phone-ip>:8080` — login, view streams, browse and download recordings, all in the browser
- **Records continuously** in 15-minute MP4 segments to the phone's storage, with configurable size limits and automatic rotation
- **Sends an SMS** when the phone's public IP address changes, so you can always find it
- **Serves recorded files over FTP/FTPS** for easy bulk download
- **Requires no screen interaction** after setup — designed for phones with broken displays

---

## Target hardware

Primary target: **Huawei P20 Lite** (Android 8.0+, dual camera).

Works on any Android 8.0+ phone. Dual-camera phones give you two independent streams; single-camera phones give you one. USB OTG support adds a third stream from a UVC webcam.

---

## How to use

### One-time setup on the phone

1. **Enable Developer Options**: Settings → About phone → tap **Build number** 7 times
2. **Enable USB debugging**: Settings → Developer options → USB debugging → ON
3. Connect the phone to your Mac via USB and tap **Allow** when prompted
4. Run the install script:
   ```bash
   ./scripts/install.sh
   ```
   This builds the APK, installs it, configures EMUI background service permissions, and opens the app.

5. Press **Start** in the app, then open **http://127.0.0.1:8080** in your browser.

Default credentials: `admin` / `admin` — change in **Settings → Access control**.

### Daily use (no USB needed)

Once installed, the phone runs the server independently:
- Plug the phone into power (charger, USB hub, etc.)
- The server starts automatically on boot (enable in Settings)
- Connect to `http://<phone-ip>:8080` from any browser on your network

To find the phone's IP: the Status screen in the app shows the current local IP.  
If the IP changes: the SMS notification feature texts you the new address.

---

## Web interface

Open `http://<phone-ip>:8080` in any browser (Chrome, Firefox, Safari, mobile browsers).

| Tab | What it does |
|-----|-------------|
| **Cameras** | 350×200px tiles for each camera source. Press ▶ Play to start a stream. Click **Expand** to open a full-width panel below the grid. |
| **Files** | List of recorded segments with file size and date. Download or delete individual files. Updated live via WebSocket. |

Sessions are exclusive: a new login revokes all other active sessions. The browser returns to the login page if the server stops or the session is revoked.

---

## Connecting from other devices (LAN access)

Huawei phones block incoming WiFi connections by default. Two options:

**Option A — disable the firewall on the phone (permanent):**

Settings → Developer options → Disable WiFi firewall restrictions

Then connect directly at `http://<phone-ip>:8080` from any device.

**Option B — Mac relay (development/testing only):**

With the USB cable connected and `./scripts/install.sh` already run:

```bash
./scripts/relay.sh
```

This relays `<mac-ip>:8080` → phone via USB tunnel. Other devices on the same WiFi can connect to the Mac's IP instead of the phone's IP.

---

## FTP access to recordings

Enable in **Settings → FTP Server**. Credentials are the same as the web login.

| Mode | Port | Security |
|------|------|----------|
| Plain FTP | 2121 | Unencrypted — LAN only |
| FTPS | 2122 | TLS — encrypted |

When FTPS is enabled, the app generates a self-signed certificate stored in the Android Keystore (hardware-backed). The SHA-256 fingerprint is shown in Settings — enter it in your FTP client to verify the server's identity.

---

## Build scripts

| Script | Purpose |
|--------|---------|
| `scripts/build.sh` | Compile the APK. Auto-detects JDK 17/21 from Homebrew or Android Studio. |
| `scripts/install.sh` | Build + install on a connected phone + configure EMUI permissions + port forward. |
| `scripts/relay.sh` | **Development only.** TCP relay from Mac WiFi → USB tunnel, so LAN devices can reach the phone server while the USB cable is connected. |

---

## Architecture

All server logic runs in an Android `ForegroundService` so it keeps running when the screen is off or when using a phone with a broken display. The UI (if available) is a Jetpack Compose single-activity app that connects to the service.

- **HTTP server**: Ktor CIO, MJPEG multipart streams, JWT auth
- **Camera**: Camera2 API, 640×480 JPEG, single buffer for low latency
- **Archive**: MediaRecorder → MP4, 15-min segments, count + 30 GB size cap
- **IP monitoring**: WorkManager periodic task, SmsManager
- **Settings**: Jetpack DataStore
- **FTP/FTPS**: hand-rolled passive-mode server, TLS via Android Keystore
- **WebSocket**: live status + file list pushed every 2 seconds

See [`CLAUDE.md`](CLAUDE.md) for full architectural detail.

---

## Permissions

| Permission | Used for |
|-----------|---------|
| `CAMERA` | Front and rear camera access |
| `RECORD_AUDIO` | Audio in recordings |
| `INTERNET` | HTTP server, public IP polling |
| `ACCESS_WIFI_STATE` | Read local IP address |
| `SEND_SMS` | IP-change notifications |
| `FOREGROUND_SERVICE` | Keep server alive when screen is off |
| `WAKE_LOCK` | Prevent CPU sleep while streaming |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |
