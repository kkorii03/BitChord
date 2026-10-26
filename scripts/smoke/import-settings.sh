#!/usr/bin/env bash
# Imports a SettingsTransfer file into a dev-debug BitChord build without
# tapping through Settings > Import > Apply.
#
#   SMOKE_SETTINGS_FILE=/path/to/smoke.bitchord-settings \
#     scripts/smoke/import-settings.sh
#
# Optional:
#   SMOKE_PACKAGE   default com.dev.bitchord
#   SMOKE_SERIAL    adb device serial, when more than one is attached
#
# The file is pushed to /data/local/tmp first. If the app cannot read that
# directory directly, it is copied into the app's cache with `run-as` and the
# receiver is given the cache-relative name instead.
set -euo pipefail

: "${SMOKE_SETTINGS_FILE:?set SMOKE_SETTINGS_FILE to a .bitchord-settings file}"
PKG="${SMOKE_PACKAGE:-com.dev.bitchord}"
RECEIVER="${SMOKE_RECEIVER:-com.music.bitchord.debug.DebugSettingsImportReceiver}"
ACTION="com.music.bitchord.debug.IMPORT_SETTINGS"
NAME="smoke.bitchord-settings"
TMP_PATH="/data/local/tmp/$NAME"

if [ ! -f "$SMOKE_SETTINGS_FILE" ]; then
    echo "import-settings: no such file: $SMOKE_SETTINGS_FILE" >&2
    exit 2
fi

ADB=(adb)
if [ -n "${SMOKE_SERIAL:-}" ]; then
    ADB=(adb -s "$SMOKE_SERIAL")
fi

"${ADB[@]}" push "$SMOKE_SETTINGS_FILE" "$TMP_PATH" >/dev/null
echo "import-settings: pushed $SMOKE_SETTINGS_FILE -> $TMP_PATH"

# Sends the broadcast and returns the receiver's log line, if any.
try_import() {
    local path="$1"
    local line=""
    "${ADB[@]}" logcat -c >/dev/null 2>&1 || true
    "${ADB[@]}" shell am broadcast \
        -a "$ACTION" \
        --es path "$path" \
        -n "$PKG/$RECEIVER" >/dev/null
    for _ in $(seq 1 15); do
        line="$("${ADB[@]}" logcat -d -s BitChord:I 2>/dev/null \
            | grep -F 'debug-import:' | tail -1 || true)"
        if [ -n "$line" ]; then
            printf '%s' "$line"
            return 0
        fi
        sleep 1
    done
}

line="$(try_import "$TMP_PATH")"
if ! printf '%s' "$line" | grep -q 'debug-import: applied='; then
    echo "import-settings: app could not read $TMP_PATH; copying into cache via run-as"
    "${ADB[@]}" shell run-as "$PKG" mkdir -p cache
    if ! "${ADB[@]}" shell run-as "$PKG" cp "$TMP_PATH" "cache/$NAME"; then
        "${ADB[@]}" shell "run-as $PKG sh -c 'cat > cache/$NAME'" < "$SMOKE_SETTINGS_FILE"
    fi
    line="$(try_import "$NAME")"
fi

if ! printf '%s' "$line" | grep -q 'debug-import: applied='; then
    echo "import-settings: import did not complete" >&2
    [ -n "$line" ] && echo "$line" >&2
    exit 1
fi

echo "$line"
