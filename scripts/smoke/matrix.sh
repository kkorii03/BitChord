# Scenario matrix for the sync tests (sourced by run.sh when scripts/smoke/matrix.env
# exists). One set changes one thing: a setting, the source list, or something the
# listener does while a song plays. Everything it does is marked in the log
# (`matrix: ...`), so scripts/smoke/matrix_report.py can read each play apart.
#
# matrix.env:
#   MATRIX_SET      name of the set
#   MATRIX_KEYS     "key=value key=value" settings to change
#   MATRIX_SOURCES  user | youtube-only
#   MATRIX_ACTION   none | home | seek | pause | screenoff | rapid | netdrop
#   MATRIX_WAIT     seconds to stay on a song after its first sound, or "full"
#   MATRIX_AFTER    with full: seconds to stay on the song the queue moved on to
#   MATRIX_LYRICS   1 = open the lyrics on screen (slow on a software GPU, so not always)

# What every set starts from, so an older export on a fork cannot pass for a setting.
MATRIX_BASE_KEYS="dolby_atmos=true audio_quality_wifi=LOSSLESS audio_quality_cellular=LOSSLESS \
auto_prefetch_hq=true smart_fade_enabled=true automix_videos=true crossfade_seconds=0 playback_speed=1.0 \
synced_lyrics=true auto_lyrics_timing=true ko_ai_lyrics=true ko_ai_timing_ahead=true lyrics_offset_ms=0"

matrix_settings() {
  local file=$1 pair
  for pair in $MATRIX_BASE_KEYS ${MATRIX_KEYS:-}; do
    python3 scripts/smoke/set_setting.py "$file" "${pair%%=*}" "${pair#*=}"
  done
  python3 - "$file" "${MATRIX_SOURCES:-user}" <<'PY'
import json, sys
path, mode = sys.argv[1], sys.argv[2]
data = json.load(open(path, encoding="utf-8"))
sources = data.get("sources") or []
if mode == "youtube-only":
    data["sources"] = [s for s in sources if s.get("kind") == "YOUTUBE"]
    json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
kinds = [s.get("kind") for s in data.get("sources") or []]
at = kinds.index("YOUTUBE") if "YOUTUBE" in kinds else -1
print(f"matrix sources: mode={mode} count={len(kinds)} youtube_at={at}")
PY
}

mx_mark() {
  adb shell log -t BitChord "matrix: set=${MATRIX_SET:-?} $*" </dev/null >/dev/null 2>&1 || true
}

mx_log() { echo "$OUT/smoke-logcat.txt"; }

mx_lines() { wc -l < "$(mx_log)" 2>/dev/null || echo 0; }

# True once $1 (a grep -E pattern) shows up after log line $2.
mx_seen_since() {
  tail -n +"$2" "$(mx_log)" 2>/dev/null | grep -v 'sync-dump' | grep -qE "$1"
}

mx_wait_for() {
  local pattern=$1 since=$2 timeout=$3 i
  for ((i = 0; i < timeout; i++)); do
    mx_seen_since "$pattern" "$since" && return 0
    sleep 1
  done
  return 1
}

# Position the media session reports; the seek and pause marks carry it.
mx_position() {
  adb shell dumpsys media_session </dev/null 2>/dev/null |
    grep -m1 -oE 'state=PlaybackState \{state=[^,]+, position=[0-9]+' |
    sed -E 's/.*\{state=([A-Za-z_0-9]+)[^,]*, position=([0-9]+)/state=\1 pos=\2/'
}

mx_key() {
  # The session answers `cmd media_session dispatch`, whatever has the focus.
  adb shell cmd media_session dispatch "$1" </dev/null >/dev/null 2>&1 || true
}

mx_ui() {
  timeout 25 adb shell uiautomator dump /sdcard/ui.xml </dev/null >/dev/null 2>&1 || return 1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
}

# Centre of the lowest node holding $1 in its text or description, nothing if none.
mx_find() {
  python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    nodes = list(ET.parse("/tmp/ui.xml").iter("node"))
except Exception:
    sys.exit(0)
box = lambda n: list(map(int, re.findall(r"\d+", n.get("bounds") or "0,0,0,0")))
hits = [n for n in nodes if want and (want in (n.get("text") or "") or want in (n.get("content-desc") or ""))]
hits = [n for n in hits if box(n)[3] - box(n)[1] >= 12]
if hits:
    x1, y1, x2, y2 = box(sorted(hits, key=lambda n: box(n)[1])[-1])
    print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}

mx_shot() {
  mkdir -p "$OUT/matrix"
  adb exec-out screencap -p > "$OUT/matrix/$1.png" 2>/dev/null || true
}

