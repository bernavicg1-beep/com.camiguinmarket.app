#!/usr/bin/env bash
# Self-test for adb-smoke-test.sh. Runs the real helper against scripts/fake-adb.sh
# without a physical device, so the helper can be regression-tested in CI.

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT/scripts/adb-smoke-test.sh"
FAKE="$ROOT/scripts/fake-adb.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/camiguin-selftest.XXXXXX")" || exit 1
STATE="$TMP/state"
trap 'rm -rf "$TMP"' EXIT

[ -f "$SCRIPT" ] || { echo "FAIL: missing $SCRIPT" >&2; exit 1; }
[ -f "$FAKE" ] || { echo "FAIL: missing $FAKE" >&2; exit 1; }

mkdir -p "$TMP/bin" "$STATE"
cp "$FAKE" "$TMP/bin/adb"
chmod +x "$TMP/bin/adb"
cat > "$TMP/bin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$TMP/bin/sleep"

PASSES=0
FAILURES=0

# init_state <fail_all> <delay> <wireless>
init_state() {
    local fail_all="${1:-0}" delay="${2:-0}" wireless="${3:-0}"
    printf '%s\n' "$fail_all" > "$STATE/fail_all"
    printf '%s\n' "$delay" > "$STATE/delay"
    printf '%s\n' "$wireless" > "$STATE/wireless"
    printf 'home\n' > "$STATE/screen"
    printf 'en\n' > "$STATE/lang"
    printf '1\n' > "$STATE/location"
    printf '0\n' > "$STATE/permission_dialog"
    printf '0\n' > "$STATE/nearby"
    printf '0\n' > "$STATE/offline"
    printf '0\n' > "$STATE/share"
}


# Verify the smoke script restored device state after every run.
assert_cleanup() {
    local ok=1
    if [ "$(cat "$STATE/lang" 2>/dev/null)" != "en" ]; then
        printf 'FAIL: cleanup: lang not restored (got %s, expected en)\n' "$(cat "$STATE/lang" 2>/dev/null)"
        ok=0
    fi
    if [ "$(cat "$STATE/offline" 2>/dev/null)" != "0" ]; then
        printf 'FAIL: cleanup: network not restored (offline=%s, expected 0)\n' "$(cat "$STATE/offline" 2>/dev/null)"
        ok=0
    fi
    if [ "$(cat "$STATE/location" 2>/dev/null)" != "1" ]; then
        printf 'FAIL: cleanup: location not restored (got %s, expected 1)\n' "$(cat "$STATE/location" 2>/dev/null)"
        ok=0
    fi
    if [ "$(cat "$STATE/permission_dialog" 2>/dev/null)" != "0" ]; then
        printf 'FAIL: cleanup: permission dialog not closed (got %s, expected 0)\n' "$(cat "$STATE/permission_dialog" 2>/dev/null)"
        ok=0
    fi
    if [ "$(cat "$STATE/screen" 2>/dev/null)" != "home" ]; then
        printf 'FAIL: cleanup: screen not home (got %s, expected home)\n' "$(cat "$STATE/screen" 2>/dev/null)"
        ok=0
    fi
    if [ "$ok" -eq 1 ]; then
        printf 'PASS: cleanup restored (English, network online, location granted, dialog closed, Home)\n'
        PASSES=$((PASSES + 1))
    else
        FAILURES=$((FAILURES + 1))
    fi
}
# run_case <case_name> <flow> <expected_exit> <max_tries> <assertions...>
run_case() {
    local case_name="$1" flow="$2" expected="$3" max_tries="$4"
    shift 4

    init_state "${FAIL_ALL:-0}" "${DELAY:-0}" "${WIRELESS:-0}"
    local status output="$TMP/out" needle
    PATH="$TMP/bin:$PATH" FAKE_ADB_STATE="$STATE" ADB_BIN="$TMP/bin/adb" \
        SMOKE_MAX_WAIT_TRIES="$max_tries" bash "$SCRIPT" "$flow" > "$output" 2>&1
    status=$?
    assert_cleanup

    if [ "$status" -ne "$expected" ]; then
        printf 'FAIL: %s exited %s, expected %s\n' "$case_name" "$status" "$expected"
        cat "$output"
        FAILURES=$((FAILURES + 1))
        return
    fi
    for needle in "$@"; do
        if ! grep -Fq -- "$needle" "$output"; then
            printf 'FAIL: %s missing: %s\n' "$case_name" "$needle"
            cat "$output"
            FAILURES=$((FAILURES + 1))
            return
        fi
    done
    printf 'PASS: %s\n' "$case_name"
    PASSES=$((PASSES + 1))
}

unset ANDROID_SERIAL SMOKE_PACKAGE

# Shared assertion list for any successful full run.
SUCCESS_ASSERTIONS=(
    "PASS: device reports no default network"
    "PASS: cached listings render with the network off"
    "PASS: offline banner is visible"
    "PASS: network is back after re-enabling Wi-Fi/data"
    "PASS: Bisaya persisted in ui_prefs/lang"
    "PASS: location prompt is visible without a fix"
    "PASS: share card PNG was created in app cache"
    "PASS: saved_search_check is registered in WorkManager"
    "Summary: 12 passed, 0 failed, 0 skipped"
)

# Consolidated runs: each 'all' invocation exercises all five flows in one pass.

FAIL_ALL=0; DELAY=0; WIRELESS=0
run_case "success" "all" 0 1 "${SUCCESS_ASSERTIONS[@]}"

FAIL_ALL=1; DELAY=0; WIRELESS=0
run_case "fail-all" "all" 1 1 \
    "FAIL: cached listings did not render with the network off" \
    "NOTE: offline banner not shown" \
    "FAIL: ui_prefs/lang is 'en', expected 'ceb'" \
    "FAIL: Bisaya label is not visible" \
    "FAIL: location prompt is not visible without a fix" \
    "FAIL: could not open a listing" \
    "FAIL: saved_search_check was not found in WorkManager" \
    "Summary: 5 passed, 6 failed, 0 skipped"

# Delayed case: the permission dialog and share PNG appear only after several
# polls, proving both retries (wait_for_permission_dialog and poll share_file_exists)
# succeed before the check passes.
FAIL_ALL=0; DELAY=1; WIRELESS=0
run_case "delayed" "all" 0 7 "${SUCCESS_ASSERTIONS[@]}"

FAIL_ALL=0; DELAY=1; WIRELESS=0
run_case "delayed-exhaustion" "all" 1 3 \
    "PASS: cached listings render with the network off" \
    "FAIL: location prompt is not visible without a fix" \
    "FAIL: share card PNG was not created" \
    "Summary: 10 passed, 2 failed, 0 skipped"

# Unique path not covered by the aggregate runs.
FAIL_ALL=0; DELAY=0; WIRELESS=1
run_case "offline-wireless-skip" "offline" 0 1 \
    "SKIP: offline check needs USB adb; airplane mode would drop wireless adb" \
    "Summary: 0 passed, 0 failed, 1 skipped"

printf '\nSelf-test: %d passed, %d failed\n' "$PASSES" "$FAILURES"
[ "$FAILURES" -eq 0 ]
