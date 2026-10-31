#!/usr/bin/env bash
# Installs the dev build on the emulator in Korean and walks every reachable
# screen, saving a screenshot (+ UI hierarchy) of each into shots/.
# Index of intended screens is written to shots/index.txt.
#
# Playback is bot-blocked from the datacenter IP, so this tour is about UI
# coverage: a shared YouTube Music link still populates the player and every
# sheet (lyrics, queue, offset, providers) opens without audio.
#
# Best effort throughout: a label that is not found is logged and skipped,
# never treated as a failure of the run.
set -x
OUT=shots
mkdir -p "$OUT"
: > "$OUT/index.txt"
PKG=com.dev.bitchord
# Dance Monkey cover: the same path a shared YouTube Music link takes, so no IME.
MAIN_VIDEO=US2I6tSRjcE

APK=$(ls app/build/outputs/apk/dev/debug/*x86_64*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/dev/debug/*universal*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/dev/debug/*.apk | head -1)
echo "APK: $APK"

adb root; sleep 3; adb wait-for-device
adb shell "setprop persist.sys.locale ko-KR; setprop persist.sys.language ko; setprop persist.sys.country KR; setprop ctl.restart zygote"
sleep 20
adb wait-for-device
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
adb shell settings put system screen_off_timeout 1800000
adb install -r -g "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell cmd locale set-app-locales $PKG --locales ko-KR || true
adb logcat -c
# Debug-only switches (inert in release): reproduce the Korea-without-Premium
# home the listener sees (music gate forced) and give it a listening history
# to build Recents / Listen again / Quick picks from. The first launch seeds
# and persists both; the second builds the home from them.
if [ "${TOUR_GATED:-1}" = "1" ]; then
  adb shell am start -n $PKG/com.music.bitchord.MainActivity \
    --ez bitchord.debug.forceMusicGate true --ez bitchord.debug.seedHistory true --ez bitchord.debug.skipOnboarding true
  sleep 20
  adb shell am force-stop $PKG
  sleep 2
fi
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 30

n=0
# Geometry for the bottom-tab coordinate fallback (4 equal slots).
SCREEN_W=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: *\([0-9]*\)x.*/\1/p' | tail -1)
SCREEN_H=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: *[0-9]*x\([0-9]*\).*/\1/p' | tail -1)
SCREEN_W=${SCREEN_W:-1080}
SCREEN_H=${SCREEN_H:-2400}
TAB_MIN_Y=$(( SCREEN_H * 17 / 20 ))

# Screenshot + hierarchy + one line in index.txt: name, then what it should show.
shot() {
  n=$((n + 1)); name=$(printf "%02d-%s" "$n" "$1")
  adb exec-out screencap -p > "$OUT/$name.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$name.xml" >/dev/null 2>&1
  echo "$name.png - ${2:-$1}" >> "$OUT/index.txt"
}

# Screenshot only, no UI dump, so burst frames stay ~700 ms apart.
burst_shot() {
  n=$((n + 1)); name=$(printf "%02d-%s" "$n" "$1")
  adb exec-out screencap -p > "$OUT/$name.png"
}

# Centre of the first node whose text or content-desc matches $1 (exact, then
# contains); $2=last picks the lowest one on screen. Prints nothing if none.
dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
}

find_center() {
  dump_ui
  python3 - "$1" "${2:-first}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want, pick = sys.argv[1], sys.argv[2]
try:
    nodes = list(ET.parse('/tmp/ui.xml').iter('node'))
except Exception:
    sys.exit(0)
def label(n): return (n.get('text') or '') + '|' + (n.get('content-desc') or '')
hits = [n for n in nodes if want in ((n.get('text') or ''), (n.get('content-desc') or ''))]
if not hits:
    hits = [n for n in nodes if want in label(n)]
if not hits:
    sys.exit(0)
def centre(n):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    return (x1 + x2) // 2, (y1 + y2) // 2
c = sorted((centre(n) for n in hits), key=lambda p: p[1])
x, y = c[-1] if pick == 'last' else c[0]
print(x, y)
PY
}

