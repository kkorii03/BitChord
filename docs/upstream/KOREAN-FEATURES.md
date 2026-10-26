# 한국 포크 전용 기능 지도 (FIXLIST 22)

이 문서는 업스트림(`kushagrasinghx/BitChord`) 합치기를 2주마다 쉽게 하기 위해
한국 포크가 업스트림과 다르게 가진 파일과 삽입 지점을 정리한 것이다. 이번 작업은
문서만 만들며 앱 동작은 바꾸지 않는다.

- 기준 업스트림: tag `v1.8.1` (`7d956f211b8a46a87184fd3ff87432b51a111dfa`)
- 기준 포크 HEAD: `0a5a1d1f` (`task/proxy-fallback`)
- 비교: `git diff v1.8.1...HEAD` (merge-base가 정확히 `v1.8.1`이라 3-dot diff가 유효)
- 줄 번호: 현재 HEAD 파일 기준의 대략 위치다. 합치기 후에는 반드시 `git diff`와
  함수명으로 다시 찾는다.

## 0. 한눈에 보는 수치

| 항목 | 수 |
|---|---:|
| v1.8.1 대비 바뀐 파일 | 547 |
| 포크에서 새로 추가한 파일 | 300 |
| 업스트림 파일을 수정한 파일 | 241 |
| 업스트림에서 삭제한 파일 | 6 |
| 추가 파일 중 운영 Kotlin | 146 |
| 추가 파일 중 테스트 Kotlin | 126 |
| 추가 파일 중 리소스 | 5 |
| 추가 파일 중 CI/문서/스크립트 | 23 |

추가 파일 300개의 구성: 운영 Kotlin 146, 테스트 126, 리소스 5, `.github` 9,
`docs/` 12, `scripts/` 2. 따라서 "한국 전용 파일"은 300개로 보되, 실제 기능
코드는 146개라고 보는 것이 맞다.

## 1. 기능별 지도

표기:
- `전용 파일`: v1.8.1에 없고 포크에만 있는 파일(통째로 우리 것).
- `끼워 넣은 부분`: 업스트림 파일 안에 포크가 추가한 위치. `파일:줄 - 함수` 형식.
- 위험 표시: `[위험]`은 2번 절의 충돌 위험 상위 10개 파일이다.

### 1.1 프록시 / 게이트 폴백

한국 IP에서 YouTube Music Premium이 없을 때 `music.youtube.com`이 막히는 것을
`www.youtube.com`으로 우회하고, 선택한 프록시(예: 일본 노드)로 스트림을 다시
해석하는 기능 묶음이다. 프록시 자체는 "일본 전용 코드"가 아니라 사용자가 설정한
해외 노드를 통과시키는 경로다.

전용 파일:
- `shared/src/jvmSharedMain/.../data/net/`: `ProxyConfig.kt`, `ProxyPlayback.kt`,
  `ProxyRoutePolicy.kt`, `ProxyHealth.kt`, `YouTubeProxy.kt`, `ResolutionRace.kt`,
  `CounterpartSwitch.kt`, `SessionScopedCache.kt`, `SharedLookups.kt`, `LogUrl.kt`
- `shared/src/jvmSharedMain/.../data/innertube/`: `MusicGateFallback.kt`,
  `YouTubeWebParser.kt`, `YouTubeWebSearch.kt`, `DirectClientResolver.kt`,
  `ProxyConnectionTest.kt`, `ResolutionOrder.kt`, `MoodLabels.kt`
- `shared/src/jvmSharedMain/.../data/`: `YouTubeFallback.kt`,
  `YouTubeFallbackFeed.kt`, `HomeFallbackRules.kt`, `YouTubeMusicClassifier.kt`,
  `YouTubeArtistResolver.kt`, `YouTubeCategoryRules.kt`,
  `YouTubeDisplayCleaner.kt`, `StoredVerdictCache.kt`, `CatalogSongMapper.kt`
- `app/src/main/java/.../data/net/`: `ProxyPlaybackStore.kt`, `CounterpartStore.kt`
- `app/src/main/java/.../playback/ProxyAwareDataSource.kt`
- `app/src/main/java/.../ui/screens/ProxySettingsSection.kt`
- `app/src/main/java/.../auth/YouTubeWebLogin.kt`
- `docs/MUSIC_GATE_FALLBACK.md`

끼워 넣은 부분:
- `[위험] shared/.../data/innertube/StreamResolver.kt:480 - resolveUncached`
  (프록시 조기 경쟁/직접 경로 대기), `:753 - resolveViaProxy`,
  `:1128 - refuseMidPlay`, `:1163 - reResolve`, `:1394 - proxyDownloadStream`,
  `:1643 - onPlaybackRefused`, `:1538 - probe(viaProxy)`
