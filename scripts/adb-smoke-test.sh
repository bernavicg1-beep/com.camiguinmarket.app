#!/usr/bin/env bash
# Physical-device smoke checks over adb.
#
# Usage:
#   scripts/adb-smoke-test.sh all
#   scripts/adb-smoke-test.sh offline
#   scripts/adb-smoke-test.sh language
#   scripts/adb-smoke-test.sh nearby
#   scripts/adb-smoke-test.sh share
#   scripts/adb-smoke-test.sh worker
#
# Prerequisites:
#   - USB debugging enabled and the debug APK installed.
#   - The app already signed in on the device. This helper never asks for,
#     reads, or prints account credentials and does not use instrumentation
#     arguments.
#   - Run the offline check over USB, not wireless adb: it toggles airplane
#     mode.
#
# Optional environment:
#   ANDROID_SERIAL / ADB_BIN / SMOKE_PACKAGE
#
# The checks use UI Automator for user-visible behavior and `run-as` for the
# debug app's cache/prefs/WorkManager database. They do not print cache values,
# contacts, tokens, or any other private data.

set -u

# Test hook for scripts/test-adb-smoke-test.sh; unset in normal use.
MAX_WAIT_TRIES="${SMOKE_MAX_WAIT_TRIES:-}"
case "$MAX_WAIT_TRIES" in
    ''|*[!0-9]*) MAX_WAIT_TRIES="" ;;
esac

PKG="${SMOKE_PACKAGE:-com.camiguinmarket.app}"
ACTIVITY="$PKG/.MainActivity"
ADB_BIN="${ADB_BIN:-adb}"

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        ADB_BIN="$ANDROID_HOME/platform-tools/adb"
    fi
fi

ADB=("$ADB_BIN")
if [ -n "${ANDROID_SERIAL:-}" ]; then
    ADB+=(-s "$ANDROID_SERIAL")
fi

PASS=0
FAIL=0
SKIP=0
NETWORK_DISABLED=0
AIRPLANE_WAS_ON=0
WIFI_WAS_ON=1
DATA_WAS_ON=1
LOCATION_REVOKED=0
LANG_CHANGED=0
LANG_RESTORE=en
FINE_WAS_GRANTED=0
COARSE_WAS_GRANTED=0
SCREEN_W=1080
SCREEN_H=2400
SERIAL=""

cleanup() {
    restore_network
    restore_location
    restore_language
    "${ADB[@]}" shell rm -f /data/local/tmp/camiguin-window.xml >/dev/null 2>&1 || true
}
trap cleanup EXIT

restore_language() {
    [ "${LANG_CHANGED:-0}" = 1 ] || return 0
    local target="${LANG_RESTORE:-en}" label="English" lang
    [ "$target" = ceb ] && label="Bisaya"
    restart_app
    tap_text "Profile" >/dev/null 2>&1 || true
    sleep 0.5
    tap_text_scroll "$label" >/dev/null 2>&1 || true
    sleep 1
    lang="$(pref_value ui_prefs lang)"
    if [ "$lang" != "$target" ]; then
        fail "could not restore ui_prefs/lang to '$target'; set the language manually"
    fi
    LANG_CHANGED=0
}

pass() { printf 'PASS: %s\n' "$*"; PASS=$((PASS + 1)); }
fail() { printf 'FAIL: %s\n' "$*"; FAIL=$((FAIL + 1)); }
skip() { printf 'SKIP: %s\n' "$*"; SKIP=$((SKIP + 1)); return 2; }
finish() {
    trap - EXIT
    cleanup
    printf '\nSummary: %d passed, %d failed, %d skipped\n' "$PASS" "$FAIL" "$SKIP"
    [ "$FAIL" -eq 0 ] && exit 0
    exit 1
}

require_device() {
    if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
        fail "no adb device. Connect one or set ANDROID_SERIAL."
        finish
    fi
    SERIAL="$("${ADB[@]}" get-serialno 2>/dev/null | tr -d '\r' || true)"
    printf 'Device: %s\n' "$SERIAL"
}

