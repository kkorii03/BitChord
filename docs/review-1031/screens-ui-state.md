# 화면 표시 상태 엄격 검토 — screens-ui-state

기준: 현재 체크아웃(`dsq/deep-ui-1031`, 베이스 `task/fix-1030`), 2026-10-30.
범위: 홈·검색·보관함·재생·대기열·가사·설정 화면이 **실제 상태와 같은 것을 보여 주는지**.
방법: 각 화면의 진입점 → 상태 계산 → 표시 분기를 코드로 따라갔고, 공통 상황(커버 영상·비디오
노래·셔플·재시작·화면 꺼짐·백그라운드·프록시·다운로드·느린 망·Android 14+)마다 끝까지
읽었다. 기기 실행 없이 코드 추적과 단위 테스트로 확인했다. `.github/`, keys, 서명은
건드리지 않았다.

표시: **HIGH** = 사용자가 잘못된 결정을 하거나 데이터를 잃을 수 있음, **MEDIUM** = 화면이
실제 상태와 다르거나 막다른 길/빈 화면, **LOW** = 문구·일관성. `고침`은 이번에 코드를
바꾼 항목, `문서만`은 확인했지만 이번 범위에서 바꾸지 않은 항목이다.

## 요약

| 심각도 | 파일:줄 | 무엇이 고장 | 사용자 영향 | 수정 |
|---|---|---|---|---|
| MEDIUM | `sharedUi/.../screens/SearchScreen.kt:376`, `:423`, `:555` | 검색 오류에 다시 시도가 없고, 결과가 0건이면 필터 아래가 완전히 빈 화면이다. | 느린 망에서 검색이 실패하면 뒤로 나갔다 들어오는 것 말고 할 수 없다. 0건과 로딩 중을 구분할 수 없다. | **고침** |
| MEDIUM | `app/.../screens/HistoryScreen.kt:81`, `:173` | 로그인 상태에서 청취 기록이 0곡이면 제목만 있고 본문이 비어 있다. | 새 계정/초기화 후 기록 화면이 고장 난 것처럼 보인다. | **고침** |
| MEDIUM | `app/.../screens/SpotifyLibraryScreen.kt:72`, `:141`, `:159` | Spotify 목록 오류에 다시 시도가 없고, 연결됐지만 재생목록이 0개면 빈 화면이다. | 일시 오류에서 복구할 방법이 없고, 빈 계정과 로딩 중을 구분할 수 없다. | **고침** |
| MEDIUM | `sharedUi/.../player/PlayerLyrics.kt:1867`, `:2158` | 가사를 찾지 못하면 "가사 없음" 한 줄만 남고 제공처 선택으로 갈 수 없다. | 가사 싱크가 2순위인 사용자가 플레이어 메뉴까지 뒤져야 한다. | **고침** |
| MEDIUM | `app/.../screens/ListenTogetherScreen.kt:236`, `:389`, `:456` | 함께 듣기 실패에 `Throwable.message` 원문을 그대로 표시한다. | `ConnectException`, `HTTP 503` 같은 영어 기술 문구가 한국어 화면에 나온다. | **고침** |
| MEDIUM | `app/.../screens/SourcesScreen.kt:157`, `:432`, `:963`, `:988` | 소스/서버 테스트 실패에 예외 원문을 표시한다. | 애드온 설정 화면이 영어 예외를 그대로 보여 준다. | **고침** |
| MEDIUM | `app/.../components/AudioPipelineDialog.kt:112`, `:149`, `:450`, `:552` | 비트 퍼펙트 판정이 `Yes`, EQ가 `Flat`, 경로가 `BLUETOOTH` 같은 영어/하드코딩이었다. | 한국어 화면 한가운데 영어가 섞이고, 다른 언어에서는 한국어가 그대로 나온다. | **고침** |
| MEDIUM | `app/.../components/SpotifyImportAlert.kt:144`, `app/.../components/AccountAlerts.kt:498`, `app/.../screens/SettingsSheet.kt:2557` | Spotify 가져오기·계정·Last.fm 실패에 예외 원문이 남아 있었다. | 같은 영어 누출. | **고침** |
| LOW | `sharedUi/.../screens/SearchScreen.kt:1151` | 분류되지 않은 검색 카드의 부제가 영어 enum `Other`로 나왔다. | 한국어 UI에 `Other`가 노출된다. | **고침** |
| LOW | `app/src/main/res/values/strings.xml:553`, `:1294` | `PCM Data Rate`, Spotify 언어 라벨이 `translatable="false"`라 번역 자체가 불가능했다. | 한국어에서 영어로 고정. | **고침** |
| MEDIUM | `app/.../download/HqLibraryUpgrade.kt:109`, `:868`, `app/.../components/DownloadManagerSheet.kt:339` | HQ 보관함 패스의 실패가 화면 어디에도 남지 않는다. `Progress`에 실패 수가 없고 `finishRun()`이 진행 상태를 지운다. | 성적표의 "다운로드·고음질 보관함 실패 140회"를 사용자는 볼 수도, 다시 시도할 수도 없다. | 문서만 |
| MEDIUM | `sharedUi/.../player/PlayerQueue.kt:371`, `:473`, `:495` | 자동 재생이 켜져 있고 추천이 아직 없으면, 조회가 끝나기 전에 "추천 곡을 찾지 못했습니다"라고 단정한다. | "자동 재생 보충 성공 24%"가 사용자에게는 실패로만 보인다. 로딩/실패 상태 구분이 없다. | 문서만 |
| MEDIUM | `app/.../screens/DetailScreen.kt:804`, `app/.../ui/MainViewModel.kt:2736` | 앨범·재생목록 상세 오류에 다시 시도가 없다. `openDetail`이 같은 페이지를 다시 밀어 중복 페이지가 생기므로 단순 재호출로는 고칠 수 없다. | 일시 오류에서 뒤로 나갔다 다시 들어와야 한다. | 문서만 |

