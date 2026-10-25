# PlaybackService 스파게티 해소 계획 (2/3: 프리페치 분리)

작업 기준: `task/proxy-fallback` HEAD (ko-101 이후 모든 변경 포함, 이전 대기열 작업 결과 포함).
로컬 Gradle 빌드는 하지 않았고 컴파일·테스트는 CI(`ko-wip-check`)가 수행한다.
사용자 기능·동작·설정·저장 데이터 형식은 바꾸지 않았다. 이번 단계는 **동작 변경 없는
코드 이동**만 한다.

이 문서는 1/3(노래 버전 분리) 기록을 그대로 남기고, 이어서 2/3(프리페치 분리)를
추가한다. 3/3은 아래 분리 계획의 남은 후보 중에서 고른다.

## 1/3 단계에서 한 일

`PlaybackService.kt`(1만 줄 이상)에서 노래 버전 결정 계열 private 함수·상태를
`app/src/main/java/com/music/bitchord/playback/SongVersionController.kt`로 옮겼다.
`PlaybackService`에는 위임 프로퍼티와 얇은 위임 함수만 남겼다.

옮긴 상태:

- `songVersionJob`, `songVersionJobFor` (다음 곡 버전 미리 해석)
- `beforeAudioVersionJob` (첫 소리 전 버전 조회)
- `lateVersionMeasureJob` (첫 소리 예산을 넘긴 늦은 버전 측정)
- `versionHoldWantsPlay`, `versionHoldHeld` (첫 소리 홀드)
- `streamingSongFileLoggedFor`, `mvSoundFileLoggedFor` (한 곡 한 번 로그)

옮긴 함수:

- `scheduleUpcomingSongVersions`, `watchForLateVersion`
- `resolveSongVersionBeforeAudio`, `installPreResolvedVersion`,
  `installStreamedRecording`, `installSongVersionBeforeAudio`
- `downloadedSongVersionFor`, `streamLongDownloadedSong`
- `logStreamingSongFile`, `logMvSoundFile`
- `beginVersionHold`, `resumeVersionHold`, `releaseVersionHold`

`PlaybackService`에 남긴 위임:

| 이름 | 이유 |
|---|---|
| `scheduleUpcomingSongVersions` | 첫 소리·전환·큐 편집·autoplay 등 7개 호출부 |
| `resolveSongVersionBeforeAudio` | 미디어 아이템 전환 콜백 |
| `streamLongDownloadedSong` | `onPlaybackStateChanged`의 다운로드 길이 판정 |
| `isResolvingBeforeAudio` (`SongVersionController`) | 위 콜백의 기존 `beforeAudioVersionJob?.isActive != true` 조건 |

의존성은 콜백 5개로만 연결했다: 현재 player, 현재 audible id, `swappingMediaId` 설정,
버전 설치 후 `loadLyricsForCurrentTrack()` + `loadSkipSegmentsForCurrentTrack()` 실행,
그리고 `Context`(MV 사운드 재다운로드 예약용). 로그 문구·로그 시점·pause/play 타이밍·
`SongVersionPolicy` 판단 순서는 그대로다. 원래 `MvSoundRedownload` import는
`PlaybackService`에서 제거했고 컨트롤러가 갖는다.

### 확인한 호출부

- `PlaybackService.kt:1420` — 첫 소리 전 조회 중이면 다운로드 길이 판정을 건너뛰는 조건.
  `!isResolvingBeforeAudio`로 동일한 의미를 유지했다(잡이 없거나 끝났으면 true).
- `PlaybackService.kt:1197, 3168, 3262, 3295, 3609, 4142-4143, 4212` — 미리 해석·전환·큐 편집.
- `SongVersionController` 내부에서만 쓰이는 함수는 서비스에 위임을 만들지 않았다.

### 검사 범위