require_app() {
    if ! "${ADB[@]}" shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; then
        fail "$PKG is not installed"
        return 1
    fi
    return 0
}

require_debuggable() {
    if ! "${ADB[@]}" shell run-as "$PKG" id >/dev/null 2>&1; then
        fail "cannot read $PKG private data; install the debug APK"
        return 1
    fi
    return 0
}

screen_size() {
    local raw
    raw="$("${ADB[@]}" shell wm size 2>/dev/null | awk '
        /Override size:/ { override=$3 }
        /Physical size:/ { physical=$3 }
        END { print (override != "" ? override : physical) }
    ' | tr -d '\r')"
    case "$raw" in
        *x*)
            SCREEN_W="${raw%x*}"
            SCREEN_H="${raw#*x}"
            ;;
    esac
}

dump_ui() {
    local attempt
    for attempt in 1 2 3; do
        if "${ADB[@]}" shell uiautomator dump /data/local/tmp/camiguin-window.xml >/dev/null 2>&1; then
            "${ADB[@]}" exec-out cat /data/local/tmp/camiguin-window.xml 2>/dev/null && return 0
        fi
        sleep 0.4
    done
    return 1
}

wait_for_text() {
    local tries="$1"
    shift
    if [ -n "$MAX_WAIT_TRIES" ] && [ "$tries" -gt "$MAX_WAIT_TRIES" ]; then
        tries="$MAX_WAIT_TRIES"
    fi
    local xml needle
    while [ "$tries" -gt 0 ]; do
        if xml="$(dump_ui)"; then
            for needle in "$@"; do
                printf '%s\n' "$xml" | grep -o 'text="[^"]*"' | grep -Fq -- "$needle" && return 0
            done
        fi
        sleep 0.5
        tries=$((tries - 1))
    done
    return 1
}

tap_matching() {
    local pattern="$1" node bounds x1 y1 x2 y2
    node="$(dump_ui | grep -o "<node[^>]*${pattern}[^>]*>" | head -1)"
    [ -n "$node" ] || return 1
    bounds="$(printf '%s' "$node" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
    [ -n "$bounds" ] || return 1
    IFS=' ' read -r x1 y1 x2 y2 <<< "$bounds"
    "${ADB[@]}" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

tap_text() { tap_matching "text=\"$1\""; }

tap_first_listing_price() { tap_matching 'text="[^"]*₱[^"]*[.,][0-9][0-9][^"]*"'; }

swipe_up() {
    "${ADB[@]}" shell input swipe $((SCREEN_W / 2)) $((SCREEN_H * 3 / 4)) $((SCREEN_W / 2)) $((SCREEN_H / 4)) 300
    sleep 0.3
}

tap_text_scroll() {
    local text="$1" i
    for i in 1 2 3 4; do
        tap_text "$text" && return 0
        swipe_up
    done
    return 1
}

restart_app() {
    "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
    "${ADB[@]}" shell am start -W -n "$ACTIVITY" >/dev/null 2>&1 || true
    sleep 2
}

require_signed_in() {
    if ! wait_for_text 12 "Profile"; then
        skip "sign in on the device first; this helper never handles credentials"
        return 2
    fi
    return 0
}

read_pref_file() {
    "${ADB[@]}" exec-out run-as "$PKG" cat "shared_prefs/$1.xml" 2>/dev/null
}

pref_value() {
    local file="$1" key="$2"
    read_pref_file "$file" | sed -n "s/.*<string name=\"$key\">\([^<]*\)<\/string>.*/\1/p" | head -1
}

cache_has_listings() {
    read_pref_file listings_cache | grep -q '<string name="all">'
}

is_wireless_adb() {
    case "$SERIAL" in
        *:*) return 0 ;;
        *) return 1 ;;
    esac
}

network_setting() {
    "${ADB[@]}" shell settings get global "$1" 2>/dev/null | tr -d ' \r\n'
}

