# 검색 영역 정밀 검토 (2026-10-31)

기준 브랜치: `task/fix-1030` · 검토 브랜치: `dsq/deep-search-1031`
범위: 검색창, 자동완성/타이핑, 결과 탭(전체/노래/비디오/앨범/아티스트/재생목록),
검색 기록, 계정 없는 검색, 결과에서 재생, 커버/MV 표시.

---

## 1. 흔한 상황별 끝까지 따라간 결과

| 상황 | 진입점 → 처리 → 결과 | 판정 |
|---|---|---|
| 타이핑 중 자동완성 | `SearchField.onValueChange` → `MainViewModel.onQueryChange` → 300ms `debounce` → `searchSuggestions` (텍스트) + `searchTypeahead` (미디어) | 동작. 단 이전 응답이 남아 새 입력 밑에 붙는 문제 수정(§3-1) |
| 타이핑 중 요청 수 | 두 파이프라인이 같은 `suggestRequests`를 각자 300ms 묶음. 키 입력마다 2요청이 아니라 “멈춘 지점”마다 2요청 | 정상. 캐시 hit 시 0요청 |
| 결과 탭 전환 | `SearchFilterTabs` → `onFilterChange` → `runSearch` → `searchPage(filter)` | 동작. 탭 이름이 한국어 하드코딩이던 문제 수정(§3-5) |
| 결과 없음/오류 | `published`가 빈 페이지를 `UiState.Error(no_results)`로, 네트워크 실패를 `Error(friendly)`로 표시 | 오류에 다시 시도가 없던 문제 수정(§3-2) |
| 결과에서 노래 재생 | `onSongClick` → `playRadio(song)` → 선호 음악 전용이면 `songVersion`으로 MV→오디오 교체 → 1곡 큐 + AutoPlay | 동작. 검색 결과는 의도적으로 “목록 전체”가 아니라 방송국 시드 1곡으로 시작(§4-1) |
| 커버 영상/비디오 노래 재생 | `isVideo=true` 행도 `playRadio`가 `preferMusicOnly`일 때 `songVersion`으로 원곡 대체, 실패/타임아웃 시 원본 유지 | 동작 |
| 커버/MV 표시 | `SongRow`/`TypeaheadSongRow`는 `rememberRemoteArtworkUrl` → 썸네일 없으면 `videoThumbnailUrl`; `BrowseRow`는 결과 썸네일 | 동작 |
| 셔플 | 검색 결과 탭에는 셔플 버튼 없음. 재생은 `playRadio`가 새 큐를 만들고 셔플 상태는 큐 화면이 관리 | 검색 영역 밖, 회귀 없음 |
| 앱 재시작 후 | 검색 기록은 `SearchHistory` SharedPreferences에서 재로드; 현재 질의/결과는 ViewModel에만 있어 사라짐 | 기록은 동작, 현재 질의 복원은 없음(§4-3) |
| 화면 꺼짐/백그라운드 | 진행 중 검색은 `viewModelScope`에서 계속되고 화면 복귀 시 결과 표시. 주기 작업 없음 | 동작 |
| 프록시 켬 | `AppSettings.proxyRevision` 수집기가 `invalidateSearchCaches()` 후 Home/Explore 재조회; 다음 검색은 새 라우트 사용 | 동작 |
| 다운로드된 곡 | 검색 결과 자체는 YouTube 행. 재생 시 다운로드/소스 대체 경로가 처리 | 동작 |
| 느린/끊긴 망 | OkHttp connect 20s/read 30s. 검색은 별도 타임아웃 없음. 자동완성 실패는 조용히 typed 행만 남김 | 부분. 커밋 검색 장시간 스피너, fallback 실패는 “결과 없음”으로 보임(§4-2) |
| Android 14+ | 로컬 보관함 검색은 저장소 권한 없으면 `UiState.Error(storage_required_read)`로 안내 | 동작 |

---

## 2. 이번에 수정한 결함

### 2-1. [HIGH] 커밋 검색이 `collectLatest`로 이전 요청을 중간에 끊어 소켓을 파괴
- 파일: `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:2413-2448`
- 증거: 파이프라인 주석은 “long-lived collector라서 새 검색이 이전 요청을 취소하지 않는다”고
  설명하지만 실제 코드는 `searchRequests.collectLatest { ... searchPage(...) }`였다.
  `collectLatest`는 새 요청이 오면 이전 블록(네트워크 호출)을 취소한다.
