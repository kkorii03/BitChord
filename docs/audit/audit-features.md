# audit-features — 빠진 기능 제안

- 점검일: 2026-10-26
- 범위: `app/`, `shared/`, `sharedUi/`의 화면·설정·플레이어 메뉴·데이터 경로
- 방법: 코드 정적 읽기와 좁은 grep/rg. 코드 수정 없음, 빌드/기기 실행 없음.
- 대상: 한국, YouTube Premium 없음, `music.youtube.com` 게이트는 도쿄 프록시/YouTube fallback으로 통과. S24 Ultra, 저장공간·데이터 무제한, 배터리 민감.

## 분류

이번 분야는 기능 제안이므로 확인된 항목은 모두 **빠진 기능**이다. 기존 기능의 버그·위험·낭비를 새로 주장하지 않는다. 각 제안의 구현 위험도는 항목 안에 따로 적었다.

- 버그: 0
- 위험: 0 (기존 코드에 대한 새 주장 없음)
- 낭비: 0
- 빠진 기능: 10

## 이미 있는 기능 — 제안에서 제외

설정 화면은 계정, 음질, 다운로드, 재생, 화면, 성능, 로컬 음악, 저장공간, Wear OS, 데이터, 기타, 언어, 고급의 13개 그룹으로 나뉜다(`SettingsSheet.kt:489`, `:526`, `:598`, `:657`, `:949`, `:1429`, `:1485`, `:1531`, `:1596`, `:1603`, `:1655`, `:1774`, `:1798`). 곡 메뉴에는 좋아요, 플레이리스트, 대기열, 공유, 다운로드, 라디오, 오디오/비디오 전환, 커버 원곡, 곡 정보, 수면 타이머, 재생 속도/피치, 시크릿 모드, 가사 오프셋이 이미 있다(`SongActionsSheet.kt:282-530`).

아래는 코드에서 확인했으므로 제안하지 않는다.

- 가사: 줄/단어 동기화, 제공처 선택, 번역, 로마자 표기, 곡별 오프셋, 잠금화면 가사, 가사 공유 카드. 로마자 토글은 `NowPlayingScreen.kt:2180`, 번역 토글은 `:2192`, 오프셋은 `LyricsController.kt:923`에 있다.
- 재생: 크로스페이드·AutoMix, 무간격, 수면 타이머와 수면 페이드아웃(`CrossfadeController.kt:1976`), 속도/피치(`SongActionsSheet.kt:488`, `:889`), 이퀄라이저, 라우드니스, 공간 음향, 스킵 사일런스, SponsorBlock류 구간 스킵.
- 플랫폼: Android Auto(`PlaybackService.kt:359`의 `MediaLibraryService`), Cast, 위젯, Wear OS, 잠금화면, 알림.
- 라이브러리: 다운로드/오프라인, 로컬 음악, WebDAV/SMB, Spotify 가져오기, 로컬 플레이리스트, 스마트 플레이리스트, 검색 필터, 라이브러리/상세 정렬, 대기열 히스토리.
- 소셜/기록: Listen Together(Jam), Last.fm/ListenBrainz, Discord, Replay/통계, 백업(로컬 플레이리스트 포함, `Backup.kt:87`).
- 개인정보: 시크릿 모드(`AppSettings.kt:321`, `SettingsSheet.kt:871`).
- 발견: YouTube Charts, moods/genres, 새 릴리스 선반. 단, 국내 차트와 국내 플랫폼 가져오기는 없다.

`docs/FEATURE_GAPS.md:33`은 시크릿 모드가 없다고 하고, `docs/COMPETITOR_ANALYSIS.md:53-56`은 로마자·이퀄라이저·SponsorBlock·Android Auto가 없다고 하지만 현재 코드에는 모두 있다. 이 문서는 현재 코드 기준으로 다시 확인했다.

---

## 1. 가사 수정·소스 고정(곡별 override)

**분류:** 빠진 기능 / **알려짐:** `docs/COMPETITOR_ANALYSIS.md:190`

**근거**

```kotlin
// sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsProviderSheet.kt:67-69
val sources = remember(savedOrder, enabledSources) {
    LyricsSource.ordered(savedOrder).filter { it in enabledSources }
}
```