record_network_state() {
    AIRPLANE_WAS_ON=0
    WIFI_WAS_ON=1
    DATA_WAS_ON=1
    [ "$(network_setting airplane_mode_on)" = 1 ] && AIRPLANE_WAS_ON=1
    [ "$(network_setting wifi_on)" = 0 ] && WIFI_WAS_ON=0
    [ "$(network_setting mobile_data)" = 0 ] && DATA_WAS_ON=0
    return 0
}

disable_network() {
    record_network_state
    NETWORK_DISABLED=1
    "${ADB[@]}" shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || {
        "${ADB[@]}" shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || true
        "${ADB[@]}" shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1 || true
    }
    "${ADB[@]}" shell svc wifi disable >/dev/null 2>&1 || true
    "${ADB[@]}" shell svc data disable >/dev/null 2>&1 || true
}

restore_network() {
    [ "${NETWORK_DISABLED:-0}" = 1 ] || return 0
    if [ "${AIRPLANE_WAS_ON:-0}" = 1 ]; then
        "${ADB[@]}" shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || true
        "${ADB[@]}" shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || true
        "${ADB[@]}" shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1 || true
    else
        "${ADB[@]}" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
        "${ADB[@]}" shell settings put global airplane_mode_on 0 >/dev/null 2>&1 || true
        "${ADB[@]}" shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1 || true
    fi
    if [ "${WIFI_WAS_ON:-1}" = 1 ]; then
        "${ADB[@]}" shell svc wifi enable >/dev/null 2>&1 || true
    else
        "${ADB[@]}" shell svc wifi disable >/dev/null 2>&1 || true
    fi
    if [ "${DATA_WAS_ON:-1}" = 1 ]; then
        "${ADB[@]}" shell svc data enable >/dev/null 2>&1 || true
    else
        "${ADB[@]}" shell svc data disable >/dev/null 2>&1 || true
    fi
    NETWORK_DISABLED=0
}

permission_granted() {
    "${ADB[@]}" shell dumpsys package "$PKG" 2>/dev/null | grep -Fq "$1: granted=true"
}

permission_dialog_visible() {
    "${ADB[@]}" shell dumpsys window 2>/dev/null \
        | grep -E 'mCurrentFocus|mFocusedApp' \
        | grep -qiE 'permissioncontroller|packageinstaller|GrantPermissions'
}

wait_for_permission_dialog() {
    local tries="${1:-10}"
    if [ -n "$MAX_WAIT_TRIES" ] && [ "$tries" -gt "$MAX_WAIT_TRIES" ]; then
        tries="$MAX_WAIT_TRIES"
    fi
    while [ "$tries" -gt 0 ]; do
        permission_dialog_visible && return 0
        sleep 0.5
        tries=$((tries - 1))
    done
    return 1
}

record_location_permissions() {
    FINE_WAS_GRANTED=0
    COARSE_WAS_GRANTED=0
    permission_granted android.permission.ACCESS_FINE_LOCATION && FINE_WAS_GRANTED=1
    permission_granted android.permission.ACCESS_COARSE_LOCATION && COARSE_WAS_GRANTED=1
    return 0
}

revoke_location() {
    LOCATION_REVOKED=1
    "${ADB[@]}" shell pm revoke "$PKG" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
    "${ADB[@]}" shell pm revoke "$PKG" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true
}

restore_location() {
    [ "${LOCATION_REVOKED:-0}" = 1 ] || return 0
    local failed=0
    if [ "${FINE_WAS_GRANTED:-0}" = 1 ]; then
        "${ADB[@]}" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || failed=1
    fi
    if [ "${COARSE_WAS_GRANTED:-0}" = 1 ]; then
        "${ADB[@]}" shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || failed=1
    fi
    if [ "$failed" = 1 ]; then
        fail "could not restore the original location permission; grant it manually"
    fi
    LOCATION_REVOKED=0
}

share_file_exists() {
    "${ADB[@]}" shell run-as "$PKG" ls cache 2>/dev/null | grep -q 'share_.*\.png'
}

clear_share_files() {
    "${ADB[@]}" shell "run-as $PKG sh -c 'rm -f cache/share_*.png'" >/dev/null 2>&1
}

