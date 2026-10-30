# 배터리 효율 전수 검토 (review-battery-1031) — 2026-10-31

기준 브랜치: `task/fix-1030` (현재 워크스페이스 HEAD).
방식: 이전 감사 문서(`docs/audit/audit-battery.md`, `docs/cleanup/battery.md`,
`docs/AUDIT_PERFORMANCE.md`, `docs/audit/audit-playback.md`)를 먼저 읽고 현재
코드의 `파일:줄`과 대조했다. 기기 프로파일링은 하지 않았고, 빈도는 코드 상수와
조건에서만 읽었다. 이번에 직접 고친 것은 원인이 분명하고 한두 파일 안에서
끝나는 MED 2건과, 그와 같은 배경 프로세스 깨우기 경로 1건이다. 재생 핵심
경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 고치지 않고 아래 표에만
남겼다.

## 1. 이전 감사 항목이 지금 코드에서 고쳐졌는지

| 이전 심각도 | 이전 항목 | 현재 코드 근거 | 판정 |
|---|---|---|---|
| HIGH | AutoMix 분석이 저전력을 무시 | `TrackAnalyzer.kt:117-120` `BatteryPolicy.automixAnalysisActive`, `:334` `heavyWorkAllowed` | 고침 |
| HIGH | 다음 곡 전체 선반입이 저전력을 무시 | `AudioCache.kt:1704-1719` `cachePrefetchBytesAllowed`를 10초 지연 뒤 다시 읽음 | 고침 |
| HIGH | MV 소리 복구 WorkManager `REPLACE` | `MvSoundRedownload.kt:111` `APPEND_OR_REPLACE` | 고침 |
| MED | 아티스트 백필 1.5초 네트워크가 화면 꺼짐·저전력에도 계속 | `ArtistFacts.kt:196-211`, `:223-229` 이번 변경으로 foreground·저전력 대기 | **고침(이번 작업)** |
| MED | 크로스페이드 ticker가 IDLE에서도 250ms/1초 | `CrossfadeController.kt:896-903`, `:3602`, `:3609` | 남음(핵심 경로, 문서만) |
| MED | 분석 헤드 대기 250ms/40ms/100ms 폴링 | `AudioCache.kt:2376-2476` | 남음 |
| MED | 곡 버전·HQ 준비가 저전력을 보지 않음 | `SongVersionController.kt:115-160`, `NextTrackPreparation.kt:410-424` | 남음(핵심 경로) |
| MED | 다운로드 worker가 빈 큐를 100ms마다 폴링 | `DownloadService.kt:118-145`, `Downloads.kt:1792-1809` 이번 변경으로 이벤트 대기 | **고침(이번 작업)** |
| MED | 다운로드 4 worker와 분석 디코더 동시 실행 | `DownloadService.kt:294` `WORKERS=4`, `TrackAnalyzer`/`HqAudioVerify` 디코더 | 남음 |
| LOW | 영상 가사 측정이 250ms 간격으로 최대 30초 확인 | `LyricsTiming.kt:1060-1064` | 남음 |
| LOW | Presence 5분 POST | `shared/.../Presence.kt:55`, `:120` | 해당 없음(의도된 간격, 유지) |
| LOW | 다운로드 알림 250ms 갱신 | `DownloadService.kt:162`, `:280` | 해당 없음(알림 요구사항, 유지) |
| LOW | 잠금화면 시계 1분 갱신 | `LockScreenLyricsActivity.kt:460-470` foreground 게이트 | 고침(이전 작업) |
| — | WakeLock/WifiLock 없음 | 수동 `PARTIAL_WAKE_LOCK`/`WifiLock` 없음. 재생 중 ExoPlayer wake mode만 사용 `PlaybackService.kt:4020-4027` | 고침(ExoPlayer wake mode) |
| — | 진단 로그 버스트 쓰기 | `TrackLogPersistence.kt:280` `TrackLogFlushPolicy` | 고침(이전 작업) |
| — | HQ 기준 오디오 중복 디코드 | `HqReferenceCache.kt:21`, `HqAudioVerify.kt:222` | 고침(이전 작업) |
| — | 보이지 않는 캔버스 검색 | `PlayerState.kt:56-90` foreground 게이트 | 고침(이전 작업) |
| — | 메시 그라데이션이 백그라운드에서 계속 | `MeshGradient.kt:136-158` foreground·lowPower 게이트 | 고침(이전 작업) |
| — | 가사 프레임 클럭이 백그라운드에서 계속 | `PlayerLyrics.kt:752-770` foreground·isPlaying 게이트 | 고침(이전 작업) |
| — | 서비스 가사 ticker 500ms 고정 | `PlaybackService.kt:10311-10335` 줄 경계 기반, 화면 꺼짐 소비자 없으면 중지 | 고침(이전 작업) |
| — | 위치 폴링 500ms가 백그라운드에서 계속 | `PlayerConnection.kt:439-451` foreground·isPlaying 게이트 | 고침(이전 작업) |
| — | `setWakeMode` 없음 | `PlaybackService.kt:4006`, `:4020-4027` 로컬/네트워크/없음 | 고침(이전 작업) |

