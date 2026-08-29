#!/usr/bin/env bash
# Relay connections from this Mac's WiFi interface → phone via USB tunnel.
# Other devices on the same WiFi connect to <mac-ip>:8080 and reach the camera server.
set -euo pipefail

ADB="$HOME/Library/Android/sdk/platform-tools/adb"

DEVICES=$("$ADB" devices | grep -v "^List" | grep "device$" | wc -l | tr -d ' ')
if [[ "$DEVICES" -eq 0 ]]; then
    echo "ERROR: No device connected via USB." >&2
    exit 1
fi

# Ensure port forward is active
"$ADB" forward tcp:8080 tcp:8080

MAC_IP=$(ipconfig getifaddr en7 2>/dev/null || ipconfig getifaddr en0 2>/dev/null || echo "unknown")
echo "Phone server is reachable at:"
echo "  USB (this Mac only): http://127.0.0.1:8080"
echo "  WiFi relay (all LAN): http://$MAC_IP:8080"
echo ""
echo "Press Ctrl+C to stop the relay."
echo ""

# Python TCP relay: listen on all interfaces:8080, forward to 127.0.0.1:8080
python3 - <<'PYEOF'
import socket, threading, sys

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