check_offline() {
    if is_wireless_adb; then
        skip "offline check needs USB adb; airplane mode would drop wireless adb"
        return 2
    fi
    restart_app
    local rc=0
    require_signed_in
    rc=$?
    [ "$rc" -eq 0 ] || return "$rc"

    if ! cache_has_listings; then
        fail "listings_cache/all is missing; open Home online once first"
        return 1
    fi
    pass "listings cache exists"

    disable_network
    restart_app
    if wait_for_text 30 "Offline"; then
        pass "offline banner is visible"
    else
        fail "offline banner is not visible"
        rc=1
    fi
    restore_network
    sleep 2
    restart_app
    return "$rc"
}

check_language() {
    restart_app
    local rc=0
    require_signed_in
    rc=$?
    [ "$rc" -eq 0 ] || return "$rc"

    if ! tap_text "Profile"; then
        fail "could not open Profile"
        return 1
    fi
    sleep 0.5

    LANG_RESTORE="$(pref_value ui_prefs lang)"
    case "$LANG_RESTORE" in
        en|ceb) ;;
        *) LANG_RESTORE=en ;;
    esac
    # Set this before tapping so an interrupted run still restores the original.
    LANG_CHANGED=1
    if ! tap_text_scroll "Bisaya"; then
        fail "could not tap Bisaya"
        return 1
    fi
    sleep 1
    local lang
    lang="$(pref_value ui_prefs lang)"
    if [ "$lang" = "ceb" ]; then
        pass "Bisaya persisted in ui_prefs/lang"
    else
        fail "ui_prefs/lang is '${lang:-missing}', expected 'ceb'"
        rc=1
    fi
    if wait_for_text 4 "Pinulongan"; then
        pass "Bisaya label is visible"
    else
        fail "Bisaya label is not visible"
        rc=1
    fi

    if ! tap_text_scroll "English"; then
        fail "could not restore English"
        return 1
    fi
    sleep 1
    lang="$(pref_value ui_prefs lang)"
    if [ "$lang" = "en" ]; then
        pass "English selected in ui_prefs/lang"
        [ "$LANG_RESTORE" = ceb ] || LANG_CHANGED=0
    else
        fail "ui_prefs/lang is '${lang:-missing}', expected 'en'"
        rc=1
    fi
    return "$rc"
}

check_nearby() {
    restart_app
    local rc=0
    require_signed_in
    rc=$?
    [ "$rc" -eq 0 ] || return "$rc"

    if ! wait_for_text 10 "Nearby" "Duol"; then
        fail "Home nearby controls were not found"
        return 1
    fi

    record_location_permissions
    revoke_location
    restart_app

    if ! wait_for_text 10 "Nearby" "Duol"; then
        fail "Home did not return after revoking location"
        restore_location
        return 1
    fi
    # Deterministic no-fix precondition. The unchanged-feed rule itself is
    # covered by NearbyFilterTest; no lazy-list snapshot is compared here.
    if permission_granted android.permission.ACCESS_FINE_LOCATION || permission_granted android.permission.ACCESS_COARSE_LOCATION; then
        fail "location permission is still granted; cannot verify the no-fix state"
        restore_location
        return 1
    fi
    pass "location permission is not granted"

    if ! tap_text "1km"; then
        fail "could not tap the 1km chip"
        rc=1
    else
        # The dialog can lag the tap by a moment; poll briefly before dismissing it.
        if wait_for_permission_dialog 10; then
            "${ADB[@]}" shell input keyevent BACK >/dev/null 2>&1 || true
            sleep 1
        fi

        if wait_for_text 10 "Enable location to filter nearby listings." "I-enable ang location"; then
            pass "location prompt is visible without a fix"
        else
            fail "location prompt is not visible without a fix"
            rc=1
        fi
    fi

    restore_location
    restart_app
    return "$rc"
}