ko-101..HEAD에서 새로 들어온 코드를 먼저 읽었다. 이번 이동과 직접 닿는 경로는
노래 버전(00-songinfo, 00b-title-normalize, 01-dlmv, 06-dlmv-duration, 09-midstream-403),
가사 제목 정규화·싱크 채점, 크로스페이드·자동 믹스, 다운로드 곡 메뉴·사진,
고음질 소스·등급·다음 곡 준비, 설정 공유, 커버 가수 이동, 충전 조건 제거다.
그중 이번에 옮긴 함수들이 참조하는 `SongVersionPolicy`, `Downloads`,
`YtMusicRepository`, `LyricsTiming`, `AudioCache` 경로만 호출부와 타입을 다시 확인했고,
동작·로그·타이밍은 바꾸지 않았다. 나머지 기능은 아래 분리 후보의 대상으로 남긴다.

## 2/3 단계에서 한 일

`PlaybackService.kt`에서 미리 받기·프리페치 계열 상태와 함수를
`app/src/main/java/com/music/bitchord/playback/PrefetchController.kt`로 옮겼다.
`PlaybackService`에는 lazy 위임 프로퍼티와 `prefetchAround`/`cancelPrefetch` 얇은
위임만 남겼다.

옮긴 상태:

- `preferredPrefetchJob`, `preferredPrefetchRequest` (다음 곡 rendition 선택)
- `alternatePreResolveJob`, `alternatePreResolveFor` (다음 곡 대체 버전 미리 해석)

옮긴 함수:

- `prefetchAround` (큐 앞 `AudioCache.prefetchQueue`, `AudioCache.noteSongs`,
  `SongVersionPolicy.aheadSongVersion` 분기)
- `preResolveAlternateAhead` (한 곡 앞 definitive refusal → alternate 교체)
- `cancelPrefetch`

`PlaybackService`에 남긴 위임:

| 이름 | 이유 |
|---|---|
| `prefetchAround` | 재생 시작·전환·설정 변경 등 4개 호출부 |
| `cancelPrefetch` | 재생 상태 콜백의 else, 설정 변경, `onDestroy` 3개 호출부 |

의존성은 생성자 인자 5개로만 연결했다: `scope`, 현재 player, 대체 버전 조회
타임아웃, `permanentReason` 판정, 그리고 `MediaItem`/`Song`을 대체 버전으로 바꾸는
`withAlternateRecording`. `preResolveAlternateAhead`의 IO 이후 메인 스레드 재확인은
기존 `this@PlaybackService.player` 대신 `currentPlayer()`로 같은 시점의 player를
읽는다. 로그 문구·로그 시점·`AudioCache.cancel()`/`prefetchQueue` 호출 순서·
`SongVersionPolicy` 판단 순서는 그대로다.

`scheduleForeignCachePurge`(재생을 지난 foreign 캐시 정리)와 HQ 업그레이드의
`AudioCache.warmRange` 호출은 프리페치 응집도가 아니므로 이번 단계에서 옮기지 않았다.

### 확인한 호출부

- `PlaybackService.kt:1197` — `onIsPlayingChanged`에서 재생/일시정지에 따라 프리페치/취소.
- `PlaybackService.kt:1523` — 재생 상태 변경 콜백의 프리페치.
- `PlaybackService.kt:4302` — 전환 직후 첫 소리/이미 들리는 행에서 프리페치.
- `PlaybackService.kt:8148-8149` — `preferMusicOnly` 변경 시 취소 후 재프리페치.
- `PlaybackService.kt:8724` — 서비스 종료 시 취소.
- `preResolveAlternateAhead`는 `prefetchAround` 내부에서만 호출되므로 서비스에
  위임을 만들지 않았다.

### 검사 범위

ko-101..HEAD에서 새로 들어온 코드 중 이번 이동과 닿는 경로를 다시 읽었다:
`AudioCache.prefetchQueue`/`Upcoming`/`noteSongs`/`cancel`, `TrackMatcher.targetOf`,
`SongVersionPolicy.aheadSongVersion`, `YtMusicRepository.songVersion`/
`cachedSongVersionFor`/`isSongVersionMissing`/`findAlternateRecording`,
`StreamResolver`, `SourceRegistry`, `TrackLog`, `AppSettings.preferMusicOnly`.
동작·로그·타이밍은 바꾸지 않았다. 나머지 기능은 아래 분리 후보의 대상으로 남긴다.

## 분리 계획 전체