- `[위험] shared/.../data/innertube/Innertube.kt:769 - browseYouTube`,
  `:775 - browseYouTubeContinuation`, `:805 - browseYouTubeAnonymous`,
  `:889 - playerResponse`, `:900 - watchNext`, `:911 - nextYouTube`,
  `:918 - rateYouTube`, `:981 - accountMenuYouTube`,
  `:1193 - playbackTracking`, `:1250 - youtubePlaybackTracking`,
  `:1309 - pingPlayback`,
  `:1326 - pingWatchtime`, `:758 - browseAnonymousThroughProxyOnly`
- `[위험] shared/.../data/YtMusicRepository.kt:128 - home` 게이트 분기,
  `:170 - homeRecentlyPlayed`, `:2119 - ensureGateKnown`,
  `:2131 - recheckGateNow`, `:2138 - isMusicGateSignal` 계열
- `[위험] app/.../data/settings/AppSettings.kt:872-900 - proxy* 상태`,
  `:1072 - initProxy`, `:2541 - setProxyConfig`, `:2567 - setProxyScope`,
  `:2590 - publishProxy`, `:2574 - readProxy`
- `app/.../BitChordApplication.kt:140 - initProxy`, `:144 - ProxyPlaybackStore.init`
- `app/.../MainActivity.kt:561 - MusicGateFallback.forceForTesting`,
  `:576 - EXTRA_PROXY`, `:1302 - ProxyHealthMonitor` 홈 알림
- `app/.../playback/ChunkedDataSource.kt:207 - canProxy`
- `app/.../data/innertube/AndroidStreamHooks.kt`, `PlaybackTracker.kt`의
  youtube.com 트래킹 분기
- `app/.../ui/screens/AccountAndScrobblingScreen.kt:80 - musicGated` 표시
- `app/.../BitChordApplication.kt`의 `ProxyPlaybackStore`/`CounterpartStore` 초기화

### 1.2 일본 경유 재생

코드상 독립된 "일본" 기능은 없다. 사용자가 설정한 프록시가 일본에 있을 때
`StreamResolver`가 그 경로로 플레이어 요청을 다시 보내고, 그 요청이 발급한
googlevideo URL을 같은 경로로 받는 구조다. 따라서 1.1의 프록시 경로와 파일이
같고, 운영 설정만 다르다.

핵심 지점:
- `shared/.../data/innertube/StreamResolver.kt:572` 주석의 Tokyo 경로,
  `:753 - resolveViaProxy`
- `shared/.../data/net/ProxyPlayback.kt`의 `ProxyPlaybackRoute.PROXY_FIRST`,
  `PROXY_RETRY`, `proxiedUrls`
- `shared/.../data/net/YouTubeProxy.kt`의 `ProxySelector`/`Authenticator`
- `app/.../playback/ProxyAwareDataSource.kt:36 - usesProxyFor`
- `app/.../data/net/ProxyPlaybackStore.kt`의 30일 기억

합칠 때는 "일본"을 따로 찾지 말고 `ProxyPlayback`/`resolveViaProxy`를 기준으로
찾으면 된다.

### 1.3 노래 버전 (뮤직비디오 ↔ 곡 버전)

뮤직비디오로 열린 곡을 같은 곡의 오디오/곡 버전으로 바꾸거나, 재생 전에 곡
버전을 먼저 찾는 기능이다. 커버/원곡 판별과 YouTube Music counterpart도 포함한다.

전용 파일:
- `app/.../playback/SongVersionController.kt`
- `shared/.../data/net/SongVersionPolicy.kt`, `CounterpartSwitch.kt`
- `app/.../data/net/CounterpartStore.kt`
- `app/.../playback/smart/VersionSwapPolicy.kt`, `HqAudioVerify.kt`,
  `HqVerificationStore.kt`, `VideoAudio.kt`, `CoverOriginals.kt`,
  `AnalysisDuration.kt`
- `shared/src/commonMain/.../data/model/SongOrigins.kt`
- `app/.../download/MvSoundRedownload.kt`, `DownloadedQuality.kt`
- `shared/.../data/cover/CoverTitles.kt`, `YouTubeCoverResolver.kt`
  (커버를 곡으로 읽는 부분)
- `app/.../playback/PrefetchController.kt`

끼워 넣은 부분:
- `[위험] app/.../playback/PlaybackService.kt:2485 - smoothSwapCurrentTrackVersion`,
  `:2778 - measureVersionAlignmentAsync`, `:2839 - prepareVersionAlignment`,
  `:2903 - logVersionSwapRefused`, `:2911 - adoptPlayerForVersionSwap`,
  `:2947 - revertCurrentToOriginal`, `:2974 - swapCurrentToVersion`,
  `:3400 - songVersionController`, `:3414 - scheduleUpcomingSongVersions`,
  `:3417 - resolveSongVersionBeforeAudio`, `:3423 - streamLongDownloadedSong`