check_share() {
    restart_app
    local rc=0
    require_signed_in
    rc=$?
    [ "$rc" -eq 0 ] || return "$rc"

    if ! wait_for_text 10 "Nearby" "Duol"; then
        fail "Home did not load"
        return 1
    fi
    local opened=0 i
    for i in 1 2 3 4 5 6; do
        if tap_first_listing_price; then
            opened=1
            break
        fi
        swipe_up
    done
    if [ "$opened" -eq 0 ]; then
        fail "could not open a listing"
        return 1
    fi

    if ! wait_for_text 10 "↗"; then
        fail "listing detail/share button did not open"
        return 1
    fi
    if ! clear_share_files; then
        fail "could not clear old share cards"
        return 1
    fi
    if ! tap_text "↗"; then
        fail "could not tap the share button"
        return 1
    fi

    # The card fetch can spend up to 20 seconds reading the listing photo.
    local found=0 tries=50
    if [ -n "$MAX_WAIT_TRIES" ] && [ "$tries" -gt "$MAX_WAIT_TRIES" ]; then
        tries="$MAX_WAIT_TRIES"
    fi
    for ((i = 1; i <= tries; i++)); do
        if share_file_exists; then
            found=1
            break
        fi
        sleep 0.5
    done
    if [ "$found" -eq 1 ]; then
        pass "share card PNG was created in app cache"
    else
        fail "share card PNG was not created"
        rc=1
    fi
    "${ADB[@]}" shell input keyevent BACK >/dev/null 2>&1 || true
    return "$rc"
}

worker_is_registered() {
    local db
    for db in databases/androidx.work.workdb databases/androidx.work.workdb-wal; do
        if "${ADB[@]}" exec-out run-as "$PKG" cat "$db" 2>/dev/null | grep -aFq "saved_search_check"; then
            return 0
        fi
    done
    return 1
}

check_worker() {
    restart_app
    local rc=0
    require_signed_in
    rc=$?
    [ "$rc" -eq 0 ] || return "$rc"

    local i tries=10
    if [ -n "$MAX_WAIT_TRIES" ] && [ "$tries" -gt "$MAX_WAIT_TRIES" ]; then
        tries="$MAX_WAIT_TRIES"
    fi
    for ((i = 1; i <= tries; i++)); do
        if worker_is_registered; then
            pass "saved_search_check is registered in WorkManager"
            return 0
        fi
        sleep 1
    done
    fail "saved_search_check was not found in WorkManager"
    return 1
}

run_check() {
    local name="$1"
    shift
    printf '\n== %s ==\n' "$name"
    "$@"
}

usage() {
    cat <<'EOF'
Physical-device smoke checks over adb.

Usage:
  scripts/adb-smoke-test.sh all
  scripts/adb-smoke-test.sh offline
  scripts/adb-smoke-test.sh language
  scripts/adb-smoke-test.sh nearby
  scripts/adb-smoke-test.sh share
  scripts/adb-smoke-test.sh worker

Prerequisites:
  - USB debugging enabled and the debug APK installed.
  - The app already signed in on the device. This helper never asks for,
    reads, or prints account credentials.
  - Run the offline check over USB, not wireless adb.

Optional environment:
  ANDROID_SERIAL / ADB_BIN / SMOKE_PACKAGE

Checks use UI Automator for visible behavior and `run-as` for the debug app's
cache, prefs, and WorkManager database. Private values are never printed.
EOF
}

command_name="${1:-all}"
case "$command_name" in
    -h|--help|help) usage; exit 0 ;;
esac

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
    fail "adb was not found. Put platform-tools on PATH or set ADB_BIN."
    finish
fi

require_device
require_app || finish
require_debuggable || finish
screen_size

case "$command_name" in
    all)
        run_check "offline cache" check_offline
        run_check "language toggle" check_language
        run_check "nearby filter" check_nearby
        run_check "share card" check_share
        run_check "saved-search worker" check_worker
        ;;
    offline) run_check "offline cache" check_offline ;;
    language) run_check "language toggle" check_language ;;
    nearby) run_check "nearby filter" check_nearby ;;
    share) run_check "share card" check_share ;;
    worker) run_check "saved-search worker" check_worker ;;
    *)
        echo "Unknown check: $command_name" >&2
        usage >&2
        exit 2
        ;;
esac

finish
