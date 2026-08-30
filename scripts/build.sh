#!/usr/bin/env bash
#
# build.sh — Build the debug APK
#
# Compiles the Android app and produces:
#   app/build/outputs/apk/debug/app-debug.apk
#
# Requirements:
#   - macOS with Homebrew JDK 17 or 21 (brew install openjdk@17)
#     OR Android Studio installed (uses its bundled JRE)
#   - Android SDK in ~/Library/Android/sdk (set sdk.dir in local.properties if different)
#
# Usage:
#   ./scripts/build.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Prefer Homebrew JDK 17; fall back to JDK 21 or Android Studio's bundled JDK
JAVA17_BREW="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
JAVA21_BREW="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
AS_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

if [[ -d "$JAVA17_BREW" ]]; then
    export JAVA_HOME="$JAVA17_BREW"
elif [[ -d "$JAVA21_BREW" ]]; then
    export JAVA_HOME="$JAVA21_BREW"
elif [[ -d "$AS_JBR" ]]; then
    export JAVA_HOME="$AS_JBR"
else
    echo "ERROR: Could not find JDK 17 or 21. Install with: brew install openjdk@17" >&2
    exit 1
fi

echo "Using JDK: $JAVA_HOME"
echo "Building debug APK..."

cd "$PROJECT_DIR"
./gradlew :app:assembleDebug

APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "Build successful: $APK"
echo "Run ./scripts/install.sh to deploy to a connected phone."