- `[위험] app/.../MainActivity.kt:1477 - switchToMusicOnly`,
  `:1542/:1786/:1789/:1837/:1858/:1893/:1996`의 버전 판단,
  `:6387 - keepsLocalFile`
- `[위험] shared/.../data/YtMusicRepository.kt:921 - counterpartAudio`,
  `:974 - officialVideo`, `:1040 - songVersion`, `:1101 - resolveSongVersion`,
  `:1232 - officialVideoBySearch`
- `shared/.../data/sources/TrackMatcher.kt:bestForVideoSwitch`, `sameRecording`,
  `isCoverOf`, `sharesArtistChannel`, `sharesRelease`
- `app/.../playback/Autoplay.kt`, `AudioCache.kt`, `SourceResolver.kt`의 버전 후보
  선택 지점

### 1.4 최근 재생 / 시크릿 모드

최근 재생은 기기 기록(`LocalPlayHistory`)을 YouTube 기록과 합쳐 보여 주고,
시크릿 모드(비공개 세션)는 스크로블/디스코드/기록 쓰기를 막는다.

전용 파일:
- `shared/.../data/RecentShelf.kt`
- `app/.../data/LocalPlayHistory.kt`
- `app/.../data/settings/PrivateListening.kt`
- `app/.../data/stats/SmartPlaylists.kt`, `ListeningStatsSeed.kt`,
  `WriteThrottle.kt`
- `app/.../playback/LastPlayed.kt` (수정됨, 큐/재생 상태 저장 확장)

끼워 넣은 부분:
- `[위험] shared/.../data/YtMusicRepository.kt:170 - homeRecentlyPlayed`,
  `:316-322` 최근 재생 병합, `:340 - localRecentShelf`, `:398-404` 기록 병합
- `[위험] app/.../ui/MainViewModel.kt:1409 - LocalPlayHistory.changes`,
  `:1863 - refreshRecentsFromLocal`, `:1907 - logRecentsPublished`,
  `:1912 - isRecentsTitle`
- `[위험] app/.../data/settings/AppSettings.kt:321 - privateListening`,
  `:324 - privateListeningSinceMs`, `:1363 - readPrivateListening`,
  `:1394 - checkPrivateListeningExpiry`, `:1620 - setPrivateListening`
- `app/.../playback/PlaybackService.kt:4308`, `:8927`, `:8993`, `:9005` -
  `PrivateListening.allows(...)` 스크로블/프레즌스 차단
- `app/.../MainActivity.kt:389 - checkPrivateListeningExpiry`,
  `:3771 - onDisablePrivateListening`, `:5049 - onTogglePrivateListening`
- `app/.../ui/components/SongActionsSheet.kt:181/:493` - 플레이어 빠른 시크릿 스위치
- `app/.../ui/screens/SettingsSheet.kt:867 - privateListening` 행

### 1.5 가사 싱크 / 번역 / AI

가사 제공처, 단어 싱크, 크로마 정렬, Deepgram 검증, Gemini 번역/로마자/의미,
영상 가사 타이밍, 잠금화면 가사를 한 묶음으로 본다.

전용 파일:
- `shared/.../data/lyrics/`: `ChromaAlignment.kt`, `Deepgram.kt`,
  `EstimatedSweep.kt`, `FileLyricsOrigin.kt`, `HeardWords.kt`,
  `KoreanLyrics.kt`, `LyricLineIndex.kt`, `LyricsAiGuard.kt`,
  `LyricsDiskCache.kt`, `LyricsFailure.kt`, `LyricsQueryVariants.kt`,
  `LyricsTimeMap.kt`, `LyricsWordAlign.kt`, `OriginalNames.kt`,
  `RecordingPick.kt`, `SongCheckWindow.kt`, `SongSyncRetry.kt`, `SyncDump.kt`
- `app/.../playback/smart/LyricsTiming.kt`, `AiLyrics.kt`,
  `AiTimingPolicy.kt`, `SyncTestMode.kt`
- `app/.../playback/TranslationAhead.kt`, `TranslationAheadPolicy.kt`
- `app/.../ui/LyricsController.kt`
- `app/.../lockscreen/LockScreenLyrics.kt`, `LockScreenLyricsActivity.kt`
- `sharedUi/.../ui/player/LyricsTimingUi.kt`, `SongMeaningSheet.kt`,
  `StandaloneLyricsPanel.kt`
- `scripts/synctest.py`

끼워 넣은 부분:
- `[위험] shared/.../data/lyrics/LyricsRepository.kt:105 - lyrics`,
  `:197 - lookUpAll`, `:303 - checkAgain`, `:479 - transcribed`,
  `:502 - search`, `:540 - searchRounds`, `:646 - lyricsFor`,
  `:712 - lookUp`, `:830 - wordSyncedWithin`