## 2. 네 가지 상태에서 무엇이 도는가

상태: 화면 켬/재생, 화면 켬/정지, 화면 끔/재생, 화면 끔/정지.
“도는가”는 코드상 시작 조건이 성립할 때를 뜻하며, 실제 기기 측정값이 아니다.

| 작업 | 화면 켬·재생 | 화면 켬·정지 | 화면 끔·재생 | 화면 끔·정지 | 근거 |
|---|---|---|---|---|---|
| 세션 재생 디코더 | 예(재생) | 아니오 | 예(재생) | 아니오 | `PlaybackService.kt:4020-4027` wake mode도 재생 중에만 |
| 크로스페이드 standby 디코더 | 전환 중만 | 아니오 | 전환 중만 | 아니오 | `CrossfadeController.kt:896-903` |
| AutoMix 분석 디코더 | 예(저전력·파티 제외) | 아니오(ticker 대기) | 예(저전력·파티 제외) | 아니오 | `TrackAnalyzer.kt:117-120`, `:334` |
| HQ 검증/기준 WAV 디코더 | 예(검증 중) | 제한적(준비 단계) | 아니오 | 아니오 | `VideoAudio.kt:63,99,139,176` 화면 게이트 |
| 곡 버전 정렬 디코더 | 예(측정 중) | 제한적 | 아니오 | 아니오 | `VersionAudioAligner.kt:169,202,211` 화면 게이트 |
| 가사 타이밍 측정 디코더 | 예(측정 중) | 예(로드된 경우) | 아니오(측정 중단) | 아니오 | `LyricsTiming.kt:1167,1180,1201` |
| 가사 자막 ticker | 줄 경계마다 | 아니오 | 잠금화면 가사/외부 컨트롤러가 있을 때만 | 아니오 | `PlaybackService.kt:10311-10355` |
| 가사 프레임 클럭/파티클 | 예(60fps) | 아니오 | 아니오 | 아니오 | `PlayerLyrics.kt:752-770` |
| 캔버스 영상 디코더 | 예 | 예(포그라운드 유지) | 아니오 | 아니오 | `CanvasArtworkPlayer.kt:289-292` |
| 메시 그라데이션 | 예(재생 중 continuous) | 아니오 | 아니오 | 아니오 | `MeshGradient.kt:136-158`, `HomeScreen.kt:1147` |
| 재생 진행 샘플러 | 5초 | 아니오 | 5초 | 아니오 | `PlaybackService.kt:8606-8620` |
| 크로스페이드 ticker | 250ms | 이벤트 대기 | 1초 | 이벤트 대기 | `CrossfadeController.kt:896-903` |
| 파티 동기화 ticker | 700ms(파티 재생) | 아니오 | 700ms(파티 재생) | 아니오 | `PartySync.kt:235`, `BatteryPolicy.partyTickerActive` |
| 파티 ping/report | 15초/10초(연결 중) | 15초/10초(연결 중) | 15초/10초(연결 중) | 15초/10초(연결 중) | `ListenTogether.kt:1333-1359` |
| 파티 health poll | 10초(화면+파티+소켓 없음) | 10초(같은 조건) | 아니오 | 아니오 | `ListenTogether.kt:489-506`, `BatteryPolicy.healthMonitorActive` |
| 다운로드 worker | 큐가 있으면 예 | 큐가 있으면 예 | 큐가 있으면 예 | 큐가 있으면 예 | `DownloadService.kt:118-145`; 빈 큐는 이제 이벤트 대기 |
| HQ 자동 패스 | 아니오(재생 중 회피) | 예(포그라운드) | 아니오 | 아니오 | `HqLibraryUpgrade.kt:586-602` foreground·PlaybackActivity 게이트 |
| MV 소리 복구 | 예(포그라운드) | 예(포그라운드) | 아니오(이번에 background cancel) | 아니오 | `BitChordApplication.kt:234-241` |
| 아티스트 백필 | 예(저전력 제외) | 예(저전력 제외) | 아니오 | 아니오 | `ArtistFacts.kt:196-211`, `:223-229` |
| 가사 재측정 루프 | 8초(최대 15분) | 8초(최대 15분) | 8초(최대 15분) | 8초(최대 15분) | `LyricsController.kt:784-796` (아래 MED) |
| 다음 곡 바이트 선반입 | 예(비미터드·저전력 제외) | 아니오 | 예(비미터드·저전력 제외) | 아니오 | `AudioCache.kt:1704-1745` |
| 다음 곡 URL 워밍업 | 예 | 아니오 | 예(저전력에서도) | 아니오 | `AudioCache.kt:1753-1765` (아래 MED) |
| 업데이트 확인 | 하루 1회(포그라운드 시작) | — | 아니오 | 아니오 | `AppUpdateChecker.kt:75-85` |
| 위젯 갱신 | 이벤트만(`updatePeriodMillis=0`) | 이벤트만 | 이벤트만 | 이벤트만 | `res/xml/widget_media_*.xml` |
| 위치·센서 | 없음 | 없음 | 없음 | 없음 | 앱 전체 grep |

