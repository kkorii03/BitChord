#!/usr/bin/env bash
# Plays scripts/smoke/songs.txt on the emulator, keeps the whole logcat stream
# and judges it with scripts/smokecheck.py. Best effort per song: an id that
# does not start playback gets one MEDIA_PLAY_FROM_SEARCH retry, and a
# search:<query> row is played through MEDIA_PLAY_FROM_SEARCH directly. The run
# continues either way so the log still covers every requested track.
#
# Sync-test mode is switched on for the whole run: the app then writes
# `sync-dump:` blocks and `synctest/<id>.wav` into its own external folder. The
# WAV files are pulled to shots/synctest/ afterwards.
#
# If SMOKE_SETTINGS_FILE names a settings-transfer file, it is pushed to the
# app folder and handed to the debug import broadcast before playback. The
# receiver is a separate change; when it is absent the run warns and continues.
#
# Usage: scripts/smoke/run.sh
# Result: shots/smoke-logcat.txt, shots/smoke-report.md, shots/synctest/*.wav,
# and the checker's exit code (0 = all checks pass or skip, 1 = a check failed,
# 2 = the APK/log/report was unreadable).
set -u
set -x

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT" || exit 2
OUT=shots
mkdir -p "$OUT"
PKG=com.dev.bitchord
APP_DIR="/sdcard/Android/data/$PKG/files"
SONGS_FILE=${SMOKE_SONGS_FILE:-scripts/smoke/songs.txt}
WAIT_PER_SONG=45
HQ_WAIT_PER_SONG=90
SYNC_WAIT_PER_SONG=75
START_TIMEOUT=20
FALLBACK_TIMEOUT=15
HQ_CONFIGURED=0

[ -f "$SONGS_FILE" ] || {
  echo "smoke: song list not found: $SONGS_FILE" >&2
  exit 2
}