```kotlin
// app/src/main/java/com/music/bitchord/ui/LyricsController.kt:923-925
fun setSongLyricsOffset(ms: Long) {
    val request = currentLyricsRequest ?: return
    val lines = timingLines ?: return
```

소스 선택과 오프셋만 있고, 줄 텍스트/시간을 고치거나 곡별로 소스를 고정하는 저장 경로는 없다. `LyricsOverride|editLyrics|saveLyrics|pinnedLyrics` grep 결과 0건이다.

**증상 또는 이득**

커버·다른 편집본에서 잘못된 LRC가 잡히면 0.2초 오프셋으로는 고칠 수 없다. 줄 단위 수정과 곡별 소스 고정이 있으면 사용자 우선순위 2(가사 싱크)를 네트워크/AI 재시도 없이 바로 만족시킬 수 있다.

**고치는 방법**

`shared`에 `LyricsOverrideStore`를 추가해 `videoId`별로 소스, 줄 텍스트, 줄 시작 시각, 수동 오프셋을 저장한다. `LyricsController.loadLyrics`/`fetchLyricsProvider`가 네트워크 조회 전에 override를 먼저 읽고, 없으면 기존 경로로 간다. `LyricsOffsetSheet` 또는 `PlayerSidePanels`의 가사 시트에 "가사 수정"을 붙이고, 줄 탭 → 텍스트 편집, ±50ms 너브, 소스 고정/해제를 제공한다. 번역·로마자 슬롯은 기존 `LyricLine` 구조를 유지해 깨지지 않게 한다.

**위험도:** 중간 — 저장 형식이 바뀌면 번역/로마자/백그라운드 보컬 슬롯이 어긋날 수 있다.

**예상 작업 크기:** M

**우선순위 점수:** 9

**배터리 비용:** 없음. 로컬 저장/표시만 한다.

---

## 2. Melon·Genie·FLO·Bugs 플레이리스트 가져오기

**분류:** 빠진 기능 / **알려짐:** 부분 — `docs/COMPETITOR_ANALYSIS.md:271`

**근거**

```kotlin
// sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/LibraryScreen.kt:343-345
if (onImportSpotifyPlaylist != null) {
    NewShelfCard(
        label = stringResource(Res.string.import_spotify),
```

```kotlin
// shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/spotify/SpotifyImporter.kt:62-63
private fun isSpotifyHost(host: String) =
    host == "spotify.com" || host.endsWith(".spotify.com")
```

라이브러리의 가져오기 카드와 파서가 Spotify 전용이다. Melon/Genie/FLO/Bugs 호스트나 국내 공유 링크를 받는 코드는 없다.

**증상 또는 이득**

국내 플랫폼에 있던 플레이리스트를 옮기려면 곡을 하나씩 검색해야 한다. 가져오기가 있으면 한국 사용자가 가장 먼저 하는 이사 작업이 한 번에 끝난다.

**고치는 방법**

`SpotifyImporter` 옆에 `KoreanPlaylistImporter`를 만든다. 공유 링크/텍스트에서 플랫폼과 playlist id를 파싱하고, 공개 페이지에서 제목·곡·아티스트·길이를 읽는다. 곡 매칭은 기존 `TrackMatcher.best`에 제목/아티스트/길이/앨범을 넘기고, ATV(노래 버전)를 우선한다. `LibraryScreen`의 `leadingCard`에 플랫폼 선택 카드를 추가하고, `MainActivity`의 Spotify 다이얼로그와 같은 흐름으로 `MainViewModel.createPlaylistWithVideoIds`에 넘긴다. 매칭 실패 행은 숨기지 말고 결과에 남긴다.

**위험도:** 높음 — 비공식 엔드포인트/로그인/차단 정책에 의존하고, 잘못 매칭하면 커버·다른 버전으로 바뀔 수 있다.

**예상 작업 크기:** L

**우선순위 점수:** 8

**배터리 비용:** 사용자 트리거 1회. 곡당 검색 1회, 동시성 4로 제한하면 주기 작업은 없다.

---

## 3. 국내 실시간 차트(Melon·Genie·Bugs·FLO) 탭

