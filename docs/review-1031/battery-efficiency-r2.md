# 배터리 효율 후속 검토 (review-battery-1031-r2) — 2026-10-30

기준 브랜치: `task/fix-1030` (현재 워크스페이스 HEAD = 이전 `review-battery-1031` 결과).
방식: `docs/review-1031/battery-efficiency.md`, `docs/audit/audit-battery.md`,
`docs/AUDIT_PERFORMANCE.md`를 먼저 읽고 현재 코드의 `파일:줄`과 대조했다. 기기
프로파일링은 하지 않았고, 빈도는 코드 상수·조건에서만 읽었다. 이번에는 이전
검증 지적 2건과 이전 문서가 남긴 HIGH/MED 중 원인이 분명하고 한두 파일 안에서
끝나는 항목을 고쳤다. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스
페이드)는 건드리지 않았다.

## 1. 이전 검증 지적 처리

| # | 심각도 | 지적 | 현재 코드 근거 | 처리 |
|---|---|---|---|---|
| 1 | MED | `MvSoundRedownload.cancel`이 background 전환마다 `cancelUniqueWork`를 호출해 실행 중인 복구 패스를 취소하고, 큰 보관함이 매번 처음부터 다시 시작된다. 이전 HIGH(패스가 끝나지 않음)를 되살린다. | `MvSoundRedownload.kt:165-185` `pause()`는 `ENQUEUED`/`BLOCKED`만 `cancelWorkById`로 지우고 `RUNNING`은 남긴다. `BitChordApplication.kt:243`이 `cancel` 대신 `pause`를 호출한다. | **고침** |
| 2 | LOW | 새 취소가 `onBackground`에만 있어, 프로세스 사망/재부팅 뒤 저장된 MV 작업이 background에서 프로세스를 깨울 수 있다. 시작 시 `scheduleIfEnabled`가 일찍 반환하며 취소하지 않는다. | `BitChordApplication.kt:247-251`이 foreground가 아니면 `pause()`를 먼저 호출한 뒤 `scheduleIfEnabled`를 호출한다. `pause()`는 실행 중 패스는 남기고 대기 작업만 지운다. | **고침** |

### 1-1. MV 복구 취소 조건 진리표

| WorkManager 상태 | 이전 `cancelUniqueWork` | 새 `pause()` |
|---|---|---|
| RUNNING | 취소됨(패스 중단) | **유지**(끝까지 진행) |
| ENQUEUED | 취소됨 | 취소됨 |
| BLOCKED | 취소됨 | 취소됨 |
| SUCCEEDED / FAILED / CANCELLED | 변화 없음 | 변화 없음 |

foreground에서 `scheduleIfEnabled`가 새 작업을 넣을 때 `pauseGeneration`을
올려, 아직 조회 중이던 pause가 새 foreground 작업을 취소하지 않게 했다
(`MvSoundRedownload.kt:140-146`, `:166-176`). 사용자가 설정을 끌 때 쓰는
`cancel()`은 기존처럼 전체 취소다(설정 해제 의도, `SettingsSheet.kt:744`).

### 1-2. 가사 재측정 루프 조건 진리표

이전 문서 MED(`LyricsController.kt:784-796`)도 이번에 고쳤다.

| 화면 | 이전 | 새 |
|---|---|---|
| 켜짐 | 8초마다 측정/확인, 최대 10회·15분 | 같음 |
| 꺼짐 | 8초마다 깨어나 `LyricsTiming`이 `null`만 반환 | **화면 켜짐 이벤트까지 suspend**, 타이머 없음 |

`PowerState.screen: StateFlow<Boolean>`을 추가하고(`PowerState.kt:30-33`,
`:41-45`), 루프가 `PowerState.screen.first { it }`로 화면 켜짐을 기다린다
(`LyricsController.kt:791-795`). 벽시계 `MEASURE_WINDOW_MS` 상한은 그대로라
화면이 다시 켜졌을 때 이미 15분이 지났으면 루프를 끝낸다.

## 2. 이전 감사 HIGH/MED가 지금 코드에서 유지되는지