- `[위험] shared/.../data/lyrics/LyricsTranslation.kt:240 - translate`,
  `:291 - resolve`, `:450 - geminiEntry`, `:1087 - explain`,
  `:1224 - nativeName`, `:1303 - placeLinesInVideo`,
  `:1412 - transcribeVideo`
- `sharedUi/.../ui/player/PlayerLyrics.kt:525 - LyricsStatusWithChange`,
  `:572 - adjustedLyricsPosition`, `:1483 - TranslationToggleButton`,
  `:1626 - LyricsTranslationMotion`, `:1818 - LyricsPanel`,
  `:3202 - rememberLyricsTranslation`
- `sharedUi/.../ui/player/LyricsOffsetSheet.kt:112 - LyricsOffsetSheet`,
  `:341 - SongOffsetSection`, `:502 - VideoTimingBlock`
- `[위험] app/.../playback/PlaybackService.kt:9216 - loadLyricsForCurrentTrack`,
  `:9279-9324 - LyricsTiming` 적용/변경 수집
- `[위험] app/.../ui/MainViewModel.kt:362 - lyricsController`,
  `:387 - loadLyrics`, `:417 - selectLyricsProvider`,
  `:420 - toggleAiLyricsTiming`, `:423 - setSongLyricsOffset`
- `app/.../MainActivity.kt:3042 - onAiLyricsTiming`
- `app/.../ui/LyricsController.kt:767 - TrackDiagnostics.noteLyricsSync`
- `app/.../playback/PlaybackService.kt:1704/:9042 - LockScreenLyrics.attach/detach`
- `shared/.../data/lyrics/EmbeddedLyrics.kt`, `app/.../download/LyricsTag.kt`의
  내장/다운로드 가사 확장

### 1.6 진단 로그

곡 한 줄 요약(경로/가사/싱크/첫 소리), 앱 이벤트 로그, 로그 내보내기, URL
리다크션을 포함한다.

전용 파일:
- `app/.../data/DiagnosticsExport.kt`, `DiagnosticsState.kt`,
  `TrackDiagnostics.kt`
- `shared/.../data/TrackLogPersistence.kt`
- `shared/.../data/net/LogUrl.kt`
- `app/.../startup/StartupTrace.kt`, `StartupWork.kt`
- `app/.../data/innertube/PlaybackTracker.kt` (수정됨)

끼워 넣은 부분:
- `app/.../BitChordApplication.kt:90 - TrackLog.initPersistence`,
  `:61/:71/:75` lifecycle, `:375 - TrackLog.crash`,
  `:393/:413/:415` 메모리/트림 로그
- `[위험] app/.../playback/PlaybackService.kt:4017 - startTrackSummary`,
  `:4049 - logTrackSummary`, `:4025/:4051 - TrackDiagnostics`
- `app/.../MainActivity.kt:5185 - TrackDiagnostics.mark`
- `app/.../ui/screens/AccountAndScrobblingScreen.kt:143 - DiagnosticsExport.write`
- `app/.../playback/SongVersionController.kt:95/:96 - TrackDiagnostics`
- `app/.../ui/LyricsController.kt:767 - noteLyricsSync`
- `shared/.../data/TrackLog.kt`의 `initPersistence`/`flush` 연결

### 1.7 한국어 문자열 / 표시

한국어 리소스, 한국어 줄바꿈(단어 단위), 국가/차트, 한국어 가사 소스, 한국어
표시 정리를 포함한다.

전용 파일:
- `app/src/main/res/values-ko/strings.xml` (1,257개 string)
- `sharedUi/src/commonMain/composeResources/values-ko/strings.xml`
- `tv/src/main/res/values-ko/strings.xml`,
  `wearos/src/main/res/values-ko/strings.xml` (이 포크는 tv/wearos를 빌드하지 않음)
- `sharedUi/.../ui/theme/PhraseLineBreak.kt`, `PhraseLineBreak.android.kt`,
  `PhraseLineBreak.jvm.kt`
- `app/.../ui/screens/ContentCountryDialog.kt`
- `shared/.../data/innertube/MoodLabels.kt`,
  `shared/.../data/YouTubeDisplayCleaner.kt`,
  `shared/.../data/lyrics/KoreanLyrics.kt`,
  `shared/.../data/charts/ChartsApi.kt`,
  `shared/.../data/ExploreCategoryNames.kt`

끼워 넣은 부분:
- `app/.../ui/components/AppLanguageDialog.kt:74 - AppLanguage("ko", ...)`
- `app/.../ui/theme/Theme.kt:62-94 - withPhraseLineBreak()` (모든 타입 스케일)
- `sharedUi/.../ui/player/PlayerLyrics.kt:2359 - PhraseLineBreak` 적용
- `app/src/main/res/values/strings.xml`에 추가된 키(약 229줄),
  `sharedUi/src/commonMain/composeResources/values/strings.xml`에 추가된 키
