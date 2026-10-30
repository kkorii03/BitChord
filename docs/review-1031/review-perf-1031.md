# 성능·배터리 재검토 (review-perf-1031)

기준: `dsq/review-perf-1031` HEAD. 방법: 이전 감사 문서(`docs/AUDIT_PERFORMANCE.md`,
`docs/audit/*.md`)를 먼저 읽고 현재 코드의 호출 경로와 대조했다. 기기 프로파일링은
하지 않았고, 빈도는 코드 상수·조건에서 확인되는 범위만 적었다. 재생 핵심 경로
(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 고치지 않고 표에만 남겼다.

## 1. 이전 감사 대조

### 1.1 `docs/AUDIT_PERFORMANCE.md` TOP 20

| # | 이전 항목 | 상태 | 현재 근거 |
|---:|---|---|---|
| 1 | 콜드 스타트가 메인에서 저장소를 열고 `join()` | 고쳐짐 | `BitChordApplication.kt:159-288`가 `StartupWork.background`로 열고 join이 없다. `startup/StartupWork.kt`. 새 예외는 N2. |
| 2 | `BitChordApp` 전체가 하나의 재시작 범위 | 남음 | `MainActivity.kt:625`, `collectAsStateWithLifecycle` 87회. 구조 분리는 이번 범위 밖. |
| 3 | `canvasFrame` 3초마다 화면 전체 재구성 | 고쳐짐 | `NowPlayingScreen.kt:470-516`의 `CanvasAwareArtworkMesh`/`CanvasAwareMeshGradient`가 `State`를 자식에서 읽는다. |
| 4 | 가사 클럭이 프레임마다 전체 목록 스캔·할당 | 고쳐짐 | `LyricLineIndex`(이진 탐색·재사용 버퍼), `PlayerLyrics.kt:1890-1928`의 `derivedStateOf`. |
| 5 | 재생 서비스 500ms 가사 티커 | 고쳐짐 | `PlaybackService.kt:10297-10336`가 줄 경계까지 계산해 한 번만 깨고, 소비자 없으면 시작하지 않는다. |
| 6 | 셀룰러 기본 LOSSLESS | 부분 | 신규 설치는 HIGH(`AppSettings.kt:3757-3758`), 기존 설치는 마이그레이션으로 LOSSLESS 유지. |
| 7 | `reportProgress`가 메인에서 prefs/JSON | 고쳐짐 | 디스크·JSON은 `progressWork` 단일 스레드(`PlaybackService.kt:892-906`), 메인에는 플레이어 읽기·포맷 비교만 남는다. |
| 8 | 취향 모델 1.5초 디바운스 | 고쳐짐 | `TasteSignals.kt:125`, `:402` 30초 디바운스 + 백그라운드 flush. |
| 9 | 청취 기록 6샘플마다 전체 월 JSON | 고쳐짐 | `ListeningStats.kt:1125` 2분 스로틀, `:399` pause/정지 강제 flush. |
| 10 | `MainViewModel.init`이 Home/Explore/Library/업데이트를 즉시 로드 | 고쳐짐 | `MainViewModel.kt:1383-1399` Home만 즉시, 나머지 2초 지연; `:1491` 일일 업데이트 확인. |
| 11 | 보관함 선반 메시가 항상 `continuous` | 고쳐짐 | `HomeScreen.kt:1147`, `:1190`이 `isPlaying`, `MeshGradient.kt:136-157`이 포그라운드·저전력 게이트. |
| 12 | 가사 행마다 RenderEffect blur | 남음 | `PlayerLyrics.kt:2246-2249`. 드래그(`browsing`) 중에는 꺼지지만 자동 스크롤·줄 전환에는 남는다. |
| 13 | 곡마다 가사 타이밍·번역 선행 | 고쳐짐 | `TranslationAheadPolicy.plan`(미터드/저전력/불확실 AutoPlay) + `PlaybackService.kt:10062-10074`. |
| 14 | 스마트 페이드가 곡마다 전체 디코드+ONNX | 부분 | 저전력·파티에서는 시작하지 않는다(`TrackAnalyzer.kt:117`). 켜져 있으면 곡당 1회 디코드는 기능상 의도. |
| 15 | 마퀴가 일시정지·가사 화면에서도 무한 | 고쳐짐 | `PlayerControls.kt:1431`, `:1567`의 `marqueeShouldCrawl(running, reduceAnimation, lowPower, foreground)`. |
| 16 | 대기열 44dp 썸네일이 원본 URL | 고쳐짐 | `PlayerQueue.kt:825`가 `artworkAt(ROW_ART_PX)`. |
| 17 | 가사 행 key 없음·`in` 스캔 | 고쳐짐 | `PlayerLyrics.kt:2192-2194` key, `:1904` `activeRowSet`, `:2237` set 멤버십. |
| 18 | 길게 누를 때 메인에서 GPU→비트맵 | 고쳐짐 | `LongPressOrigin.kt:105-124`가 `scope.launch`로 옮기고 1024px 상한. |
| 19 | 설정 화면이 `positionMs` 값을 2/초 읽음 | 고쳐짐 | `MainActivity.kt:3329`가 `PlaybackPosition` 객체를 넘긴다. |
| 20 | `remember` 없는 목록 가공 | 부분 | `LibraryScreen.kt:435`는 `remember`; `ExploreScreen.kt:126`·`SearchScreen.kt:337`는 여전히 LazyListScope 안에서 계산. |

### 1.2 `docs/audit/audit-battery.md`

| 항목 | 상태 | 현재 근거 |
|---|---|---|
| HIGH AutoMix가 저전력 무시 | 고쳐짐 | `TrackAnalyzer.kt:117` `BatteryPolicy.automixAnalysisActive`. |
| HIGH 다음 곡 전체 선반입이 저전력 무시 | 고쳐짐 | `AudioCache.kt:1704`, `:1713` `BatteryPolicy.cachePrefetchBytesAllowed`. |
| HIGH MV 소리 복구 `REPLACE` | 고쳐짐 | `MvSoundRedownload.kt:111` `APPEND_OR_REPLACE`. |
| MED 아티스트 백필이 화면 꺼짐·저전력에도 1.5초 네트워크 | 남음 | `ArtistFacts.kt:192-206`, `:335`. 새 항목 N3. |
| MED 크로스페이드 ticker가 IDLE에도 계속 | 남음 | 재생 코어, 문서만. |
| MED 분석 헤드 250/40/100ms 폴링 | 남음 | 재생 코어, 문서만. |
| MED 곡 버전·HQ 조회가 저전력 무시 | 남음 | 재생 코어, 문서만. |
| MED `DownloadService` 4 worker 100ms 폴링 | 남음 | `DownloadService.kt:118-127`, `:289-291`. 새 항목 N4. |
| MED 다운로드와 분석 디코드 동시 실행 | 남음 | 재생 코어, 문서만. |
| LOW 영상 가사 측정 250ms 확인 | 남음 | 가사 싱크 코어, 문서만. |

### 1.3 그 밖의 감사 문서 (HIGH/MED만)

| 문서 | 이전 HIGH/MED | 현재 상태 |
|---|---|---|
| `check-crash.md` | 다음 곡 준비가 메인에서 `HqVerificationStore.bestTier` 파싱 | 남음(재생 코어). `NextTrackPreparation.kt:416-424`가 여전히 `run`(Main)에서 호출. |
| | HQ 검증 병렬 디코드가 전역 세마포어로 직렬화 | 남음(재생 코어). |
| | 설정 가져오기 무제한 `readBytes` OOM | 고쳐짐. `SettingsTransfer.kt:292-306` 상한. |
| | `AudioCache.hqHeadReady` 누수 | 남음. `AudioCache.kt:1534`, `:1583`에 remove 경로 없음. |
| | 시작 시 HQ 계획 파싱 | 남음. `HqLibraryUpgrade.kt:231-232`가 `ensureLoaded`를 메인에서 호출. |
| `check-data.md` | NextPrep이 override 무시 | 남음(가사 코어). `TranslationAhead.kt:130-153`은 override를 보지 않는다. |
| | 설정/백업 숫자 범위 미검증 | 고쳐짐. `AppSettings.kt:1222`, `:1232`, `:1840` clamp. |
| | HQ 교체 `hqOffsetMs/hqRate` 미독 | 남음(가사 코어). 저장만 하고 `LyricsTiming`에서 읽지 않는다. |
| | HQ 자동 패스가 Wi-Fi 전용 우회 | 고쳐짐. 자동 패스는 `hqPassAllowsMeteredData(manualPass=false)`=`false`(`HqLibraryUpgrade.kt:54`, `:659`). |
| | 소스 변경 시 옛 HQ verdict 유지 | 남음. `SourceRegistry.kt:303`, `:427`은 검색 답만 지운다. |
| | 설정 공유에 HQ 키 누락 | 고쳐짐. `AppSettings.kt:2928-2929`, `:3006`, `:3251-3253`. |
| `check-net.md` | Deepgram 실패를 `version=1`로 영구 기록 | 남음(가사 코어). `DownloadedSyncCheck.decide`에 `ownNoAnswer`가 없다. |
| | 잘린 프로파일로 영구 거부 | 남음(재생 코어). |
| | 긴 Retry-After에서 같은 URL 재시도 | 남음(재생 코어). |
| | HQ override 잔존 | 남음. `Downloads.fail`(`:2597-2600`)이 `hqOverrides`를 지우지 않는다. |
| `check-flow.md` | 자동 믹스 standby 버전 미추종, queueKey 재시작 누락, 행 단위 가사 캐시, 다운로드 파일 안전 규칙 우회, 스트리밍을 로컬로 오판 | 남음(모두 재생 코어). |
| `check-lyrics.md` / `check-sync-regress.md` | 교체 파일 재검증·RECHECK override 제거 | 고쳐짐. `LyricsTiming.kt:1862-1890`이 `syncCheckVersion=0`, `removeRecheck`. |
| | NextPrep override 무시, HQ shift 이중 적용, 잠금화면 override 미적용, override videoId 단일 키 | 남음(가사 코어). |
| `check-concurrency.md` | `resetSongCheck`가 `songSyncGate` 밖 | 남음(가사 코어). `LyricsTiming.kt:1862`에 gate 없음. |
| | 나머지 MED 6건(장기 gate, HQ 취소, lock map, Whisper 누수, 정렬 직렬화, rebuild 유실) | 남음(재생 코어). |

## 2. 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 |
|---|---|---|---|---|
| HIGH | `app/.../MainActivity.kt:625` | `BitChordApp` 한 함수가 87개 `collectAsStateWithLifecycle`를 읽는다. 검색 제안·좋아요·다운로드·큐 상태 어느 하나만 바뀌어도 루트 본문이 다시 실행된다. | 목록 스크롤 중 좋아요/다운로드/큐 갱신 때 루트 전체 재구성 비용. | 탭/시트별 컴포저블로 쪼개고 collect를 그 안으로 내린다. 이번에는 문서만(대규모). |
| MED | `app/.../data/spotify/LocalPlaylistStore.kt:69-77` | `init`이 메인에서 `load()` → `prefs.getString` + `parsePlaylists`(전체 JSON)를 실행한다. | 로컬 플레이리스트가 큰 사용자의 콜드 스타트 첫 프레임 지연. | 다른 저장소처럼 `StartupWork.background`로 열고, 쓰기 전 `ensureLoaded` 게이트. |
| MED | `app/.../data/stats/ArtistFacts.kt:192-206`, `:335` | `for (name in requests)`가 화면 꺼짐·저전력과 무관하게 1.5초 간격 네트워크를 계속한다. | 화면을 끈 채 재생만 해도 아티스트 백필이 라디오를 깨운다. | `AppForeground`/`AppSettings.lowPower` 이벤트로 채널 소비를 멈추고 복귀 시 재개. |
| MED | `app/.../download/DownloadService.kt:118-127`, `:289-291` | 큐가 빈 동안 4 worker가 `IDLE_POLL_MS=100`로 최대 2초 폴링한다. | 다운로드 시작/종료 순간 worker당 20회, 총 80회 불필요한 wake-up. | `Downloads`의 채널/플로우 wake-up으로 교체. |
| MED | `app/.../playback/smart/TrackAnalyzer.kt:130-154`, `:1519-1540` | `results`/`headResults`/`tailResults`/`sourceAnalysisUris` 등이 `ConcurrentHashMap`이고 `release()`가 `results`를 지우지 않는다. | 스마트 페이드를 오래 켜 두면 분석한 곡 수만큼 메모리 증가. | `results`도 release에서 clear, 세션 캐시는 LRU 상한. 재생 코어라 문서만. |
| MED | `app/.../playback/PlaybackService.kt:389` | `songCache`는 `ConcurrentHashMap<String, Song>`이고 상한이 없다(검색 결과 캐시만 20개 상한, `:11846`). | 긴 세션에서 해석한 곡 객체가 계속 쌓인다. | LRU/상한 또는 current queue 밖 정리. 재생 코어라 문서만. |
| MED | `sharedUi/.../player/PlayerLyrics.kt:2246-2249` | 줄 전환마다 `LINE_FALLOFF_BLUR[step]`가 바뀌어 offscreen layer와 RenderEffect를 다시 만든다. `browsing`(드래그)만 예외다. | 가사 자동 스크롤 중 줄마다 blur 재생성. | `listState.isScrollInProgress`일 때 `0.dp`, 또는 blur를 1~2단계만 유지. |
| MED | `app/.../data/stats/Backup.kt:126` | 가져오기가 `readBytes()`로 파일 전체를 메모리에 올렸다. | 큰 파일을 공유받아 가져오면 OOM. | **이번 수정**: 32MB 상한 `readBackupText`, 초과 시 한국어 오류. |
| MED | `app/.../BitChordApplication.kt:96`, `:411-422` | 콜드 스타트마다 `process.txt`를 메인에서 `readLines`/`writeText`. | 첫 프레임 전에 작은 디스크 I/O가 동기로 들어간다. | **이번 수정**: IO 코루틴으로 이동, warm 판정은 순수 함수. |
| LOW | `sharedUi/.../screens/ExploreScreen.kt:126`, `SearchScreen.kt:337`, `:400` | LazyListScope 안에서 `flatMap`/`distinctBy`/`chunked`/`filterIsInstance`를 재구성마다 다시 계산. | 큰 카테고리 목록에서 스크롤 중 약간의 할당. | 컴포저블 상단에서 `remember(source, columns)`로 계산해 넘긴다. |
| LOW | `sharedUi/.../screens/LibraryScreen.kt:398` | `items(visibleItems)`에 key가 없다(같은 파일 `:459`에는 있다). | 보관함 선반 재사용 시 슬롯 상태가 흔들릴 수 있다. | `key = { it.browseId ?: it.title }`. |
| LOW | `app/.../ui/MainViewModel.kt:477` | `listenerCache`가 account/profile 키로 무제한 증가한다. | 계정을 많이 오가면 스냅샷이 남는다. | 작은 LRU 상한. |
| LOW | `app/.../playback/CrossfadeController.kt:1015`, `CanvasArtworkPlayer.kt:325-481` | 릴리스에서도 `android.util.Log.d` 문자열을 먼저 만든다. | 전환·캔버스 프레임마다 소량의 문자열 할당. | `BuildConfig.DEBUG`/`Log.isLoggable` 가드 또는 `TrackLog`만 사용. |

### 확인했지만 문제 없음

- 같은 요청 중복: 표지 `DownloadedArtworkResolver`/`RemoteArtworkStore`/`YouTubeCoverResolver`,
  가사 `LyricsRepository.inFlight`, 검색 디바운스, 홈 로드/갱신 합류는 각각 in-flight
  또는 LRU로 합쳐져 있다.
- 비트맵·캐시 상한: Coil 메모리 20%·디스크 100MB(`BitChordApplication.kt:371-381`),
  `CanvasCache` LRU, `ArtworkMeshBackdrop` mesh 8개/blur 8개, `MediaWidgetArt` 8MB.
- 코루틴 누수: `PlayerConnection` 500ms 폴링은 `isPlaying && foreground`, 가사 프레임
  루프·메시 continuous·잠금화면 시계는 포그라운드 게이트, `TranslationAhead`/`NextPrep`은
  새 작업 전에 이전 job을 취소한다.
- 배터리 원칙: 이번 수정은 새 타이머/폴링을 만들지 않았다. warm 마커는 시작 시 1회,
  백업 읽기는 사용자 가져오기 1회다.

## 3. 이번 수정

### N1. 백업 가져오기 크기 상한 — `Backup.kt`, `SettingsTransfer.kt`

- 전: `openInputStream(source).use { it.readBytes().decodeToString() }`로 파일 전체를
  할당했다.
- 후: `Backup.readBackupText()`가 `SettingsTransfer.readTextBounded`를 재사용해
  32MB까지만 읽고, 넘으면 `백업 파일이 너무 큽니다`로 거부한다. `SettingsTransfer`의
  기존 설정 상한(1MB)과 문구는 기본값으로 그대로다.
- 테스트: `BackupLibraryRoundTripTest`에 `a backup within the cap is read whole`,
  `a backup larger than the cap is refused before it is decoded`.

### N2. 콜드 스타트 warm 마커 I/O 이동 — `BitChordApplication.kt`

- 전: `logProcessStart`가 `Application.onCreate`에서 `process.txt`를 읽고 썼다.
- 후: 같은 함수를 `CoroutineScope(Dispatchers.IO).launch`로 옮겼다. warm/cold 판정은
  `isWarmProcessRestart(now, previousAt, window)`로 분리해 파일 없이 검증한다.
- 테스트: 새 `ProcessRestartWarmthTest` 4건(마커 없음/창 안/창 밖/미래).

## 4. 테스트 결과

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.ProcessRestartWarmthTest' --tests 'com.music.bitchord.data.stats.BackupLibraryRoundTripTest' --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.data.settings.SettingsTransferEdgeCasesTest' --console=plain -q` 통과.

## 5. 남은 것

- HIGH인 `BitChordApp` 루트 재구성과 재생 코어의 MED(가사 싱크·크로스페이드·곡 해석)는
  폰 검증이 필요해 이번에 고치지 않았다. 위 표의 파일:줄이 다음 작업의 입력이다.
- 기기 실측(첫 프레임, 프레임 드랍, 메모리)은 이 러너에서 할 수 없다. 이 문서의 수치는
  코드 경로와 상수에서 읽은 기대값이다.

## 6. 2차 수정 (review-perf-1031-r2)

이전 차수 결과에서 남은 지적 2건을 코드로 확인하고 고쳤다. 재생 핵심 경로는 건드리지 않았다.

### N3. 백업 내보내기 크기 상한 — `Backup.kt`

- 지적: 가져오기에만 32MB 상한이 있어, 내보내기는 그 상한을 넘는 파일을 만들 수 있었고
  그 파일은 다시 가져올 수 없었다.
- 확인: `exportTo`가 `encode(file).toByteArray()`를 상한 검사 없이 그대로 썼다.
- 수정: `encodeBackupBytes(file, maxBytes = MAX_BACKUP_BYTES)`가 인코딩 직후
  `MAX_BACKUP_BYTES`를 검사하고, 넘으면 `백업 파일이 너무 큽니다`로 거부한다.
  `exportTo`는 이 바이트 배열을 그대로 쓰므로 내보낸 파일은 항상 가져오기 상한 안에 있다.
- 테스트: `BackupLibraryRoundTripTest`에 `an export within the cap is written whole`,
  `an export larger than the cap is refused before it is written`.

### N4. warm 마커 타임스탬프 파싱 — `BitChordApplication.kt`

- 지적: `process.txt`는 `pid now` 한 줄인데 `readLines().getOrNull(1)`을 읽어
  `previousAt`이 항상 0이었고, 모든 실행이 cold로 기록됐다.
- 확인: 쓰기는 `marker.writeText("${pid} $now")` 한 줄, 읽기는 두 번째 줄이었다.
- 수정: `processMarkerLine(pid, now)`와 `processMarkerTimestamp(text)`로 분리했다.
  파서는 첫 줄의 두 번째 토큰을 읽으므로 기존에 쓰인 한 줄 마커와도 호환된다.
  `logProcessStart`는 `marker.readText()` → `processMarkerTimestamp` → `marker.writeText(line)`.
- 테스트: `ProcessRestartWarmthTest`에 `the marker written for this process reads back its
  timestamp`(실제 임시 파일 왕복), `a legacy marker still yields the timestamp on its first line`.

### 테스트 결과 (2차)

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.ProcessRestartWarmthTest' --tests 'com.music.bitchord.data.stats.BackupLibraryRoundTripTest' --console=plain -q` 통과.