**분류:** 빠진 기능 / **알려짐:** 신규 (Melon 기능은 `docs/COMPETITOR_ANALYSIS.md:113-119`에 있으나 gap 제안은 없음)

**근거**

```kotlin
// shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/charts/ChartsApi.kt:63
private const val URL = "https://charts.youtube.com/youtubei/v1/browse?alt=json"
```

```kotlin
// shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/charts/ChartsApi.kt:30-32
TRACKS("trackViews", "TRACKS", "인기곡"),
VIDEOS("videoViews", "VIDEOS", "인기 뮤직비디오"),
ARTISTS("artistViews", "ARTISTS", "인기 아티스트"),
```

```kotlin
// app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:1672-1674
val charts = MoodGenre(
    title = text(R.string.charts),
    browseId = Innertube.CHARTS_BROWSE_ID,
```

Explore의 Charts는 YouTube Charts 세 목록뿐이다. 국내 차트 제공처를 부르는 코드는 없다.

**증상 또는 이득**

한국 사용자가 가장 자주 보는 "멜론 TOP100/실시간"을 보려면 다른 앱을 열어야 한다. 같은 화면에서 국내 차트를 보면 탐색 이탈이 줄고, 차트 곡을 바로 재생·저장할 수 있다.

**고치는 방법**

`ChartsApi`와 같은 형태의 `KoreanChartsApi`를 추가하고 제공처별 응답을 정규화한다. `MoodGenrePlaylistsScreen`의 차트 국가 선택 옆에 제공처 탭(YouTube/멜론/지니/벅스/FLO)을 붙인다. `MainViewModel.openMoodGenre`/`loadCharts`에서 차트 종류를 받아 분기하고, 응답은 10분 캐시한다. 각 행은 `TrackMatcher`로 YouTube 영상에 매칭하되, 원곡/ATV를 우선하고 매칭 신뢰도가 낮으면 "검색"으로 남긴다. 제공처가 실패하면 기존 YouTube Charts로 조용히 폴백한다.

**위험도:** 높음 — 비공식 API/스크래핑, 차단, 그리고 차트 곡이 커버/다른 버전으로 매칭될 위험.

**예상 작업 크기:** L

**우선순위 점수:** 8

**배터리 비용:** 차트 화면을 열 때 1회, 제공처별 10분 캐시. 주기 폴링 없음.

---

## 4. 한글 초성 검색(로컬·다운로드·플레이리스트 필터)

**분류:** 빠진 기능 / **알려짐:** 신규

**근거**

```kotlin
// app/src/main/java/com/music/bitchord/data/LocalGroups.kt:177-179
fun Song.matchesSearch(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        artist.contains(query, ignoreCase = true)
```

로컬/플레이리스트 필터는 단순 부분 문자열 비교다. `초성|chosung|jamo` 처리 코드는 없다. 검색 탭의 YouTube 검색은 서버 검색이라 초성을 이해하지 못한다.

**증상 또는 이득**

"ㄱㅇㅅ"로 김연수, "ㅇㅇ"로 아이유를 찾는 식의 초성 입력이 되지 않는다. 한국 사용자가 로컬 음악·다운로드·플레이리스트에서 곡을 찾는 속도가 눈에 띄게 좋아진다.

**고치는 방법**

`shared`에 순수 함수 `HangulChosung.of(text)`와 `matchesChosung(query, text)`를 추가한다. `Song.matchesSearch`에서 일반 부분 문자열이 실패하면 제목/아티스트/앨범의 초성과 비교한다. `DetailScreen.matching`, `LocalFolder`, 라이브러리 로컬 검색이 같은 함수를 쓰게 한다. YouTube 서버 검색은 그대로 두고, 로컬 목록에만 적용한다.

**위험도:** 낮음 — 로컬 문자열 비교만 추가한다.

**예상 작업 크기:** S

**우선순위 점수:** 8

**배터리 비용:** 없음.

---

## 5. M3U·CSV·일반 텍스트 플레이리스트 가져오기

**분류:** 빠진 기능 / **알려짐:** `docs/FEATURE_GAPS.md:62`

**근거**

```kotlin
// app/src/main/java/com/music/bitchord/download/OfflineHls.kt:41
if (!playlist.startsWith("#EXTM3U")) error("Invalid HLS playlist")
```