## 고친 결함

### 1. 검색 결과 0건/오류의 빈 화면과 막다른 길 (MEDIUM)

- **증거(수정 전)**: `SearchScreen.kt:373`은 `results is UiState.Error -> item { MessageState(results.message) }`,
  `:526`의 라이브러리 검색 오류도 같았다. `results is UiState.Success` 분기는
  `searchSections(...).forEach`만 했고, 섹션이 비면 아무 항목도 그리지 않았다.
- **영향**: 검색어를 제출했는데 결과가 없으면 필터 탭 아래가 완전히 빈 화면이다. 느린 망에서
  요청이 실패하면 메시지만 있고 다시 시도가 없다.
- **빈도**: 검색은 홈 다음으로 가장 많이 여는 화면이고, 오프라인/프록시 오류·오타·희귀곡에서
  항상 발생한다.
- **고친 곳**:
  - `SearchScreen`에 `onRetry`를 추가하고 YouTube/애드온/라이브러리 오류 모두
    `MessageState(..., actionLabel = retry, onAction = onRetry)`로 바꿨다.
  - `UiState.Success(emptyList())`이면 `search_no_results` 한 줄과 다시 시도를 그린다.
    `TopResultCard`가 있는 경우는 결과가 있는 것이므로 빈 상태를 그리지 않는다.
  - `MainViewModel.retrySearch()`는 현재 소스(YouTube/애드온/라이브러리)를 그대로 다시 묻는다.
- **회귀 테스트**: `sharedUi/src/jvmTest/.../screens/SearchEmptyStateTest.kt`
  - `aCompletedSearchWithNoRowsNeedsAnEmptyState`
  - `rowsResultsDoNotShowTheEmptyState`
  - `askingAndFailingAreNotTheSameAsNothingMatched`

### 2. 청취 기록 0곡의 빈 화면 (MEDIUM)

- **증거**: `HistoryScreen.kt`의 `UiState.Success` 분기는 `songs`가 비면 아무것도 그리지 않았다.
- **영향**: 새 계정이나 기록 초기화 후 기록 화면이 제목만 남는다.
- **고친 곳**: `historyHasNoSongs(state, signedIn)`을 추가해 로그인+성공+0곡일 때
  `history_empty`("아직 재생한 항목이 없습니다.")를 그린다. 게스트는 기존 로그인 안내를 유지한다.