# Labels that end the session or leave the app; never tapped, whatever the
# screen. The same list is also excluded from tap_first_item's candidates.
is_dangerous() {
  printf '%s' "$1" | grep -Eiq '로그인|로그아웃|sign[ _-]?in|sign[ _-]?out|logout|삭제|지우기|delete|clear|공유|share|http|외부'
}

# Returns 0 only when the label was found and tapped, so callers can decide
# whether a following BACK would be safe.
tap() {
  local at
  if is_dangerous "$1"; then
    echo "refusing dangerous label: $1"
    return 1
  fi
  at=$(find_center "$1" "$2")
  if [ -n "$at" ]; then
    adb shell input tap $at
    sleep "${3:-4}"
    return 0
  fi
  echo "not found: $1"
  return 1
}

swipe_up() { adb shell input swipe 540 1700 540 600 400; sleep 3; }

# Fast way back to the head of a long page.
scroll_top() {
  local i
  for i in 1 2 3 4 5 6 7 8; do adb shell input swipe 540 700 540 1900 250; sleep 0.5; done
  sleep 1
}

back() { adb shell input keyevent 4; sleep "${1:-3}"; }

# The IME leaves the bottom bar covered; one ESCAPE dismisses it. BACK is not
# used here: at the tab-bar root it would finish the activity.
keyboard_shown() { adb shell dumpsys input_method 2>/dev/null | grep -q 'mInputShown=true'; }
hide_keyboard() {
  if keyboard_shown; then
    adb shell input keyevent 111
    sleep 1
  fi
}

# Package of the resumed activity (empty if it cannot be parsed).
current_pkg() {
  local p
  p=$(adb shell dumpsys activity activities 2>/dev/null | sed -n 's/.*mResumedActivity.* \([A-Za-z0-9_.]*\)\/.*/\1/p' | head -1)
  [ -n "$p" ] || p=$(adb shell dumpsys window 2>/dev/null | sed -n 's/.*mCurrentFocus=.* \([A-Za-z0-9_.]*\)\/.*/\1/p' | head -1)
  printf '%s' "$p"
}

