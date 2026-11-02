# Phone crawl issues — vc131 (11-03)

Texts below come from the on-device accessibility crawl attached to the task
(s001–s044). Screen ids are the crawl ids. Each entry says what is wrong and
whether this lane fixed it.

Counts by kind: **raw/data titles 14**, **odd Korean or Apple-wording drift 8**,
**untranslated English 5**, **duplicated/truncated labels 10**, **no-op taps 5**.
This lane fixed **6 string resources** (see "Fixed here").

## Fixed here (plain string/label resources)

| screen | problem | fix |
|---|---|---|
| s001 | `find_your_mood` is `분위기 찾기` in the app resource but the shelf header is `내 분위기 찾기` (the crawl shows the shelf text). Screen title and section header name the same place differently. | app `values-ko`: `내 분위기 찾기` |
| s034, s044 | `좋아요 표시한 음악에서 제거` uses 음악 while the rest of the app and the crawl use `좋아요 표시한 노래`. | app + sharedUi `remove_from_liked` -> `좋아요 표시한 노래에서 제거` |
| s001 (For You) | sharedUi `personalized_recommendations` is a verb label (`나를 위한 맞춤 추천 받기`) while the app/crawl label is the noun form `나를 위한 추천`. | sharedUi `values-ko`: `나를 위한 추천` |
| s001, s042 | `One Direction 외 비슷한 아티스트` uses `외`; the English source is `%1$s & Similar Artists`, and the apple-compare doc asks for the display joiner. | sharedUi `values-ko`: `%1$s & 비슷한 아티스트` |
| s029 | `minutes_listened` was `청취 시간(분)`, a bracketed unit inside the noun label. Apple Music puts the unit on the value. | app `values-ko`: `청취 시간`, and the Replay card/poster value uses `minutes_listened_value` (`%1$s분` in ko, `%1$s` elsewhere). Every non-Korean label keeps its unit, so `再生時間（分）` is not followed by a second `分`. |

## Raw upload titles / data (left to the title-cleaning lane)

These are catalogue strings, not UI copy; changing them here would duplicate
that other lane's work. Listed for that lane.

| screen | text | note |
|---|---|---|
| s001 | `Perfect - EP` | raw release title; the card then appends `EP • 2015년`, so the path reads `Perfect - EP EP • 2015` (see duplication below). |
| s001 | `Blue Rock (cover)` | lowercase English `(cover)` tag. |
| s002, s010, s015, s021, s022, s026, s031, s037, s039, s041 | `Drag Me Down (Big Payno x AFTERHRS Remix) (feat. LunchMoney Lewis)` | raw nested-parenthesis title. |
| s003, s004, s008, s009, s016, s017, s018, s023, s024, s032, s033 | `Made In The A.M. (Deluxe Edition)` | raw album title with English edition tag. |
| s011, s035, s037, s039, s041 | `Perfect (Stripped)` / `Perfect (Matoma Remix)` | raw version tags. |
| s005 | `Stole My Heart` | data. |
| s015, s016, s020 | `HUNTR/X, EJAE, AUDREY NUNA 및 REI AMI` | artist credit uses `및`; the Apple Music KR comparison doc asks for display joiner ` & ` (display only, stored credit unchanged). |
| s026 | `벼랑위의 포뇨 ost (cover)` | lowercase `ost`, lowercase `(cover)` and a cover credit appended to the title. |
| s030 | `시인의 마을`, `영시의 사랑`, `바나나차차 (BANANA CHACHA)` | recent-search data, including parenthetical romanization. |
| s030 | `Various Artists` | raw artist name. |
| s030 | `Original Soundtrack`, `플레이리스트 · Original Soundtrack` | raw curator/type label. |
| s036 | `ファンサ - Fansa (cover) - 오화요` | launcher/widget data; Japanese title plus English romanization and cover tag. |
| s038 | `지금 재생 중` inline in the track list | playback marker, not a title. |

## Odd Korean / Apple-wording drift