- `[위험] app/.../ui/screens/SettingsSheet.kt`의 한국어 라벨/문구(여러 행)
- `app/.../MainActivity.kt`의 한국어 온보딩/표시 문자열

### 1.8 자동재생 개인화

시드 곡의 라디오, 취향 풀, 유사 후보, 재생 순서를 합쳐 자동재생을 개인화한다.

전용 파일:
- `app/.../data/taste/AutoplayOrder.kt`, `Recommender.kt`,
  `SimilarCandidates.kt`, `TastePool.kt`, `TasteSignals.kt`
- `shared/.../data/net/AutoplayQueuePolicy.kt`
- `app/.../data/stats/SmartPlaylists.kt`, `ListeningStatsSeed.kt`,
  `WriteThrottle.kt`
- `app/.../data/BoundedMemo.kt` (정규화 메모)

끼워 넣은 부분:
- `app/.../playback/Autoplay.kt:122 - loadAutoplayTracks`,
  `:144` 개인화 분기, `:222 - rankedExtensions`,
  `:235 - SimilarCandidates`, `:236 - TastePool`, `:247 - Recommender`,
  `:277 - AutoplayOrder`, `:310 - seedProfileOf`
- `[위험] app/.../playback/PlaybackService.kt:3437 - toggleAutoplayFromNotification`,
  `:3490 - loadAutoplayForCurrentTrack`, `:3645 - partyAutoplayWaiting`,
  `:3670 - topUpPartyAutoplay`, `:3688 - refreshAutoplayIfQueueEmpty`,
  `:3705 - boundedAutoplayEligible`, `:3720 - autoplayEnabled`,
  `:3730 - dropAutoplayTracksFromQueue`, `:3743 - stashAutoplayTracks`,
  `:3764 - restoreAutoplayTracks`
- `app/.../BitChordApplication.kt:258 - TasteSignals.init`,
  `:261 - SimilarCandidates.init`
- `[위험] app/.../data/settings/AppSettings.kt`의 `personalisedAutoplay` 설정
- `app/.../ui/screens/SettingsSheet.kt`의 자동재생 개인화 행

### 1.9 그 밖의 포크 전용 묶음

합치기 때 기능별로 같이 봐야 하는 나머지 묶음이다.

- HQ/오디오: `HqHostPolicy`, `HqAudioVerify`, `HqVerificationStore`,
  `HqMatchPolicy`, `HqReferenceCache`, `HqTier`, `QualityUpgradeAdvice`,
  `ResamplingAudioProcessor`, `AudioOutputHealth`, `SilenceSkipper`,
  `SilenceSkipOffset`, `DecoderMonitor`, `PlaybackParametersMath`
- 배터리/저전력: `LowPowerMode`, `LowPowerDevice`, `PowerState`,
  `BufferCeiling`, `HeadphoneResume`, `CrossfadeArmPolicy`, `AnalysisHeadRetry`
- 다운로드/오프라인: `DownloadedQuality`, `LikedAutoDownload`,
  `MvSoundRedownload`, `DownloadedArtworkResolver`, `DownloadedArtworkCandidates`
- 커버/캔버스: `Artwork`, `CoverTitles`, `YouTubeCoverResolver`,
  `CoverOriginals`, `AppleSubtitle`, `AlbumSubtitle`, `ArtistCountText`
- UI/편의: `MainActivityHelpers`, `MainActivityLayout`, `MainActivitySortMenu`,
  `ForkAlerts`, `SegmentedControl`, `LikedMusicCover`, `AppleSubtitleText`,
  `PlayerExpandGuard`, `WidgetOpenIntent`, `QueueEdit`, `ShuffleAppend`,
  `SkipSegments`, `StreamRefusalRecovery`, `UnplayableRecovery`
- 시작/성능: `StartupTrace`, `StartupWork`, `PrefetchController`,
  `SessionScopedCache`, `SharedLookups`, `BoundedMemo`, `WriteThrottle`

## 2. 충돌 위험 상위 10개 업스트림 파일

`git diff --numstat v1.8.1...HEAD`에서 수정된 업스트림 파일만 골라 변경 줄 수가
큰 순서다. `+/-`는 포크 기준 추가/삭제 줄 수다.