| 이전 심각도 | 항목 | 현재 근거 | 판정 |
|---|---|---|---|
| HIGH | AutoMix 분석이 저전력을 무시 | `TrackAnalyzer.kt:117-120`, `:334` `BatteryPolicy.automixAnalysisActive` | 고침 유지 |
| HIGH | 다음 곡 전체 선반입이 저전력을 무시 | `AudioCache.kt:1704-1719` `cachePrefetchBytesAllowed` 재확인 | 고침 유지 |
| HIGH | MV 소리 복구 `REPLACE` | `MvSoundRedownload.kt:116` `APPEND_OR_REPLACE` | 고침 유지 |
| MED | 아티스트 백필 1.5초 네트워크 | `ArtistFacts.kt:196-211`, `:223-229` foreground·저전력 대기 | 고침 유지 |
| MED | 다운로드 worker 빈 큐 100ms 폴링 | `DownloadService.kt:118-145`, `Downloads.kt:1792-1809` revision 대기 | 고침 유지 |
| MED | 가사 재측정 루프가 화면 꺼짐에도 8초 | `LyricsController.kt:791-795` 화면 이벤트 대기 | **고침(이번 작업)** |
| MED | MV background 취소가 실행 중 패스 취소 | `MvSoundRedownload.kt:165-185` `RUNNING` 유지 | **고침(이번 작업)** |
| MED | Hq 자동 패스가 대상 없이도 foreground/재생마다 enqueue | `HqLibraryUpgrade.kt:231-258` 실패 없으면 `KEEP` enqueue, `:586-602` worker가 빈 계획이면 종료 | 남음(아래 3절) |
| MED | Hq 자동 패스가 저전력을 보지 않음 | `HqLibraryUpgrade.kt:228`, `:586-602`, `:1134` `runAllowed`/미터드만 확인 | 남음(고음질 검증 예외, 아래 3절) |
| MED | 저전력에서 다음 곡 URL lookahead 계속 | `AudioCache.kt:1753-1765` `urlLookahead`가 저전력을 보지 않음 | 남음(곡 해석 핵심 경로, 폰 검증 필요) |
| MED | 곡 버전·HQ 준비가 저전력을 보지 않음 | `SongVersionController.kt:115`, `NextTrackPreparation.kt:188`, `:410-424` | 남음(곡 해석 핵심 경로, 폰 검증 필요) |
| MED | 분석 헤드 대기 250ms/40ms/100ms 폴링 | `AudioCache.kt:2376-2476` | 남음(재생 핵심 경로, 폰 검증 필요) |
| MED | 크로스페이드 ticker가 IDLE에서도 250ms/1초 | `CrossfadeController.kt:896-903` | 남음(크로스페이드 핵심 경로, 폰 검증 필요) |
| MED | 다운로드 4 worker와 분석 디코더 동시 실행 | `DownloadService.kt:294` `WORKERS=4`, `TrackAnalyzer`/`HqAudioVerify` | 남음(다운로드 속도와 상충, 별도 결정) |
| LOW | Hq 판정 저장이 prefs 전체를 다시 씀 | `HqVerificationStore.kt` | 남음 |
| LOW | 파티 ping/report가 화면 꺼짐에도 계속 | `ListenTogether.kt:1333-1359` | 해당 없음(파티 유지에 필요, 유지) |
| LOW | 영상 가사 측정 250ms 확인 | `LyricsTiming.kt:1060-1064` | 남음 |
| LOW | HqVerificationStore.init이 시작마다 파일 전체 읽기 | `HqVerificationStore.kt:103-105` | 남음 |

## 3. 새로 찾은 문제

이번에 바꾼 경로에서 새 HIGH/MED는 확인하지 못했다. 아래는 이전 문서에서
남긴 항목 중 이번에 고치지 않은 이유다.