| screen | text | problem |
|---|---|---|
| s029 | `1,237회 재생 · 2026` | year appears without `년`, while album pages write `2015년` and `EP • 2015년`. |
| s029 | `가입일 10/26` | `M/YY` date format is English-style; Apple KR would write `2026년 10월`. The value is built as `%02d/%02d` in `ReplayModel.kt`. |
| s029 | `Spotify` row has no Korean role label | brand is fine, but it sits between `다운로드` and `자주 들은 노래` with no description. |
| s013, s034 | `이런 항목 덜 추천` | YouTube-style phrase; Apple has no equivalent. Kept because it is this app's own action. |
| s025 | `추가한 날짜`, `기본 순서`, `제목(A–Z, 가–하)`, `제목(Z–A, 하–가)` | acceptable, but `추가한 날짜` and `최근 추가된 항목` name the same sort in two places. |
| s028 | `출퇴근 & 등하교` | uses `&` while settings copy uses `및`; the Apple comparison doc prefers `&` for display, so this is a consistency note only. |
| s029 | `1,237회 재생 · 2026` + `가입일 10/26` | two meta lines use different formats (bare year, slash date). |
| s036 | `3 페이지 중 1 페이지입니다.` | awkward spacing (`3페이지 중 1페이지입니다.` is the Apple/standard form). This text comes from the launcher's widget picker, not BitChord. |

## Untranslated English UI

| screen | text | note |
|---|---|---|
| s001, s002, s010 | `EP • 2015년` | `EP` is the standard Korean release-type label, but it is mixed with `앨범`/`싱글` elsewhere. |
| s030 | `Original Soundtrack` | data, see above. |
| s030 | `Various Artists` | data. |
| s036 | `StickerView` | launcher widget name, not BitChord. |
| s036 | `ヘルス` | launcher tile name (Japanese), not BitChord. |

`Shorts` (`shelf_shorts`) is still English in both `values-ko` files, but it is
not visible anywhere in this crawl; it is the YouTube Shorts brand name, so it
was left alone.

## Duplicated / truncated / garbled labels

| screen | text | note |
|---|---|---|
| s002, s010 | path `Perfect - EP EP • 2015` | title plus `EP • 2015년` subtitle repeats `EP`. |
| s005 | path `셔플 > 셔플` | the action name is pushed as the destination name. |
| s007, s018 | path `재생 > 셔플`, `재생 > 재생` | same: transport actions are recorded as screens. |
| s008 | path `One Direction > One Direction` | artist header tap re-pushes the artist page instead of doing nothing. |
| s012, s022, s031, s037, s041 | track numbers missing (`Home` without `2`, `Perfect (Matoma Remix)` without `3`) | row index labels are intermittently absent in the album list. |
| s027 | path `홈 홈` | selected tab is pushed again. |
| s028 | path `둘러보기 둘러보기` | selected tab is pushed again. |
| s029 | path `보관함 보관함` | selected tab is pushed again. |
| s039 | path `4 Drag Me Down (Big Payno x AFTERHRS Rem` | crawler truncation, not a UI truncation. |
| s042 | path `One Direction 외 비슷한 아티` | crawler truncation. |

## Taps that did nothing

| screen | tap | expected |
|---|---|---|
| s018 | `재생`, `셔플` on the album page | no screen change is correct: they start playback. Listed as observed, not a bug. |
| s018 | `Perfect (Stripped) 고해상도 무손실 더 보기` | should open the song menu (the same sheet shown by s013/s019). It did nothing. |
| s019 | `3 Perfect (Matoma Remix) 더 보기`, `4 Drag Me Down (Big Payno x AFTERHRS Rem) 더 보기` while `이 목록에서 검색` is open | should open the song menu. It did nothing. |
| s035 | `One Direction` under the album title | should open the artist page. It did nothing. |
| s039 | `재생` / `셔플` on the album page | same expected transport behavior as s018. |

## Not fixed here

- Title/data cleaning (raw `(cover)`, `ost`, `feat.`, edition/remix tags,
  `Various Artists`, `Original Soundtrack`, artist `및` joiner) is another
  lane's ownership.
- Missing track numbers and the no-op `더 보기` / artist taps are UI/state
  behavior, not string resources.
- Replay date/number formats (`%02d/%02d`, bare year) come from
  `ReplayModel.kt`, not `values-ko`; the `분` unit label is fixed above.