| 순위 | 파일 | +/- | 포크가 넣은 것 | 합칠 때 주의점 |
|---:|---|---:|---|---|
| 1 | `app/.../playback/PlaybackService.kt` | +2624/-284 | 곡 버전 컨트롤러, 프리페치, 번역 선행, 가사 티커/타이밍, 진단 요약, 시크릿 게이트, HQ 검증, 잠금화면 | 업스트림 재생 콜백/상태 흐름을 먼저 살리고, 포크 호출을 lifecycle 지점에 다시 꽂는다. `songVersionController`, `prefetchController`, `PrivateListening.allows`, `TrackDiagnostics`를 검색 |
| 2 | `app/.../ui/MainViewModel.kt` | +908/-482 | `LyricsController` 위임, 최근 재생 갱신, 스마트 플레이리스트, 국가/차트, 타입어헤드 캐시 | `loadHome`/`refreshHome` 구조가 바뀌면 포크의 recents 병합을 이벤트 지점에 다시 넣는다. 업스트림 상태 필드를 지우지 않는다 |
| 3 | `app/.../MainActivity.kt` | +1084/-281 | `switchToMusicOnly`, 디버그 스위치, 프록시 상태 알림, 시크릿 토글, 플레이어 확장 가드, 온보딩 | UI는 업스트림을 우선하고 포크 분기는 함수 단위로 다시 연결. 이미 뺀 `MainActivityHelpers/Layout/SortMenu`를 다시 합치지 않는다 |
| 4 | `shared/.../data/YtMusicRepository.kt` | +1240/-108 | 게이트 분기(`MusicGateFallback`/`YouTubeFallback`), 최근 재생 병합, `counterpartAudio`/`songVersion`, 국가 차트 | 각 공개 메서드 입구의 `ensureGateKnown()` 분기를 빠뜨리지 않는다. 업스트림의 새 API/continuation을 유지하고 fallback은 분기로만 붙인다 |
| 5 | `shared/.../data/innertube/Innertube.kt` | +1069/-155 | youtube.com 엔드포인트, 프록시 전용 browse, `accountMenuYouTube`, 재생 트래킹, 세션 스코프 | `postMusic`/`postYouTube` 시그니처와 세션 스코프가 바뀌면 포크 메서드를 그 위에 다시 얹는다. 익명 검색 규칙 유지 |
| 6 | `shared/.../data/lyrics/LyricsTranslation.kt` | +1008/-50 | Gemini 번역/로마자/의미, 원어 이름, 영상 가사 배치/전사, 캐시 | 업스트림 번역 파이프라인을 유지하고 Gemini는 fallback으로만. 캐시 키/슬롯 마커가 바뀌면 포크 캐시를 무효화 |
| 7 | `app/.../data/settings/AppSettings.kt` | +896/-34 | 프록시, 시크릿, 가사/AI/번역, 자동재생 개인화, 저전력, 백업 SECRETS | 키 이름 충돌을 먼저 확인. 백업/복원의 `SECRETS`/`DEVICE_LOCAL` 목록에 포크 키를 계속 넣는다 |
| 8 | `shared/.../data/innertube/StreamResolver.kt` | +852/-51 | 프록시 조기 경쟁, `resolveViaProxy`, 거절/중간 재해석, 프록시 다운로드, 학습 순서 | 가장 위험. 업스트림의 클라이언트 순서/새 resolver를 먼저 보존하고, 프록시는 "거절 시 한 번" 규칙으로 감싼다. `ResolutionOrder` 상태를 잃지 않는다 |
| 9 | `app/.../playback/AudioCache.kt` | +630/-166 | 프리페치/warm range, 분석 retain, HQ 검증, 캐시 잠금 | SimpleCache 단일 writer/잠금 규칙을 업스트림 기준으로 유지. 포크의 `timingKeep`/`playerHoldsKey`를 다시 연결 |
| 10 | `app/.../ui/screens/SettingsSheet.kt` | +602/-156 | 프록시/시크릿/가사/국가/잠금화면/자동재생 행, 포크 알림, 세그먼트 컨트롤 | 업스트림 행을 유지하고 포크 행은 `ProxySettingsSection`, `ForkAlerts`, `ContentCountryDialog` 호출로 붙인다. row 순서/키 중복 주의 |

다음 순위 감시 대상: `shared/.../lyrics/LyricsRepository.kt` (+708/-30),
`shared/.../sources/TrackMatcher.kt` (+657/-36),
`shared/.../SongInfoRepository.kt`, `shared/.../cover/CoverTitles.kt`,
`sharedUi/.../PlayerLyrics.kt`, `sharedUi/.../LyricsOffsetSheet.kt`,
`sharedUi/.../NowPlayingScreen.kt`.

## 3. 함수 하나를 통째로 옮길 수 있는 후보 (제안만, 실제 이동 없음)

아래 5개는 업스트림 파일에 들어간 포크 코드 중 경계가 비교적 분명한 것이다.
이번 작업에서는 옮기지 않았고, 다음 정리 작업에서 하나씩 시험할 후보로만 적는다.
각 후보는 "포크 파일로 함수를 옮기고 업스트림 파일에는 한 줄 호출만 남긴다"는
규칙을 전제로 한다.

