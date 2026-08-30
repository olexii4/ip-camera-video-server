#!/usr/bin/env bash
#
# local-run.sh — LOCAL DEVELOPMENT ONLY
#
# Problem this solves:
#   Huawei (EMUI) blocks all incoming TCP connections on the WiFi interface by default,
#   so other devices on the LAN cannot reach the camera server at 192.168.0.100:8080
#   even though the server is running and the port is open.
#
# How it works:
#   1. Sets up an ADB USB port forward: localhost:8080 → phone:8080
#   2. Starts a Python TCP relay that listens on ALL interfaces of this Mac (0.0.0.0:8080)
#      and pipes each connection through the USB tunnel to the phone
#
# Result:
#   Other devices on the same WiFi can reach the camera server at the Mac's IP:
#     http://<mac-ip>:8080    (e.g. http://192.168.0.103:8080)
#
# Limitations:
#   - Requires this Mac to stay running with the USB cable connected
#   - Port 8080 must not be in use on the Mac
#   - This is a development/testing workaround; for permanent LAN access on the phone
#     go to Settings → Developer options → Disable WiFi firewall restrictions
#
# Usage:
#   ./scripts/local-run.sh
#   # Then open http://<mac-ip>:8080 on any device on the same WiFi
#   # Press Ctrl+C to stop
#
set -euo pipefail

ADB="$HOME/Library/Android/sdk/platform-tools/adb"

DEVICES=$("$ADB" devices | grep -v "^List" | grep "device$" | wc -l | tr -d ' ')
if [[ "$DEVICES" -eq 0 ]]; then
    echo "ERROR: No device connected via USB." >&2
    echo "Connect the phone via USB with USB debugging enabled, then retry." >&2
    exit 1
fi

# Ensure port forward is active
"$ADB" forward tcp:8080 tcp:8080

MAC_IP=$(ipconfig getifaddr en7 2>/dev/null || ipconfig getifaddr en0 2>/dev/null || echo "unknown")
echo "Phone server is reachable at:"
echo "  USB only (this Mac): http://127.0.0.1:8080"
echo "  All LAN devices:     http://$MAC_IP:8080"
echo ""
echo "Press Ctrl+C to stop the relay."
echo ""

# Pure Python TCP relay — no extra dependencies required
python3 - <<'PYEOF'
import socket, threading

LOCAL_PORT  = 8080
TARGET_HOST = "127.0.0.1"
TARGET_PORT = 8080

def relay(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        for s in (src, dst):
            try: s.shutdown(socket.SHUT_RDWR)
            except: pass
            try: s.close()
            except: pass

srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", LOCAL_PORT))
srv.listen(32)
print(f"Relay listening on 0.0.0.0:{LOCAL_PORT} → {TARGET_HOST}:{TARGET_PORT}")

while True:
    client, addr = srv.accept()
    print(f"  Connected: {addr[0]}:{addr[1]}")
    try:
        upstream = socket.create_connection((TARGET_HOST, TARGET_PORT), timeout=5)
    except Exception as e:
        print(f"  Upstream error: {e}")
        client.close()
        continue
    for a, b in [(client, upstream), (upstream, client)]:
        threading.Thread(target=relay, args=(a, b), daemon=True).start()
PYEOF