- 사용자 영향: 빠르게 필터를 바꾸거나 결과를 연달아 누르면 이전 검색 소켓이 끊기고,
  풀링된 OkHttp 연결을 다음 요청이 물려받아 `Software caused connection abort`류 실패가 난다.
  검색이 느린 망에서 특히 자주 보인다.
- 빈도: 필터 연타·검색어 수정·느린 망에서 흔함.
- 수정: `collect`로 바꾸고 최신성은 `isCurrentSearchResponse(requestId, newestRequestId)`로만
  판정한다. 오래된 응답은 화면에 닿지 않고, 이전 요청은 끝까지 완료된다.
- 회귀 테스트: `com.music.bitchord.data.net.SearchSuggestionRowsTest.a stale response is not current`

### 2-2. [MEDIUM] 검색 오류 화면에 “다시 시도”가 없어 막다른 길
- 파일: `sharedUi/.../screens/SearchScreen.kt:378-383`, `:544-550`,
  호출부 `app/.../MainActivity.kt:3975-3981`
- 증거: YouTube/라이브러리/애드온 결과 오류가 `MessageState(message)`만 그렸다.
  같은 앱의 홈·탐색·보관함은 `retry` 액션이 있다.
- 사용자 영향: 일시적 네트워크 오류에서 검색 탭을 나갔다 들어오는 것 외에 복구 수단이 없다.
- 빈도: 끊긴 망/프록시 재연결 직후 흔함.
- 수정: `SearchScreen(onRetry=…)` 추가, 오류 `MessageState`에 `Res.string.retry` 액션 연결.
  라이브러리는 `loadLibrarySongs()`, 그 외는 `submitSearch()`를 다시 부른다.

### 2-3. [MEDIUM] 예전 검색 기록의 raw query(`q:…`)를 탭하면 가짜 videoId로 재생 시도
- 파일: `shared/.../model/SearchHistoryEntity.kt:52-76`,
  `app/.../MainActivity.kt:3985-4004`
- 증거: `toSong()`은 `q:` 항목에 null을 돌려주지만, `onHistoryClick`은
  `entityType == TRACK`이면 무조건 `Song(videoId = entity.id)`를 만들어 `playRadio`에 넘겼다.
  즉 `videoId="q:…"` 재생 시도 → 조용히 실패.
- 사용자 영향: 이전 버전에서 업그레이드한 사용자의 최근 검색 항목이 눌러도 아무 일도 없다.
- 빈도: 업그레이드 사용자에게만, 그러나 한 번 생기면 목록에 계속 남음.
- 수정: `SearchHistoryEntity.historyAction()`을 추가해 `Play` / `Open` / `Search`로 분기.
  raw query는 `searchFor(term)`으로 다시 검색한다.
- 회귀 테스트: `com.music.bitchord.data.model.SearchHistoryActionTest`
  (`an old raw query searches again instead of playing a fake video id`)

### 2-4. [MEDIUM] 타이핑 중 이전 자동완성/미디어가 새 입력 밑에 남음
- 파일: `app/.../ui/MainViewModel.kt:2215-2244`, `:2506-2528`
- 증거: `onQueryChange`는 `suggestRequests.tryEmit`만 하고 `_suggestions`/`_typeaheadResults`를
  그대로 뒀다. 새 조회가 실패하면 `?: return`으로 빠져 이전 prefix의 행이 계속 표시됐다.
  또 서버가 같은 자동완성을 두 번 주면 LazyColumn key `suggest:$term`이 중복돼 크래시할 수 있었다.
- 사용자 영향: 필드 글자와 다른 곡/완성 목록이 보이고, 중복 응답 시 검색 목록이 예외로 죽는다.
- 빈도: 끊긴 망에서 흔함. 중복 완성은 드물지만 발생 시 치명적.
- 수정: 입력 즉시 `_suggestions = listOf(typed)`(정확 검색 행) + `_typeaheadResults = empty`,
  응답 조립은 `suggestionRows()`로 중복 제거.
- 회귀 테스트: `com.music.bitchord.data.net.SearchSuggestionRowsTest`
  (`duplicate completions collapse so lazy list keys stay unique`)