# Player open, then the lyrics. `lyric-clock:` is only written while the lyrics draw.
mx_open_lyrics() {
  local title at try n=${MATRIX_OPENS:-0}
  MATRIX_OPENS=$((n + 1))
  title=$(grep -v 'sync-dump' "$(mx_log)" | grep 'play: ->' | tail -1 | sed -E 's/.*play: -> //; s/ - .*//' | cut -c1-10)
  for try in 1 2 3; do
    mx_ui || { sleep 3; continue; }
    at=$(mx_find "가사")
    if [ -z "$at" ]; then
      at=$(mx_find "$title")
      if [ -z "$at" ]; then
        # The mini player sits above the tab bar on this profile.
        at="540 2040"
      fi
      adb shell input tap $at </dev/null
      sleep 5
      mx_ui || { sleep 3; continue; }
      at=$(mx_find "가사")
    fi
    if [ -n "$at" ]; then
      adb shell input tap $at </dev/null
      sleep 4
      mx_mark "lyrics open try=$try"
      [ "$n" -gt 2 ] || mx_shot "lyrics-$n"
      return 0
    fi
    adb shell input swipe 540 1200 540 400 400 </dev/null
    sleep 3
  done
  mx_mark "lyrics NOT open"
  [ "$n" -gt 2 ] || mx_shot "lyrics-failed-$n"
  cp /tmp/ui.xml "$OUT/matrix/ui-failed-$n.xml" 2>/dev/null || true
  return 1
}

# Sleeps until $1 seconds after the song's first sound.
mx_until() {
  local left=$(($1 - (SECONDS - MATRIX_T0)))
  [ "$left" -gt 0 ] && sleep "$left"
  return 0
}

mx_away() {
  local away=$1
  mx_mark "home leave away=$away $(mx_position)"
  adb shell input keyevent 3 </dev/null
  sleep "$away"
  mx_mark "home back $(mx_position)"
  adb shell am start -n "$PKG/com.music.bitchord.MainActivity" </dev/null >/dev/null 2>&1 || true
}

mx_seek() {
  local direction=$1 times=$2 i
  mx_mark "seek $direction before $(mx_position)"
  for ((i = 0; i < times; i++)); do
    mx_key "$direction"
    sleep 1
  done
  mx_mark "seek $direction after $(mx_position)"
}

mx_hold() {
  local away=$1
  mx_mark "pause hold=$away $(mx_position)"
  mx_key pause
  sleep "$away"
  mx_mark "resume $(mx_position)"
  mx_key play
}

mx_action() {
  local key=$1 i before
  case "${MATRIX_ACTION:-none}" in
    home)
      mx_until 60; mx_away 30
      mx_until 125; mx_away 90
      ;;
    seek)
      mx_until 60;  mx_seek rewind 6
      mx_until 100; mx_seek fast-forward 6
      mx_until 140; mx_seek rewind 12
      mx_until 180; mx_seek fast-forward 12
      ;;
    pause)
      mx_until 60; mx_hold 30
      mx_until 125; mx_hold 90
      ;;
    screenoff)
      mx_until 15
      mx_mark "screen off $(mx_position)"
      adb shell input keyevent 223 </dev/null
      ;;
    netdrop)
      # The song check is on its way out at about 15-40 s.
      mx_until 20
      mx_mark "network off $(mx_position)"
      adb shell svc wifi disable </dev/null; adb shell svc data disable </dev/null
      sleep 45
      adb shell svc wifi enable </dev/null; adb shell svc data enable </dev/null
      mx_mark "network on $(mx_position)"
      ;;
    rapid)
      mx_until 15
      for i in 1 2 3 4; do
        before=$(play_count)
        mx_mark "rapid next $i"
        mx_key next
        wait_for_new_play "$before" 8 || true
        sleep 2
      done
      mx_mark "rapid back to $key"
      MATRIX_SINCE=$(mx_lines)
      play_song "$key" plain 0
      mx_wait_for 'TIMING first audio' "$MATRIX_SINCE" 150 || mx_mark "no first audio after rapid"
      MATRIX_T0=$SECONDS
      ;;
  esac
}

matrix_play() {
  local key=$1 kind=$2 wait=${MATRIX_WAIT:-240} before
  MATRIX_SINCE=$(mx_lines)
  mx_mark "song=$key start action=${MATRIX_ACTION:-none}"
  play_song "$key" "$kind" 0
  if ! mx_wait_for 'TIMING first audio' "$MATRIX_SINCE" 150; then
    mx_mark "song=$key no first audio in 150 s"
    return 0
  fi
  MATRIX_T0=$SECONDS
  [ "${MATRIX_LYRICS:-0}" = 1 ] && mx_open_lyrics
  before=$(play_count)
  mx_action "$key"
  if [ "$wait" = full ]; then
    # The song ends by itself and the queue moves on; a song this long is rare.
    local i
    for ((i = 0; i < 520; i++)); do
      [ "$(play_count)" -gt "$before" ] && break
      sleep 1
    done
    mx_mark "song=$key ended-or-limit after=$((SECONDS - MATRIX_T0)) moved=$(($(play_count) - before))"
    [ -n "${MATRIX_AFTER:-}" ] && sleep "$MATRIX_AFTER"
  else
    mx_until "$wait"
  fi
  if [ "${MATRIX_ACTION:-none}" = screenoff ]; then
    adb shell input keyevent 224 </dev/null
    adb shell wm dismiss-keyguard </dev/null
    mx_mark "screen on $(mx_position)"
  fi
  mx_mark "song=$key done position $(mx_position)"
}