| 심각도 | 파일:줄 | 증거(코드) | 사용자 영향 | 수정안 / 보류 이유 |
|---|---|---|---|---|
| MED | `HqLibraryUpgrade.kt:231-258`, `:586-602` | 실패 기록이 없으면 `scheduleIfEnabled`가 foreground/재생 전환마다 `KEEP`으로 worker를 넣는다. worker는 `CONNECTED` 제약이라 늦게 네트워크가 돌아오면 background에서 프로세스를 깨우고, `runOne`은 `!AppForeground.isForeground`이면 즉시 반환한다. | 10-29 로그의 무조작 프로세스 시작 경로 중 하나. 깨어나도 할 일이 없으면 프로세스 시작 비용만 낸다. | 대상이 있을 때만 enqueue하려면 `hasRetryableWork`를 실패 유무와 무관하게 먼저 봐야 하고, 이는 verdict 저장소 읽기다. 별도 캐시/이벤트가 필요해 이번 범위에서 보류. background 진입 시 취소는 이미 있다(`:270-278`). |
| MED | `HqLibraryUpgrade.kt:228`, `:586-602`, `:1134` | 자동 HQ 대체·선반입이 기본 켜짐인데 `runAllowed()`는 `enabled() || manualPass`만 보고, `runOne`도 미터드 여부만 본다. `AppSettings.lowPower`/`heavyWorkAllowed` 검사가 없다. | 절전 모드에서도 큰 파일을 자동으로 내려받고 검증·디코드한다. | 고음질 검증 경로라 이번에는 보류(요청의 예외 조건). 자동 패스는 `manualPass || !AppSettings.lowPower`일 때만 허용하고 저전력 해제 이벤트에서 재예약하는 안을 폰 검증 후 적용. |
| MED | `AudioCache.kt:1753-1765` | `cacheBytes`가 false인 저전력에서도 `urlLookahead = if (metered) 1 else QUEUE_LOOKAHEAD + 1`이고 `drop(1)`도 하지 않아 다음 곡 이후 URL을 계속 해석한다. | 저전력 재생 중 곡마다 최대 2개의 추가 스트림 해석 요청이 나간다. | 저전력이면 `urlLookahead`를 1로 제한. 곡 해석 핵심 경로라 문서만 남기고 폰 검증 후 적용. |
| MED | `SongVersionController.kt:115-160`, `NextTrackPreparation.kt:188`, `:410-424` | `scheduleUpcomingSongVersions`는 `preferMusicOnly`와 파티만 보고, `hqWanted`는 `heavyWorkAllowed`를 보지 않는다. | 저전력에서 곡마다 앞뒤 최대 4행의 버전 조회와 HQ 대기가 실행된다. | `heavyWorkAllowed`가 false면 다음 재생으로 미룸. 노래 버전 핵심 경로라 폰 검증 필요. |
| MED | `AudioCache.kt:2376-2476` | `awaitAnalysisHead`는 다른 writer를 기다릴 때 `delay(250)`, 일반 루프에서 `delay(40)`, `awaitAnalysisTrack`은 `delay(100)`으로 상태를 폴링한다. | AutoMix·가사 타이밍이 같은 곡의 헤드 바이트를 기다리는 동안 IO 스레드가 초당 최대 25회 깨어난다. | 바이트 임계치·`analysisHeadsInFlight` 해제를 `CompletableDeferred`/이벤트로 알림. 재생 핵심 경로라 폰 검증 필요. |
| MED | `CrossfadeController.kt:896-903` | `Phase.IDLE`에서 `active().isPlaying`이면 화면 켬 250ms, 화면 끔 1000ms로 계속 tick한다. 전환이 불가능한 설정에서도 같다. | 화면 끔 재생 1분에 60회, 화면 켬 240회 코루틴이 깨어난다. 이전 측정 7~8초/분의 주요 잔여 후보. | 다음 전환 예정 시각까지의 timeout으로 바꾸고, 전환이 불가능하면 `tickerWake`만 기다린다. 크로스페이드 핵심 경로라 폰 검증 필요. |
| MED | `DownloadService.kt:294` | 다운로드 4 worker와 AutoMix/HQ 분석이 동시에 실행될 수 있고, 디코드만 전역 세마포어로 직렬화된다. | 다운로드 중 곡을 넘기면 4개 worker와 분석 디코더가 같은 시간대에 CPU/네트워크를 쓴다. | 다운로드 활성 시 백그라운드 분석을 이벤트로 잠시 멈추거나 worker 수를 줄인다. 다운로드 속도와 상충해 별도 결정. |

## 4. 네 가지 상태에서 바뀐 항목

| 작업 | 화면 켬·재생 | 화면 켬·정지 | 화면 끔·재생 | 화면 끔·정지 | 근거 |
|---|---|---|---|---|---|
| MV 소리 복구(대기 작업) | foreground에서 예약 | foreground에서 예약 | 시작 안 함(대기 작업 취소) | 시작 안 함 | `MvSoundRedownload.kt:127-146`, `:165-185` |
| MV 소리 복구(실행 중 패스) | 계속 | 계속 | 현재 패스만 끝까지, 뒤 작업은 취소 | 현재 패스만 끝까지 | 같은 곳, `RUNNING` 유지 |
| 가사 재측정 루프 | 8초 간격(측정 전) | 8초 간격(로드된 경우) | 화면 켜짐 이벤트까지 suspend | suspend | `LyricsController.kt:787-802` |
| 나머지(디코더·ticker·선반입 등) | 이전 문서 표와 같음 | 같음 | 같음 | 같음 | `docs/review-1031/battery-efficiency.md` 2절 |

## 5. 테스트

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.MvSoundRedownloadPolicyTest' --tests 'com.music.bitchord.playback.PowerStateScreenTest' --console=plain -q` 통과.
  - `MvSoundRedownloadPolicyTest`: `leaving the app leaves a running repair alone`, `leaving the app drops work that has not started`, `finished work is not cancelled again` 추가.
  - `PowerStateScreenTest`: `work waiting for the screen resumes on the screen-on event` 추가.
- 기존 `DownloadWorkWakeTest`, `DownloadsAbandonPendingTest`, `MvSoundRedownloadPolicyTest`의 정책 단언은 그대로 통과.

## 6. 남은 확인

- 화면 꺼짐 재생 CPU 목표(7~8초/분 이하)는 이번에도 기기 측정을 하지 않았다.
  코드상 잔여 wake 후보는 크로스페이드 ticker(화면 끔 재생 60회/분),
  파티 ping/report(참여 시 분당 약 10회), Hq 자동 패스 enqueue다.
- 크로스페이드·곡 해석·노래 버전·가사 싱크 계산·분석 헤드 대기는 재생 핵심
  경로라 이번에 수정하지 않았다. 위 3절에 근거와 수정안만 남겼다.
- Hq 자동 패스의 저전력 게이트는 고음질 검증 예외로 보류했다. 사용자가
  “모두 다시 받기”를 누른 수동 패스는 계속 예외로 둬야 한다.
