# Apple Music (Android, KR, v7.0.0) vs BitChord 1.9 — from the user's 4 screenshots (11-02)

## Settings
- Apple: one short page. Grouped rounded cards under small grey section captions: (top card) 로그인 / 디스플레이 옵션: 테마 (subtitle = current value "시스템 테마"), 모션 (subtitle "애니메이션이 표시되는 방식을 관리합니다."), 가사 (subtitle "더 큰 텍스트: 가사"), 대비 증가 (toggle right, subtitle sentence) / 진단: 자동으로 보냄 (toggle), 진단 및 개인정보 보호에 관하여 / 정보: Apple Music 및 개인정보 보호에 관하여, Apple Music 이용 약관, 승인서, 피드백 제공, 지원 받기 (external-link arrow ↗ at right).
- Footer: grey "버전 7.0.0(1614)" + "Copyright © 2015–2026 Apple Inc. 모든 권리 보유." — Korean, no link list.
- Row pattern: title (white) + subtitle under it in grey; subtitles are either the current value or one 합니다체 sentence; toggles at the right; a link that leaves the app gets ↗.
- BitChord: footer is English link list -> make it "버전 1.9 (versionCode)" + one Korean line, move GitHub/Discord/developer links into an "정보" group (오픈 소스 라이선스, 피드백 제공, 지원 받기 ↗ style). Value shown under the title rather than at the right where Apple does so (테마-style rows). Keep cards/captions consistent.

## Tab bar
- Apple: floating rounded pill with 5 labeled tabs: 홈 / 새로운 음악 / 라디오 / 보관함 / 검색; selected = accent color icon + label; others white.
- BitChord: 홈 / 둘러보기 / 보관함 + separate round search button; collapses to 2 buttons on scroll. -> rename 둘러보기 to "새로운 음악" (Apple KR current name); search as a labeled tab inside the pill ("검색"); consider a 라디오 tab (stations / mixes we already have). Keep labels visible.

## Browse ("새로운 음악")
- Apple: huge bold page title; hero carousel cards: small grey caption ("최신 앨범", "신규 미니 앨범"), title, artist in grey, large rounded artwork; bold section headers with a chevron ("인기 신곡 >", "업데이트된 플레이리스트 >", "지금 인기 있는 곡 >", "모두가 듣고 있는 화제의 음악 >"); song sections are horizontally paged columns of 4 rows (artwork, title, grey artist, ⋮) with hairline dividers; playlist cards = square artwork + title + curator; explicit badge "19" in a small circle after the title.
- BitChord explore: a grid of colored mood tiles (YouTube Music style) and plain section titles. -> Apple-style section headers with chevron (tap = see all), song rows with ⋮, hero cards with caption/title/artist, curator line under playlist cards, 19 badge for explicit.

## Copy/term patterns seen
- 지금 시작하기, 새로운 음악, 최신 앨범, 인기 신곡, 업데이트된 플레이리스트, 지금 인기 있는 곡, 모두가 듣고 있는 화제의 음악, 디스플레이 옵션, 대비 증가, 자동으로 보냄, 피드백 제공, 지원 받기, 승인서, 버전.
- Artist joiner is "&" ("A2O & A2O LITE", "에픽하이 & 다비치"), not "및" — our credits from YouTube Music KR use "및"; display them with " & " like Apple (display only; the stored credit stays).

## Still to capture (tonight on the S24 via ADB, Apple Music installed there)
지금 재생 중, 가사 화면, 대기열(다음 항목), 노래 길게 누르기 메뉴, 보관함, 검색 (빈 화면/결과), 앨범, 아티스트, 플레이리스트, 미니 플레이어.
