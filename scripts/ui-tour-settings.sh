#!/usr/bin/env bash
# Walks the Settings sub-screens on an already-installed Korean dev build.
#
# The full screen tour lives in .github/ui-tour/tour.sh. Its Settings section
# only scrolled to each group header, so settings-audio-quality,
# settings-downloads and settings-playback were pictures of the Settings list
# itself. Those groups have no pages of their own: their detail opens as a sheet
# from a row. This script taps the row instead of the header:
#
#   settings-audio-quality  -> Wi-Fi 스트리밍 / 셀룰러 스트리밍 quality sheet
#   settings-downloads      -> 다운로드 품질 sheet
#   settings-playback       -> 자동 믹스 성능 sheet
#
# Usage: scripts/ui-tour-settings.sh
# Result: shots/*.png and one line per screen in shots/settings-index.txt.
set -u
set -x

OUT=${OUT:-shots}
PKG=${PKG:-com.dev.bitchord}
mkdir -p "$OUT"
: > "$OUT/settings-index.txt"

n=0
shot() {
  n=$((n + 1))
  local name
  name=$(printf "%02d-%s" "$n" "$1")
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "$name.png - ${2:-$1}" >> "$OUT/settings-index.txt"
}

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
}

# Centre of the first node whose text or content-desc matches $1 (exact first,
# then contains). $2=last picks the lowest match.
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

is_dangerous() {
  printf '%s' "$1" | grep -Eiq '로그인|로그아웃|sign[ _-]?in|sign[ _-]?out|logout|삭제|지우기|delete|clear|공유|share|http|외부'
}

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

scroll_top() {
  local i
  for i in 1 2 3 4 5 6 7 8; do
    adb shell input swipe 540 700 540 1900 250
    sleep 0.5
  done
  sleep 1
}

scroll_to() {
  local label=$1 tries=${2:-10} i at
  for i in $(seq 1 "$tries"); do
    at=$(find_center "$label" first)
    if [ -n "$at" ]; then echo "$at"; return 0; fi
    adb shell input swipe 540 1700 540 600 400
    sleep 2
  done
  return 1
}

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

back() { adb shell input keyevent 4; sleep "${1:-3}"; }

# Open Library -> account menu -> Settings, without assuming the previous
# screen.
open_settings() {
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 8
  scroll_top
  tap "보관함" first 5 || tap "Library" first 5 || true
  sleep 1
  scroll_top
  tap "계정 전환" first 3 || tap "Switch account" first 3 || true
  sleep 2
  shot settings-account-menu "Account menu (settings entry)"
  tap "설정" first 5 || tap "Settings" first 3 || true
  sleep 2
}

section_settings() {
  open_settings
  shot settings-top "Settings (top)"

  # Audio quality opens a sheet from a ceiling row, not from the group header.
  scroll_top
  if tap_scrolled "Wi-Fi 스트리밍" 4 12 || tap_scrolled "셀룰러 스트리밍" 4 12 ||
    tap_scrolled "On Wi-Fi" 4 12 || tap_scrolled "On mobile data" 4 12; then
    shot settings-audio-quality "Audio quality sheet"
    back
  fi

  # Downloads: the quality row opens the per-track sheet.
  scroll_top
  if tap_scrolled "다운로드 품질" 4 12 || tap_scrolled "Download quality" 4 12; then
    shot settings-downloads "Download quality sheet"
    back
  fi

  # Playback: Automix performance is the row with its own detail sheet.
  scroll_top
  if tap_scrolled "자동 믹스 성능" 4 12 || tap_scrolled "Automix performance" 4 12; then
    shot settings-playback "Automix performance sheet"
    back
  fi

  # Keep the group-header pictures the full tour also takes.
  scroll_top
  for spec in "계정 및 연동:settings-account-group" "오디오 품질:settings-audio-quality-group" \
    "다운로드:settings-downloads-group" "재생:settings-playback-group" \
    "화면 표시:settings-appearance" "성능:settings-performance" \
    "로컬 음악:settings-local-music" "저장 공간:settings-storage" \
    "나의 데이터:settings-your-data" "기타:settings-miscellaneous" \
    "언어:settings-language" "고급 및 실험적 기능:settings-advanced"; do
    label=${spec%%:*}
    key=${spec#*:}
    if [ -n "$(scroll_to "$label" 14)" ]; then
      sleep 1
      shot "$key" "Settings section: $label"
    fi
  done
}

section_settings

echo "index:"
cat "$OUT/settings-index.txt"
exit 0
