# BitChord 코드 구조 정리·최적화 계획 (문서 전용)

- 기준: 2026-10-26, 현재 워크스페이스 HEAD.
- 범위: `app`, `shared`, `sharedUi`, `tv`의 Kotlin 소스. 테스트·빌드 산출물·폰트·fixture·바이너리는 제외.
- 방법: `git ls-files` + `wc -l`, `git grep`, `grep`으로 정적 확인. `rg`는 이 환경에 없어 쓰지 않았다. 프로파일러·기기 측정은 하지 않았고, 빈도는 코드 상수와 호출 경로에서 확인되는 범위만 적었다.
- 이 문서는 계획만 담는다. **코드·조건·문구·설정·저장 형식은 하나도 바꾸지 않았다.**
- 안전 규칙은 이름이 언급되지 않는 한 삭제·완화하지 않는다. 특히 폰 스피커 PCM16 캡(`app/src/main/java/com/music/bitchord/playback/AudioOutputPolicy.kt:20-23`, `app/src/main/java/com/music/bitchord/playback/audio/OutputNegotiator.kt:111-116`, `:207-210`)은 그대로 둔다.

---

## 1. 가장 큰 소스 파일 상위 15

테스트를 제외한 Kotlin 소스 기준이다. `파일:줄`은 해당 클래스/객체/함수가 시작하는 줄이다.