| # | 현재 위치 | 옮길 곳 | 업스트림 파일에 남길 것 | 안전한 이유 / 전제 |
|---:|---|---|---|---|
| 1 | `app/.../MainActivity.kt:557 - applyDebugLaunchSwitches` | `app/.../MainActivityHelpers.kt` 또는 새 `DebugLaunchSwitches.kt` | `applyDebugLaunchSwitches(intent)` 한 줄 | 디버그 빌드 전용이고 UI 상태를 만들지 않는다. `EXTRA_*` 상수와 `DEBUG_TAG`를 함께 옮기거나 `internal`로 바꿔야 한다 |
| 2 | `app/.../MainActivity.kt:606 - Intent.isLaunchedFromHistory` | `MainActivityHelpers.kt` | `intent.isLaunchedFromHistory()` 호출 | 순수 확장 함수. `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`만 읽는다 |
| 3 | `app/.../playback/PlaybackService.kt:2240 - noteTasteSkip` | 새 `playback/TasteSkip.kt` 또는 `data/taste/TasteSignals.kt` | `noteTasteSkip(surface)` 한 줄 | `Player`의 현재 항목/위치만 읽어 `TasteSignals.onUserAdvance`에 넘긴다. 서비스 필드 의존이 없다 |
| 4 | `app/.../playback/PlaybackService.kt:2903 - logVersionSwapRefused` | `TrackDiagnostics.kt` 또는 `SongVersionController.kt` | `logVersionSwapRefused(...)` 한 줄 | 로그 한 줄만 쓰는 순수 함수. 서비스 상태를 바꾸지 않는다 |
| 5 | `app/.../ui/screens/SettingsSheet.kt:3142 - SettingsSegmentedControl` | 새 `ui/components/SettingsControls.kt` | `SettingsSegmentedControl(...)` 호출 | 자체 완결형 Composable. `EqualizerScreen`에서도 쓰므로 `internal` 유지. 같은 파일의 `settingsSwitchColors`(2868)도 같이 옮기면 효과가 크다 |

옮기지 말아야 할 대표 예:
- `PlaybackService.startTrackSummary`/`logTrackSummary`: 서비스 필드와 타이밍 상태에
  강하게 묶여 있다.
- `StreamResolver.resolveViaProxy`: private 헬퍼(`timed`, `describeMediaUrl`,
  `served`)와 `Stream` 타입에 의존한다. 옮기려면 인터페이스를 먼저 만들어야 한다.
- `YtMusicRepository.counterpartAudio`: repository의 `call`, 캐시, `Innertube`
  상태에 의존한다.
- `MainActivity.switchToMusicOnly`: 플레이어/뷰모델/컨트롤러 상태가 많아 함수 하나만
  떼어내기 어렵다.

## 4. 전용 운영 Kotlin 파일 전체 목록 (146개)

패키지별로 묶은 것이다. 테스트 파일(126개)은 빼고 운영 코드만 적는다.

- 루트: `MainActivityHelpers.kt`, `MainActivityLayout.kt`, `MainActivitySortMenu.kt`
- `auth`: `YouTubeWebLogin.kt`
- `data`: `BoundedMemo.kt`, `CatalogSongMapper.kt`, `DiagnosticsExport.kt`,
  `DiagnosticsState.kt`, `ExploreCategories.kt`, `ExploreCategoryArtworkCache.kt`,
  `ExploreCategoryFilter.kt`, `ExploreCategoryNames.kt`, `HomeFallbackRules.kt`,
  `LocalPlayHistory.kt`, `RecentShelf.kt`, `StoredVerdictCache.kt`,
  `TrackDiagnostics.kt`, `TrackLogPersistence.kt`, `YouTubeArtistResolver.kt`,
  `YouTubeCategoryRules.kt`, `YouTubeDisplayCleaner.kt`, `YouTubeFallback.kt`,
  `YouTubeFallbackFeed.kt`, `YouTubeMusicClassifier.kt`
- `data/charts`: `ChartsApi.kt`
- `data/cover`: `Artwork.kt`, `CoverTitles.kt`, `DownloadedArtworkCandidates.kt`,
  `YouTubeCoverResolver.kt`
- `data/innertube`: `DirectClientResolver.kt`, `MoodLabels.kt`,
  `MusicGateFallback.kt`, `ProxyConnectionTest.kt`, `ResolutionOrder.kt`,
  `YouTubeWebParser.kt`, `YouTubeWebSearch.kt`
- `data/lyrics`: `ChromaAlignment.kt`, `Deepgram.kt`, `EstimatedSweep.kt`,
  `FileLyricsOrigin.kt`, `HeardWords.kt`, `KoreanLyrics.kt`, `LyricLineIndex.kt`,
  `LyricsAiGuard.kt`, `LyricsDiskCache.kt`, `LyricsFailure.kt`,
  `LyricsQueryVariants.kt`, `LyricsTimeMap.kt`, `LyricsWordAlign.kt`,
  `OriginalNames.kt`, `RecordingPick.kt`, `SongCheckWindow.kt`,
  `SongSyncRetry.kt`, `SyncDump.kt`
