#!/usr/bin/env bash
# Plays a fixed list of YouTube Music links on the emulator, keeps the logcat
# and judges it with scripts/smokecheck.py. Best effort per song: a link that
# does not start playback gets one MEDIA_PLAY_FROM_SEARCH retry, and the run
# continues either way so the log still covers every requested track.
#
# Usage: scripts/smoke/run.sh
# Result: shots/smoke-logcat.txt, shots/smoke-report.md, and the checker's exit
# code (0 = all checks pass, 1 = a check failed, 2 = the log/report was unreadable).
set -u
set -x

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT" || exit 2
OUT=shots
mkdir -p "$OUT"
PKG=com.dev.bitchord
WAIT_PER_SONG=45
START_TIMEOUT=20
FALLBACK_TIMEOUT=15

# id|search query for the MEDIA_PLAY_FROM_SEARCH fallback.
SONGS=(
  "RJSbO8UZVY|Youngblood 5 Seconds of Summer"
  "QYh6mYIJG2Y|7 rings Ariana Grande"
  "ekzHIouo8Q4|When I Was Your Man Bruno Mars"
  "av5JD1dfj_c|Dancing With A Stranger Sam Smith Normani"
  "CnAmeh0-E-U|Sucker Jonas Brothers"
  "US2I6tSRjcE|오화요 Dance Monkey"
)

APK=$(ls app/build/outputs/apk/dev/debug/*x86_64*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/dev/debug/*universal*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/dev/debug/*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/*/debug/*.apk 2>/dev/null | head -1)
echo "APK: ${APK:-<none; using installed package>}"

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
  adb install -r -g "$APK" || { echo "smoke: APK install failed" >&2; exit 2; }
fi
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell cmd locale set-app-locales "$PKG" --locales ko-KR || true

# First launch without an account: skip onboarding once, then start normally.
adb shell am start -n "$PKG/com.music.bitchord.MainActivity" \
  --ez bitchord.debug.skipOnboarding true >/dev/null 2>&1 || true
sleep 20
adb shell am force-stop "$PKG" || true
sleep 2
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 20

adb logcat -c || true
: > "$OUT/smoke-playback.txt"

play_count() {
  adb logcat -d -s BitChord:I 2>/dev/null | grep -c 'play: ->' || true
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

play_song() {
  local id=$1 query=$2 started=$SECONDS before
  before=$(play_count)
  adb shell am start -a android.intent.action.VIEW \
    -d "https://music.youtube.com/watch?v=$id" -p "$PKG" >/dev/null 2>&1 || true
  if wait_for_new_play "$before" "$START_TIMEOUT"; then
    echo "$id VIEW started" >> "$OUT/smoke-playback.txt"
  else
    echo "$id VIEW did not start; trying MEDIA_PLAY_FROM_SEARCH" >> "$OUT/smoke-playback.txt"
    before=$(play_count)
    adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH \
      -p "$PKG" --es query "$query" >/dev/null 2>&1 || true
    if wait_for_new_play "$before" "$FALLBACK_TIMEOUT"; then
      echo "$id fallback started" >> "$OUT/smoke-playback.txt"
    else
      echo "$id fallback did not start" >> "$OUT/smoke-playback.txt"
    fi
  fi
  # Every song gets the same listening window, however it was started.
  local elapsed=$((SECONDS - started))
  if [ "$elapsed" -lt "$WAIT_PER_SONG" ]; then
    sleep $((WAIT_PER_SONG - elapsed))
  fi
}

for entry in "${SONGS[@]}"; do
  play_song "${entry%%|*}" "${entry#*|}"
done

adb logcat -d > "$OUT/smoke-logcat.txt" || true
python3 scripts/smokecheck.py "$OUT/smoke-logcat.txt" --report "$OUT/smoke-report.md"
exit $?
