#!/usr/bin/env sh
# Verifies that built APKs ship the bundled extensions intact.
#
# Regression check: AGP's default ignoreAssetsPattern drops asset directories
# whose names start with "_" (e.g. _locales), which silently breaks bundled
# WebExtensions such as uBlock Origin. Each APK must contain at least one entry
# under every path listed in REQUIRED_PREFIXES.
#
# Usage: scripts/verify_apks.sh [APK_OR_DIR ...]
#   With no arguments, checks every *.apk in app/build/outputs/installable/.
#   Exits non-zero if no APKs are found or any APK is missing a required path.
set -eu

PROJECT_ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
DEFAULT_DIR="$PROJECT_ROOT/app/build/outputs/installable"

REQUIRED_PREFIXES='assets/extensions/ublock-origin/manifest.json
assets/extensions/ublock-origin/_locales/'

if ! command -v unzip >/dev/null 2>&1; then
    echo "verify_apks: 'unzip' is required but was not found on PATH" >&2
    exit 2
fi

[ "$#" -gt 0 ] || set -- "$DEFAULT_DIR"

# Expand directories into the APKs they contain.
apks=""
for arg in "$@"; do
    if [ -d "$arg" ]; then
        for f in "$arg"/*.apk; do
            if [ -f "$f" ]; then
                apks="$apks
$f"
            fi
        done
    elif [ -f "$arg" ]; then
        apks="$apks
$arg"
    else
        echo "verify_apks: no such file or directory: $arg" >&2
        exit 2
    fi
done

if [ -z "$apks" ]; then
    echo "verify_apks: no APKs found in: $*" >&2
    exit 1
fi

failures=0
checked=0
OLD_IFS=$IFS
IFS='
'
for apk in $apks; do
    [ -n "$apk" ] || continue
    checked=$((checked + 1))
    if ! listing=$(unzip -Z1 "$apk" 2>/dev/null); then
        echo "FAIL $apk: not a readable zip/APK" >&2
        failures=$((failures + 1))
        continue
    fi
    missing=""
    for prefix in $REQUIRED_PREFIXES; do
        if ! printf '%s\n' "$listing" | grep -q -F -- "$prefix"; then
            missing="$missing $prefix"
        fi
    done
    if [ -n "$missing" ]; then
        echo "FAIL $apk: missing$missing" >&2
        failures=$((failures + 1))
    else
        locales=$(printf '%s\n' "$listing" | grep -c -F -- 'assets/extensions/ublock-origin/_locales/' || true)
        echo "OK   $apk ($locales _locales entries)"
    fi
done
IFS=$OLD_IFS

if [ "$failures" -gt 0 ]; then
    echo "verify_apks: $failures of $checked APK(s) are missing bundled extension files." >&2
    echo "verify_apks: run scripts/fetch_extensions.sh before building, and make sure" >&2
    echo "verify_apks: androidResources.ignoreAssetsPattern does not exclude '_*' dirs." >&2
    exit 1
fi
echo "verify_apks: all $checked APK(s) contain bundled uBlock Origin with _locales."