## 3. 새로 찾은 문제 (이번에 고치지 않은 것 포함)

| 심각도 | 파일:줄 | 증거(코드) | 사용자 영향 | 수정안 |
|---|---|---|---|---|
| MED | `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:784-796` | 가사 로드 뒤 `while (attempts < 10 && now < giveUpAt)` 안에서 `delay(REMEASURE_CHECK_MS)`(8초). `LyricsTiming.readFeatures`는 화면 꺼짐이면 `return null`(`LyricsTiming.kt:1167,1201`)이라 측정이 진행되지 않는데도 루프는 최대 15분(`MEASURE_WINDOW_MS`) 계속된다. | 가사를 연 채 화면을 끄고 재생하면 8초마다 최대 15분(약 112회) 코루틴이 깨어나 디스크·캐시를 다시 확인한다. | `PowerState`에 화면 상태 `StateFlow`를 추가하고, 이 루프를 `screenOn`일 때만 돌리며 화면이 켜지는 이벤트에 다시 시작한다. 가사 싱크 계산 자체는 건드리지 않는다. |
| MED | `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:243-253`, `:586-602`, `:982-994`, `:1128-1136` | 실패 기록이 없으면 `scheduleIfEnabled`가 매 foreground/재생 전환마다 `ExistingWorkPolicy.KEEP`로 worker를 넣는다. worker는 `NetworkType.CONNECTED` 제약이라 네트워크가 늦게 돌아오면 백그라운드에서 프로세스를 깨울 수 있고, `runOne`은 `!AppForeground.isForeground`이면 즉시 반환한다(`:590`). | 사용자 조작 없이 프로세스가 시작되는 경로 중 하나(10-29 로그 9회). 깨어난 뒤 할 일이 없어도 프로세스 시작 비용은 이미 지불된다. | 계획/판정에 실제 대상이 있을 때만 enqueue하고, background 진입 시 unique work를 취소한다(현재 Hq는 취소, MV는 이번에 추가). `hasRetryableWork`를 실패 유무와 무관하게 먼저 보는 비용은 별도 캐시로 줄인다. |
| MED | `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:228`, `:586-602` | `runAllowed()`는 `enabled() || manualPass`만 보고, `runOne`도 미터드 여부만 본다. `AppSettings.lowPower`/`heavyWorkAllowed` 검사가 없다. 자동 HQ 대체·선반입은 기본 켜짐(`AppSettings.kt:278-281`). | 절전 모드에서도 큰 파일을 자동으로 내려받고 검증·디코드를 한다. 셀룰러에서는 사용자가 “Wi-Fi만”을 꺼 둔 경우 큰 다운로드가 나간다. | 자동 패스는 `manualPass || !AppSettings.lowPower`일 때만 허용하고, 저전력 해제 이벤트(`AppSettings.lowPower.first { !it }`)에서 `scheduleIfEnabled`를 다시 호출한다. 수동 “모두 다시 받기”는 예외. |
| MED | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1753-1765` | `cacheBytes`가 false인 저전력에서도 `urlLookahead = if (metered) 1 else QUEUE_LOOKAHEAD + 1`이고, `drop(1)`도 하지 않아 다음 곡 이후 URL을 계속 해석한다. | 저전력 재생 중 곡마다 최대 2개의 추가 스트림 해석 요청이 나간다. 바이트보다 작지만 라디오/네트워크 wake는 남는다. | 저전력이면 `urlLookahead`를 1로 제한한다. URL 워밍업 자체는 유지한다(전환 지연 방지). |
| MED | `app/src/main/java/com/music/bitchord/playback/SongVersionController.kt:115-160`, `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:410-424` | `scheduleUpcomingSongVersions`는 `preferMusicOnly`와 파티만 보고, `hqWanted`는 `heavyWorkAllowed`를 보지 않는다. `NextTrackPreparation`의 HQ 단계도 저전력 게이트가 없다. | 저전력에서 곡마다 앞뒤 최대 4행의 버전 조회와 HQ 대기가 실행된다. | `heavyWorkAllowed`가 false면 버전/HQ 단계를 다음 재생으로 미룬다. 곡 해석 핵심 경로라 폰 검증 후 별도 작업. |
| MED | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:2376-2476` | `awaitAnalysisHead`는 다른 writer를 기다릴 때 `delay(250)`, 일반 루프에서 `delay(40)`, `awaitAnalysisTrack`은 `delay(100)`으로 상태를 폴링한다. | AutoMix·가사 타이밍이 같은 곡의 헤드 바이트를 기다리는 동안 IO 스레드가 초당 최대 25회 깨어난다. | 바이트 임계치 도달과 `analysisHeadsInFlight` 해제를 `CompletableDeferred`/이벤트로 알린다. |
| MED | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:896-903` | `Phase.IDLE`에서 `active().isPlaying`이면 화면 켬 250ms, 화면 끔 1000ms로 계속 tick한다. 전환이 불가능한 설정(스마트 페이드 끔, 크로스페이드 0)에서도 같다. | 화면 끔 재생 1분에 60회, 화면 켬 240회 코루틴이 깨어나 플랜을 재평가한다. 이전 측정 7~8초/분의 주요 잔여 후보다. | 다음 전환 예정 시각까지의 timeout으로 바꾸고, 전환이 불가능하면 `tickerWake`만 기다린다. 재생 핵심 경로라 이번에는 문서만. |
| MED | `app/src/main/java/com/music/bitchord/download/DownloadService.kt:294`, `app/src/main/java/com/music/bitchord/playback/smart/AudioDecoder.kt` | 다운로드 4 worker와 AutoMix/HQ 분석이 동시에 실행될 수 있고, 디코드만 전역 세마포어로 직렬화된다. | 다운로드 중 곡을 넘기면 4개 worker와 분석 디코더가 같은 시간대에 CPU/네트워크를 쓴다. | 다운로드가 활성일 때 백그라운드 분석을 이벤트로 잠시 멈추거나 worker 수를 줄인다. 다운로드 속도와 상충하므로 별도 결정. |
| LOW | `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:131`, `:159`, `:237-255` | 판정이 저장될 때마다 최대 2,000개 항목을 문자열로 다시 만들어 prefs 전체를 쓴다. | 같은 곡의 후보 여러 개를 검증하면 쓰기가 겹친다. | `StoredVerdictCache`처럼 1~2초 디바운스한다. |
| LOW | `app/src/main/java/com/music/bitchord/data/listentogether/ListenTogether.kt:1333-1359` | 파티 소켓이 연결된 동안 `pingLoop` 15초, `reportLoop` 10초가 화면·재생 상태와 무관하게 계속된다. | 파티에 참여한 채 화면을 끄면 분당 약 10회 전송이 남는다. | 파티 동기화·멤버 유지에 필요하므로 유지. 배터리 세이버에서만 간격을 늘리는 정책은 별도 결정. |
| LOW | `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1060-1064` | `awaitVideoOpening`이 250ms 간격으로 최대 `VIDEO_TIMEOUT_MS`까지 캐시를 확인한다. | 영상/커버 가사 측정에서 오디오가 늦게 도착하면 수십 회 확인한다. | 캐시 바이트 증가 이벤트로 대체한다. |
| LOW | `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:103-105` | `init()`이 프로세스 시작마다 verdict 파일 전체를 읽는다. | WorkManager 등으로 프로세스가 깨어날 때마다 파일 읽기가 한 번 더 발생한다. | 위 MED(프로세스 깨우기)와 함께 처리. |

## 4. 이번 변경 내용과 조건 진리표

변경 파일은 아래 8개다. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는
수정하지 않았다.

- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/BatteryPolicy.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/BatteryPolicyTest.kt`
- `app/src/main/java/com/music/bitchord/startup/AppForeground.kt`
- `app/src/main/java/com/music/bitchord/data/stats/ArtistFacts.kt`
- `app/src/main/java/com/music/bitchord/download/Downloads.kt`
- `app/src/main/java/com/music/bitchord/download/DownloadService.kt`
- `app/src/main/java/com/music/bitchord/BitChordApplication.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadWorkWakeTest.kt`