- **회귀 테스트**: `app/src/test/.../screens/HistoryEmptyStateTest.kt` 3건.

### 3. Spotify 보관함 오류/빈 상태 (MEDIUM)

- **증거**: `SpotifyLibraryScreen.kt:130-136`은 오류 텍스트만, 빈 목록은 아무것도 그리지 않았다.
- **고친 곳**: `reload` 카운터로 같은 로더를 다시 돌리는 `다시 시도` 버튼, `loaded` 플래그로
  첫 로딩 중 빈 상태가 번쩍이지 않게 했다. 0개일 때는 `no_results`를 쓴다
  (이전 후보였던 `no_playlists_yet`은 "위 항목으로 만드십시오"라 이 화면에 없는 버튼을 가리킨다).

### 4. 가사 없음 화면의 막다른 길 (MEDIUM)

- **증거(수정 전)**: `PlayerLyrics.kt:2140-2146`은 "이 노래에는 가사가 없습니다."만 그렸다.
  `LyricsStatusWithChange`도 "없음"에서는 `onStatusClick`을 null로 두어 제공처 선택을 열지 않았다.
- **고친 곳**: `LyricsPanel`에 `onChooseProvider`를 추가하고, 빈 상태에
  `choose_lyrics_provider`("가사 제공처 선택") 버튼을 붙였다. 두 `LyricsPanel` 호출부
  (`NowPlayingScreen.kt:2235`, `:3600`)가 기존 `showLyricsProviders` 시트를 연다.
- **영향**: 가사 자동 조회 실패 시 바로 다른 제공처를 시도할 수 있다.

### 5. 영어 예외 원문 누출 (MEDIUM)

- **증거**: 함께 듣기 3곳, 소스 4곳, Spotify 가져오기 1곳, 계정 알림 1곳, Last.fm 1곳이
  `Throwable.message`를 그대로 화면에 넣었다.
- **고친 곳**: 모두 `FriendlyError.message(context, error)`로 바꿨다. 이 매퍼는 이미 한국어인
  메시지는 그대로 두고, `UnknownHostException`/`SocketTimeoutException`/SSL/프록시/429/5xx/
  401·403/ConnectException만 한국어 문장으로 바꾼다.
- **회귀 테스트**: 기존 `app/src/test/.../ui/FriendlyErrorTest.kt`가 매퍼의 한국어 통과와
  HTTP 상태 인식을 고정한다. 새 화면 호출부는 같은 매퍼를 쓰므로 별도 분기 테스트가 필요 없다.

### 6. 오디오 파이프라인 언어 혼용 (MEDIUM)

- **증거(수정 전)**:
  - `AudioPipelineDialog.kt:118` `"Yes${detail...}"`
  - `:369-373` `eqPreset.name.lowercase()` / `"Flat"`
  - `:471` `PipelineRow("경로", outputStatus.routeKind.name)` → `BLUETOOTH`
  - `app/src/main/res/values/strings.xml:512` `pipeline_pcm_data_rate translatable="false"`
  - `:1222-1223` Spotify 언어 라벨 `translatable="false"`
- **고친 곳**:
  - `bitExactVerdict`를 `BitExactVerdict` enum으로 바꾸고 화면에서 `stringResource`로 렌더링한다.
    기존 판정 순서는 그대로다.
  - EQ 프리셋은 `EqualizerScreen`의 `localizedLabel()`을 `internal`로 열어 재사용하고,
    꺼짐은 `eq_preset_flat`("플랫")을 쓴다.
  - 경로는 `route_phone/wired/usb/bluetooth/hdmi/other`로 번역한다.
  - `pipeline_pcm_data_rate`, Spotify 언어 라벨의 `translatable="false"`를 제거하고
    `values-ko`를 추가했다.
- **진리표(비트 퍼펙트 판정, 수정 전 → 수정 후 동일)**

  | dsd | loudness | eq | spatial | outputExact | 수정 전 문자열 | 수정 후 enum |
  |---|---|---|---|---|---|---|
  | true | * | * | * | * | 아니요 — DSD를 PCM으로 변환 | DsdToPcm |
  | false | true | * | * | * | 아니요 — 사운드 보정 | Loudness |
  | false | false | true | * | * | 아니요 — EQ | Equalizer |
  | false | false | false | true | * | 아니요 — 공간 음향 | Spatial |
  | false | false | false | false | true | **Yes** | Exact → "예" |
  | false | false | false | false | false | 아니요 — {detail 또는 하위 단계} | Downstream |

