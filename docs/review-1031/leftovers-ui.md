# UI 영역 HIGH·MEDIUM 잔여 지적 정리 (1031)

기준: `task/fix-1030` 통합판. 지적별로 현재 코드를 확인하고, 같은 문제의 중복 지적은 한 행으로 합쳤다.

| 지적 | 판정 | 근거 | 처리 |
|---|---|---|---|
| [776][757][632][648] AI 타이밍 버튼이 키/언어/오디오 준비와 무관하게 노출되거나, 부분 스트림에서 영구히 숨겨짐 | 이미 고쳐짐 | `LyricsController.kt:687`의 `aiTimingAvailable`이 `LyricsAiGuard.manualTimingAvailable`로 Gemini/Deepgram·언어·오디오를 함께 판정. `:774-789`가 `AudioCache.renditionCompleted` 구독으로 캐시 완료 시 다시 계산. `LyricsTimingUi.kt:60`은 `adjustable && (byAi || aiAvailable)` | 변경 없음 |
| [673] 다운로드 곡의 WebDAV 업로드 행이 `isLocalFile`로 숨겨짐 | 이미 고쳐짐 | `SongActionsSheet.kt:805`, `MainActivity.kt:5154` 모두 `WebDavUploads.isUploadable(song)` 사용, 서버 설정도 확인 | 변경 없음 |
| [405] 장르 콜백이 검색 소스를 YouTube로 바꾸지 않고 이전 `selectedMoodGenre`를 남김 | 이미 고쳐짐 | `MainActivity.kt:3341-3349`에서 `navigator.clear()`, `closeMoodGenre()`, `setSearchSource(YOUTUBE)`, `searchFor(genre)` 순서로 처리 | 변경 없음 |
| [609] 일시적 실패에서도 HQ 검증 쌍을 버려 재시도가 기존 저음질 파일을 채택 | 이미 고쳐짐 | `Downloads.kt:2009`가 `keepVerifiedPair = e.automaticFailure().transient` 전달, `:2732-2737`이 일시 실패에만 쌍 유지 | 변경 없음 |
| [369] 가사 일시 미스 후 패널 재개방 시 재조회 불가 | 이미 고쳐짐 | `LyricsController.kt:450` `retryLyricsIfMissed`, `MainActivity.kt:3109` `onLyricsOpen` 연결. `NowPlayingScreen.kt:1063`에서 패널 열 때 호출 | 변경 없음 |
| [334] 잠금화면/알림/차량 타이밍 경로에 대체 스트림 가드 없음 | 이미 고쳐짐 | `PlaybackService.kt:10931` `substituted`, `:10934/:10946` `streamTimingAllowed(... substituted=...)` | 변경 없음 |
| [324] 8초 재측정 루프와 250ms `awaitVideoOpening` 폴링 | 이미 고쳐짐 | `LyricsController.kt:942-957`은 `AudioCache.served` 이벤트 대기, `LyricsTiming.kt:1096-1121`은 `onSubscription`+`served`/`awaitRenditionPrefix` 이벤트 대기 | 변경 없음 |
| [325] 일부 스킵 경로에 `lyrics-sync:` 이유 로그 없음 | 여전함 → 고침 | `LyricsTiming.kt:1634/1670`(song-check no entry), `:1676`(429 defer), `:2592/2596`(calibration skip)에 이유 로그 추가 | `LyricsTiming.kt` |
| [401][421] 빈 검색 결과가 Error로 가서 빈 상태 도달 불가, 빈 캐시가 재시도를 무력화 | 이미 고쳐짐 | `MainViewModel.kt:4100` `searchPageState`가 빈 페이지도 `Success`, `:4089-4093` `reuseCachedSearchPage`가 `forceNetwork`면 캐시 우회, `:2389` 재시도가 `forceNetwork=true` | 변경 없음 |
| [388] 빈 히스토리가 Error라 `historyHasNoSongs`가 죽은 분기 | 이미 고쳐짐 | `MainViewModel.kt:4104` `historyPageState`가 빈 목록을 `Success`로 반환, `HistoryScreen.kt:78` 분기 도달 | 변경 없음 |
| [353] 재시도가 항상 `anonymous=false`로 계정 검색 기록에 남김 | 이미 고쳐짐 | `MainViewModel.kt:370` `lastSearchAnonymous`, `:2554` 요청 시 저장, `:2389` 재시도에 재사용 | 변경 없음 |
| [317] Library 재시도가 Loading/Job 없이 중복 스캔 시작 | 이미 고쳐짐 | `MainViewModel.kt:2472-2501` 이전 `localMusicLoadJob` 취소, `localMusicLoadState`로 Loading 전환 후 단일 Job 실행 | 변경 없음 |
| [354][305] 신규 문자열이 `values`/`values-ko`에만 존재 | 여전함 → 고침 | `app/src/main/res/values-*/strings.xml` 18개 로케일에 누락 키 추가(영문 기본값, 기존 폴백과 동일 문구). `settings_footer_*` 포함. `LocaleStringsTest` 통과 | 로케일 strings.xml 전체 |
| [438] 재생성 시 원래 런치 인텐트를 다시 소비 | 이미 고쳐짐 | `LaunchReplay.kt:34-47`이 서비스한 모든 지문을 저장/비교, `MainActivity.kt:468-478` 저장된 집합 사용 | 변경 없음 |
| [405-2] 재생성된 액티비티의 새 위젯 인텐트가 `recreating = savedInstanceState != null`로 억제됨 | 이미 고쳐짐 | `MainActivity.kt:474` `recreating = replayingLaunch` (LaunchReplay 판정 결과) 사용 | 변경 없음 |
| [643] 모든 위젯 인텐트가 빈 지문으로 충돌 | 여전함 → 고침 | `LaunchReplay.kt:48-61` 지문에 `PlayerDeepLink.EXTRA_OPEN_PLAYER` 포함, `:73` 순수 `fingerprintOf`로 분리. `MainActivity.kt:602-607` `onNewIntent`는 소비 전에 지문 계산. 회귀 테스트 `LaunchReplayTest` 추가 | `LaunchReplay.kt`, `MainActivity.kt`, `LaunchReplayTest.kt` |
| [413] `giveUpAt`을 화면 대기 후 재확인하지 않음 | 이미 고쳐짐 | `LyricsController.kt:910-936` 화면 켜짐 대기 뒤 `lyricsMeasurementAllowed(...giveUpAt...)` 재확인 | 변경 없음 |
| [383] 로컬/다운로드 영상 재측정이 `AudioCache.served`를 못 받아 15분 대기 | 이미 고쳐짐 | `LyricsController.kt:942` `measureWaitsForStream(localUri)`가 로컬 파일이면 즉시 AI 폴백으로 진행, 스트림만 이벤트 대기 | 변경 없음 |
| [446] `collect` 직렬화로 새 검색이 이전 타임아웃을 기다림 | 이미 고쳐짐 | `MainViewModel.kt:2547-2560` `SearchRequestPipeline.submit`으로 독립 실행, `isCurrent()`로 폐기 요청 차단 | 변경 없음 |
| [513] 타이핑 중 요소 0을 버려 결과 영역이 빈 스페이서가 됨 | 이미 고쳐짐 | `SearchScreen.kt:748/766` `suggestionDisplayRows`가 요소 0을 질의 액션 행으로 렌더 | 변경 없음 |
| [399] 비어 있지 않은 `sections`를 무시해 영상 전용 아티스트가 Error | 이미 고쳐짐 | `DetailTarget.kt:39-48` `hasShelves`, `MainViewModel.kt:3087/3131` `hasShelves = page.sections.isNotEmpty()` 전달 | 변경 없음 |
| [455] 첫 응답 Error 시 continuation의 숨김 영상이 버려짐 | 이미 고쳐짐 | `MainViewModel.kt:4125-4139` `mergeDetailContinuation`이 Error/empty를 seed로 허용하고 hidden/suggested로 승격 | 변경 없음 |
| [423] 명시적 ARTIST 힌트보다 접두사 추론이 우선 | 이미 고쳐짐 | `MainViewModel.kt:2935-2936` `detailTypeFor`가 `hint == ARTIST`를 우선, `:2952`에서 사용 | 변경 없음 |
| [392] TV `tv_up_next`가 “다음 재생”으로 남음 | 여전함 → 고침 | `tv/src/main/res/values-ko/strings.xml:222`를 “다음 항목”으로 변경, `KoreanStringsTest`에 단언 추가 | tv strings, KoreanStringsTest |
| [384] 애드온 전부 실패를 빈 결과로 표시 | 여전함 → 고침 | `MainViewModel.kt:2399-2438` 응답을 `Result`로 보존, 전부 실패면 `UiState.Error(connection_failed)`·부분 실패는 로그 | `MainViewModel.kt` |
| [314] 다음 페이지 실패가 조용히 사라짐 | 여전함 → 고침 | `MainViewModel.kt:243-244` `searchLoadMoreFailed`, `:2680-2686` 실패 시 토큰 유지·상태 노출, `SearchScreen.kt:524-528` 끝 행 재시도 UI, `MainActivity.kt:3980-3983` 연결 | `MainViewModel.kt`, `SearchScreen.kt`, `MainActivity.kt` |
| [514] `showArtwork=false`인데 타일마다 커버 네트워크 요청 | 여전함 → 고침 | `ExploreScreen.kt:227-242`에서 `LaunchedEffect`/`onVisible` 제거, 호출부·`MainActivity.kt` 배선 제거 | `ExploreScreen.kt`, `SearchScreen.kt`, `MainActivity.kt` |
| [224] 고급 옵션 하단에 기술 용어 “HQ” 노출 | 여전함 → 고침 | `values-ko/strings.xml:1047` “저장된 고음질 오프셋…”으로 변경, `KoreanStringsTest` 단언 추가 | values-ko, KoreanStringsTest |
| [296] VLLM Liked Music 로그인 필요 상태가 일반 메시지로 빠짐 | 여전함 → 고침 | `DetailScreen.kt:434-444` `likedMusicPage`를 smart-empty/sign-in 조건에 포함 | `DetailScreen.kt` |
| [482] `rememberLoadingTimedOut`이 요청 동일성 없이 불리언만 키로 사용 | 여전함 → 고침 | `Common.kt:1069-1080` `requestKey` 추가, History/Detail/Search/Home/Explore/Library에서 요청 키·재시도 카운터 전달 | `Common.kt` 및 6개 화면 |
| [468] 모든 첫 페이지가 빈 성공인데 Home이 Loading에 머묾 | 여전함 → 고침 | `MainViewModel.kt:1942-1951` 마지막 대기 shelf가 끝나고 아직 Loading이면 `Success(emptyList())` 게시 | `MainViewModel.kt` |
| [400] “Show stats for nerds” 토글이 폰 플레이어에서 무효 | 여전함 → 고침 | `PlayerControls.kt:387-388` `host.settings.showNerdStats`가 켜지면 탭 없이도 표시 | `PlayerControls.kt` |
| [375] 설정 하단이 압축되며 토글 행의 주의 문구가 사라짐 | 여전함 → 고침 | `SettingsSheet.kt:740-812` MV 재다운로드·HQ 교체·HQ 미리 저장(상한 포함)·재검사·좋아요 자동 다운로드 행에 기존 subtitle 표시 | `SettingsSheet.kt` |
| [440][332] ui-tour가 재생 중 Settings 화면/새 설정 스크립트를 실행하지 않음 | 여전함(보호 경로) | `.github/ui-tour/tour.sh`는 저장소 규칙상 수정 금지 | 남김: `.github/` 보호. 필요한 변경은 `tour.sh`의 `section_now_playing`에 재생 중 Settings 단계 추가, `ui-tour.yml`/tour에서 `scripts/ui-tour-settings.sh` 호출 |

## 테스트 결과
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.startup.LaunchReplayTest' --tests 'com.music.bitchord.ui.KoreanStringsTest' --tests 'com.music.bitchord.LocaleStringsTest'` 통과