### 4-1. 아티스트 백필을 foreground·저전력 이벤트로 대기

- 이전: `worker()`가 `requests`에서 이름을 꺼내면 곧바로 1.5초 간격으로 조회했다.
  화면 꺼짐·저전력과 무관했다.
- 지금: 각 이름을 처리하기 전에 `AppSettings.lowPower`와
  `AppForeground.foreground`를 `combine(...).first { ... }`로 기다린다. 타이머가
  아니라 두 StateFlow의 변화가 깨우는 방식이다. 요청은 `requests`(UNLIMITED)에
  그대로 남아 foreground·저전력 해제 때 이어서 처리된다.
- `AppForeground`에는 기존 `isForeground`와 같은 값을 내는 `foreground: StateFlow<Boolean>`만
  추가했다. 기존 리스너·동작은 그대로다.

| lowPower | foreground | 기존 동작 | 새 동작 |
|---|---|---|---|
| false | false | 조회함 | **대기** |
| false | true | 조회함 | 조회함 |
| true | false | 조회함 | **대기** |
| true | true | 조회함 | **대기** |

기존에 조회하던 세 경우 중 `false/true`만 남고, 나머지는 다음 이벤트까지 미룬다.
저전력 해제나 foreground 복귀가 오면 별도 타이머 없이 재개한다.

