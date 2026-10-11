#!/usr/bin/env bash
# Installs the dev build on the emulator in Korean, walks the main screens and
# saves a screenshot (+ UI hierarchy) of each into shots/. Never fails the job.
set -x
OUT=shots
mkdir -p "$OUT"
PKG=com.dev.bitchord

APK=$(ls app/build/outputs/apk/dev/debug/*x86_64*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/dev/debug/*universal*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls app/build/outputs/apk/dev/debug/*.apk | head -1)
echo "APK: $APK"

adb shell "setprop persist.sys.locale ko-KR; setprop ctl.restart zygote"
sleep 20
adb wait-for-device
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
adb shell settings put system screen_off_timeout 1800000
adb install -r -g "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell cmd locale set-app-locales $PKG --locales ko-KR || true
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 25

n=0
shot() {
  n=$((n + 1)); name=$(printf "%02d-%s" "$n" "$1")
  adb exec-out screencap -p > "$OUT/$name.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$name.xml" >/dev/null 2>&1
}

# Centre of the first node whose text or content-desc matches $1 (exact, then
# contains); $2=last picks the lowest one on screen. Prints nothing if none.
find_center() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
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

tap() {
  local at
  at=$(find_center "$1" "$2")
  if [ -n "$at" ]; then adb shell input tap $at; else echo "not found: $1"; fi
  sleep "${3:-4}"
}

swipe_up() { adb shell input swipe 540 1700 540 600 400; sleep 3; }

shot home
swipe_up; shot home-scrolled
swipe_up; shot home-scrolled-2

tap "검색" last 5; tap "Search" last 3; shot search
# The search field: the first EditText on screen.
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
[ -n "$field" ] && adb shell input tap $field
sleep 2
adb shell input text "Dance%sMonkey"
adb shell input keyevent 66
sleep 10; shot search-results

tap "Dance Monkey" first 15; shot playing
tap "Dance Monkey" last 6; shot player
adb shell input swipe 540 1200 540 400 400; sleep 3; shot player-below
tap "가사" first 8; shot lyrics
adb shell input keyevent 4; sleep 3
adb shell input keyevent 4; sleep 3

tap "보관함" last 5; tap "Library" last 3; shot library
swipe_up; shot library-scrolled
tap "설정" first 5; tap "Settings" first 3; shot settings
swipe_up; shot settings-2
swipe_up; shot settings-3
swipe_up; shot settings-4
swipe_up; shot settings-5
# A cover video, opened the way a shared link would be: player, lyrics, timing.
for v in US2I6tSRjcE qQNbfxDLxq0; do
  adb shell am start -a android.intent.action.VIEW -d "https://music.youtube.com/watch?v=$v" $PKG
  sleep 30; shot "video-$v-mini"
  tap "Dance Monkey" last 4; tap "Rolling" last 4; shot "video-$v-player"
  tap "가사" first 10; tap "Lyrics" first 6; shot "video-$v-lyrics"
  sleep 40; shot "video-$v-lyrics-later"
  adb shell input keyevent 4; sleep 2; adb shell input keyevent 4; sleep 2
done
adb logcat -d > "$OUT/logcat.txt"
ls -la "$OUT"
exit 0