- **회귀 테스트**:
  - `app/src/test/.../ui/components/AudioPipelineVerdictTest.kt` (6개 분기)
  - `app/src/test/.../ui/UiTranslationCoverageTest.kt` (17개 키의 한국어 존재 + translatable 금지)

### 7. 검색 카드의 영어 enum `Other` (LOW)

- **증거**: `SearchScreen.kt:1119-1123`이 부제가 비었을 때 `item.type.name.lowercase()`를 썼다.
  `appleCardKind(BrowseType.OTHER)`는 null이라 이 분기로 떨어진다.
- **고친 곳**: `stringResource(Res.string.more)`("더 보기")를 쓴다. `Locale` import도 제거했다.
- **비고**: 순수 문자열 선택이라 별도 단위 테스트 대신 `UiTranslationCoverageTest`와
  `SearchEmptyStateTest`로 주변 상태를 고정했다.

## 고치지 못한 HIGH/MEDIUM

### A. HQ 보관함 패스 실패가 화면에 없다 (MEDIUM, 성적표 140회와 직결)

- **근거**: `HqLibraryUpgrade.Progress`는 `running/done/total/waiting`만 있고 실패 수가 없다
  (`HqLibraryUpgrade.kt:109-116`). 실패는 `failures` 맵에 재시도 정책용으로만 남고,
  패스가 끝나면 `finishRun()`이 `_progress.value = Progress()`로 지운다(`:868-880`).
  `DownloadManagerSheet`의 HQ 줄은 `progress.visible`일 때만 진행률을 보여 주고
  (`DownloadManagerSheet.kt:227`, `:363`), `session.failed`는 사용자가 시작한 일반
  다운로드만 센다(`DownloadSession.kt:87`).
- **영향**: 자동/수동 HQ 교체가 실패해도 화면은 조용히 원래 문구로 돌아간다. 사용자는
  "이미 최고 음질"인지 "방금 실패"인지 구분할 수 없고, 실패한 곡을 다시 고를 수도 없다.
- **왜 이번에 못 고쳤나**: `Progress`에 `failed`를 넣고 `finishRun` 뒤에도 결과를 남기려면
  패스 수명(자동 재시작/포그라운드 재개/수동 패스)과 저장 키를 함께 바꿔야 한다. 지금
  `failures`는 "이번 패스"가 아니라 "영구 재시도 이력"이라 그대로 세면 140회가 매번
  다시 표시된다. 필요한 작업: 패스별 `failedThisRun`과 종료 요약 상태를 추가하고,
  Downloads 화면에 "N곡 실패 · 다시 시도"를 붙인 뒤, `HqLibraryUpgrade` 상태 기계 테스트를
  추가한다.

### B. 자동 재생 빈 상태가 로딩을 실패로 단정한다 (MEDIUM, 성적표 24%와 직결)

- **근거**: `PlayerQueue.kt:371`은 `autoplayEnabled || tracks.autoplay.isNotEmpty()`일 때
  머리글을 그린다. `AutoplayHeading`은 `hasTracks`만 받아(`:473`)
  `false`면 `autoplay_empty_description`("추천 곡을 찾지 못했습니다", `values-ko:13`)를 쓴다.
  서비스의 `autoplayLoadJob`/`autoplaySeed` 상태는 화면에 노출되지 않는다
  (`PlaybackService.kt:1054-1055`, `:3495-3510`).
- **영향**: 자동 재생을 켠 직후, 또는 느린 망에서 조회가 진행 중일 때 화면은 이미
  "추천 없음"이라고 말한다. 실제로는 곧 채워질 수 있어 사용자는 기능이 고장 났다고 읽는다.
- **필요한 작업**: `PlaybackService`가 `Idle/Loading/Ready/Empty/Failed` 상태를
  `StateFlow`로 내보내고, `PlayerQueue`가 그 값으로 문구를 고른다. 배터리 원칙상 폴링이
  아니라 `autoplayLoadJob`의 시작/완료/취소 이벤트에서만 갱신해야 한다.

