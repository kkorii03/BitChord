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
SCENARIO_MODE=0
case "${SMOKE_SCENARIO:-}" in review|REVIEW) SCENARIO_MODE=1 ;; esac
case "${GITHUB_REF_NAME:-}" in *review*) SCENARIO_MODE=1 ;; esac
# A smoke/*mv* branch runs only the version-decision list: GitHub's runners get
# no YouTube audio, so only what is decided before audio can be judged there.
if [ "$SCENARIO_MODE" = 0 ]; then
  case "${GITHUB_REF_NAME:-}" in *mv*) SONGS_FILE=scripts/smoke/songs-mv.txt ;; esac
fi
WAIT_PER_SONG=45
HQ_WAIT_PER_SONG=90
SYNC_WAIT_PER_SONG=75
START_TIMEOUT=20
FALLBACK_TIMEOUT=15
HQ_CONFIGURED=0

[ "$SCENARIO_MODE" = 1 ] || [ -f "$SONGS_FILE" ] || {
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
    # A file adb pushes under /sdcard belongs to the shell user and the app
    # could not read it (second run: "debug-import: unreadable path"). The
    # debuggable app copies it into its own files dir with run-as instead.
    adb push "$SMOKE_SETTINGS_FILE" /data/local/tmp/smoke.bitchord-settings >/dev/null 2>&1 &&
      adb shell "run-as $PKG sh -c 'mkdir -p files && cat /data/local/tmp/smoke.bitchord-settings > files/smoke.bitchord-settings'" </dev/null &&
      SETTINGS_DEVICE="/data/data/$PKG/files/smoke.bitchord-settings" || {
        echo "smoke: could not hand $SMOKE_SETTINGS_FILE to the app" >&2
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
  # Explicit component and no data URI: a file:// data on the intent does not
  # match the receiver's action-only filter, which is why the first run got
  # no debug-import line at all.
  adb shell am broadcast -a com.music.bitchord.debug.IMPORT_SETTINGS \
    -n "$PKG/com.music.bitchord.debug.DebugSettingsImportReceiver" \
    --es path "$SETTINGS_DEVICE" </dev/null || true
  for i in $(seq 1 10); do
    grep -q 'debug-import:' "$OUT/smoke-logcat.txt" && break
    sleep 1
  done
  if grep -q 'debug-import: applied=' "$OUT/smoke-logcat.txt"; then
    HQ_CONFIGURED=1
    echo "smoke: settings import confirmed: $(grep -m1 -o 'debug-import: applied=.*' "$OUT/smoke-logcat.txt")"
  else
    # Without the listener's settings (music-only, HQ sources, proxy) the run
    # judges a different app; stop now instead of an hour later.
    echo "smoke: settings import FAILED: $(grep -m1 -o 'debug-import:.*' "$OUT/smoke-logcat.txt" || echo 'no debug-import line')" >&2
    exit 2
  fi
elif [ -n "${SMOKE_SETTINGS_FILE:-}" ]; then
  echo "smoke: settings were given but could not be handed to the app" >&2
  exit 2
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
  local key=$1 kind=$2 wait_override=${3:-} query="" started=0 before wait_seconds elapsed
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
    wait_seconds=${wait_override:-$(wait_seconds_for "$kind")}
    elapsed=$((SECONDS - started))
    if [ "$elapsed" -lt "$wait_seconds" ]; then
      sleep $((wait_seconds - elapsed))
    fi
  fi
}

scenario_mark() {
  adb shell log -t BitChord "smoke-scenario: $1" >/dev/null 2>&1 || true
}

scenario_begin() {
  SCENARIO_NAME=$1
  SCENARIO_START_LINE=$(wc -l < "$OUT/smoke-logcat.txt" 2>/dev/null || echo 0)
  scenario_mark "$SCENARIO_NAME start"
}

scenario_end() {
  local name=$1 end
  sleep 1
  scenario_mark "$name end"
  sleep 1
  end=$(wc -l < "$OUT/smoke-logcat.txt" 2>/dev/null || echo 0)
  sed -n "${SCENARIO_START_LINE},${end}p" "$OUT/smoke-logcat.txt" \
    > "$OUT/scenario/$name.txt" 2>/dev/null || true
}

scenario_since_start() {
  sed -n "$((SCENARIO_START_LINE + 1)),\$p" "$OUT/smoke-logcat.txt" 2>/dev/null
}

scenario_wait_for() {
  local pattern=$1 timeout=${2:-30} i
  for ((i = 0; i < timeout; i++)); do
    if scenario_since_start | grep -qE "$pattern"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# Like scenario_wait_for, but ignores everything before the last occurrence of
# $1. A9 uses it so a crossfade line left by an earlier scenario cannot make
# the run think this local playback already started blending.
scenario_wait_for_after() {
  local marker=$1 pattern=$2 timeout=${3:-30} i
  for ((i = 0; i < timeout; i++)); do
    if awk -v m="$marker" '
      index($0, m) { buf = ""; seen = 1; next }
      seen { buf = buf $0 "\n" }
      END { printf "%s", buf }
    ' "$OUT/smoke-logcat.txt" 2>/dev/null | grep -qE "$pattern"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

scenario_cmd() {
  adb shell am broadcast -a com.music.bitchord.debug.SCENARIO \
    -n "$PKG/com.music.bitchord.debug.DebugScenarioReceiver" "$@" \
    </dev/null >/dev/null 2>&1 || true
}

run_scenario_a1() {
  scenario_begin A1
  play_song k4b7dnA8z0I scenario 60
  scenario_end A1
}

run_scenario_a2() {
  scenario_begin A2
  play_song "search:오화요 Dance Monkey cover" scenario 60
  scenario_end A2
}

run_scenario_a3() {
  scenario_begin A3
  play_song "search:Tones and I Dance Monkey Topic" scenario 60
  scenario_end A3
}

run_scenario_a4() {
  local i before
  scenario_begin A4
  for i in 1 2 3; do
    before=$(play_count)
    adb shell input keyevent 87 >/dev/null 2>&1 || true
    wait_for_new_play "$before" 10 || true
    sleep 1
  done
  scenario_end A4
}

run_scenario_a9() {
  local run_id="$$-$(date +%s)"
  local dir="$OUT/scenario/audio"
  local first="$dir/bitchord-smoke-$run_id-440.flac"
  local second="$dir/bitchord-smoke-$run_id-660.flac"
  local device_dir="/sdcard/Music/bitchord-smoke-$run_id"
  local device_first="$device_dir/bitchord-smoke-$run_id-440.flac"
  local device_second="$device_dir/bitchord-smoke-$run_id-660.flac"
  local paths before
  scenario_begin A9
  if ! command -v ffmpeg >/dev/null 2>&1; then
    scenario_mark "A9 skip: no ffmpeg"
    scenario_end A9
    return
  fi
  mkdir -p "$dir"
  if ! ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "sine=frequency=440:duration=60:sample_rate=44100" \
    -c:a flac "$first"; then
    scenario_mark "A9 skip: ffmpeg failed"
    scenario_end A9
    return
  fi
  if ! ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "sine=frequency=660:duration=60:sample_rate=44100" \
    -c:a flac "$second"; then
    scenario_mark "A9 skip: ffmpeg failed"
    scenario_end A9
    return
  fi
  # A per-run directory and file names: a fixed /sdcard/Music name would
  # overwrite (and the cleanup would delete) a file the user already had.
  if ! adb shell mkdir -p "$device_dir" >/dev/null 2>&1 ||
    ! adb push "$first" "$device_first" >/dev/null 2>&1 ||
    ! adb push "$second" "$device_second" >/dev/null 2>&1; then
    scenario_mark "A9 skip: push failed"
    adb shell rm -rf "$device_dir" >/dev/null 2>&1 || true
    scenario_end A9
    return
  fi
  scenario_mark "A9 files=bitchord-smoke-$run_id-440,bitchord-smoke-$run_id-660"
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://$device_first" >/dev/null 2>&1 || true
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://$device_second" >/dev/null 2>&1 || true
  sleep 3
  scenario_cmd --es command crossfade --ei seconds 8
  sleep 1
  before=$(play_count)
  paths="$device_first:$device_second"
  scenario_cmd --es command play-local --es paths "$paths"
  wait_for_new_play "$before" 20 || true
  if scenario_wait_for_after 'debug-scenario: play-local' 'crossfade: start' 90; then
    sleep 12
  fi
  scenario_end A9
  # The second pushed track may still be playing: stop it before its file goes,
  # or B3 inherits a read error that is not its own.
  adb shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null 2>&1 || true
  sleep 1
  adb shell rm -rf "$device_dir" >/dev/null 2>&1 || true
}

run_scenario_b3() {
  scenario_begin B3
  scenario_cmd --es command repeat --ei mode 0
  sleep 2
  scenario_cmd --es command repeat --ei mode 1
  sleep 2
  scenario_cmd --es command repeat --ei mode 2
  sleep 2
  scenario_cmd --es command repeat --ei mode 0
  sleep 2
  scenario_cmd --es command shuffle --ez enabled false
  sleep 2
  scenario_cmd --es command shuffle --ez enabled true
  sleep 2
  scenario_cmd --es command shuffle --ez enabled false
  sleep 2
  scenario_cmd --es command autoplay --ez enabled false
  sleep 2
  scenario_cmd --es command autoplay --ez enabled true
  sleep 2
  scenario_cmd --es command autoplay --ez enabled false
  sleep 2
  scenario_end B3
}

run_scenario_c2() {
  scenario_begin C2
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep "${SMOKE_C2_WAIT:-600}"
  scenario_end C2
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 20
}

run_scenario_c5() {
  local i
  scenario_begin C5
  adb shell am start -a android.intent.action.VIEW \
    -d "https://music.youtube.com/watch?v=7QihqRWMrz8" -p "$PKG" \
    </dev/null >/dev/null 2>&1 || true
  for ((i = 0; i < 30; i++)); do
    if scenario_since_start | grep 'play: ->' | grep -qv '7QihqRWMrz8'; then
      break
    fi
    sleep 1
  done
  sleep 2
  scenario_end C5
}

run_scenario_c6() {
  local pids
  scenario_begin C6
  pids=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | tr '\n' ' ')
  scenario_mark "C6 package=$PKG pids=$pids"
  adb shell svc wifi disable >/dev/null 2>&1 || true
  adb shell svc data disable >/dev/null 2>&1 || true
  sleep 30
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
  sleep 5
  scenario_end C6
}

run_review_scenarios() {
  mkdir -p "$OUT/scenario"
  run_scenario_a1
  run_scenario_a4
  run_scenario_a2
  run_scenario_a3
  run_scenario_a9
  run_scenario_b3
  run_scenario_c2
  play_song "search:Tones and I Dance Monkey" plain 10
  run_scenario_c5
  run_scenario_c6
  python3 scripts/smoke/scenario.py \
    --dir "$OUT/scenario" --report "$OUT/scenario-report.md"
}

if [ "$SCENARIO_MODE" = 1 ]; then
  run_review_scenarios
  SCENARIO_EXIT=$?
  cleanup_logcat
  trap - EXIT
  if [ ! -s "$OUT/smoke-logcat.txt" ]; then
    echo "smoke: logcat dump is empty; cannot judge the run" >&2
    exit 2
  fi
  adb shell rm -f "$APP_DIR/sync-test.flag" >/dev/null 2>&1 || true
  [ -f "$OUT/scenario-report.md" ] && cp "$OUT/scenario-report.md" "$OUT/smoke-report.md"
  exit "$SCENARIO_EXIT"
fi

# fd 3, not stdin: adb shell inside the loop reads stdin and swallowed the
# rest of the song list after the first song on the first run.
while IFS=$'\t' read -r -u 3 key kind _rest || [ -n "$key" ]; do
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
  # No audio after three songs means YouTube refused this network (the runner
  # IPs get "confirm you're not a bot"); say so now instead of after an hour.
  SONGS_DONE=$(( ${SONGS_DONE:-0} + 1 ))
  if [ "$SONGS_DONE" -eq 3 ] && ! grep -qE 'track-summary:.*firstAudio=[0-9]' "$OUT/smoke-logcat.txt"; then
    if grep -qE 'LOGIN_REQUIRED|SignInConfirmNotBot' "$OUT/smoke-logcat.txt"; then
      echo "smoke: NO AUDIO - YouTube bot check on this network (proxy not used or refused)" >&2
    else
      echo "smoke: NO AUDIO after 3 songs" >&2
    fi
    exit 3
  fi
done 3< "$SONGS_FILE"

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