### 4-2. 다운로드 worker의 빈 큐 폴링을 큐 revision 대기로 교체

- 이전: `work()`가 빈 큐에서 `delay(100)` 후 `idleFor += 100`을 반복했다.
  worker 4개가 최대 2초 동안 각각 20회, 합계 80회 깨어났다.
- 지금: `Downloads`에 `workRevision: MutableStateFlow<Long>`을 두고
  `enqueue`·`cancel`·`abandonPending`·`onIdle`·`onStopped`에서 증가시킨다.
  worker는 대기 전에 revision을 읽고, 큐가 비면
  `awaitWorkChange(seen, 남은 grace)` 또는 `awaitWorkChange(seen)`로 잔다.
  revision은 모든 worker가 같은 값을 보는 broadcast라 한 worker가 소비해
  다른 worker가 굶지 않는다.
- 정지 조건 자체는 바꾸지 않았다. 기존 `idleFor >= 2,000 && !busy`를
  `BatteryPolicy.downloadWorkerShouldStop(idleFor, graceMs, busy)`로 그대로 옮겼다.

| idleFor >= grace | busy | 기존 정지 | 새 정지 |
|---|---|---|---|
| false | false | 아니오 | 아니오 |
| false | true | 아니오 | 아니오 |
| true | false | 예 | 예 |
| true | true | 아니오 | 아니오 |

