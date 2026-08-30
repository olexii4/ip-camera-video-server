#!/usr/bin/env bash
#
# test.sh — Run JVM unit tests
#
# Runs all unit tests that do not require an Android device (JVM-only).
# Test reports are written to app/build/reports/tests/testDebugUnitTest/
#
# Requirements:
#   - JDK 17 or 21 (brew install openjdk@17)
#
# Usage:
#   ./scripts/test.sh                         # run all unit tests
#   ./scripts/test.sh AuthManagerTest         # run a single test class
#   ./scripts/test.sh ArchiveManagerTest      # run another class
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

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

cd "$PROJECT_DIR"

if [[ $# -gt 0 ]]; then
    # Run a specific test class
    echo "Running test class: $1"
    ./gradlew :app:test --tests "com.ipcamera.videoserver.*.$1" --no-daemon
else
    echo "Running all unit tests..."
    ./gradlew :app:test --no-daemon
fi

echo ""
echo "Reports: app/build/reports/tests/testDebugUnitTest/index.html"