### C. 상세 페이지 오류의 다시 시도 (MEDIUM)

- **근거**: `DetailScreen.kt:804`는 `MessageState(state.message)`만 그린다. `MainViewModel.openDetail`
  (`:2736`)은 같은 `browseId`의 페이지가 `Loading`일 때만 중복을 막고, `Error`일 때 다시
  호출하면 같은 페이지를 하나 더 밀어 넣는다. 그래서 화면의 재시도 버튼을 단순히
  `openDetail`에 연결하면 뒤로가기가 두 번 필요해진다.
- **필요한 작업**: `openDetail`의 로드 블록을 `loadDetail(page)`로 분리하고, 오류 상태의
  기존 페이지를 제자리에서 `Loading`으로 바꾼 뒤 같은 로더를 다시 돌린다. 그 다음
  `DetailScreen`에 `onRetry`를 넘긴다.

### D. 다음 곡 싱크 준비 상태를 화면이 보여 주지 않는다 (성적표 29%, 화면 결함 아님)

- **근거**: 화면은 현재 곡의 `LyricsController` 상태만 읽고(`LyricsController.kt:270`의
  `NextPrepCache`는 다음 곡이 시작될 때 소비), 다음 곡의 준비 진행/실패는 표시하지 않는다.
- **판정**: 화면이 거짓말을 하는 것은 아니다. 29%라는 수치는 `NextTrackPreparation`/
  `LyricsTiming`의 준비 정책·타임아웃 문제이므로 이 화면 레인에서 고칠 수 없다. 표시를
  추가하려면 재생 레인의 이벤트 상태가 먼저 필요하다.

## 흔한 상황별 추적표

| 상황 | 진입점 | 코드 경로 | 실제 화면 결과 | 판정 |
|---|---|---|---|---|
| 커버 영상 | 홈/검색 카드 → 곡 메뉴 | `SongActionsSheet.kt:430-470` `coverSingerChannelId`, `CoverTitles.creditedArtist` | 커버 채널/원곡자 행이 실제 값으로 표시된다. | 정상 |
| 비디오 노래 | 플레이어 → 대기열 | `PlayerQueue.kt:375-383` `VideoRadioRow(enabled = autoplayEnabled)` | 자동 재생이 꺼져 있으면 스위치도 꺼진 채다. | 정상 |
| 셔플 | 플레이어 모드 캡슐 | `PlayerModeControls.kt:41-70`, `PlayerControls.kt:659-690` | 셔플/반복/자동 재생의 아이콘·배지·접근성 문구가 각각 상태와 일치한다. | 정상(기존 테스트 있음) |
| 앱 재시작 후 | 플레이어/보관함 | `AppSettings.repeatMode` 복원, `NowPlayingScreen.kt:970-975` 마지막 화면 복원, `Downloads.init` 백그라운드 로드 | 반복 모드와 마지막 플레이어 화면이 유지된다. | 정상 |
| 화면 꺼짐 | 플레이어/가사 | `PlayerConnection.kt:433-446` 포그라운드 게이트, `PlayerLyrics.kt:1998-2014` 일회성 delay | 위치 폴링과 가사 타이머가 포그라운드에서만 돈다. | 정상 |
| 백그라운드 | 플레이어 프리웜 | `NowPlayingScreen.kt:986-999` `rememberIsForeground` | 백드롭/가사/대기열 프리웜을 건너뛴다. | 정상 |
| 프록시 켬 | 설정/소스/검색 | `FriendlyError.kt:22-49`, `SourcesScreen.kt:157` 등 | 프록시/타임아웃/5xx가 한국어 문장으로 표시된다. | 정상(이번에 소스·함께 듣기 보강) |
| 다운로드된 곡 | 목록/곡 메뉴 | `Common.kt:843-857` `downloadedIds()`, `SongActionsSheet.kt:690-785` 디스크 재확인 | 저장 배지와 "삭제" 행이 실제 파일 기준으로 정리된다. | 정상(파일 삭제 지연은 기존 주석의 트레이드오프) |
| 느린/끊긴 망 | 홈/탐색/보관함/검색/Spotify | 홈·탐색·보관함은 이미 retry, 검색·Spotify는 이번에 추가, 상세는 미수정 | 대부분 한 번에 다시 시도할 수 있다. 상세만 뒤로가기 필요. | 부분 |
| Android 14+ | 검색→로컬 음악 | `MainViewModel.loadLibrarySongs()` `hasStoragePermission` 실패 시 `storage_required_read` | 권한이 없으면 빈 목록이 아니라 이유를 보여 준다. | 정상 |