즉 정지 진리표는 동일하고, 깨어나는 방식만 100ms 타이머에서 이벤트 대기로 바뀌었다.
시작 직후 batch가 채워지는 2초 grace는 그대로 유지한다.

### 4-3. MV 소리 복구 worker를 background에서 취소

- 이전: `AppForeground.onBackground`가 `HqLibraryUpgrade`만 취소했다.
  `MvSoundRedownload`는 포그라운드에서 enqueue된 뒤 네트워크 제약으로 늦게
  실행되면 백그라운드에서 프로세스를 깨울 수 있었다.
- 지금: 같은 background 이벤트에서 `MvSoundRedownload.cancel(this)`도 호출한다.
  작업은 다음 foreground 이벤트(`BitChordApplication.kt:229-233`)에서 다시
  예약되며, 이미 큐에 들어간 다운로드는 `DownloadService`가 계속 처리한다.
  `MvSoundRedownload`의 기존 `APPEND_OR_REPLACE` 정책과 worker의
  `AppForeground` 검사는 그대로다.

## 5. 테스트

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.BatteryPolicyTest'` 통과.
  - 추가: `artist backfill runs only on screen and out of low power`
  - 추가: `a download worker stops only after the grace with nothing in flight`
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.DownloadWorkWakeTest'` 통과.
  - 추가: `a queue change wakes every idle worker`
  - 추가: `waiting times out when the queue does not change`
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.MvSoundRedownloadPolicyTest' --tests 'com.music.bitchord.download.DownloadsAbandonPendingTest'` 통과.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.

## 6. 남은 확인

- 화면 꺼짐 재생 CPU 목표(7~8초/분 이하)는 이번에 기기 측정을 하지 않았다.
  코드상 잔여 wake 후보는 크로스페이드 ticker(화면 끔 재생 60회/분),
  파티 ping/report(참여 시 분당 약 10회), 가사 재측정 루프(8초 간격)다.
  앞의 둘은 이번 범위에서 고치지 않았고, 크로스페이드는 재생 핵심 경로라
  폰 검증이 필요하다.
- 아티스트 백필을 foreground로 제한했으므로, 백그라운드에서 재생만 하다가
  Replay를 열면 그 시점에 백필이 시작된다. 캐시된 값은 즉시 보인다.
- WorkManager가 백그라운드에서 프로세스를 깨우는 경로는 Hq 자동 패스에 남아
  있다. 계획/판정이 비었을 때 enqueue를 생략하는 최적화는 별도 작업으로
  남겼다(위 MED).