`PlaybackService`는 이미 `CrossfadeController`, `QueueCoordinator`, `AudioCache`,
`SourceResolver`, `QualityUpgrade` 등으로 일부가 빠져 있다. ko-101 이후 생긴
`HqTier`/`HqMatchPolicy`/`HqReferenceCache`/`LyricsQueryVariants`/`SongSyncRetry`와
이전 대기열 작업의 `QueueCoordinator`/`BoundedQueuePlanner`/`BoundedContextPersistence`는
이미 순수 파일로 분리되어 있으므로 그대로 둔다. 남은 책임을 다음 순서로 떼어낸다.
각 단계는 **동작·로그·타이밍 불변**을 원칙으로 하고, 한 단계에서 한 응집도만 옮긴다.

| 단계 | 후보 파일 | 옮길 책임 | 대략 위치 | 위험도 |
|---|---|---|---|---|
| 1 (완료) | `SongVersionController.kt` | MV→노래 전환, 첫 소리 홀드, 늦은 버전, 다운로드 길이 판정 | `PlaybackService` 3385-4026 (이동 전) | 낮음 |
| 2 | `VersionSwapController.kt` | 영화/릴리스 수동 버전 스왑, 정렬 측정, standby 채택 | `smoothSwapCurrentTrackVersion`, `prepareVersionAlignment`, `adoptPlayerForVersionSwap`, `measureVersionAlignmentAsync` | 중간 |
| 3 (완료) | `PrefetchController.kt` | 다음 곡 준비·프리페치·캐시 워밍·미리 받기 | `prefetchAround`, `preResolveAlternateAhead`, `cancelPrefetch`, `AudioCache.prefetchQueue` 호출부 | 중간 |
| 4 | `TransitionController.kt` | 크로스페이드·자동 믹스 arming/handoff, 전환 필터·loudness 연결 | `crossfade`, `onHandoff`, `adoptPlayer`, `CrossfadeArmPolicy` 호출부 | 높음 |
| 5 | `ProxyPlaybackController.kt` | 프록시 재생·스트림 선택·중간 403·소스 폴백 | `SourceResolver`/`StreamChoice`/`ProxyAwareDataSource` 연동부 | 높음 |
| 6 | `QueueController.kt` | 큐 편집·bounded context·autoplay append·history trim | `QueueCoordinator` 밖에 남은 큐 변이·`scheduleBoundedContextTopUp` | 중간 |
| 7 | `HqUpgradeController.kt` | 고음질 업그레이드·오디션·등급 로그·다음 곡 준비 연동 | `QualityUpgrade`, `HqAudioVerify`, `currentTierFor`, `logHqTier` | 중간 |
| 8 | `AudioOutputController.kt` | 출력 경로 전환·리샘플·무음/정지 복구 | `checkOutputHealth`, `recoverAudibleGains`, `rebuildPlayersForOutput`, `resampleTargetRate` | 중간 |
| 9 | `LyricsController.kt` | 가사 로드·번역 ahead·싱크 채점·스킵 세그먼트 | `loadLyricsForCurrentTrack`, `loadSkipSegmentsForCurrentTrack`, `schedule*Translation*` | 낮음 |
| 10 | `CastController.kt` | 캐스트/원격 스피커 연결 | `CastController` 연동부 | 중간 |

### 권장 순서와 이유

1. **VersionSwapController**: 이번 `SongVersionController`와 같은 "버전" 응집도라
   상태·콜백 형태를 재사용할 수 있고, `swappingMediaId`·standby player 규칙을 한곳에 모은다.
2. **PrefetchController (완료)**: 배터리 원칙(주기 폴링 금지, 이벤트 기반)과 직접 닿는다.
   다음 곡 준비가 전환·버전·HQ 경로와 얽혀 있어, 전환/버전 분리 후가 안전하다.
3. **TransitionController**: 가장 위험하다. 크로스페이드 handoff는 `player`/`standby`,
   필터 역할 교대, `audibleMediaId`, HQ 업그레이드가 동시에 걸린다. 앞 단계들이
   콜백을 정리한 뒤에 한다.
4. **ProxyPlaybackController**: 소스 해석·폴백은 실패 복구와 403 재시도가 섞여 있어
   네트워크 경로 테스트가 필요하다.
5. **QueueController**: `QueueCoordinator`와 `BoundedQueuePlanner`가 이미 순수 로직을
   갖고 있으므로, 남은 변이 호출만 옮기면 된다. 이전 대기열 작업 결과를 여기서 흡수한다.