## 성적표 숫자(vc85, 362곡)와 화면의 관계

| 수치 | 화면에서 보이는 것 | 코드 설명 | 이번 조치 |
|---|---|---|---|
| 다음 곡 싱크 준비 29% | 아무 표시 없음 | `NextPrepCache`는 다음 곡 시작 때만 소비된다(`LyricsController.kt:270`). 준비 실패/지연은 화면 상태가 아니다. | 화면 결함 아님, 재생 레인 필요 |
| 무손실 확인 후 실제 무손실 재생 4% | 배지는 측정된 스트림만 표시 | `NerdStats.Snapshot.isLossless`는 디코더 mime 기준이고(`NerdStats.kt:94-116`), `PlaybackQualityLabel`은 그 값만 그린다. 수치가 낮은 원인은 소스 선택/폴백이지 배지가 아니다. | 파이프라인 화면의 표시 언어를 고침 |
| 자동 재생 보충 성공 24% | 빈 대기열에 "추천 곡을 찾지 못했습니다" | 조회 중/실패/진짜 없음의 구분이 화면에 없다(`PlayerQueue.kt:473-497`). | 문서만(B) |
| 사용자 조작 없는 백그라운드 시작 11회 | 화면 없음 | 재생 서비스/알림 경로. 화면 표시와 무관. | 범위 밖 |
| 다운로드·고음질 보관함 실패 140회 | HQ 진행 줄이 사라짐 | `HqLibraryUpgrade.Progress`에 실패 수가 없고 `finishRun()`이 지운다. 일반 다운로드 실패만 `DownloadSession`에 남는다. | 문서만(A) |

## ui-tour 화면 수 74 → 67

- **원인은 화면 삭제가 아니다.** 현재 체크아웃의 진입점은 모두 남아 있다:
  - 보관함 링크 `libraryLinks()`에 `다운로드`/`로컬 음악`/스마트 플레이리스트 4개가 있다
    (`app/.../screens/LibraryPhoneParts.kt:45-118`).
  - 설정에는 `계정 및 연동`, `함께 듣기`, `소스`, `EQ`, `가사 소스`, `Replay` 행이 있다
    (`SettingsSheet.kt:603-625`, `:1101-1106`, `:1371-1379`, `:1777-1782`).
  - 검색 필터와 플레이어 시트도 그대로다.
- **원인은 tour 스크립트의 best-effort 계수 방식이다.** `tour.sh`의 `shot()`은 실제로 도달한
  경우에만 `n`을 올리고, 대부분의 단계가 `if tap ...; then shot ...` 또는
  `tap_scrolled`(찾지 못하면 로그만 남기고 1 반환)이다. `index.txt`에는 실패/건너뜀이
  기록되지 않으므로, 같은 스크립트도 네트워크·라벨 스크롤·봇 차단에 따라 74에서 67로
  줄어든다. `section_search`의 앨범/아티스트/재생목록 상세는 `tap_first_item`이 실패하면
  각 3장(상세 1 + `scroll_shots` 2)이 통째로 사라진다. 따라서 7장 차이는 상세 2개(3+3)와
  단일 `shot` 조건 1장(1)의 조합으로는 설명되지만, `index.txt`에 건너뜀 기록이 없어
  정확히 어느 단계였는지는 확정할 수 없다. 이전 판의 "두 종류의 실패(3+3+1)" 서술은
  스마트 플레이리스트 실패가 3장인데 1로 적혀 산술이 맞지 않았으므로 바로잡는다.