- `data/model`: `AlbumSubtitle.kt`, `AppleSubtitle.kt`, `ArtistCountText.kt`,
  `SongOrigins.kt`
- `data/net`: `ArtistCandidate.kt`, `AutoplayQueuePolicy.kt`, `CounterpartStore.kt`,
  `CounterpartSwitch.kt`, `LogUrl.kt`, `ProxyConfig.kt`, `ProxyHealth.kt`,
  `ProxyPlayback.kt`, `ProxyPlaybackStore.kt`, `ProxyRoutePolicy.kt`,
  `ResolutionRace.kt`, `SessionScopedCache.kt`, `SharedLookups.kt`,
  `SongVersionPolicy.kt`, `TrackSummaryFormat.kt`, `TypeaheadLookupPolicy.kt`,
  `YouTubeProxy.kt`
- `data/remote`: `DownloadedArtworkResolver.kt`
- `data/settings`: `LowPowerMode.kt`, `PrivateListening.kt`
- `data/sources`: `HqMatchPolicy.kt`, `HqReferenceCache.kt`, `HqTier.kt`,
  `QualityUpgradeAdvice.kt`
- `data/stats`: `ListeningStatsSeed.kt`, `SmartPlaylists.kt`, `WriteThrottle.kt`
- `data/taste`: `AutoplayOrder.kt`, `Recommender.kt`, `SimilarCandidates.kt`,
  `TastePool.kt`, `TasteSignals.kt`
- `download`: `DownloadedQuality.kt`, `LikedAutoDownload.kt`,
  `MvSoundRedownload.kt`
- `lockscreen`: `LockScreenLyrics.kt`, `LockScreenLyricsActivity.kt`
- `playback`: `AnalysisHeadRetry.kt`, `BufferCeiling.kt`, `CrossfadeArmPolicy.kt`,
  `DecoderMonitor.kt`, `HeadphoneResume.kt`, `HqHostPolicy.kt`, `LowPowerDevice.kt`,
  `LyricSubtitleScheduler.kt`, `PlaybackParametersMath.kt`, `PowerState.kt`,
  `PrefetchController.kt`, `ProxyAwareDataSource.kt`, `QueueEdit.kt`,
  `ShuffleAppend.kt`, `SkipSegments.kt`, `SongVersionController.kt`,
  `StreamRefusalRecovery.kt`, `TranslationAhead.kt`, `TranslationAheadPolicy.kt`,
  `UnplayableRecovery.kt`
- `playback/audio`: `AudioOutputHealth.kt`, `ResamplingAudioProcessor.kt`,
  `SilenceSkipOffset.kt`, `SilenceSkipper.kt`
- `playback/smart`: `AiLyrics.kt`, `AiTimingPolicy.kt`, `AnalysisDuration.kt`,
  `CoverOriginals.kt`, `HqAudioVerify.kt`, `HqVerificationStore.kt`,
  `LyricsTiming.kt`, `SyncTestMode.kt`, `VersionSwapPolicy.kt`, `VideoAudio.kt`
- `startup`: `StartupTrace.kt`, `StartupWork.kt`
- `ui`: `LyricsController.kt`
- `ui/components`: `AppleSubtitleText.kt`, `ForkAlerts.kt`, `LikedMusicCover.kt`,
  `SegmentedControl.kt`
- `ui/player`: `LyricsTimingUi.kt`, `PlayerExpandGuard.kt`, `SongMeaningSheet.kt`,
  `StandaloneLyricsPanel.kt`
- `ui/screens`: `ContentCountryDialog.kt`, `ProxySettingsSection.kt`
- `ui/theme`: `PhraseLineBreak.kt`, `PhraseLineBreak.android.kt`,
  `PhraseLineBreak.jvm.kt`
- `widget`: `WidgetOpenIntent.kt`

## 5. 유지보수 규칙

1. 새 포크 기능은 가능하면 `app/.../` 또는 `shared/.../`의 새 파일에 둔다.
2. 업스트림 파일에는 "호출 한 줄" 또는 "분기 한 블록"만 넣는다.
3. 업스트림 파일에 넣은 블록에는 `// FORK:` 같은 검색 가능한 표식을 남긴다.
   (이 문서 작성 시점에는 표식이 일부만 있으므로, 다음 정리 작업에서 통일한다.)
4. 이 문서의 줄 번호는 합치기마다 갱신한다. 함수명은 그대로 두는 편이 안전하다.
5. `git diff v1.8.1...HEAD --stat`는 기준 태그가 바뀌면 새 태그로 다시 계산한다.