### 2-5. [MEDIUM] 검색 결과 탭 이름과 빈 부제가 언어와 무관하게 한국어/영어 enum
- 파일: `sharedUi/.../screens/SearchScreen.kt:1157-1163`, `:1217-1227`
- 증거: 탭 텍스트는 `SearchFilter.label`(하드코딩 한국어), 빈 부제는
  `item.type.name.lowercase(...)`(영어 `Album`/`Playlist`)를 그대로 그렸다.
- 사용자 영향: 비한국어 UI에서 탭이 한국어로, 한국어 UI에서도 빈 부제가 영어로 나온다.
- 빈도: 비한국어 로케일 사용자에게 항상. 빈 부제는 드묾.
- 수정: `SearchFilter.labelResource()`와 `browseFallbackLabel()`을 리소스로 매핑.
  한국어 문구(전체/노래/비디오/앨범/아티스트/플레이리스트)는 그대로 유지.
- 회귀 테스트: `com.music.bitchord.ui.screens.SearchBrowseLabelTest`

### 2-6. [MEDIUM] 자동완성 미디어 행의 앨범/아티스트 길게 누르기가 빈 콜백
- 파일: `sharedUi/.../screens/SearchScreen.kt:312-321`, `:772-817`
- 증거: `BrowseRow`에 `onLongPress = onSongLongPress?.let { { /* not applicable */ } }`를 넘겨
  롱프레스가 소비되지만 아무 동작도 하지 않았다.
- 사용자 영향: 자동완성에서 앨범/아티스트를 길게 눌러도 메뉴가 없다.
- 빈도: 낮음~중간.
- 수정: 드롭다운에 `onBrowseLongPress`를 전달해 결과 행과 같은 앨범/재생목록 메뉴를 연다.

### 2-7. [MEDIUM] 애드온 검색 기록 저장이 초기화 전에 호출되면 크래시 가능
- 파일: `app/.../data/settings/SearchHistory.kt:87-95`
- 증거: `saveAddon()`만 `open()`을 부르지 않고 `prefs.edit()`를 바로 사용했다.
  `init()`의 열기는 백그라운드로 미뤄져 있어, 앱 시작 직후 애드온 검색이 먼저 도달하면
  `UninitializedPropertyAccessException`이 날 수 있다.
- 사용자 영향: 앱 재시작 직후 애드온 탭에서 검색하면 드물게 크래시.
- 빈도: 낮음.
- 수정: `open()` + `prefs` 초기화 확인 후 저장.

---

## 3. 고치지 못한 결함 (이유와 필요한 작업)

### 3-1. [MEDIUM] 애드온 전체 실패가 “결과 없음”으로 표시됨
- 파일: `app/.../ui/MainViewModel.kt:2270-2296`
- 증거: 각 애드온을 `runCatching { ... }.getOrDefault(emptyList())`로 감싸 실패/타임아웃을
  빈 목록으로 바꾼다. 모두 실패하면 `UiState.Success(emptyList())` → “있는 애드온이 없습니다”.
- 영향: 네트워크 장애와 실제 미보유를 구분할 수 없다.
- 필요한 작업: 애드온별 `Result`를 모아 전부 실패일 때만 오류 상태 + 재시도 문구를 보여주고,
  부분 실패는 성공 행과 함께 로그로 남긴다. 새 사용자 문구 확정 필요.

### 3-2. [MEDIUM] 다음 페이지 실패가 조용히 사라짐
- 파일: `app/.../ui/MainViewModel.kt:2461-2486`
- 증거: `searchContinuation`의 `onFailure`를 처리하지 않는다. 스크롤 끝에서 더 로드되지 않는다.
- 영향: 느린 망에서 목록이 끝난 줄 알고 이탈한다.
- 필요한 작업: continuation 오류 상태를 두고 목록 끝에 “다시 시도” 행을 붙인다.
  `SearchScreen`의 `loadingMore`와 별도 상태가 필요하다.

### 3-3. [MEDIUM] YouTube.com 폴백 실패가 “검색 결과 없음”으로 보임
- 파일: `shared/.../data/YtMusicRepository.kt:605-634`,
  `shared/.../data/innertube/YouTubeWebSearch.kt:79-118`
- 증거: `YouTubeWebSearch.songs()`는 전송/파싱 실패를 빈 목록으로 반환한다. 그러면
  `music.getOrThrow()`로 돌아가 원래 결과가 비었으면 `no_results` 오류가 된다.
- 영향: 한국 비프리미엄에서 Songs/VIDEOS 탭이 네트워크 문제로 비면 사용자는 “곡이 없다”고 오해.
- 필요한 작업: `songs()`를 `Result`로 바꿔 “폴백 실패”와 “실제 0건”을 구분하고,
  전자는 재시도 가능한 오류로 표시한다.

