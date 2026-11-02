# ui-tour 41376859810 (task/u19-tg 3609c241) — Opus visual notes (sheets 1, 4 of 5)

- 46 settings main: "가사" row appears twice (category list + Korean entry row at the bottom). After ui r2 the Korean row shows only on the main page, so the duplicate is still on the main page -> remove the Korean entry row (category 가사 now opens the fork lyrics page).
- 46 settings footer: English "bitchord 1.9 · GitHub · 개발자 · Discord · 표시하기 ~YouTube Music & Listen Together Backend" -> Korean / Apple-style about line.
- 61 song actions: "캐시에서 삭제" is developer jargon (Apple has no such item) -> rename (e.g. "저장된 사본 삭제"?) or hide; check "취침 타이머" vs Apple's term.
- 08-10 explore > mood (운동): only "연결 시간이 초과되었습니다. 다시 시도하십시오." + 다시 시도. Unknown if emulator network or v1.9 merge bug -> check on phone; check browse/mood request path for the merge.
- 03-04 home scrolled: grey skeletons never fill (emulator not signed in?) -> confirm on phone.
- 59 now playing: video area black; format line "Opus · 48.0 kHz · 135 kbps · 스테레오" shown on the main player (Apple shows only a badge like 무손실/Dolby Atmos) -> consider badge-only.
- **User report (11-02, screens 12/13 library search '쇼')**: search suggestions show non-music YouTube content (radio talk show '김태현의 정치쇼' episodes, comedy clips '빵 터짐 주의…') - Apple Music suggests only songs/artists/albums/playlists. Fix: default search (suggestions + results) shows music kinds only; podcast episodes only under the '에피소드' tab; non-music videos never in suggestions. Investigate which request/parser lets them in (suggestion API item types) and what the 'YouTube' scope tab means.
- **12 library-scrolled-1**: suggestion result thumbnails are blank grey squares (same rows show art on screen 13) -> art not loaded / late for suggestion rows.
- Good/Apple-like: album & artist pages, 재생/셔플 buttons, Wi-Fi 스트리밍/셀룰러 스트리밍/고해상도 무손실, collapsed tab bar.
- Not reviewed visually: sheets 2, 3, 5 (settings sub-pages, sources, lyrics) — covered by the text review (.tmp_verify/tour/tour-wording.md).
