#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"

if [[ ! -f "$ADB" ]]; then
    echo "ERROR: adb not found at $ADB" >&2
    exit 1
fi

if [[ ! -f "$APK" ]]; then
    echo "APK not found. Building first..."
    "$SCRIPT_DIR/build.sh"
fi

echo "Checking for connected device..."
DEVICES=$("$ADB" devices | grep -v "^List" | grep "device$" | wc -l | tr -d ' ')
if [[ "$DEVICES" -eq 0 ]]; then
    echo "ERROR: No Android device connected via USB." >&2
    echo "  1. Enable Developer Options: Settings → About phone → tap 'Build number' 7 times" >&2
    echo "  2. Enable USB debugging: Settings → Developer options → USB debugging" >&2
    echo "  3. Connect USB cable and tap 'Allow' on the phone" >&2
    exit 1
fi

echo "Installing $APK..."
"$ADB" install -r "$APK"

echo ""
echo "Launching app..."
"$ADB" shell am start -n com.ipcamera.videoserver/.ui.MainActivity

echo ""
echo "Setting up ADB port forward (localhost:8080 → device:8080)..."
"$ADB" forward tcp:8080 tcp:8080
echo ""
echo "App installed and running."
echo "Connect to the server at: http://127.0.0.1:8080"
echo ""
echo "Get a token:"
echo "  curl -s -X POST http://127.0.0.1:8080/oauth/token -d 'username=admin&password=admin'"