| # | 파일 | 줄 수 | 책임 목록 | 첫 분리 후보 |
|---|---|---:|---|---|
| 1 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:348` | 11,731 | `MediaLibraryService`/`MediaSession` 수명 · 두 ExoPlayer와 crossfade handoff · 오디오 출력/이펙트/라우팅 · 큐 저장·복원·bounded context · HQ 업그레이드·버전 스왑·audition · 가사 자막·스킵 세그먼트 · 재생 실패 복구·소스 fallback · Android Auto browse tree · scrobble/Discord/ListenBrainz/party · 진행 샘플러·nerd stats | Android Auto browse tree(순수 매핑) + 하단 라벨 함수 |
| 2 | `app/src/main/java/com/music/bitchord/MainActivity.kt:618` | 6,427 | Activity 수명·딥링크 소비 · `BitChordApp` 루트 내비게이션/탭/플레이어 시트/다이얼로그 호스트 · Frosted 메뉴 · 화면 전환 | 탭별 `HomeTab`/`ExploreTab`/`LibraryTab`/`SearchTab` |
| 3 | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt:690` | 3,980 | 전체 화면 플레이어 · 배경/아트워크/캔버스 · 세로/가로 레이아웃 · 가사/큐 패널 · 스와이프/드래그 제스처 · 다이얼로그 | 배경·아트워크 레이어(`479-688`) |
| 4 | `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:120` | 3,842 | 홈/탐색/보관함/검색/상세/계정 상태 · 로드 파이프라인·캐시 · 재생 컨텍스트·좋아요 상태 · 로컬/원격 상세 | 홈·탐색 피드 |
| 5 | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:171` | 3,749 | 모든 설정 `StateFlow` · 기본값·마이그레이션 · SharedPreferences 읽기/쓰기 · 백업 키 직렬화 | enum·기본값·순수 정책(`49-170`, `3738-3749`) |
| 6 | `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:195` | 3,550 | 설정 화면 전체 · 섹션 그룹 13개 · 검색 필터 · 품질/다운로드/성능 시트 · 공용 행 컴포넌트 | 섹션별 group composable |
| 7 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:112` | 3,533 | 재생 전환 상태 머신 · 분석/계획/arming/fade/handoff · 필터·보컬·베이스 ride · 속도/비트 정렬 · sleep fade/bail | 순수 계획·fade 계산 함수 |
| 8 | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt:1825` | 3,486 | 가사 시계/playhead · 스트립 · 가사 패널 리스트 · 번역/로마자 토글 · 현재 줄·스와이프 애니메이션 · 번역 상태 | 가사 패널(`1825-2698`) |
| 9 | `app/src/main/java/com/music/bitchord/download/Downloads.kt:116` | 2,821 | 다운로드 DB/파일 저장 · 메타데이터·컬렉션 · MV 사운드 suspect/복구 · 다운로드 큐·전송 · 배지 | 데이터 모델·배지(`2612-2821`) |
| 10 | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:83` | 2,812 | Media3 `SimpleCache` · 키/eviction/purge · 다음 곡 prefetch · whole rendition · 분석 head 대기 · playback/read-ahead factory | 키·eviction·캐시 상태(`491-1266`) |
| 11 | `app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt:298` | 2,660 | 상세 페이지 · 헤더/액션 · 트랙 리스트·정렬/검색 · 앨범/아티스트/섹션 · 배경 | 헤더·액션·섹션 카드 |
| 12 | `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:84` | 2,417 | 가사 타이밍 측정/보정 · AI 검증 · 다운로드 재확인 · 캘리브레이션·진단·저장 | 측정/보정 순수 로직 |
| 13 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:61` | 2,245 | YouTube Music 홈/최근/차트 · 검색/typeahead · 스트림·버전 해석 · 보관함/플레이리스트/계정 · gate fallback | 홈·피드 |
| 14 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/Innertube.kt:87` | 2,179 | Innertube 클라이언트·컨텍스트/세션/인증 · browse/player/search/next 엔드포인트 · 재시도 · playback tracking | 요청 빌더·컨텍스트/헤더 |
| 15 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:70` | 2,008 | 스트림 후보 탐색·서명 · 프록시/InnerTubeX · unplayable 기억 · mid-play 재해석 · 다운로드/probe | 후보 선택·품질 필터 |

### 파일별 책임을 조금 더 풀어 쓰면

- **PlaybackService**: `348-1694` 서비스 수명·세션·검색/Auto 캐시, `1695-4480` 플레이어 구성·세션 명령·재생 등록, `4480-5899` 실패/대안/재시작, `5899-7291` HQ·버전·audition, `7292-8940` 출력/이펙트/equalizer/loudness, `8941-9416` scrobble/Discord/ListenBrainz, `9417-9870` 가사·스킵 세그먼트, `9871-10600` 세션 명령·Auto 라이브러리, `10601-11350` Android Auto browse tree.
- **MainActivity**: `372-617` Activity/인텐트/딥링크, `618-6274` 거대 루트 composable, `6279-6400` Frosted 메뉴, `6401-6427` 문자열/목록 helper.
- **NowPlayingScreen**: `398-688` 도킹·가로 배치 기하, `690-3938` 플레이어 본문. 세로/가로 분기(`1780`, `1921`)와 가사/큐 패널(`2222`, `3589`, `3729`)이 한 함수 안에 있다.
- **MainViewModel**: `120-518` 상태·생성·검색 파이프라인, `824-1375` 플레이리스트/보관함, `1383-1520` init·탭 진입, `1604-2036` 홈/탐색, `2037-2196` 보관함/히스토리, `2197-2600` 검색, `2709-3408` 상세, `3442-3842` 계정/세션.
- **AppSettings**: `49-170` enum/정책, `171-1100` 설정 상태, `1110-1600` init/마이그레이션/읽기, `1606-3350` setter, `3350-3749` 백업/직렬화·기본값.
- **SettingsSheet**: `195-2610` 한 화면. 섹션 시작은 `601`, `638`, `710`, `803`, `1095`, `1585`, `1641`, `1687`, `1759`, `1835`, `1954`, `1978`. `2612-3550`은 시트·행·검색 helper.
- **CrossfadeController**: `112-259` 콜백/설정, `260-1453` 상태·tick·계획, `1454-2347` begin/fade/handoff/sleep/bail, `2347-2900` 필터 ride, `2900-3533` 속도/비트 정렬.
- **PlayerLyrics**: `679-811` playhead/clock, `812-1485` sweep/draw, `1485-1719` 토글/상태, `1825-2698` 가사 패널, `2698-3486` 현재 줄·번역/로마자.
- **Downloads**: `116-2611` 저장소·스캔·전송·컬렉션, `2612-2821` 메타데이터/배지/모델.
- **AudioCache**: `491-1266` 키/캐시 상태/eviction, `1267-1721` playback·read-ahead factory/prefetch, `1722-1930` whole rendition, `1931-2540` 분석 head/rendition, `2541-2812` 데이터소스·fetch.
- **DetailScreen**: `298-1026` 본문, `1027-1630` 헤더/배경, `1631-2094` 액션/통계, `2095-2660` 섹션·행·helper.
- **LyricsTiming**: `84-801` 측정/보정, `802-1213` 비디오/AI 준비, `1214-1740` AI 검증, `1741-2417` 다운로드 재확인·캘리브레이션·저장.
- **YtMusicRepository**: `107-587` 홈/최근/차트/무드, `588-1023` 검색/스트림/버전, `1024-1348` 공식 영상/계정/보관함/라디오, `1349-1800` 상세/플레이리스트, `1801-2245` 좋아요/플레이리스트 편집/artist/gate.
- **Innertube**: `234-671` visitor/session/channel/컨텍스트, `701-1136` browse/player/search, `1137-1407` tracking/통계, `1408-1882` 인증·쓰기 API, `1883-2179` 요청 직렬화·헤더·서명.
- **StreamResolver**: `273-487` resolve·unplayable 기억, `488-1045` 프록시/InnerTubeX/직접/업로드, `1046-1450` 헤더·재해석·다운로드, `1451-2008` 품질 선택·서명·probe·URL 설명.

---

## 2. 동작 변경 없이 “옮기기만” 하는 분리 순서

원칙: 아래 단계는 **코드 위치만 바꾼다.** 조건·상수·호출 순서·스레드·타이머를 바꾸지 않는다. 각 단계 뒤 `:app:compileProdReleaseKotlin`과 해당 단위 테스트를 먼저 통과시킨 뒤 다음 단계로 간다.

### 0단계 — 순수 함수/상수 (위험 낮음, 먼저)

| 순서 | 추출 단위 | 원본 | 이동 대상 | 예상 효과 | 위험 |
|---:|---|---|---|---|---|
| 0-1 | Android Auto browse tree 매핑 | `PlaybackService.kt:11131-11350` | `playback/auto/AutoBrowseTree.kt` | 서비스에서 ~220줄 제거, 서비스 수명과 무관 | 낮음. `MediaItem`/`ShelfItem` 변환만 옮기고 캐시 필드는 콜백으로 |
| 0-2 | 라벨/이유 문자열 | `PlaybackService.kt:11704-11730` | `playback/PlaybackLabels.kt` | 서비스 하단 정리 | 낮음 |
| 0-3 | 상세 목록 정렬/검색/헤더 helper | `DetailScreen.kt:1441-1485`, `:2369-2398`, `:2598-2660` | `ui/screens/detail/DetailListOps.kt` | 화면 파일에서 순수 로직 분리 | 낮음 |
| 0-4 | 가사 텍스트/시간 helper | `PlayerLyrics.kt:162-190`, `:466-502`, `:575-584`, `:1596-1630`, `:2864-2945` | `ui/player/LyricsText.kt` | 패널 파일 축소, 단위 테스트 쉬움 | 낮음 |
| 0-5 | 다운로드 메타데이터/배지/모델 | `Downloads.kt:2612-2821` | `download/DownloadMetadata.kt` | 저장소와 표시 로직 분리 | 낮음 |
| 0-6 | 설정 enum/기본값/순수 정책 | `AppSettings.kt:49-170`, `:3738-3749` | `data/settings/SettingsDefaults.kt` | 설정 파일의 비상태 부분 분리 | 낮음 |

### 1단계 — Compose 섹션 (위험 낮음~중간)

| 순서 | 추출 단위 | 원본 | 이동 대상 | 예상 효과 | 위험 |
|---:|---|---|---|---|---|
| 1-1 | 설정 섹션 13개 | `SettingsSheet.kt:601`, `:638`, `:710`, `:803`, `:1095`, `:1585`, `:1641`, `:1687`, `:1759`, `:1835`, `:1954`, `:1978` | `ui/screens/settings/*Section.kt` | 루트 recomposition 범위 축소, 섹션별 `collect` 이동 가능 | 낮음. `SettingsSearch`/`SettingsGroupScope` 계약 유지 |
| 1-2 | 상세 헤더/액션/섹션 카드 | `DetailScreen.kt:1027-2094`, `:2095-2660` | `ui/screens/detail/*.kt` | 2,660줄 파일을 화면 단위로 분리 | 낮음 |
| 1-3 | 플레이어 배경/아트워크 | `NowPlayingScreen.kt:398-688`, `:2300-2380` | `ui/player/PlayerBackground.kt` | 캔버스 프레임 갱신이 플레이어 본문을 덜 건드림 | 중간. `DockFrame`/측정 좌표 공유 |
| 1-4 | 탭 콘텐츠 | `MainActivity.kt:3773-4131` | `ui/tabs/HomeTab.kt`, `ExploreTab.kt`, `LibraryTab.kt`, `SearchTab.kt` | 루트 4,656줄 composable의 재실행 범위 축소 | 중간~높음. 87개 `collect`를 각 탭으로 내려야 함 |
| 1-5 | 가사 패널/현재 줄 | `PlayerLyrics.kt:1825-2698`, `:2948-3143` | `ui/player/LyricsPanel.kt`, `CurrentLyricLine.kt` | 가사 시계와 렌더링 분리 | 중간. 스크롤/포커스 상태 공유 |
| 1-6 | 플레이어 세로/가로 레이아웃 | `NowPlayingScreen.kt:1921-2250`, `:2300-3760` | `LandscapePlayer.kt`, `PortraitPlayer.kt` | 3,248줄 본문 분리 | 중간~높음. `playerBounds`/`dockFrame`/시트 모션 공유 |

### 2단계 — 상태 있는 도메인 (위험 높음, 마지막)

| 순서 | 추출 단위 | 원본 | 이동 대상 | 예상 효과 | 위험 |
|---:|---|---|---|---|---|
| 2-1 | Auto/세션 browse 캐시 | `PlaybackService.kt:383-643`, `:10600-11350` | `playback/auto/AutoBrowseCache.kt` | 서비스에서 캐시·변환 책임 분리 | 높음. `MediaSession` 콜백 수명 |
| 2-2 | 큐 저장/복원/bounded context | `PlaybackService.kt:3038-3440`, `:7598-7760` | `playback/QueuePersistenceCoordinator.kt` | 저장 경로와 재생 상태 머신 분리 | 높음. 저장 순서·`progressWork` 단일 스레드 보존 |
| 2-3 | HQ/버전 스왑/audition | `PlaybackService.kt:2446-3037`, `:5899-7020` | `playback/quality/QualityUpgradeCoordinator.kt` | 서비스에서 품질 정책 분리 | 높음. 플레이어 교체·핸드오프와 얽힘 |
| 2-4 | 가사/스킵/번역 ahead | `PlaybackService.kt:9417-9870` | `playback/LyricsSubtitleController.kt` | 서비스에서 자막 ticker 분리 | 중간. `lyricMetadataWanted()`와 저전력/화면 조건 유지 |
| 2-5 | 출력/이펙트/라우팅 | `PlaybackService.kt:7292-8940` | `playback/audio/OutputCoordinator.kt` | 오디오 정책 분리 | 높음. PCM16 캡·Samsung 예외 보존 |
| 2-6 | scrobble/Discord/party | `PlaybackService.kt:8941-9416` | `playback/presence/PresenceCoordinator.kt` | 부가 기능 분리 | 중간. 네트워크/소켓 수명 |
| 2-7 | `MainViewModel` 기능별 컨트롤러 | `MainViewModel.kt:824-3842` | `ui/home/HomeController.kt`, `SearchController.kt`, `LibraryController.kt`, `DetailController.kt`, `AccountController.kt` | 3,842줄 VM 분해, 기능별 테스트 | 중간~높음. 공유 캐시/세션/generation |
| 2-8 | `AudioCache` 키/캐시/prefetch/분석 head | `AudioCache.kt:491-2812` | `playback/cache/CacheStore.kt`, `PrefetchController.kt`, `AnalysisHeadGate.kt` | 캐시 책임 분리 | 높음. Media3 `CacheDataSource` 계약·키 일치 |
| 2-9 | `CrossfadeController` 계획/렌더/필터/속도 | `CrossfadeController.kt:260-3533` | `playback/crossfade/TransitionPlanner.kt`, `FadeRenderer.kt`, `FilterRide.kt`, `SpeedAligner.kt` | 실시간 상태 머신 분해 | 높음. ticker/`tickerWake`/handoff 순서 |
| 2-10 | `Downloads` 저장소/전송/컬렉션 | `Downloads.kt:116-2821` | `download/DownloadStore.kt`, `DownloadTransfer.kt`, `DownloadCollections.kt` | 2,821줄 객체 분해 | 높음. DB 스키마·마이그레이션 |
| 2-11 | `YtMusicRepository` 도메인 | `YtMusicRepository.kt:61-2245` | `data/home/HomeRepository.kt`, `SearchRepository.kt`, `LibraryRepository.kt`, `PlaylistRepository.kt` | API 오케스트레이션 분리 | 중간. gate/세션 공유 |
| 2-12 | `Innertube`/`StreamResolver` 요청·서명 | `Innertube.kt:87-2179`, `StreamResolver.kt:70-2008` | `innertube/InnertubeClient.kt`, `StreamCandidateResolver.kt` | 네트워크/서명 경로 분리 | 높음. 인증·프록시·서명 실패 경로 |

**하지 말 것**: 위 단계에서 조건을 “정리”하지 않는다. 특히 `lowPower`, `metered`, `heavyWorkAllowed`, `isInteractive`, `inParty`, 화면 꺼짐 조건은 그대로 복사한다. 조건을 합치거나 빼야 할 이유가 생기면 별도 작업으로 분리하고 기존/새 진리표를 모두 적는다.

---

## 3. 죽은 코드 후보 상위 30

확인 방법: 각 이름을 `git grep -n -w '<이름>' -- '*.kt'`로 검색해 **선언 1곳 외 호출 0**인 것만 넣었다. 테스트에서만 쓰는 함수는 여기서 제외했다(아래 “테스트 전용” 참고). `git grep` 결과가 1인 것은 선언만 있다는 뜻이다.

| # | 함수 | 파일:줄 | git grep | 내용 | 예상 효과 | 위험 |
|---:|---|---|---|---|---|---|
| 1 | `LyricsSidePanel` | `sharedUi/.../ui/player/PlayerSidePanels.kt:38` | 1(선언만) | 플레이어 밖 가사 사이드 패널 | `PlayerSidePanels.kt` 220줄 중 약 120줄 제거 후보 | 낮음. 호출·테스트 0 |
| 2 | `QueueSidePanel` | `sharedUi/.../ui/player/PlayerSidePanels.kt:158` | 1(선언만) | 플레이어 밖 큐 사이드 패널 | 같은 파일 나머지 제거 후보 | 낮음 |
| 3 | `drawPlainBackdrop` | `app/.../ui/components/backdrop/DrawBackdropModifier.kt:56` | 1(선언만) | vendored backdrop API | 미사용 vendored 표면 축소 | 중간. vendored 원본과의 차이 |
| 4 | `asComposeShader` | `app/.../ui/components/backdrop/RuntimeShader.kt:25` | 1(선언만) | vendored shader 변환 | 미사용 helper 제거 | 중간. vendored |
| 5 | `rememberCombinedBackdrop` 3개 overload | `app/.../ui/components/backdrop/backdrops/CombinedBackdrop.kt:22`, `:32`, `:43` | 3(선언만) | backdrop 조합 | `CombinedBackdrop.kt` 전체 미사용 후보 | 중간. vendored |
| 6 | `runtimeShaderEffect` | `app/.../ui/components/backdrop/effects/RenderEffect.kt:29` | 1(선언만) | vendored shader effect | 미사용 effect helper 제거 | 중간. vendored |
| 7 | `isRpcRunning` | `shared/.../com/my/kizzy/rpc/KizzyRPC.kt:49` | 1(선언만) | Discord RPC 연결 상태 | 미사용 API 제거 | 낮음 |
| 8 | `toRpcImage` | `shared/.../com/my/kizzy/utils/Ext.kt:17` | 1(선언만) | Discord 이미지 변환 | 미사용 확장 제거 | 낮음 |
| 9 | `isFullyCached` | `app/.../playback/AudioCache.kt:2394` | 1(선언만) | 연속 캐시 완료 판정 | 미사용 판정 제거. 주석의 “구멍” 설명은 `cachedPrefixBytes`로 이동 | 낮음. 테스트 0 |
| 10 | `setHqPrefetchLimit` | `app/.../data/settings/AppSettings.kt:1677` | 1(선언만) | HQ prefetch 한도 setter | setter만 제거, `hqPrefetchLimit` 프로퍼티는 유지 | 낮음 |
| 11 | `avoidIdentityShuffleMediaItems` | `app/.../playback/QueueShuffle.kt:301` | 1(선언만) | `MediaItem`용 identity shuffle 회피 | 중복 제거(사용 중인 `Int` 버전 유지) | 낮음 |
| 12 | `isAnalysed` | `app/.../playback/smart/TrackAnalyzer.kt:281` | 1(선언만) | 분석 결과 존재 여부 | 미사용 진단 API 제거 | 낮음 |
| 13 | `canJoin` | `app/.../data/listentogether/ListenTogether.kt:816` | 1(선언만) | 파티 참가 가능 여부 | 미사용 API 제거 | 낮음 |
| 14 | `speedUpBetween` | `shared/.../playback/smart/TransitionPolicy.kt:85` | 1(선언만) | BPM 속도 정렬 | 미사용 정책 함수 제거 | 낮음. 테스트 0 |
| 15 | `systemTag` | `shared/.../data/LocaleTags.kt:47` | 1(선언만) | 시스템 로케일 태그 | 미사용 helper 제거 | 낮음 |
| 16 | `sameLanguageScriptAware` | `shared/.../data/LocaleTags.kt:99` | 1(선언만) | 스크립트 구분 언어 비교 | 미사용 helper 제거 | 낮음 |
| 17 | `recentShelf` | `shared/.../data/YouTubeFallback.kt:544` | 1(선언만) | 로컬 최근 재생 shelf | 미사용 fallback API 제거 | 낮음 |
| 18 | `coverFor` | `shared/.../data/cover/YouTubeCoverResolver.kt:127` | 1(선언만) | 커버 조회 shortcut | 미사용 shortcut 제거 | 낮음 |
| 19 | `fromSnapshot` | `shared/.../playback/QueueWindow.kt:249` | 1(선언만) | `QueueWindowSnapshot` 복원 | 미사용 복원 API 제거 | 중간. 큐 스냅샷 경로 |
| 20 | `upcomingCount` | `shared/.../playback/QueueWindow.kt:366` | 1(선언만) | 예정 항목 수 | 미사용 API 제거 | 낮음 |
| 21 | `nextAutomatic` | `shared/.../playback/QueueWindow.kt:396` | 1(선언만) | 자동 다음 alias | alias 제거(`advanceAutomatically` 유지) | 낮음 |
| 22 | `localizeCardSubtitle` | `sharedUi/.../ui/screens/HomeScreen.kt:880` | 1(선언만) | 카드 부제 번역 | 미사용 composable 제거 | 낮음 |
| 23 | `TvDialogList` | `tv/.../ui/tv/components/TvComponents.kt:469` | 1(선언만) | TV 다이얼로그 목록 | 미사용 composable 제거 | 낮음 |
| 24 | `hasTouchScreen` | `tv/.../ui/tv/DeviceType.kt:36` | 1(선언만) | 터치스크린 여부 | 미사용 device helper 제거 | 낮음 |
| 25 | `isLowRam` | `tv/.../ui/tv/DeviceType.kt:44` | 1(선언만) | 저RAM 여부 | 미사용 helper 제거 | 낮음 |
| 26 | `hasMicrophone` | `tv/.../ui/tv/DeviceType.kt:52` | 1(선언만) | 마이크 여부 | 미사용 helper 제거 | 낮음 |
| 27 | `getPalette` | `tv/.../ui/tv/personalization/PersonalizationModels.kt:55` | 1(선언만) | TV 테마 팔레트 | 미사용 팔레트 제거 | 낮음 |
| 28 | `deletePreviousGrapheme` / `moveCursorLeft` / `moveCursorRight` | `tv/.../PersonalizationModels.kt:139`, `:152`, `:163` | 각 1(선언만) | TV 자모 편집 | 미사용 편집 helper 제거 | 낮음 |
| 29 | `appleTween` | `tv/.../ui/tv/theme/TvMotion.kt:57` | 1(선언만) | TV tween spec | 미사용 spec 제거 | 낮음 |
| 30 | TV 설정 setter 9개: `setTvFontFamily`, `setTvLiquidGlassEnabled`, `setTvBlurIntensity`, `setTvVideoAuto1080p`, `setSoundCheckEnabled`, `setAddPlaylistSongsToLibrary`, `setAddFavoriteSongsToLibrary`, `setTvNickname`, `setTvNavLayout` | `tv/.../data/settings/TvSettings.kt:66`, `:77`, `:82`, `:93`, `:103`, `:108`, `:113`, `:134`, `:144` | 각 1(선언만) | TV 설정 setter | 미사용 setter 제거. UI 연결은 동작 변경이라 별도 작업 | 낮음 |

**테스트 전용으로 남긴 것**: `computeHealthResolution`, `materializedSongs`, `activeNames`/`activeCount`, `hasAnsweredFor`, `parseSkipSegments`, `tailAfterIncoming`, `isFakeHighQuality`, `QueueTimeline`의 `shuffled*`/`sectionedOrder`, `TvPinThrottle.getFailedAttempts` 등은 프로덕션 호출은 0이지만 테스트가 참조한다. 삭제하려면 해당 테스트도 같이 정리해야 하므로 이번 30개에서는 제외했다.

---

## 4. 메인 스레드 성능 후보 상위 10

정적 후보이며 프로파일러 수치는 아니다. “확신”은 코드만으로 어디까지 말할 수 있는지다.

| # | 파일:줄 | 무엇이 의심되는가 | 근거 | 예상 효과 | 위험/확신 |
|---:|---|---|---|---|---|
| 1 | `app/.../MainActivity.kt:618-6274` | `BitChordApp` 루트 하나가 4,656줄·재시작 스코프. 피드/검색/플레이어 상태가 바뀔 때마다 루트 본문과 인자 평가가 다시 돈다 | `collectAsStateWithLifecycle` 87개, `LaunchedEffect` 58개 | 탭/시트별 composable로 내리면 홈 피드·검색·다운로드 변경이 다른 탭을 재실행하지 않음 | 중간. 상태 호이스팅 필요, 높은 확신 |
| 2 | `app/.../ui/screens/SettingsSheet.kt:195-2610` | 설정 화면 하나가 94개 설정을 직접 collect. 한 스위치 변경이 13개 섹션 전체를 재실행 | `collectAsStateWithLifecycle` 94개 | 섹션별 collect로 이동하면 토글 시 해당 섹션만 갱신 | 낮음, 높은 확신 |
| 3 | `sharedUi/.../ui/player/NowPlayingScreen.kt:690-3938` | 3,248줄 플레이어 본문이 배경/가로/세로/가사/큐/제스처를 모두 포함. 17개 collect가 같은 스코프 | `collectAsStateWithLifecycle` 17개, 분기 `1780`/`1921` | 배경·가로·세로·패널 분리로 상태 변경 전파 축소 | 중간. 측정 좌표/도킹 상태 공유, 높은 확신 |
| 4 | `app/.../ui/player/CanvasArtworkPlayer.kt:351-370`, `:545-590` | 캔버스가 보이는 동안 3초마다 `TextureView.getBitmap()`으로 GPU→비트맵 동기 readback | `MESH_REFRESH_MS=3000`(`NowPlayingScreen.kt:293`), `getBitmap()` | readback 주기/크기 축소 또는 백그라운드 복사 경로로 옮기면 캔버스 곡의 주기적 hitch 감소 | 중간. 화면 품질/배경 색 정확도, 중간 확신 |
| 5 | `sharedUi/.../ui/components/LongPressOrigin.kt:96-159` | 길게 누를 때 `GraphicsLayer`를 최대 1024px로 기록하고 `toImageBitmap()`을 메인 스코프에서 실행 | `MAX_CAPTURE_PX=1024f`(`:163`), `toImageBitmap()`(`:146`) | 캡처 크기 축소/재사용/백그라운드화로 long-press jank 감소 | 낮음. 메뉴 미리보기 품질, 중간 확신 |
| 6 | `app/.../BitChordApplication.kt:85-290` | `onCreate`에서 `AppSettings.init`/`readAll`과 여러 store 초기화가 동기로 실행 | `AppSettings.kt:1122-1151`, `:1158`의 전체 설정 읽기 | 첫 프레임 전 디스크/Keystore 작업을 더 줄이면 콜드 스타트 개선 | 높음. 첫 재생/설정 기본값 준비 순서, 높은 확신 |
| 7 | `app/.../ui/MainViewModel.kt:1383-1400` | `init`에서 검색 파이프라인 3개를 시작하고 `loadHome()`을 즉시 호출. 홈은 첫 화면이라 필요하지만 나머지는 경쟁 | `startSearchPipeline`, `startSuggestPipeline`, `startTypeaheadMediaPipeline`, `loadHome()` | 검색 파이프라인을 첫 검색 입력까지 지연하면 시작 시 코루틴/네트워크 경쟁 감소 | 낮음. 검색 첫 응답 지연, 중간 확신 |
| 8 | `app/.../playback/PlaybackService.kt:7972-8040`, `:7482-7567`, `:8044-8103` | 재생 중 5초 샘플러가 메인 스코프에서 `publishNerdStats()`/`checkOutputHealth()`를 호출 | `scope=Dispatchers.Main`(`:846`), `PROGRESS_SAMPLE_MS=5_000`(`:11395`) | 통계 스냅샷/출력 건강 검사를 progress executor로 옮기면 5초마다 메인 작업 감소 | 중간. 출력 복구 타이밍, 중간 확신 |
| 9 | `sharedUi/.../ui/player/PlayerLyrics.kt:2246-2450` | 활성 줄 주변 행마다 `.blur()` RenderEffect 레이어를 만들고, 스크롤/줄 전환 때 다시 블러 | `:2303`, `:2449`, `LINE_FALLOFF_BLUR` | 중간 단계 이하 블러 제거 또는 스크롤 중 0dp면 GPU 레이어/재블러 감소 | 낮음. 가사 분위기, 중간 확신 |
| 10 | `app/.../playback/PartyPersonalQueueStash.kt:67-80`, `app/.../playback/BoundedContextPersistence.kt:99-105` | 파티 진입/라디오 큐 확정 시 `SharedPreferences.commit()` 동기 쓰기가 메인 경로에서 실행 | `PartySync.kt:928` → `stashFromPlayer`, `PlaybackService.kt:7640`/`:10554` → `saveImmediately` | `apply`/전용 writer로 옮기면 파티 진입·큐 확정 시 main 디스크 대기 제거 | 중간. 종료 직후 복원 보장, 중간 확신 |

**이미 고쳐진 것으로 확인해 이 계획에서 제외한 것**: 캔버스 프레임을 작은 composable로 분리한 것(`NowPlayingScreen.kt:471-525`, `:925`), 가사 줄 인덱스(`LyricLineIndex`, `PlayerLyrics.kt:1892-1929`), 가사 자막을 줄 경계 `delay`로 바꾼 것(`LyricSubtitleScheduler.kt:34-44`, `PlaybackService.kt:9677-9700`), 재생 샘플러의 이벤트 기반 `samplerPlaying`(`PlaybackService.kt:7972-7984`), 메시 그라디언트의 `continuous=isPlaying`(`HomeScreen.kt:1147`, `:1190`), 마퀴의 foreground/저전력 게이트(`PlayerControls.kt:1458`, `:1594`).

---

## 5. 배터리: 기존 감사와 중복하지 않는 범위

`docs/audit/audit-battery.md`가 이미 있으므로 같은 항목을 다시 조사·기재하지 않는다. 그 문서가 다룬 것:

- AutoMix 저전력 분석 중지(`TrackAnalyzer.kt:117`), 다음 곡 바이트 prefetch 저전력 중지(`AudioCache.kt:1598`, `:1607`), MV 소리 복구 `APPEND_OR_REPLACE`, 아티스트 백필 1.5초 네트워크, Crossfade ticker, 분석 head 폴링, DownloadService 100ms 폴링, 디코더 경쟁, 영상 가사 250ms 확인, Presence 5분, 다운로드 알림 250ms, 잠금화면 1분 갱신.

이 계획에서 배터리 관련해서 **새로 추가하는 것**은 리팩터링이 기존 가드를 깨지 않게 하는 불변식과, 기존 감사에 없던 동기 commit 후보 하나뿐이다.

| 불변식 | 지켜야 할 파일:줄 | 이유 |
|---|---|---|
| AutoMix는 저전력/파티에서 새 디코드를 시작하지 않는다 | `TrackAnalyzer.kt:117`, `:334` | 기존 H1 회귀. 분리해도 `BatteryPolicy.automixAnalysisActive`를 유일한 정의로 유지 |
| 다음 곡 바이트는 비과금·비저전력에서만 쓴다. 10초 지연 뒤 다시 읽는다 | `AudioCache.kt:1598-1612` | 기존 H2 회귀. prefetch를 별 클래스로 옮겨도 쓰기 직전 재확인 |
| MV 소리 복구는 진행 중 작업을 취소하지 않는다 | `MvSoundRedownload.kt`의 `APPEND_OR_REPLACE` | 기존 H3 회귀 |
| Crossfade는 새 타이머를 만들지 않고 `tickerWake`/timeout으로만 깬다 | `CrossfadeController.kt:317-332`, `:824-840` | 기존 ticker 지적. 분리 시 polling으로 바꾸지 않는다 |
| 재생 샘플러는 `samplerPlaying` 이벤트로만 돌고 새 주기 타이머를 만들지 않는다 | `PlaybackService.kt:7972-7984` | 배터리 원칙: 이벤트 기반 |
| 다운로드 worker는 큐 wake-up을 쓰고 100ms polling을 되살리지 않는다 | `DownloadService.kt:113-127`, `:278-291` | 기존 M 항목 |
| 폰 스피커 PCM16 캡과 Samsung 예외를 유지한다 | `AudioOutputPolicy.kt:20-23`, `OutputNegotiator.kt:111-116`, `:207-210` | 실제 실패를 설명하는 안전 규칙 |
| 미디어 파일을 메모리에 통째로 올리거나 다운로드 파일을 제자리에서 다시 쓰지 않는다 | `AudioCache.kt`, `Downloads.kt`의 스트리밍 경로 | 기존 안전 규칙. 분리 중에도 유지 |

**기존 감사에 없던 후보**: `PartyPersonalQueueStash.kt:67-80`의 `commit()`과 `BoundedContextPersistence.kt:99-105`의 `commit()`이 메인 경로에서 불린다(성능 표 #10). 배터리 폴링은 아니지만 화면이 꺼진 파티 진입/큐 확정에서도 디스크를 기다리게 할 수 있으므로, 옮길 때 `progressWork`/전용 writer로 보내고 복원 보장은 유지한다.

---

## 6. 실행 순서와 검증

1. **0단계** 순수 함수 이동 → `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q`.
2. **1단계** Compose 섹션 이동 → 같은 컴파일 + 관련 화면 스모크. recomposition 개선은 `MainActivity`/`SettingsSheet`/`NowPlayingScreen` 순.
3. **2단계** 상태 있는 도메인은 한 번에 하나씩. 각 단계마다 기존 테스트를 먼저 돌린다.
   - `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.BatteryPolicyTest' --console=plain -q`
   - `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.LyricLineIndexTest' --console=plain -q`
   - `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.QueueWindow*' --console=plain -q`
   - `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.MvSoundRedownloadPolicyTest' --console=plain -q`
4. 조건을 바꾸는 작업이 생기면 이 문서와 별도로 기존/새 진리표를 남긴다. 이 계획 자체는 조건 변경을 제안하지 않는다.
5. 파일 이동만 하는 커밋과 동작을 바꾸는 커밋을 섞지 않는다.

---

## 7. 10줄 요약

1. 상위 15개 파일이 전체 소스의 큰 부분을 차지하며, `PlaybackService`(11,731줄)와 `MainActivity`(6,427줄)가 최우선 분해 대상이다.
2. 가장 안전한 시작은 `PlaybackService` 하단 라벨/Auto browse tree, `DetailScreen`·`PlayerLyrics`·`Downloads`의 순수 helper를 옮기는 0단계다.
3. 다음은 `SettingsSheet` 섹션, `DetailScreen` 헤더/섹션, `NowPlayingScreen` 배경, `MainActivity` 탭을 분리해 recomposition 범위를 줄이는 1단계다.
4. 상태 있는 `PlaybackService`/`AudioCache`/`CrossfadeController`/`Downloads` 분해는 저장 순서·ticker·Media3 계약 때문에 마지막 2단계로 미룬다.
5. 죽은 코드 후보 30개를 `git grep` 선언 1곳 기준으로 확인했고, `PlayerSidePanels.kt` 전체와 TV helper·설정 setter가 가장 안전한 삭제 후보다.
6. vendored backdrop helper는 호출 0이지만 upstream 재적용 비용이 있어 삭제 보류가 합리적이다.
7. 성능 1순위는 루트/설정/플레이어의 거대한 Compose 스코프이며, 캔버스 `getBitmap`·long-press 캡처·메인 동기 commit은 별도 후속 후보다.
8. 배터리는 `docs/audit/audit-battery.md`와 중복하지 않고, 리팩터링 시 저전력/이벤트 기반 가드를 보존하는 불변식만 추가했다.
9. 이 계획은 코드·조건·문구·저장 형식을 바꾸지 않으며, 모든 단계는 “옮기기만” 한 뒤 컴파일과 기존 테스트로 확인한다.
10. 다음 작업은 0단계 추출부터 시작하고, 각 단계의 diff가 순수 이동인지 먼저 검토한다.