# Centre of a bottom-bar item of our app, matched by text, content-desc or
# resource-id. Only nodes inside the own package and the bottom strip count, so
# the launcher's full-screen "홈" and its search pill can never be picked up.
tab_node() {
  local labels=$1 ids=$2
  dump_ui
  python3 - "$PKG" "$TAB_MIN_Y" "$labels" "$ids" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pkg, min_y = sys.argv[1], int(sys.argv[2])
labels = [s for s in sys.argv[3].split('|') if s]
ids = [s.lower() for s in sys.argv[4].split('|') if s]
try:
    nodes = list(ET.parse('/tmp/ui.xml').iter('node'))
except Exception:
    sys.exit(0)
hits, loose = [], []
for n in nodes:
    if n.get('package') != pkg:
        continue
    try:
        x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', n.get('bounds') or ''))
    except Exception:
        continue
    if y1 < min_y or y2 - y1 < 20:
        continue
    text = (n.get('text') or '').strip()
    desc = (n.get('content-desc') or '').strip()
    rid = (n.get('resource-id') or '').lower()
    c = ((y1 + y2) // 2, (x1 + x2) // 2)
    if any(text == s or desc == s for s in labels) or any(s in rid for s in ids):
        hits.append(c)
    elif any(s in text or s in desc for s in labels):
        loose.append(c)
hits = hits or loose
if hits:
    hits.sort()
    print(hits[-1][1], hits[-1][0])
PY
}

# Coordinate fallback: four equal slots along the bottom tab bar.
tab_xy() { echo $(( SCREEN_W * (2 * $1 + 1) / 8 )) $(( SCREEN_H - SCREEN_H / 13 )); }

ALL_TAB_LABELS="홈|둘러보기|보관함|검색|Home|Explore|Library|Search"
ALL_TAB_IDS="tab_home|tab_explore|tab_library|tab_search|nav_home|nav_explore|nav_library|nav_search|home_tab|explore_tab|library_tab|search_tab"
tab_bar_visible() { [ -n "$(tab_node "$ALL_TAB_LABELS" "$ALL_TAB_IDS")" ]; }

# $1=key $2=slot index (0=Home, 1=Explore, 2=Library, 3=Search), $3=labels
# ("|"-separated, text or content-desc), $4=resource-id fragments.
tap_tab() {
  local key=$1 idx=$2 at
  hide_keyboard
  at=$(tab_node "$3" "$4")
  if [ -z "$at" ]; then
    at=$(tab_xy "$idx")
    echo "tab $key: not in dump, coordinate fallback at $at"
  fi
  adb shell input tap $at
  sleep "${5:-5}"
  return 0
}

# Every section starts from the same place: the Home tab, with no sheet or
# detail page left open by the section before it. BACK is only sent while the
# app is resumed and only until the tab bar is back: four blind BACKs at the
# Home root finish the activity and leave the launcher in front, whose bottom
# search pill then answers to "검색".
reset_home() {
  local i pkg
  hide_keyboard
  for i in 1 2 3; do
    tab_bar_visible && break
    pkg=$(current_pkg)
    if [ -n "$pkg" ] && [ "$pkg" != "$PKG" ]; then break; fi
    adb shell input keyevent 4
    sleep 2
  done
  pkg=$(current_pkg)
  if [ "$pkg" != "$PKG" ]; then
    echo "reset_home: '${pkg:-unknown}' in front, bringing $PKG back"
    adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
    sleep 15
  fi
  hide_keyboard
  tap_tab home 0 "홈|Home" "tab_home|nav_home|home_tab"
  sleep 2
}

# Swipe up until $1 is on screen; prints its centre or nothing.
scroll_to() {
  local label=$1 tries=${2:-10} i at
  for i in $(seq 1 "$tries"); do
    at=$(find_center "$label" first)
    if [ -n "$at" ]; then echo "$at"; return 0; fi
    adb shell input swipe 540 1700 540 600 400; sleep 2
  done
  return 1
}

# Find a label by scrolling down, then tap it. $2=sleep, $3=max swipes.
tap_scrolled() {
  local at
  if is_dangerous "$1"; then
    echo "refusing dangerous label: $1"
    return 1
  fi
  at=$(scroll_to "$1" "${3:-10}")
  if [ -n "$at" ]; then
    adb shell input tap $at
    sleep "${2:-5}"
    return 0
  fi
  echo "not found after scrolling: $1"
  return 1
}

# $1=name prefix, $2=times, $3=description
scroll_shots() {
  local prefix=$1 times=${2:-2} desc=${3:-$1} i
  for i in $(seq 1 "$times"); do
    swipe_up
    shot "$prefix-$i" "$desc (scroll $i)"
  done
}

# Tap the first plausible piece of content below the fixed top bars, e.g. the
# first album card in a filter, without knowing its title. Skips labels that
# belong to the chrome.
tap_first_item() {
  local at
  dump_ui
  at=$(python3 - "${1:-620}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
min_top = int(sys.argv[1])
skip = {
    '전체', '노래', '비디오', '앨범', '아티스트', '재생목록', 'YouTube', '보관함',
    '최근 검색', '지우기', '검색', '설정', '홈', '둘러보기', '다운로드',
    '로컬 음악', '자주 들은 노래', '반복 재생 중', '다시 듣기',
    '좋아요 표시했지만 덜 들은 노래', '모두 보기', '더 보기', '상위 결과',
    '재생', '셔플', '구독', '팔로우',
    '로그인', '로그아웃', '삭제', '공유', '외부에서 열기', '외부 링크',
}
try:
    nodes = list(ET.parse('/tmp/ui.xml').iter('node'))
except Exception:
    sys.exit(0)
out = []
for n in nodes:
    t = (n.get('text') or '').strip()
    if not t or t in skip:
        continue
    try:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    except Exception:
        continue
    if y1 < min_top or y2 > 2150 or x2 - x1 < 40:
        continue
    out.append(((y1, x1), ((x1 + x2) // 2, (y1 + y2) // 2)))
if out:
    out.sort()
    print(*out[0][1])
PY
)
  if [ -n "$at" ]; then
    adb shell input tap $at
    sleep "${2:-5}"
    return 0
  fi
  echo "no item found"
  return 1
}

# With no default IME the search field receives hardware key events as plain
# Latin; the Korean IME would otherwise compose them into jamo.
IME=$(adb shell settings get secure default_input_method | tr -d '\r')
case "$IME" in ''|null) IME= ;; esac
latin_input_start() { adb shell settings put secure default_input_method ''; sleep 1; }
latin_input_end() { [ -n "$IME" ] && adb shell settings put secure default_input_method "$IME"; sleep 1; }

# Focus the search field: the first EditText on screen.
tap_search_field() {
  dump_ui
  field=$(python3 - <<'PY'
import re, xml.etree.ElementTree as ET
try:
    for n in ET.parse('/tmp/ui.xml').iter('node'):
        if n.get('class') == 'android.widget.EditText':
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds'))); print((x1 + x2) // 2, (y1 + y2) // 2); break
except Exception:
    pass
PY
)
  if [ -n "$field" ]; then
    adb shell input tap $field
    return 0
  fi
  return 1
}

# ---- Home ---------------------------------------------------------------
section_home() {
  shot home "Home tab"
  scroll_shots home-scrolled 3 "Home scrolled"
}

# ---- Explore ------------------------------------------------------------
section_explore() {
  reset_home
  tap_tab explore 1 "둘러보기|Explore" "tab_explore|nav_explore|explore_tab|explore"
  shot explore "Explore tab"
  scroll_shots explore-scrolled 2 "Explore scrolled"
  # A mood/genre page from the grid (titles are Korean).
  if tap_scrolled "운동" 5 5; then
    shot explore-mood "Explore mood/genre page"
    scroll_shots explore-mood-scrolled 2 "Explore mood page scrolled"
    back
  fi
}

# ---- Library ------------------------------------------------------------
section_library() {
  reset_home
  tap_tab library 2 "보관함|Library" "tab_library|nav_library|library_tab|library"
  shot library "Library tab"
  scroll_shots library-scrolled 3 "Library scrolled"
  scroll_top

  # Folders.
  if tap_scrolled "다운로드" 5 8; then
    shot library-downloads "Library - Downloads"
    scroll_shots library-downloads-scrolled 2 "Library - Downloads scrolled"
    back
  fi
  scroll_top
  if tap_scrolled "로컬 음악" 5 8; then
    shot library-local "Library - Local music"
    if tap "아티스트" first 4; then shot library-local-artists "Library - Local music, artists tab"; fi
    if tap "앨범" first 4; then shot library-local-albums "Library - Local music, albums tab"; fi
    scroll_shots library-local-scrolled 2 "Library - Local music scrolled"
    back
  fi

  # Smart playlists made from this device's listening.
  for spec in "자주 들은 노래:library-smart-top" "반복 재생 중:library-smart-repeat" "다시 듣기:library-smart-rediscover" "좋아요 표시했지만 덜 들은 노래:library-smart-liked-unplayed"; do
    label=${spec%%:*}; key=${spec#*:}
    scroll_top
    if tap_scrolled "$label" 5 6; then
      shot "$key" "Smart playlist: $label"
      scroll_shots "$key-scrolled" 2 "Smart playlist: $label scrolled"
      back
    fi
  done

  # Listening history, from the Library top bar.
  scroll_top
  if tap "청취 기록" first 6; then
    shot history "Listening history"
    scroll_shots history-scrolled 2 "Listening history scrolled"
    back
  fi
}

# ---- Settings -----------------------------------------------------------
section_settings() {
  reset_home
  # Settings is reached from the Library top bar, so visit Library first
  # rather than assuming the previous section left us there.
  tap_tab library 2 "보관함|Library" "tab_library|nav_library|library_tab|library"
  sleep 2
  scroll_top
  # Settings lives behind the top-right account button (content-desc
  # '계정 전환'), not as a row on the Library page: open that menu first.
  tap "계정 전환" first 3 || tap "Switch account" first 3 || true
  sleep 2
  shot settings-account-menu "Account menu (settings entry)"
  if tap "설정" first 5 || tap "Settings" first 3; then
    shot settings-top "Settings (top)"
    scroll_shots settings-scrolled 3 "Settings scrolled"

    # One shot at each section header, wherever it sits on the long page.
    scroll_top
    for spec in "계정 및 연동:settings-account-group" "오디오 품질:settings-audio-quality" "다운로드:settings-downloads" "재생:settings-playback" "화면 표시:settings-appearance" "성능:settings-performance" "로컬 음악:settings-local-music" "저장 공간:settings-storage" "나의 데이터:settings-your-data" "기타:settings-miscellaneous" "언어:settings-language" "고급 및 실험적 기능:settings-advanced"; do
      label=${spec%%:*}; key=${spec#*:}
      # The compact settings page dropped several of these headers; a short
      # search keeps each missing one from spending a minute of the timeout.
      at=$(scroll_to "$label" 6)
      if [ -n "$at" ]; then
        sleep 1
        shot "$key" "Settings section: $label"
      fi
    done

    # Sub screens reachable from Settings.
    scroll_top
    if tap_scrolled "함께 듣기" 6 8; then
      shot settings-listen-together "Listen together screen"
      scroll_shots settings-listen-together-scrolled 2 "Listen together scrolled"
      back
    fi

    scroll_top
    if tap_scrolled "계정 및 연동" 6 8; then
      shot settings-account "Account and scrobbling"
      scroll_shots settings-account-scrolled 2 "Account and scrobbling scrolled"
      scroll_top
      # "Spotify" is both the group header and the row; the row is lower.
      if tap "Spotify" last 5; then
        shot settings-spotify "Spotify (account) screen"
        scroll_shots settings-spotify-scrolled 2 "Spotify screen scrolled"
        back
      fi
      if tap_scrolled "Discord" 6 8; then
        shot settings-discord "Discord (rich presence) screen"
        scroll_shots settings-discord-scrolled 2 "Discord screen scrolled"
        back
      fi
      back
    fi

    scroll_top
    if tap_scrolled "소스" 6 14; then
      shot settings-sources "Sources screen"
      scroll_shots settings-sources-scrolled 2 "Sources screen scrolled"
      back
    fi

    scroll_top
    if tap_scrolled "EQ" 6 14; then
      shot settings-equalizer "Equalizer screen"
      scroll_shots settings-equalizer-scrolled 2 "Equalizer scrolled"
      back
    fi

    scroll_top
    if tap_scrolled "가사 소스" 6 20; then
      shot settings-lyrics-sources "Lyrics sources dialog"
      back
    fi

    scroll_top
    if tap_scrolled "Replay" 6 20; then
      shot replay "Replay screen"
      scroll_shots replay-scrolled 2 "Replay scrolled"
      back
    fi

    back
  fi
}

# ---- Search -------------------------------------------------------------
section_search() {
  reset_home
  tap_tab search 3 "검색|Search" "tab_search|nav_search|search_tab"
  sleep 3
  shot search-empty "Search (empty, keyboard up)"

  latin_input_start
  tap_search_field || true
  sleep 2
  adb shell input text "Dance"
  sleep 5
  shot search-typing "Search with partial text (suggestions)"
  adb shell input text "%sMonkey"
  sleep 1
  adb shell input keyevent 66
  sleep 2
  latin_input_end
  sleep 8
  shot search-results "Search results (All)"

  # Result tabs, then the detail page each one leads to.
  if tap "노래" first 6; then sleep 3; shot search-results-songs "Search results - Songs tab"; fi
  if tap "앨범" first 6; then
    sleep 4
    shot search-results-albums "Search results - Albums tab"
    if tap_first_item 620 6; then
      shot album-detail "Album detail"
      scroll_shots album-detail-scrolled 2 "Album detail scrolled"
      back
    fi
  fi
  if tap "아티스트" first 6; then
    sleep 4
    shot search-results-artists "Search results - Artists tab"
    if tap_first_item 620 6; then
      shot artist-detail "Artist detail"
      scroll_shots artist-detail-scrolled 2 "Artist detail scrolled"
      back
    fi
  fi
  if tap "재생목록" first 6; then
    sleep 4
    shot search-results-playlists "Search results - Playlists tab"
    if tap_first_item 620 6; then
      shot playlist-detail "Playlist detail"
      scroll_shots playlist-detail-scrolled 2 "Playlist detail scrolled"
      back
    fi
  fi
  if tap "비디오" first 6; then sleep 3; shot search-results-videos "Search results - Videos tab"; fi
}

# ---- Now playing, lyrics, queue, sheets ---------------------------------
section_now_playing() {
  reset_home
  # A shared link populates the player even though the datacenter IP cannot play.
  adb shell am start -a android.intent.action.VIEW -d "https://music.youtube.com/watch?v=$MAIN_VIDEO" $PKG
  sleep 20
  shot now-playing-mini "Now playing (mini player)"
  if tap "Dance Monkey" last 4 || tap "Rolling" last 4; then
  sleep 2
  shot now-playing "Now playing (expanded)"
  adb shell input swipe 540 1200 540 400 400; sleep 3
  shot now-playing-scrolled "Now playing scrolled"

  if tap "가사" first 10 || tap "Lyrics" first 6; then
    shot lyrics "Lyrics"
    swipe_up; shot lyrics-scrolled "Lyrics scrolled"
    # Provider chooser, from the "가사 제공: ..." status line.
    if tap "가사 제공" first 5; then
      shot lyrics-providers "Lyrics provider sheet"
      back
    fi
  fi

  if tap "다음 재생" first 6 || tap "Up next" first 6; then
    shot queue "Queue sheet"
    scroll_shots queue-scrolled 2 "Queue sheet scrolled"
    back
  fi

  # Track menu -> lyrics sync/offset sheet.
  if tap "더 보기" first 5 || tap "More" first 5; then
    shot song-actions "Song actions sheet"
    if tap_scrolled "가사 싱크 조절" 4 4; then
      shot lyrics-offset "Lyrics offset sheet"
      back
    else
      back
    fi
  fi

  if tap "오디오 출력" first 5; then
    shot audio-output "Audio output sheet"
    back
  fi
  back
  fi
}

# Each section is independent: one that finds its label missing, or ends on a
# different screen than it expected, must not keep the next one from running.
# reset_home at the head of every section is what makes that safe, and
# run_section always succeeds so a failed section never aborts the rest.
run_section() {
  local fn=$1 label=$2 start=$n
  echo "== section $label start (shots $n)"
  "$fn" || echo "section $label reported an error"
  echo "== section $label end (shots $n, +$((n - start)))"
  return 0
}

# ---- Coming back to the app must not expand the player --------------------
# Reported on the device: with the mini player showing, leaving the app (Home,
# Back, Recents) and returning raised the full-screen player by itself. Each
# way back is captured, and `expanded=` counts markers only the full player
# has, so the result can be read from index.txt as well as the pictures.
player_expanded() {
  dump_ui
  local n
  n=$(grep -c -E 'text="(다음 재생|가사)"|content-desc="(다음 재생|가사)"|text="(Up next|Lyrics)"' /tmp/ui.xml 2>/dev/null || true)
  echo "${n:-0}"
}
section_return() {
  reset_home
  adb shell am start -a android.intent.action.VIEW -d "https://music.youtube.com/watch?v=$MAIN_VIDEO" $PKG
  sleep 20
  shot return-0-before "Mini player showing, before leaving"
  echo "return-0 expanded=$(player_expanded)" >> "$OUT/index.txt"
  # Home key, then the launcher icon.
  adb shell input keyevent 3; sleep 4
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1; sleep 7
  shot return-1-home "After Home key and launcher icon"
  echo "return-1-home expanded=$(player_expanded)" >> "$OUT/index.txt"
  # Back key leaves the app, then the launcher icon.
  adb shell input keyevent 4; sleep 4
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1; sleep 7
  shot return-2-back "After Back key and launcher icon"
  echo "return-2-back expanded=$(player_expanded)" >> "$OUT/index.txt"
  # Home, then back to the app through Recents.
  adb shell input keyevent 3; sleep 3
  adb shell input keyevent 187; sleep 4
  adb shell input tap $(( SCREEN_W / 2 )) $(( SCREEN_H / 2 )); sleep 7
  shot return-3-recents "After Recents"
  echo "return-3-recents expanded=$(player_expanded)" >> "$OUT/index.txt"
  # And a long stay in the background (the process may be reclaimed).
  adb shell input keyevent 3; sleep 2
  adb shell am kill $PKG; sleep 3
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1; sleep 9
  shot return-4-killed "After the app was killed in the background"
  echo "return-4-killed expanded=$(player_expanded)" >> "$OUT/index.txt"
  adb logcat -d | grep -E 'player expand' | tail -20 > "$OUT/player-expand.txt" || true
}

# ---- Proxy save must not crash -------------------------------------------
# Saving proxy settings once killed the app on the device (pool eviction on
# the main thread). Saves an unreachable proxy through the debug switch, which
# takes the same AppSettings path as the settings dialog, then checks the
# process is still alive and the Home tab still renders.
section_proxy() {
  reset_home
  adb shell am start -n $PKG/com.music.bitchord.MainActivity --es bitchord.debug.proxy "http://tour:tour@127.0.0.1:9"
  sleep 12
  if adb shell pidof $PKG >/dev/null 2>&1; then echo "proxy-save: alive" >> "$OUT/index.txt"; else echo "proxy-save: CRASHED" >> "$OUT/index.txt"; fi
  # Only crashes of our own process count (uiautomator's dump tool crashes on
  # its own sometimes and must not be mistaken for ours).
  adb logcat -d | grep -A3 'FATAL EXCEPTION' | grep -B1 -A2 "Process: $PKG" | head -20 > "$OUT/proxy-crash.txt" || true
  [ -s "$OUT/proxy-crash.txt" ] && echo "proxy-save: APP CRASHED (see proxy-crash.txt)" >> "$OUT/index.txt"
  shot proxy-saved "Home after saving an unreachable proxy (must not crash)"
  # Turn it back off for any later section.
  adb shell am start -n $PKG/com.music.bitchord.MainActivity --es bitchord.debug.proxy "off"
  sleep 5
}

# Quick mode: only the screens a home/explore/artwork change can affect.
if [ "${TOUR_QUICK:-0}" = "1" ]; then
  run_section section_home home
  run_section section_explore explore
  run_section section_search search
  run_section section_now_playing now-playing
  run_section section_return return
  run_section section_proxy proxy
else
  run_section section_home home
  run_section section_explore explore
  run_section section_library library
  run_section section_settings settings
  run_section section_search search
  run_section section_now_playing now-playing
  # The Settings groups open their detail as sheets from a row; this walks
  # them (section_settings above only reaches the group headers).
  section_settings_sheets() { reset_home; OUT="$OUT" bash scripts/ui-tour-settings.sh; }
  [ -f scripts/ui-tour-settings.sh ] && run_section section_settings_sheets settings-sheets
fi

adb logcat -d > "$OUT/logcat.txt"
# Why playback did not start, if it did not (bot check, 403, source error...).
adb logcat -d | grep -E 'playback failed|resolve failed|SignInConfirmNotBot|ExoPlaybackException|Playback error' > "$OUT/playback-errors.txt" 2>/dev/null || true
echo "index:"
cat "$OUT/index.txt"
ls -la "$OUT"
exit 0
