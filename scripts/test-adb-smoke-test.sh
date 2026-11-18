#!/usr/bin/env bash
# Self-test for adb-smoke-test.sh. Runs the real helper against scripts/fake-adb.sh.

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

init_state() {
    local fail_all delay
    case "$1" in
        success) fail_all=0; delay=0 ;;
        fail-all) fail_all=1; delay=0 ;;
        delayed) fail_all=0; delay=1 ;;
        *) echo "unknown case: $1" >&2; exit 1 ;;
    esac
    printf '%s\n' "$fail_all" > "$STATE/fail_all"
    printf '%s\n' "$delay" > "$STATE/delay"
    printf 'home\n' > "$STATE/screen"
    printf 'en\n' > "$STATE/lang"
    printf '1\n' > "$STATE/location"
    printf '0\n' > "$STATE/permission_dialog"
    printf '0\n' > "$STATE/nearby"
    printf '0\n' > "$STATE/offline"
    printf '0\n' > "$STATE/share"
}

run_case() {
    local case_name="$1" expected="$2" max_tries="$3"
    shift 3

    init_state "$case_name"
    local status output="$TMP/out" needle
    PATH="$TMP/bin:$PATH" FAKE_ADB_STATE="$STATE" ADB_BIN="$TMP/bin/adb" \
        SMOKE_MAX_WAIT_TRIES="$max_tries" bash "$SCRIPT" all > "$output" 2>&1
    status=$?

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

SUCCESS_ASSERTIONS=(
    "PASS: offline banner is visible"
    "PASS: Bisaya persisted in ui_prefs/lang"
    "PASS: location prompt is visible without a fix"
    "PASS: share card PNG was created in app cache"
    "PASS: saved_search_check is registered in WorkManager"
    "Summary: 9 passed, 0 failed, 0 skipped"
)

run_case success 0 1 "${SUCCESS_ASSERTIONS[@]}"

run_case fail-all 1 1 \
    "FAIL: offline banner is not visible" \
    "FAIL: ui_prefs/lang is 'en', expected 'ceb'" \
    "FAIL: Bisaya label is not visible" \
    "FAIL: location prompt is not visible without a fix" \
    "FAIL: share card PNG was not created" \
    "FAIL: saved_search_check was not found in WorkManager" \
    "Summary: 3 passed, 6 failed, 0 skipped"

# The fake exposes the dialog and share PNG only after several polls.
run_case delayed 0 4 "${SUCCESS_ASSERTIONS[@]}"

printf '\nSelf-test: %d passed, %d failed\n' "$PASSES" "$FAILURES"
[ "$FAILURES" -eq 0 ]
