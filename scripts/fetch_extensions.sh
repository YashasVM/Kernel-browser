#!/usr/bin/env sh
# POSIX counterpart of fetch_extensions.ps1: downloads the pinned AMO XPIs,
# verifies SHA-256 checksums and Gecko IDs, and unpacks them into app assets.
# Usage: scripts/fetch_extensions.sh [--clean]
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CACHE_DIR="$PROJECT_ROOT/extensions-cache"
ASSET_DIR="$PROJECT_ROOT/app/src/main/assets/extensions"
METADATA_PATH="$PROJECT_ROOT/app/src/main/assets/extensions.json"

# slug|id|name|version|url|sha256
EXTENSIONS='ublock-origin|uBlock0@raymondhill.net|uBlock Origin|1.71.0|https://addons.mozilla.org/firefox/downloads/file/4814095/ublock_origin-1.71.0.xpi|47f788a1fc2c014830b30bb0ef9588615701b98c5265fb19b8cf4ba779849feb
cookie-editor|{c3c10168-4186-445c-9c5b-63f12b8e2c87}|Cookie-Editor|1.13.0|https://addons.mozilla.org/firefox/downloads/file/4241002/cookie_editor-1.13.0.xpi|3d6fd83a8343dfa5e4461d83c2856264fb74b36b1c165305168d013f4831dbb0'

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

manifest_id() {
    python3 -I - "$1/manifest.json" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1], encoding="utf-8"))
settings = manifest.get("browser_specific_settings") or manifest.get("applications") or {}
for key in ("gecko", "gecko_android"):
    gecko_id = (settings.get(key) or {}).get("id")
    if gecko_id:
        print(gecko_id)
        break
PY
}

if [ "${1:-}" = "--clean" ]; then
    rm -rf "$ASSET_DIR"
fi
mkdir -p "$CACHE_DIR" "$ASSET_DIR"

metadata="["
separator=""
OLD_IFS=$IFS
IFS='
'
for line in $EXTENSIONS; do
    IFS='|' read -r slug id name version url sha <<EOF
$line
EOF
    archive="$CACHE_DIR/$slug-$version.xpi"
    destination="$ASSET_DIR/$slug"
    [ -f "$archive" ] || curl -sSfL -o "$archive" "$url"

    actual=$(sha256_of "$archive")
    if [ "$actual" != "$sha" ]; then
        echo "Checksum mismatch for $name. Expected $sha, got $actual" >&2
        exit 1
    fi

    rm -rf "$destination"
    mkdir -p "$destination"
    unzip -q "$archive" -d "$destination"

    found=$(manifest_id "$destination")
    if [ "$found" != "$id" ]; then
        echo "Manifest ID mismatch for $name. Expected $id, got $found" >&2
        exit 1
    fi

    metadata="$metadata$separator
  {\"id\": \"$id\", \"slug\": \"$slug\", \"displayName\": \"$name\", \"version\": \"$version\", \"sha256\": \"$sha\", \"sourceUrl\": \"$url\", \"assetUri\": \"resource://android/assets/extensions/$slug/\"}"
    separator=","
done
IFS=$OLD_IFS

printf '%s\n]\n' "$metadata" > "$METADATA_PATH"
echo "Fetched and unpacked extensions into $ASSET_DIR"