6. **Hq/AudioOutput/Lyrics/Cast**: 각각 독립적이라 마지막에 병렬로 진행할 수 있다.

### 공통 제약

- 사용자에게 보이는 동작·설정·저장 형식 불변. 로그 문구와 타이밍도 불변.
- 배터리 우선: 새 주기 폴링·타이머를 만들지 않는다. 기존 이벤트(전환, 첫 소리,
  큐 편집, 업그레이드 완료)에만 붙인다.
- 널 안전: `Song?`/`MediaItem?` 스마트캐스트가 콜백 경계를 넘지 않게 지역 변수로 고정한다.
  과거 `Song?` 스마트캐스트 컴파일 오류를 피하기 위해 `item`/`song`을 각각 검사한다.
- 새 순수 로직은 `shared`의 `com.music.bitchord.data...` 테스트로 검증한다.
  이번 단계는 순수 로직 추가가 없어 새 테스트는 만들지 않았다(CI 컴파일로 확인).
- `.github/`, `keys/`, 서명 설정, `.ds-ctx/`는 건드리지 않는다.

## 1/3 단계의 검증

- 옮기기 전/후 문자열 리터럴 27개가 동일함을 확인했다(로그 문구 불변).
- 옮긴 본문을 정규화해 비교했고, 의도한 치환(`player`→`currentPlayer()`,
  `audibleMediaId`→`currentAudibleMediaId()`, `swappingMediaId = x`→`setSwappingMediaId(x)`,
  설치 후 두 콜백→`onVersionInstalled()`) 외 로직 차이는 없다.
- `git diff --check` 통과. 로컬 빌드 금지로 컴파일은 CI에 맡긴다.

## 2/3 단계의 검증

- `prefetchAround`/`preResolveAlternateAhead`/`cancelPrefetch`의 로그 문자열과
  `AudioCache.cancel()`·`prefetchQueue` 호출 순서가 이동 전과 동일함을 확인했다.
- 의도한 치환은 `this@PlaybackService.player`→`currentPlayer()`,
  `MediaItem.withAlternateRecording`→`swapToAlternate` 콜백,
  `ALTERNATE_LOOKUP_TIMEOUT_MS`→생성자 인자뿐이다.
- 순수 로직 추가가 없어 새 `shared` 테스트는 만들지 않았다(CI 컴파일로 확인).
- `git diff --check` 통과. 로컬 빌드 금지로 컴파일은 CI에 맡긴다.

## 3/3 단계에서 한 일 (MainViewModel 가사 분리)

파일 이름은 `12-ui3`이지만 내용은 원래 `11 코드 정리 c` 작업이다. 이번에는
`PlaybackService`가 아니라 `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt`의
가사 로딩·타이밍을 `app/src/main/java/com/music/bitchord/ui/LyricsController.kt`로
옮겼다. `MainViewModel`에는 상태 노출 프로퍼티와 얇은 위임 함수만 남겼다.

옮긴 상태:

- `_lyrics`/`lyrics`, `_lyricsSource`/`lyricsSource`, `_lyricsChecked`/`lyricsChecked`,
  `_lyricsProviderStates`/`lyricsProviderStates`, `_lyricsTiming`/`lyricsTiming`
- `rawLyricsResult`, `lyricsRaceOrder`, `lyricsTimingJob`, `aiTimingJob`,
  `songCheckStartedFor`, `lyricsJob`, `manualLyricsJobs`, `lyricsProviderResults`
- `LyricsRequest`, `currentLyricsRequest`, `lyricsGeneration`, `selectedLyricsSource`,
  `fileLyricsLabel`, `lyricsFor`
- `timingLines`, `timingBaseAllowed`, `timingMeasureAllowed`, `appliedTiming`,
  `earlyMeasureJob`
- 상수 `PATIENCE_MS`, `MAX_MEASURE_ATTEMPTS`, `REMEASURE_CHECK_MS`, `MEASURE_WINDOW_MS`

옮긴 함수:

- `loadLyrics`(파일 가사 우선 처리, 네트워크 조회, duration 재시도 포함)
- `selectLyricsProvider`, `fetchLyricsProvider`
- `providerStarted`, `providerFinished`, `providerCancelled`, `showFirstUsable`,
  `showLyricsResult`, `publishLyrics`
- `startTiming`(노래 검증 분기·AI ahead·측정 루프 포함), `startEarlyMeasure`,
  `refreshTiming`(SyncTestMode 보고 포함)
- `aiAhead`, `autoAiIfNeeded`, `runAiTiming`, `toggleAiLyricsTiming`,
  `setSongLyricsOffset`
- `init`의 `LyricsRepository.upgrades` 수집기(배경에서 더 나은 가사가 오면 교체)

`MainViewModel`에 남긴 위임:

| 이름 | 이유 |
|---|---|
| `lyrics`, `lyricsSource`, `lyricsChecked`, `lyricsProviderStates`, `lyricsTiming` | `MainActivity`/TV 플레이어가 그대로 구독 |
| `loadLyrics` | `MainActivity.kt:1259`, `TvNowPlayingScreen.kt:107` 호출부 유지 |
| `selectLyricsProvider` | `MainActivity.kt:3030` |
| `toggleAiLyricsTiming` | `MainActivity.kt:3036` |
| `setSongLyricsOffset` | `MainActivity.kt:3037` |

의존성은 생성자 인자 2개로만 연결했다: `Application`과 `viewModelScope`.
컨트롤러가 ViewModel을 다시 참조하지 않으므로 상태 소유권이 한 방향이다.
의도한 치환은 `viewModelScope`→`scope`, `getApplication()`/
`getApplication<Application>()`→`app`, 그리고 완전 수식 타입을 import로 바꾼 것뿐이다.
기존 KDoc·주석은 옮긴 본문에 그대로 두고, ViewModel의 위임에는 짧은 안내만 남겼다.
로그 문구·로그 시점·세대(`lyricsGeneration`) 검사·provider 경쟁 순서·AI/측정
타이밍은 그대로다.

### 확인한 호출부

- `MainActivity.kt:978-985` — 가사 상태 5개 구독. 이름·타입 동일.
- `MainActivity.kt:1259-1275` — `loadLyrics`의 모든 인자(커버 제목 정규화,
  `artistAliases` 포함)가 위임 시그니처와 일치.
- `MainActivity.kt:3028-3037` — provider 선택·AI 타이밍·수동 오프셋 콜백.
- `TvNowPlayingScreen.kt:107` — TV 플레이어의 `loadLyrics` 호출.
- `MainViewModel.init` — 가사 업그레이드 수집기를 컨트롤러 `init`으로 이동.
  시작 시점은 ViewModel 초기화 시점으로 사실상 동일하다.

### 검사 범위

ko-101..HEAD에서 새로 들어온 코드를 먼저 확인했다. 이번 이동과 직접 닿는
`artistAliases`(커버 가수 이동), `SyncTestMode.report`(싱크 채점 모드),
`shown` 변수 수정(화면에 표시된 가사 기준 진단), `LyricsQueryVariants`,
`SongSyncRetry`, `CoverTitles`, `LyricsTiming`/`HqAudioVerify` 경로를 다시 읽었다.
`LyricsRepository`·`EmbeddedLyrics`·`FileLyricsOrigin`·`LyricsAiGuard`·
`LyricsTranslation`의 시그니처와 널 처리도 그대로 사용한다. 크로스페이드·자동 믹스,
다운로드 곡 메뉴·사진, 고음질 소스·등급·다음 곡 준비, 설정 공유, 충전 조건 제거,
그리고 이전 대기열 작업은 이번 가사 이동과 호출 경로가 겹치지 않아 건드리지 않았다.

### 3/3 단계의 검증

- 이동 전/후 본문을 정규화해 비교했고, 위 치환 외 로직 차이는 없다.
- 로그 문자열(`lyrics: no sources enabled`, `lyrics-provider`, `lyrics: <source> ...`,
  `cmd: lyrics-offset`)과 호출 순서가 동일함을 확인했다.
- 새 순수 로직 추가가 없어 새 `shared` 테스트는 만들지 않았다(CI 컴파일로 확인).
- `git diff --check` 통과. 로컬 빌드 금지로 컴파일은 CI에 맡긴다.
