#!/usr/bin/env bash
# Stateful adb stand-in for scripts/test-adb-smoke-test.sh.
# It emulates only the commands and UI states used by adb-smoke-test.sh.

set -u

STATE="${FAKE_ADB_STATE:?FAKE_ADB_STATE is required}"
FAIL_ALL="$(cat "$STATE/fail_all" 2>/dev/null || printf 0)"
DELAY="$(cat "$STATE/delay" 2>/dev/null || printf 0)"
DELAY_POLLS=3

get() { cat "$STATE/$1" 2>/dev/null || printf '%s' "$2"; }
put() { printf '%s\n' "$2" > "$STATE/$1"; }

node() {
    printf '<node text="%s" bounds="[%s,%s][%s,%s]" />\n' "$1" "$2" "$3" "$4" "$5"
}

dump_xml() {
    local screen lang
    screen="$(get screen home)"
    lang="$(get lang en)"
    printf '<hierarchy rotation="0">\n'
    case "$screen" in
        home)
            node "Profile" 10 2000 110 2100
            if [ "$lang" = ceb ]; then
                node "Duol:" 10 400 110 500
            else
                node "Nearby:" 10 400 110 500
            fi
            node "1km" 10 500 110 600
            if [ "$(get offline 0)" = 1 ] && [ "$FAIL_ALL" = 0 ]; then
                node "Offline" 10 100 200 200
            fi
            if [ "$(get nearby 0)" = 1 ] &&
               [ "$(get location 1)" = 0 ] &&
               [ "$(get permission_dialog 0)" = 0 ] &&
               [ "$FAIL_ALL" = 0 ]; then
                if [ "$lang" = ceb ]; then
                    node "I-enable ang location" 10 600 500 700
                else
                    node "Enable location to filter nearby listings." 10 600 500 700
                fi
            fi
            node "₱250.00" 10 700 210 800
            ;;
        profile)
            node "English" 10 900 110 1000
            node "Bisaya" 10 1000 110 1100
            if [ "$lang" = ceb ]; then
                node "Pinulongan" 10 1100 200 1200
            fi
            ;;
        detail)
            node "↗" 900 100 1000 200
            ;;
    esac
    printf '</hierarchy>\n'
}

tap_at() {
    local x="$1" y="$2" screen
    screen="$(get screen home)"
    case "$screen" in
        home)
            case "$x $y" in
                "60 2050") put screen profile ;;
                "110 750") put screen detail ;;
                "60 550")
                    put nearby 1
                    if [ "$(get location 1)" = 0 ]; then
                        if [ "$DELAY" = 1 ]; then
                            put permission_dialog "$DELAY_POLLS"
                        else
                            put permission_dialog 1
                        fi
                    fi
                    ;;
            esac
            ;;
        profile)
            case "$x $y" in
                "60 1050")
                    if [ "$FAIL_ALL" = 1 ]; then
                        put lang en
                    else
                        put lang ceb
                    fi
                    ;;
                "60 950") put lang en ;;
            esac
            ;;
        detail)
            case "$x $y" in
                "950 150")
                    if [ "$FAIL_ALL" = 0 ]; then
                        if [ "$DELAY" = 1 ]; then
                            put share "$DELAY_POLLS"
                        else
                            put share 1
                        fi
                    fi
                    ;;
            esac
            ;;
    esac
}

shell_cmd() {
    local first="${1:-}"
    case "$first" in
        *"rm -f cache/share_*.png"*)
            put share 0
            return 0
            ;;
    esac
    case "$first" in
        pm)
            case "${2:-}" in
                path) echo "package:/data/app/base.apk" ;;
                revoke|grant)
                    if [ "$2" = grant ]; then put location 1; else put location 0; fi
                    ;;
            esac
            ;;
        run-as)
            case "${3:-}" in
                id) echo "uid=1000(u0_a0) gid=1000(u0_a0)" ;;
                ls)
                    local share
                    share="$(get share 0)"
                    if [ "$share" -gt 1 ]; then
                        put share "$((share - 1))"
                    elif [ "$share" = 1 ]; then
                        echo "share_1.png"
                    fi
                    ;;
            esac
            ;;
        input)
            case "${2:-}" in
                tap) tap_at "${3:-0}" "${4:-0}" ;;
                keyevent)
                    if [ "$(get permission_dialog 0)" = 1 ]; then
                        put permission_dialog 0
                    else
                        case "$(get screen home)" in
                            detail|profile) put screen home ;;
                        esac
                    fi
                    ;;
            esac
            ;;
        am)
            case "${2:-}" in
                force-stop)
                    put screen home
                    put nearby 0
                    put permission_dialog 0
                    ;;
            esac
            ;;
        settings)
            case "${2:-}" in
                get)
                    case "${4:-}" in
                        airplane_mode_on) echo 0 ;;
                        wifi_on) echo 1 ;;
                        mobile_data) echo 1 ;;
                    esac
                    ;;
            esac
            ;;
        cmd)
            case "${4:-}" in
                enable) put offline 1 ;;
                disable) put offline 0 ;;
            esac
            ;;
        dumpsys)
            case "${2:-}" in
                package)
                    local granted=false
                    [ "$(get location 1)" = 1 ] && granted=true
                    echo "android.permission.ACCESS_FINE_LOCATION: granted=$granted"
                    echo "android.permission.ACCESS_COARSE_LOCATION: granted=$granted"
                    ;;
                window)
                    local dialog
                    dialog="$(get permission_dialog 0)"
                    if [ "$dialog" = 1 ]; then
                        echo "mCurrentFocus=Window{0 u0 com.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity}"
                    else
                        if [ "$dialog" -gt 1 ]; then
                            put permission_dialog "$((dialog - 1))"
                        fi
                        echo "mCurrentFocus=Window{0 u0 com.camiguinmarket.app/com.camiguinmarket.app.MainActivity}"
                    fi
                    ;;
            esac
            ;;
        wm) echo "Physical size: 1080x2400" ;;
    esac
    return 0
}

exec_out() {
    case "${1:-}" in
        cat)
            case "${2:-}" in
                /data/local/tmp/camiguin-window.xml) dump_xml ;;
            esac
            ;;
        run-as)
            case "${3:-}" in
                cat)
                    case "${4:-}" in
                        shared_prefs/listings_cache.xml)
                            printf '<map><string name="all">[]</string></map>\n'
                            ;;
                        shared_prefs/ui_prefs.xml)
                            printf '<map><string name="lang">%s</string></map>\n' "$(get lang en)"
                            ;;
                        databases/androidx.work.workdb|databases/androidx.work.workdb-wal)
                            [ "$FAIL_ALL" = 0 ] && echo "saved_search_check"
                            ;;
                    esac
                    ;;
            esac
            ;;
    esac
}

if [ "${1:-}" = "-s" ]; then
    shift 2
fi
cmd="${1:-}"
shift || true
case "$cmd" in
    get-state) echo "device" ;;
    get-serialno) echo "FAKE123" ;;
    shell) shell_cmd "$@" ;;
    exec-out) exec_out "$@" ;;
esac
