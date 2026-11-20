#!/usr/bin/env bash
set -euo pipefail

# Runs the five-flow device smoke suite. Credentials come from the shell
# environment and are passed only as instrumentation arguments; they are never
# written to the APK, Gradle files, or the repository.
#
# Usage:
#   SMOKE_TEST_EMAIL=... SMOKE_TEST_PASSWORD=... scripts/run-smoke-tests.sh
#
# Optional:
#   ANDROID_SERIAL=emulator-5554  (or a physical device serial)
#   GRADLE_BIN=gradle             (defaults to gradle)

: "${SMOKE_TEST_EMAIL:?Set SMOKE_TEST_EMAIL to an existing test account}"
: "${SMOKE_TEST_PASSWORD:?Set SMOKE_TEST_PASSWORD to the test account password}"

GRADLE_BIN="${GRADLE_BIN:-gradle}"
ADB=(adb)
if [ -n "${ANDROID_SERIAL:-}" ]; then
  ADB=(adb -s "$ANDROID_SERIAL")
fi

"$GRADLE_BIN" :app:assembleDebug :app:assembleDebugAndroidTest

"${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
"${ADB[@]}" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

set +e
"${ADB[@]}" shell am instrument -w -r \
  -e smokeEmail "$SMOKE_TEST_EMAIL" \
  -e smokePassword "$SMOKE_TEST_PASSWORD" \
  com.camiguinmarket.app.test/androidx.test.runner.AndroidJUnitRunner
status=$?
set -e

if [ "$status" -ne 0 ]; then
  bash "$(dirname "$0")/collect-smoke-artifacts.sh" smoke-failure-artifacts || true
fi
exit "$status"
