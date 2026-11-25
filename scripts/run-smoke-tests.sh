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
#   SMOKE_TEST_CLASSES='pkg.Cls#test,pkg.Cls#other'  (run only these tests)

: "${SMOKE_TEST_EMAIL:?Set SMOKE_TEST_EMAIL to an existing test account}"
: "${SMOKE_TEST_PASSWORD:?Set SMOKE_TEST_PASSWORD to the test account password}"

GRADLE_BIN="${GRADLE_BIN:-gradle}"
ADB=(adb)
if [ -n "${ANDROID_SERIAL:-}" ]; then
  ADB=(adb -s "$ANDROID_SERIAL")
fi

# Maven Central intermittently answers 429 (rate limit) for a runner's IP, which
# fails dependency resolution before the emulator is ever exercised. The build
# never runs tests, so retrying it cannot hide a test failure.
build_apks() {
  "$GRADLE_BIN" :app:assembleDebug :app:assembleDebugAndroidTest
}

attempt=1
until build_apks; do
  if [ "$attempt" -ge 3 ]; then
    echo "Gradle build failed after $attempt attempts" >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  echo "Gradle build failed - retrying in 20s (attempt $attempt)" >&2
  sleep 20
done

"${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
"${ADB[@]}" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# AGP's connectedDebugAndroidTest wrapper drives the same runner, but only the
# plain `am instrument` invocation below matches the local/dev flow, so every
# job runs this script and narrows the run with SMOKE_TEST_CLASSES instead.
MATCH_ARGS=()
if [ -n "${SMOKE_TEST_CLASSES:-}" ]; then
  MATCH_ARGS=(-e class "$SMOKE_TEST_CLASSES")
fi

set +e
"${ADB[@]}" shell am instrument -w -r "${MATCH_ARGS[@]}" \
  -e smokeEmail "$SMOKE_TEST_EMAIL" \
  -e smokePassword "$SMOKE_TEST_PASSWORD" \
  com.camiguinmarket.app.test/androidx.test.runner.AndroidJUnitRunner
status=$?
set -e

if [ "$status" -ne 0 ]; then
  bash "$(dirname "$0")/collect-smoke-artifacts.sh" smoke-failure-artifacts || true
fi
exit "$status"