- **수정하지 않은 이유**: `.github/`는 이번 작업의 금지 영역이다. tour를 의미 있게 만들려면
  (1) `index.txt`에 `skipped:` 줄을 남기고, (2) 최소 화면 수를 assert하며, (3) 검색 결과가
  봇 차단이면 실패로 표시해야 한다. 이는 별도 `.github` 작업이다.

## 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 통과.
- `./gradlew :sharedUi:jvmTest --console=plain -q` → 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.ui.*' --console=plain -q` → 통과.
- 전체 `:app:testProdReleaseUnitTest`는 1690개 중 22개가 실패한다. **이 22개는 이번 변경
  전 기준(HEAD)에서도 똑같이 실패하는 기존 실패**다(`git stash`로 HEAD를 되돌려 같은
  클래스만 다시 실행해 확인). 해당 클래스:
  `ArtistNameMatchTest`, `LocaleStringsTest`, `ProviderLyricsTest`, `SourceFormatsTest`,
  `BluetoothAudioTrackerTest`. 이번 변경이 새로 만든 실패는 없다.
- 새 테스트:
  - `SearchEmptyStateTest` (`sharedUi`)
  - `HistoryEmptyStateTest` (`app`)
  - `AudioPipelineVerdictTest` (`app`)
  - `UiTranslationCoverageTest` (`app`)

## 바꾼 파일

- `sharedUi/.../ui/screens/SearchScreen.kt`
- `sharedUi/.../ui/player/PlayerLyrics.kt`
- `sharedUi/.../ui/player/NowPlayingScreen.kt`
- `sharedUi/src/commonMain/composeResources/values/strings.xml`
- `sharedUi/src/commonMain/composeResources/values-ko/strings.xml`
- `app/.../MainActivity.kt`
- `app/.../ui/MainViewModel.kt`
- `app/.../ui/components/AccountAlerts.kt`
- `app/.../ui/components/AudioPipelineDialog.kt`
- `app/.../ui/components/SpotifyImportAlert.kt`
- `app/.../ui/screens/EqualizerScreen.kt`
- `app/.../ui/screens/HistoryScreen.kt`
- `app/.../ui/screens/ListenTogetherScreen.kt`
- `app/.../ui/screens/SettingsSheet.kt`
- `app/.../ui/screens/SourcesScreen.kt`
- `app/.../ui/screens/SpotifyLibraryScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ko/strings.xml`
- 새 테스트 4개 (위 경로)

## 지키지 않은 것 / 남긴 위험

- 기존 안전 규칙·상한·주석 의도는 삭제하거나 약화하지 않았다. `PlayerConnection`의
  포그라운드 게이트, `Downloads`의 Wi-Fi/디스크 확인, `NerdStats`의 측정 기준,
  `PlayerModeControls`의 반복/셔플 판정은 그대로다.
- 다운로드 파일을 다시 쓰거나 통째로 메모리에 읽는 경로를 추가하지 않았다.
- `.github/`, keys, 서명 설정은 건드리지 않았다.
- 남은 HIGH/MEDIUM은 위 A·B·C이며, 모두 화면 밖 상태 흐름(패스 수명, 재생 서비스 상태,
  `openDetail` 분리)을 먼저 만들어야 해서 이번 파일 범위에서 안전하게 고칠 수 없었다.

## 2차 검토 (지적 9건 처리)

기준: 1차 커밋 `8bb6c37` 위, 2026-10-30. 지적 번호는 1차 VERDICT 순서.