APK=$(ls app/build/outputs/apk/dev/debug/*x86_64*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/dev/debug/*universal*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/dev/debug/*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/*/debug/*.apk 2>/dev/null | head -1)

# Same emulator setup as .github/ui-tour/tour.sh: root, Korean, unlocked, install.
adb root || true
sleep 3
adb wait-for-device
adb shell "setprop persist.sys.locale ko-KR; setprop persist.sys.language ko; setprop persist.sys.country KR; setprop ctl.restart zygote" || true
sleep 20
adb wait-for-device
for i in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break
  sleep 3
done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell input keyevent 224 || true
adb shell wm dismiss-keyguard || true

if [ -n "$APK" ]; then
  echo "APK: $APK"
  adb install -r -g "$APK" || { echo "smoke: APK install failed" >&2; exit 2; }
else
  INSTALLED=$(adb shell pm path "$PKG" 2>/dev/null | tr -d '\r' | head -1)
  [ -n "$INSTALLED" ] || {
    echo "smoke: no APK found and $PKG is not installed" >&2
    exit 2
  }
  echo "APK: <none; using installed package: $INSTALLED>"
fi

INSTALLED=$(adb shell pm path "$PKG" 2>/dev/null | tr -d '\r' | head -1)
[ -n "$INSTALLED" ] || { echo "smoke: $PKG is not installed" >&2; exit 2; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell cmd locale set-app-locales "$PKG" --locales ko-KR || true

# Sync-test mode: the flag lives in the app's own external folder, so the app
# can read it without broad storage access. Stale WAVs from an earlier run are
# removed before the first song so a missing file means a missing check.
adb shell "mkdir -p '$APP_DIR' && rm -rf '$APP_DIR/synctest' && touch '$APP_DIR/sync-test.flag'" >/dev/null 2>&1 || {
  echo "smoke: could not create $APP_DIR/sync-test.flag; sync dumps will be missing" >&2
}

SETTINGS_DEVICE=""
if [ -n "${SMOKE_SETTINGS_FILE:-}" ]; then
  if [ -f "$SMOKE_SETTINGS_FILE" ]; then
    SETTINGS_DEVICE="$APP_DIR/smoke-settings.json"
    adb push "$SMOKE_SETTINGS_FILE" "$SETTINGS_DEVICE" >/dev/null 2>&1 || {
      echo "smoke: could not push $SMOKE_SETTINGS_FILE" >&2
      SETTINGS_DEVICE=""
    }
  else
    echo "smoke: SMOKE_SETTINGS_FILE not found: $SMOKE_SETTINGS_FILE" >&2
  fi
fi

# First launch without an account: skip onboarding once, then start normally.
adb shell am start -n "$PKG/com.music.bitchord.MainActivity" \
  --ez bitchord.debug.skipOnboarding true >/dev/null 2>&1 || true
sleep 20
adb shell am force-stop "$PKG" || true
sleep 2
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 20

# Stream logcat for the whole run: the default ring buffer wraps during a list
# this long, and a final dump would lose the early `track-summary`/`play:` lines.
adb logcat -c || true
: > "$OUT/smoke-logcat.txt"
: > "$OUT/smoke-playback.txt"
adb logcat -v threadtime > "$OUT/smoke-logcat.txt" 2>/dev/null &
LOGCAT_PID=$!
cleanup_logcat() {
  kill "$LOGCAT_PID" 2>/dev/null || true
  wait "$LOGCAT_PID" 2>/dev/null || true
}
trap cleanup_logcat EXIT

if [ -n "$SETTINGS_DEVICE" ]; then
  adb shell am broadcast -a com.music.bitchord.debug.IMPORT_SETTINGS -p "$PKG" \
    --es path "$SETTINGS_DEVICE" \
    --es file "$SETTINGS_DEVICE" \
    --es settings "$SETTINGS_DEVICE" \
    --es uri "file://$SETTINGS_DEVICE" \
    -d "file://$SETTINGS_DEVICE" >/dev/null 2>&1 || true
  for i in $(seq 1 10); do
    grep -q 'debug-import:' "$OUT/smoke-logcat.txt" && break
    sleep 1
  done
  if grep -q 'debug-import:' "$OUT/smoke-logcat.txt"; then
    HQ_CONFIGURED=1
    echo "smoke: settings import confirmed (debug-import:)"
  else
    echo "smoke: settings import receiver not available (no debug-import:); continuing" >&2
  fi
fi

play_count() {
  grep -c 'play: ->' "$OUT/smoke-logcat.txt" 2>/dev/null || true
}

wait_for_new_play() {
  local before=${1:-0} timeout=${2:-15} i now
  for ((i = 0; i < timeout; i++)); do
    now=$(play_count)
    if [ "${now:-0}" -gt "${before:-0}" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_seconds_for() {
  case "$1" in
    hq) echo "$HQ_WAIT_PER_SONG" ;;
    cover|plain) echo "$SYNC_WAIT_PER_SONG" ;;
    *) echo "$WAIT_PER_SONG" ;;
  esac
}

start_search() {
  local query=$1
  adb shell "am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -p '$PKG' --es query '$query'" >/dev/null 2>&1 || true
}

play_song() {
  local key=$1 kind=$2 query="" started=0 before wait_seconds elapsed
  case "$key" in
    search:*) query=${key#search:} ;;
  esac

  before=$(play_count)
  if [ -n "$query" ]; then
    start_search "$query"
    if wait_for_new_play "$before" "$START_TIMEOUT"; then
      started=$SECONDS
      echo "$key MEDIA_PLAY_FROM_SEARCH started" >> "$OUT/smoke-playback.txt"
    else
      echo "$key MEDIA_PLAY_FROM_SEARCH did not start" >> "$OUT/smoke-playback.txt"
    fi
  else
    adb shell am start -a android.intent.action.VIEW \
      -d "https://music.youtube.com/watch?v=$key" -p "$PKG" >/dev/null 2>&1 || true
    if wait_for_new_play "$before" "$START_TIMEOUT"; then
      started=$SECONDS
      echo "$key VIEW started" >> "$OUT/smoke-playback.txt"
    else
      echo "$key VIEW did not start; trying MEDIA_PLAY_FROM_SEARCH" >> "$OUT/smoke-playback.txt"
      before=$(play_count)
      start_search "$key"
      if wait_for_new_play "$before" "$FALLBACK_TIMEOUT"; then
        started=$SECONDS
        echo "$key fallback started" >> "$OUT/smoke-playback.txt"
      else
        echo "$key fallback did not start" >> "$OUT/smoke-playback.txt"
      fi
    fi
  fi

  # The listening window starts when playback is detected, not when the VIEW
  # was sent, so a fallback-started song is not cut short.
  if [ "$started" -gt 0 ]; then
    wait_seconds=$(wait_seconds_for "$kind")
    elapsed=$((SECONDS - started))
    if [ "$elapsed" -lt "$wait_seconds" ]; then
      sleep $((wait_seconds - elapsed))
    fi
  fi
}

while IFS=$'\t' read -r key kind _rest || [ -n "$key" ]; do
  key=${key%$'\r'}
  kind=${kind%$'\r'}
  case "$key" in
    ''|'#'*) continue ;;
  esac
  [ -n "$kind" ] || {
    echo "smoke: song line without kind: $key" >&2
    continue
  }
  play_song "$key" "$kind"
done < "$SONGS_FILE"

# `track-summary` is written for the outgoing track, so the last song only gets
# its summary when another track becomes current. Move on once more (and fall
# back to a dummy link if NEXT cannot advance) before the log is closed.
force_final_summary() {
  local before
  before=$(play_count)
  echo "smoke: forcing one final track change for the last track summary" >> "$OUT/smoke-playback.txt"
  adb shell input keyevent 87 >/dev/null 2>&1 || true
  if ! wait_for_new_play "$before" 10; then
    adb shell am start -a android.intent.action.VIEW \
      -d "https://music.youtube.com/watch?v=dQw4w9WgXcQ" -p "$PKG" >/dev/null 2>&1 || true
    wait_for_new_play "$before" "$FALLBACK_TIMEOUT" || true
  fi
  sleep 2
}
force_final_summary

cleanup_logcat
trap - EXIT

if [ ! -s "$OUT/smoke-logcat.txt" ]; then
  echo "smoke: logcat dump is empty; cannot judge the run" >&2
  exit 2
fi

mkdir -p "$OUT/synctest"
if adb shell test -d "$APP_DIR/synctest" >/dev/null 2>&1; then
  for name in $(adb shell ls "$APP_DIR/synctest" 2>/dev/null | tr -d '\r'); do
    [ -n "$name" ] || continue
    adb pull "$APP_DIR/synctest/$name" "$OUT/synctest/$name" >/dev/null 2>&1 || {
      echo "smoke: could not pull synctest/$name" >&2
    }
  done
fi
adb shell rm -f "$APP_DIR/sync-test.flag" >/dev/null 2>&1 || true

CHECK_ARGS=("$OUT/smoke-logcat.txt" --report "$OUT/smoke-report.md" --songs "$SONGS_FILE")
if [ "$HQ_CONFIGURED" = 1 ]; then
  CHECK_ARGS+=(--hq-configured)
fi
python3 scripts/smokecheck.py "${CHECK_ARGS[@]}"
exit $?