```kotlin
// shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/spotify/SpotifyImporter.kt:62-63
private fun isSpotifyHost(host: String) =
    host == "spotify.com" || host.endsWith(".spotify.com")
```

저장소의 `.m3u` 처리는 HLS 매니페스트 읽기/쓰기뿐이고, 사용자가 고른 `.m3u`/`.csv`를 플레이리스트로 가져오는 경로는 없다.

**증상 또는 이득**

다른 플레이어에서 내보낸 목록을 BitChord로 옮길 수 없다. M3U/CSV는 국내 플랫폼 API가 막혀도 쓸 수 있는 가장 안전한 이사 경로다.

**고치는 방법**

`shared`에 `M3uPlaylist.kt` 순수 파서를 추가한다: BOM/CRLF, `#EXTM3U`, `#EXTINF:duration,Artist - Title`, 주석, 상대/절대 경로, YouTube URL, CSV 헤더. `LocalPlaylistStore.importPlaylist`와 `MainViewModel.importPlaylist(uri)`를 만들고, 라이브러리/설정에서 `ActivityResultContracts.OpenDocument()`로 파일을 받는다. YouTube id는 직접 매칭하고, 나머지는 `TrackMatcher`로 검색하되 동시성을 제한하고 미매칭 행을 결과에 남긴다.

**위험도:** 낮음 — 파일 파싱과 기존 매칭 경로 재사용.

**예상 작업 크기:** M

**우선순위 점수:** 7

**배터리 비용:** 사용자 트리거 1회. 미매칭 곡만 검색한다.

---

## 6. 현재 대기열을 플레이리스트로 저장

**분류:** 빠진 기능 / **알려짐:** 신규

**근거**

```kotlin
// sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt:232
onClear: () -> Unit,
```

```kotlin
// sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt:448-450
private fun QueueClearButton(style: TextStyle, locked: Boolean, onClear: () -> Unit) {
    Text(
        text = stringResource(Res.string.clear),
```

```kotlin
// app/src/main/java/com/music/bitchord/ui/components/SongActionsSheet.kt:290-292
ActionRow(
    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
    label = stringResource(R.string.add_to_playlist),
```

대기열 헤더에는 비우기만 있고, 곡 메뉴의 플레이리스트 추가는 현재 곡 1개만 대상이다. 대기열 전체 저장 경로는 없다.

**증상 또는 이득**

라디오/AutoPlay로 만든 좋은 대기열을 남기려면 곡을 하나씩 플레이리스트에 넣어야 한다. "이 대기열 저장" 한 번이면 그 목록을 자산으로 남길 수 있다.

**고치는 방법**

`PlayerQueue` 헤더에 "대기열 저장"을 추가하고 `onSaveQueue`를 연결한다. `PlayerConnection`에서 현재 미디어 목록을 읽어 `MainViewModel.createPlaylistWithVideoIds(title, privacy, videoIds, songs)`에 넘긴다. 로그인 상태면 YouTube Music에, 실패/비로그인은 기존 `LocalPlaylistStore` 폴백으로 저장한다. 제목 입력은 기존 `createPlaylist` 다이얼로그를 재사용한다.

**위험도:** 낮음 — 기존 플레이리스트 생성 경로 재사용.

**예상 작업 크기:** S

**우선순위 점수:** 7

**배터리 비용:** 없음. 저장 시 네트워크 1회.

---

## 7. 원격 플레이리스트·라이브러리 다중 선택 일괄 작업

**분류:** 빠진 기능 / **알려짐:** 신규

**근거**

```kotlin
// app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt:493-495
val folderState = folder?.state
val folderGroups = if (folder != null && folderState != null) {
    rememberLocalFolderGroups(page.browseId, folder, folderState.tab, rawSongs, query)
```

```kotlin
// app/src/main/java/com/music/bitchord/ui/screens/LocalFolder.kt:157-161
var selectedIds by mutableStateOf<Set<String>>(emptySet())
    private set
var selectedGroups by mutableStateOf<Set<String>>(emptySet())
```

선택 상태와 선택 액션 바는 `LocalFolder`(다운로드/로컬 음악)에만 있다. 원격 플레이리스트·앨범·라이브러리 목록에는 다중 선택이 없다.

