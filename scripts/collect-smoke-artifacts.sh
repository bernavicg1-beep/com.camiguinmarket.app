#!/usr/bin/env bash
# Collects diagnostics for a failed smoke run: a UI dump, a screenshot and a
# logcat with credentials, tokens and emails redacted.
#
# Usage:
#   scripts/collect-smoke-artifacts.sh <output-dir>

set -uo pipefail

out="${1:?usage: collect-smoke-artifacts.sh <output-dir>}"
mkdir -p "$out"

# uiautomator writes the hierarchy to stdout, so no device temp file is needed.
adb exec-out uiautomator dump /dev/tty > "$out/ui-dump.xml" 2>/dev/null || true
adb exec-out screencap -p > "$out/screenshot.png" 2>/dev/null || true
adb logcat -d 2>/dev/null | sed -E \
    -e 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/[REDACTED_EMAIL]/g' \
    -e 's/(smokePassword|password|passwd|token|secret|authorization|api[_-]?key|client[_-]?secret)[=:][^[:space:]]*/\1=[REDACTED]/gi' \
    -e 's/Bearer [A-Za-z0-9._-]+/Bearer [REDACTED]/g' \
    -e 's/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/[REDACTED_JWT]/g' \
    > "$out/logcat.txt" || true