### 3-4. [LOW] 현재 검색어/결과가 프로세스 재시작 후 복원되지 않음
- 파일: `app/.../ui/MainViewModel.kt:203`
- 증거: `_query`가 `MutableStateFlow`일 뿐 `SavedStateHandle`이 아니다.
- 영향: 앱이 죽으면 검색하던 화면이 빈 검색 페이지로 돌아온다. 기록은 남는다.
- 필요한 작업: `SavedStateHandle`에 query/filter만 저장, 결과는 캐시 재조회.

### 3-5. [LOW] 검색 기록에 “검색어”가 아니라 “연 결과”만 남음
- 파일: `app/.../ui/MainViewModel.kt:2129-2197`, `MainActivity.kt:3979` 주석
- 증거: `recordSearch()`는 어디서도 호출되지 않고, 실제 기록은 결과 탭의 `recordEntity`뿐이다.
  “Suggestions land in search history via searchFor → recordSearch” 주석은 사실과 다르다.
- 영향: 검색만 하고 결과를 누르지 않으면 최근 검색에 남지 않는다. 의도된 엔티티 기록 방식과
  충돌하는 죽은 코드/주석이 남아 있다.
- 필요한 작업: 제품 결정(질의 기록 vs 엔티티 기록) 후 `recordSearch`를 연결하거나 제거하고
  주석을 고친다. 이번에는 기록 형식을 바꾸지 않았다.

---

## 4. 사용 성적표와 검색의 관계

- **다음 곡 싱크 준비 29%**: 검색 결과 탭은 `playRadio`로 “탭한 1곡 + 사용자 대기열”만 만든다
  (`QueueTimeline.buildOneOffQueue`). 다음 곡은 AutoPlay 방송국이 도착한 뒤에야 생기므로,
  검색에서 시작한 세션은 첫 곡 재생 중 다음 곡 싱크를 준비할 대상이 없다. 이는 “같은 곡 커버
  20개를 큐에 넣지 않는다”는 의도적 결정(주석 `MainActivity.kt:1917-1922`)의 부작용이다.
  검색 영역만 바꿔 해결하려면 방송국 시드를 먼저 백그라운드로 받아 2번째 곡을 미리 넣어야 하며,
  이번 범위에서는 큐 설계를 건드리지 않았다.
- **자동 재생 보충 성공 24% / 사용자 조작 없는 백그라운드 시작 11회**: 검색 탭 재생은 방송국
  로드가 끝나야 AutoPlay 후보가 생긴다. 느린 망에서는 그 사이 큐가 1곡으로 끝나 다음 곡 보충이
  실패로 집계된다. 위와 같은 큐 설계 문제이며 검색 요청 자체의 폴링 문제는 아니다(검색 경로에
  주기 타이머 없음).
- **무손실 확인 후 실제 무손실 재생 4%**: 검색 결과는 `prefetchTopResult`가 상위 1곡만 미리
  데우고, 나머지는 탭 시점에 해석한다. 검색에서 여러 곡을 연달아 들으면 확인-재생 간격이 벌어져
  `HqAudioVerify` 결과와 실제 스트림 선택이 어긋날 수 있다. 이번에는 검색 경로에 새 폴링을 넣지
  않았다(배터리 우선).
- **다운로드·고음질 보관함 실패 140회**: 검색 영역은 다운로드/보관함 실패의 직접 원인이 아니다.
  검색 결과의 대체 소스 핀(`prefetchTopResult`의 `StreamChoice.remember`)이 검색에서 시작한
  재생에만 걸리고, 백그라운드 다운로드 경로는 별도 정책을 쓴다. 따라서 이 숫자는 이번 영역의
  수정으로 줄이지 않았다.

---

## 5. 이번 변경의 테스트

- 신규: `com.music.bitchord.data.model.SearchHistoryActionTest`
- 신규: `com.music.bitchord.data.net.SearchSuggestionRowsTest`
- 신규: `com.music.bitchord.ui.screens.SearchBrowseLabelTest`
- 기존 회귀: `InnertubeSearchPrivacyTest`, `YouTubeWebSearchParserTest`, `SearchPagingTest`,
  `SearchHistoryTrackMappingTest`, `SearchHistoryPauseTest`
- 빌드: `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과