선택 상태와 선택 액션 바는 `LocalFolder`(다운로드/로컬 음악)에만 있다. 원격 플레이리스트·앨범·라이브러리 목록에는 다중 선택이 없다.

**증상 또는 이득**

원격 플레이리스트에서 여러 곡을 한 번에 다운로드/플레이리스트 추가/대기열 추가/삭제할 수 없다. 긴 플레이리스트를 정리하거나 오프라인으로 만들 때 곡마다 메뉴를 여는 비용이 크다.

**고치는 방법**

`LocalFolderState`의 선택 로직을 `DetailScreen` 전체에서 쓸 수 있는 `SongSelectionState`로 일반화한다. `LocalFolderSelectionBar`를 재사용해 선택 시 다운로드, 플레이리스트 추가, 대기열 추가, 캐시/다운로드 삭제 액션을 띄운다. 롱프레스로 선택 모드에 들어가고, Back은 선택 해제가 먼저 먹게 한다. `MainViewModel`의 기존 `addToPlaylists`, `startDownload`, `removeFromPlaylist`에 목록을 넘긴다.

**위험도:** 낮음 — UI 상태와 기존 액션 연결.

**예상 작업 크기:** M

**우선순위 점수:** 7

**배터리 비용:** 선택한 작업을 실행할 때만. 대기 상태 비용 없음.

---

## 8. 구독 아티스트 새 릴리스/새 커버 알림(앱 열 때)

**분류:** 빠진 기능 / **알려짐:** `docs/COMPETITOR_ANALYSIS.md:298`

**근거**

```kotlin
// app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:742
if (YtMusicRepository.setSubscribed(current.channelId, target).isSuccess) {
```

```kotlin
// app/src/main/java/com/music/bitchord/data/AppUpdateChecker.kt:176
private const val UPSTREAM_CHANNEL_ID = "upstream_releases"
```

```kotlin
// app/src/main/java/com/music/bitchord/download/DownloadService.kt:256
const val CHANNEL_ID = "downloads"
```

구독 토글은 있지만 새 릴리스 알림 채널은 재생·다운로드·WebDAV·업데이트뿐이다. 구독 채널의 새 영상을 감시하는 코드는 없다.

**증상 또는 이득**

좋아하는 VTuber의 새 커버가 올라와도 앱을 직접 열어 채널을 확인하기 전까지 모른다. 앱을 열 때 새 릴리스만 조용히 알려주면 "새 커버를 놓치는" 문제를 주기 폴링 없이 해결할 수 있다.

**고치는 방법**

`ReleaseWatchStore`에 채널별 마지막 확인 영상 id를 저장한다. `MainActivity`의 `ON_START` 또는 홈 첫 로드 이벤트에서 구독 채널/새 릴리스 선반을 1회 조회하고, 새 항목이 있으면 로컬 알림 채널 `artist_releases`로 알린다. WorkManager 주기 작업이나 타이머는 쓰지 않는다. 설정에 "구독 아티스트 새 릴리스 알림" 토글과 알림 권한 게이트를 두고, 실패는 조용히 넘긴다.

**위험도:** 중간 — 알림 권한, 네트워크 실패, 채널 피드 형식 변화.

**예상 작업 크기:** M

**우선순위 점수:** 6

**배터리 비용:** 앱이 포그라운드로 올 때 1회. 주기 폴링/타이머 없음.

---

## 9. 가사 글자 크기·줄 간격 조절

**분류:** 빠진 기능 / **알려짐:** 신규

**근거**

```kotlin
// sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt:2343-2345
MaterialTheme.typography.headlineLarge.copy(
    fontSize = 34.sp,
    lineHeight = 41.sp,
```

```kotlin
// sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt:2350-2352
MaterialTheme.typography.headlineMedium.copy(
    fontSize = 30.sp,
    lineHeight = 38.sp,
```

가사 크기와 줄 간격이 34/41sp, 30/38sp로 고정되어 있고, `lyrics_font|lyrics_size|font_size` 설정 문자열/코드는 없다.

**증상 또는 이득**