| # | 심각도 | 처리 | 내용 |
|---|---|---|---|
| 1 | MEDIUM | **고침** | `published()`가 빈 페이지를 `Error(no_results)`가 아니라 `Success(emptyList())`로 낸다. 이제 `SearchScreen`의 `search_no_results` 빈 상태와 다시 시도가 실제로 도달한다. |
| 2 | MEDIUM | **고침** | `loadHistory()`가 빈 기록을 `Success(emptyList())`로 낸다. `historyHasNoSongs()`가 살아나 `history_empty`가 그려진다. |
| 3 | LOW | **고침** | Spotify의 빈 재생목록 분기를 제거했다. `SpotifyLibrary.playlists()`가 항상 Liked Songs를 먼저 넣으므로 이 화면은 실제로 비지 않는다. |
| 4 | MEDIUM | **고침 + 일부 반박** | `retrySearch()`가 마지막 요청의 `anonymous` 값을 재사용한다. 다만 "계정 유튜브 기록에 남는다"는 영향은 코드상 성립하지 않는다: `Innertube.postMusic`이 모든 search 엔드포인트에 `check(anonymous)`를 걸고(`Innertube.kt:1723-1726`), `search()`는 `anonymous` 인자를 요청에 전달하지 않는다. `InnertubeSearchPrivacyTest`가 이 계약을 고정한다. 플래그 재사용은 원 요청과 캐시 조기반환 동작을 맞추기 위한 것이다. |
| 5 | LOW | **고침** | `runAddonSearch(q, record = false)` 경로를 추가해 재시도가 `SearchHistory.recordAddonQuery()`로 목록 순서를 바꾸지 않는다. |
| 6 | MEDIUM | **고침** | 로컬 음악 스캔에 `localMusicLoadJob`을 두고 이전 스캔을 취소한다. 성공 목록은 유지하되 오류 상태는 `Loading`으로 바꿔 낡은 오류/재시도 버튼이 새 요청 위에 남지 않는다. |
| 7 | MEDIUM | **고침** | 새 17개 키를 `values`/`values-ko` 외 지원 16개 로케일(es, fr, de, pl, pt, id, hi, ja, ru, zh, zh-rTW, he, it, tr, th, vi)에 추가했다. `LocaleStringsTest`의 누락 목록에서 이 17개가 사라졌다. (기존 224개 누락은 이번 변경 전부터 있던 별개 부채다.) |
| 8 | LOW | **고침** | Spotify 로더가 `CancellationException`을 다시 던져, 쿠키/재시도로 취소된 이펙트가 새 로드의 `error`/`loading`을 덮지 않는다. |
| 9 | LOW | **고침** | tour 산술을 바로잡았다. 상세 실패는 3장씩이므로 7장은 상세 2개(3+3) + 단일 shot 1장(1) 조합이며, skip 로그가 없어 정확한 단계는 확정 불가라고 명시했다. |

### 조건 변경 전후 진리표

`published()`의 빈 결과:

| rows | 1차 | 2차 |
|---|---|---|
| 비어 있음 | `Error(no_results)` (화면은 빈 상태 대신 오류+재시도) | `Success(emptyList())` (화면은 `search_no_results`+재시도) |
| 1개 이상 | `Success(rows)` | `Success(rows)` |

로컬 음악 재스캔 시작 시 표시:

| 현재 상태 | 1차 | 2차 |
|---|---|---|
| `Success(rows)` | 그대로 | 그대로 (목록 유지) |
| `Loading` | 그대로 | `Loading` |
| `Error(...)` | 그대로 (낡은 오류+재시도 노출) | `Loading` |

### 이번에 바꾼 파일

- `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt`
- `app/src/main/java/com/music/bitchord/ui/screens/SpotifyLibraryScreen.kt`
- `app/src/main/res/values-{de,es,fr,he,hi,id,it,ja,pl,pt,ru,th,tr,vi,zh,zh-rTW}/strings.xml`
- `app/src/test/java/com/music/bitchord/ui/screens/HistoryEmptyStateTest.kt` (파이프라인 케이스 추가)
- `app/src/test/java/com/music/bitchord/ui/SearchEmptyPipelineTest.kt` (신규)
- `app/src/test/java/com/music/bitchord/ui/LocalMusicLoadStateTest.kt` (신규)
- `docs/review-1031/screens-ui-state.md`

### 검증 (2차)

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.ui.SearchEmptyPipelineTest' --tests 'com.music.bitchord.ui.screens.HistoryEmptyStateTest' --tests 'com.music.bitchord.ui.UiTranslationCoverageTest' --console=plain -q` → 통과.
- `./gradlew :sharedUi:jvmTest --tests 'com.music.bitchord.ui.screens.SearchEmptyStateTest' --console=plain -q` → 통과.
- `LocaleStringsTest`는 여전히 16개 로케일에서 실패하지만 누락 키는 기존 224개뿐이며, 이번에 추가한 17개는 더 이상 목록에 없다.