큰 글씨를 쓰거나 작은 화면/차량에서 볼 때 가사가 너무 작거나 줄이 답답하다. 크기/줄 간격을 조절하면 한국어 긴 가사도 읽기 편해지고, 가사 화면 접근성이 좋아진다.

**고치는 방법**

`AppSettings`에 `lyricsScale`(0.8–1.4)과 `lyricsLineSpacing`을 추가한다. `PlayerLyrics`의 동기/비동기 스타일과 잠금화면 가사에서 `fontSize`/`lineHeight`에 곱한다. 설정 > 가사 또는 `LyricsOffsetSheet`에 슬라이더와 미리보기를 붙이고, 기본값은 현재 값(1.0)으로 둔다. 타이머/폴링은 없다.

**위험도:** 낮음 — 표시 스타일만 바꾼다.

**예상 작업 크기:** S

**우선순위 점수:** 6

**배터리 비용:** 없음.

---

## 10. 로컬 플레이리스트·설정 자동 백업/복원(WebDAV)

**분류:** 빠진 기능 / **알려짐:** 신규 (수동 백업은 `docs/FEATURE_GAPS.md:77`에 알려짐)

**근거**

```kotlin
// app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:389-390
val exportPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/json"),
```

```kotlin
// app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:404-405
val importPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
```

```kotlin
// app/src/main/java/com/music/bitchord/data/webdav/WebDavUploads.kt:77
suspend fun upload(context: Context, songs: List<Song>): Summary = withContext(Dispatchers.IO) {
```

백업은 사용자가 파일을 고르는 수동 export/import이고, WebDAV는 원격 라이브러리 읽기와 오디오 업로드에 쓰인다. 설정/로컬 플레이리스트를 WebDAV에 자동으로 올리고 복원하는 경로는 없다.

**증상 또는 이득**

기기를 바꾸거나 앱을 지웠다 깔 때, 백업 파일을 미리 꺼내두지 않았으면 로컬 플레이리스트·설정·검색 기록을 잃는다. WebDAV 자동 백업이 있으면 "백업 잊음"으로 인한 손실을 막는다.

**고치는 방법**

기존 WebDAV 자격 증명(`AppSettings.webdavUrl/Username/Password`)을 재사용해 `WebDavBackup`을 만든다. `Backup.encode` 결과를 고정 경로(예: `/BitChord/backup.json`)에 변경 시 디바운스 업로드하거나 "지금 동기화"로 올린다. 새 기기/첫 실행에서는 내려받아 `Backup.validate` 후 사용자에게 교체/병합을 묻는다. 인증 토큰·비밀키는 `AppSettings`의 기존 SECRETS 제외 규칙을 그대로 따르고, 충돌 시 자동 덮어쓰기 대신 선택지를 준다. 주기 폴링은 하지 않는다.

**위험도:** 중간 — 병합 충돌, 잘못된 자동 덮어쓰기, WebDAV 경로 권한.

**예상 작업 크기:** M

**우선순위 점수:** 6

**배터리 비용:** 변경 시/수동 동기화만. 폴링 없음.

---

## 배터리 때문에 제외한 후보

- 보컬 분리/스템 분리(Sing 모드): 온디바이스 모델/디코드가 곡마다 무겁고 S24에서 발열·배터리 비용이 크다.
- 상시 새 릴리스 폴링/푸시: 주기 네트워크 작업이라 배터리 우선 원칙에 어긋난다. 위 8번은 앱 열 때 1회로 제한했다.
- 플레이리스트 자동 전체 다운로드 유지: 네트워크·저장 I/O가 크고, 사용자가 명시적으로 요청할 때만 하는 현재 방식이 배터리에 낫다.
- 샤잠식 음악 인식: 마이크/핑거프린트 모델과 네트워크가 필요해 L이며, 이번 10개보다 우선순위가 낮다. (추측: SongRec류 포팅 비용 큼)

## 요약 (10줄 이내)

1. 가사 수정·소스 고정(곡별 override) — 9
2. Melon·Genie·FLO·Bugs 플레이리스트 가져오기 — 8
3. 국내 실시간 차트(Melon·Genie·Bugs·FLO) 탭 — 8
4. 한글 초성 검색(로컬·다운로드·플레이리스트) — 8
5. M3U·CSV·텍스트 플레이리스트 가져오기 — 7
