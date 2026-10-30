# 배터리 효율 검토 3차 (review-battery-1031-r3b) — 2026-10-30

기준 브랜치: `task/fix-1030` (현재 워크스페이스 HEAD = 이전 `review-battery-1031-r2` 결과).
방식: `docs/audit/audit-battery.md`, `docs/AUDIT_PERFORMANCE.md`,
`docs/review-1031/battery-efficiency.md`, `docs/review-1031/battery-efficiency-r2.md`를
먼저 읽고 현재 코드의 `파일:줄`과 대조했다. 기기 프로파일링은 하지 않았고, 빈도는
코드 상수·조건에서만 읽었다. 이번에는 이전 검증 지적 3건을 고쳤다. 재생 핵심
경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 수정하지 않았고, 아래
표에만 남겼다.

## 1. 이전 검증 지적 처리

| # | 심각도 | 지적 | 현재 코드 근거 | 처리 |
|---|---|---|---|---|
| 1 | MED | BLOCKED 후속 작업을 취소하면 고유 작업 체인에 CANCELLED 잎이 남고, 다음 `APPEND_OR_REPLACE`가 실행 중인 패스까지 포함한 체인 전체를 교체해 foreground 복귀 때 스캔이 다시 시작된다. | `MvSoundRedownload.kt:172-196` `pause()`가 취소 직전에 각 작업 상태를 다시 읽고 `cancelTargets()`에 넘긴다. `:211-221`은 체인에 `RUNNING`이 하나라도 있으면 아무것도 취소하지 않는다. `RUNNING`이 없을 때만 ENQUEUED/BLOCKED를 취소하므로 CANCELLED 잎이 실행 중 패스 위에 생기지 않는다. | **고침** |
| 2 | LOW | ENQUEUED/BLOCKED 상태를 한 번 읽고 나중에 취소하므로, 그 사이 RUNNING으로 바뀐 작업을 패스 중간에 취소할 수 있다. | `MvSoundRedownload.kt:181-190` 목록을 읽은 뒤 `getWorkInfoById(...).get()?.state`로 각 작업을 취소 직전에 다시 읽는다. `:211-221`은 다시 읽은 상태가 RUNNING이면 체인 전체를 건드리지 않고, null(사라짐)이나 그 밖의 상태도 취소 대상에서 뺀다. | **고침** |
| 3 | MED | 가사 재측정 루프의 `giveUpAt` 검사가 화면 대기 전에만 평가되어, 15분이 지난 뒤 화면이 켜져도 측정을 한 번 더 실행한다. | `LyricsController.kt:787-791` 루프 조건을 `lyricsMeasurementAllowed()`로 바꾸고, `:800-809` 화면 켜짐 대기에서 깨어난 직후 같은 함수로 시각·횟수·측정 여부를 다시 검사해 `break`한다. 함수는 `:1225-1231`에 있다. | **고침** |

### 1-1. MV 복구 취소 조건 진리표

`cancelTargets()`는 취소 직전에 다시 읽은 체인 상태를 받는다.

| 체인 상태(취소 직전) | 이전 `pause()` | 새 `cancelTargets()` |
|---|---|---|
| RUNNING + ENQUEUED/BLOCKED | RUNNING은 유지, 후속 작업 취소 → CANCELLED 잎 생성 | **아무것도 취소하지 않음** |
| ENQUEUED/BLOCKED만 | 취소 | 취소 |
| RUNNING만 | 유지 | 유지 |
| SUCCEEDED/FAILED/CANCELLED/null | 변화 없음 | 취소하지 않음 |

후속 작업이 남아 있으면 실행 중 패스가 끝난 뒤 네트워크 제약이 풀릴 때
한 번 깨어날 수 있다. `MvSoundRedownloadWorker.doWork()`가
`!AppForeground.isForeground`이면 즉시 `Result.success()`로 끝나므로 스캔이나
다운로드는 하지 않는다(`MvSoundRedownload.kt:232-242`). 패스를 중간에 취소해
처음부터 다시 시작하던 회귀보다 비용이 작다.

### 1-2. 가사 재측정 상한 진리표

| 화면 | 대기 전 | 화면 켜짐 직후 | 새 동작 |
|---|---|---|---|
| 켜짐 | 시각 < `giveUpAt`, 시도 < 10, 미측정 | — | 측정/확인 후 8초 대기 |
| 꺼짐 | 시각 < `giveUpAt` | 시각 < `giveUpAt` | 화면 켜짐 이벤트 뒤 측정 |
| 꺼짐 | 시각 < `giveUpAt` | 시각 >= `giveUpAt` | **루프 종료, 측정 없음** |
| 꺼짐 | 시각 >= `giveUpAt` | — | 루프 진입 안 함 |

## 2. 이전 감사 문서 항목이 지금 코드에서 고쳐졌는지

| 이전 심각도 | 항목 | 현재 코드 근거 | 판정 |
|---|---|---|---|
| HIGH | AutoMix 분석이 저전력을 무시 | `TrackAnalyzer.kt:117-120` `BatteryPolicy.automixAnalysisActive` | 고침 유지 |
| HIGH | 다음 곡 전체 선반입이 저전력을 무시 | `AudioCache.kt:1704-1719` `cachePrefetchBytesAllowed` 재확인 | 고침 유지 |
| HIGH | MV 소리 복구 `REPLACE` | `MvSoundRedownload.kt:118` `APPEND_OR_REPLACE` | 고침 유지 |
| MED | 아티스트 백필 1.5초 네트워크 | `ArtistFacts.kt:224` `combine(lowPower, foreground)` 대기 | 고침 유지 |
| MED | 다운로드 worker 빈 큐 100ms 폴링 | `DownloadService.kt:118-145`, `Downloads.kt:1796-1809` revision 대기 | 고침 유지 |
| MED | 가사 재측정 루프가 화면 꺼짐에도 8초 | `LyricsController.kt:787-811` 화면 이벤트 대기 + 상한 재검사 | **고침(이번 작업)** |
| MED | MV background 취소가 실행 중 패스 취소 | `MvSoundRedownload.kt:172-196`, `:211-221` RUNNING 체인 유지 | **고침(이번 작업)** |
| MED | Hq 자동 패스가 대상 없이도 foreground/재생마다 enqueue | `HqLibraryUpgrade.kt:231-258` 실패 없으면 `KEEP` enqueue, `:591-602` worker 빈 계획 종료 | 남음 |
| MED | Hq 자동 패스가 저전력을 보지 않음 | `HqLibraryUpgrade.kt:228`, `:591-602`, `:1134` `runAllowed`/미터드만 확인 | 남음(고음질 검증 예외) |
| MED | 저전력에서 다음 곡 URL lookahead 계속 | `AudioCache.kt:1753-1756` `urlLookahead`가 저전력을 보지 않음 | 남음(곡 해석 핵심 경로) |
| MED | 곡 버전·HQ 준비가 저전력을 보지 않음 | `SongVersionController.kt:115-117`, `NextTrackPreparation.kt:188`, `:410-424` | 남음(곡 해석 핵심 경로) |
| MED | 분석 헤드 대기 250ms/40ms/100ms 폴링 | `AudioCache.kt:2376`, `:2409`, `:2451`, `:2475` | 남음(재생 핵심 경로) |
| MED | 크로스페이드 ticker가 IDLE에서도 250ms/1초 | `CrossfadeController.kt:896-903` | 남음(크로스페이드 핵심 경로) |
| MED | 다운로드 4 worker와 분석 디코더 동시 실행 | `DownloadService.kt:294` `WORKERS=4` | 남음(다운로드 속도와 상충) |
| LOW | Hq 판정 저장이 prefs 전체를 다시 씀 | `HqVerificationStore.kt:238-254` | 남음 |
| LOW | 파티 ping/report가 화면 꺼짐에도 계속 | `ListenTogether.kt:1328-1358`, 간격 `:1778`, `:1785` | 해당 없음(파티 유지에 필요) |
| LOW | 영상 가사 측정 250ms 확인 | `LyricsTiming.kt:1052-1064`, `:1240` | 남음 |
| LOW | HqVerificationStore.init이 시작마다 파일 전체 읽기 | `HqVerificationStore.kt:102-105` | 남음 |
| — | WakeLock/WifiLock | `PARTIAL_WAKE_LOCK`/`WifiLock`/`WakeLock` grep 결과 없음 | 해당 없음 |
| — | 위치·센서 | 앱 코드에 위치·센서 사용 없음 | 해당 없음 |

## 3. 새로 찾은 문제

이번 검증 지적 3건 외에 새 HIGH/MED는 확인하지 못했다. 아래 LOW는 이번
수정의 남은 비용이다.

| 심각도 | 파일:줄 | 증거(코드) | 사용자 영향 | 수정안 / 보류 이유 |
|---|---|---|---|---|
| LOW | `MvSoundRedownload.kt:172-196`, `:211-221` | 실행 중 패스가 있으면 후속 ENQUEUED/BLOCKED 작업을 취소하지 않는다. 패스가 끝난 뒤 네트워크가 돌아오면 그 작업이 프로세스를 깨울 수 있다. worker는 `:232-242`에서 background면 즉시 반환한다. | 드물게 프로세스 시작 비용 1회. 스캔·다운로드·디코더는 시작하지 않는다. | 체인 취소 회귀를 막는 대가로 수용. 완전히 없애려면 실행 중 패스 완료 이벤트에 후속 작업 취소를 붙이는 별도 작업이 필요하다. |
| MED | `HqLibraryUpgrade.kt:231-258` | 실패 기록이 없으면 `scheduleIfEnabled`가 foreground/재생 전환마다 `KEEP`으로 worker를 넣는다. worker는 `CONNECTED` 제약이라 늦게 네트워크가 돌아오면 background에서 프로세스를 깨우고, `:591-602`에서 `!AppForeground.isForeground`이면 즉시 반환한다. | 10-29 로그의 무조작 프로세스 시작 경로 중 하나. 깨어나도 할 일이 없으면 프로세스 시작 비용만 낸다. | 대상이 있을 때만 enqueue하려면 `hasRetryableWork`를 실패 유무와 무관하게 먼저 봐야 하고, 이는 verdict 저장소 읽기다. 별도 캐시/이벤트가 필요해 보류. |
| MED | `HqLibraryUpgrade.kt:228`, `:591-602`, `:1134` | 자동 HQ 대체·선반입이 기본 켜짐인데 `runAllowed()`는 `enabled() || manualPass`만 보고, `runOne`도 미터드 여부만 본다. `AppSettings.lowPower`/`heavyWorkAllowed` 검사가 없다. | 절전 모드에서도 큰 파일을 자동으로 내려받고 검증·디코드한다. | 고음질 검증 경로라 이번에는 보류. 자동 패스는 `manualPass || !AppSettings.lowPower`일 때만 허용하고 저전력 해제 이벤트에서 재예약하는 안을 폰 검증 후 적용. |
| MED | `AudioCache.kt:1753-1756` | `cacheBytes`가 false인 저전력에서도 `urlLookahead = if (metered) 1 else QUEUE_LOOKAHEAD + 1`이고 `drop(1)`도 하지 않아 다음 곡 이후 URL을 계속 해석한다. | 저전력 재생 중 곡마다 최대 2개의 추가 스트림 해석 요청이 나간다. | 저전력이면 `urlLookahead`를 1로 제한. 곡 해석 핵심 경로라 폰 검증 후 적용. |
| MED | `SongVersionController.kt:115-117`, `NextTrackPreparation.kt:188`, `:410-424` | `scheduleUpcomingSongVersions`는 `preferMusicOnly`와 파티만 보고, `hqWanted`는 `heavyWorkAllowed`를 보지 않는다. | 저전력에서 곡마다 앞뒤 최대 4행의 버전 조회와 HQ 대기가 실행된다. | `heavyWorkAllowed`가 false면 다음 재생으로 미룸. 노래 버전 핵심 경로라 폰 검증 필요. |
| MED | `AudioCache.kt:2376`, `:2409`, `:2451`, `:2475` | `awaitAnalysisHead`는 다른 writer를 기다릴 때 `delay(250)`, 일반 루프에서 `delay(40)`, `awaitAnalysisTrack`은 `delay(100)`으로 상태를 폴링한다. | AutoMix·가사 타이밍이 같은 곡의 헤드 바이트를 기다리는 동안 IO 스레드가 초당 최대 25회 깨어난다. | 바이트 임계치·`analysisHeadsInFlight` 해제를 `CompletableDeferred`/이벤트로 알림. 재생 핵심 경로라 폰 검증 필요. |
| MED | `CrossfadeController.kt:896-903` | `Phase.IDLE`에서 `active().isPlaying`이면 화면 켬 250ms, 화면 끔 1000ms로 계속 tick한다. 전환이 불가능한 설정에서도 같다. | 화면 끔 재생 1분에 60회, 화면 켬 240회 코루틴이 깨어난다. 이전 측정 7~8초/분의 주요 잔여 후보. | 다음 전환 예정 시각까지의 timeout으로 바꾸고, 전환이 불가능하면 `tickerWake`만 기다린다. 크로스페이드 핵심 경로라 폰 검증 필요. |
| MED | `DownloadService.kt:294` | 다운로드 4 worker와 AutoMix/HQ 분석이 동시에 실행될 수 있고, 디코드만 전역 세마포어로 직렬화된다. | 다운로드 중 곡을 넘기면 4개 worker와 분석 디코더가 같은 시간대에 CPU/네트워크를 쓴다. | 다운로드 활성 시 백그라운드 분석을 이벤트로 잠시 멈추거나 worker 수를 줄인다. 다운로드 속도와 상충해 별도 결정. |

## 4. 네 가지 상태에서 바뀐 항목

| 작업 | 화면 켬·재생 | 화면 켬·정지 | 화면 끔·재생 | 화면 끔·정지 | 근거 |
|---|---|---|---|---|---|
| MV 소리 복구(대기 작업) | foreground에서 예약 | foreground에서 예약 | 실행 중 패스가 없을 때만 대기 작업 취소 | 실행 중 패스가 없을 때만 대기 작업 취소 | `MvSoundRedownload.kt:172-196`, `:211-221` |
| MV 소리 복구(실행 중 패스) | 계속 | 계속 | 현재 패스 끝까지, 후속 작업은 남겨 둠(끝나면 무동작) | 현재 패스 끝까지, 후속 작업은 남겨 둠(끝나면 무동작) | 같은 곳, `:118`, `:232-242` |
| 가사 재측정 루프 | 8초 간격(측정 전) | 8초 간격(로드된 경우) | 화면 켜짐 이벤트까지 suspend, 깨어나면 상한 재검사 | suspend | `LyricsController.kt:787-811` |
| 나머지(디코더·ticker·선반입 등) | 이전 문서 표와 같음 | 같음 | 같음 | 같음 | `docs/review-1031/battery-efficiency-r2.md` 4절 |

## 5. 테스트

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.MvSoundRedownloadPolicyTest' --tests 'com.music.bitchord.ui.LyricsMeasurementWindowTest' --tests 'com.music.bitchord.playback.PowerStateScreenTest' --console=plain -q` 통과.
  - `MvSoundRedownloadPolicyTest`: `a chain with a running pass cancels nothing`, `only work still waiting to start is cancelled`, `work that started after the snapshot is not cancelled` 추가.
  - `LyricsMeasurementWindowTest`(새 파일): `a measurement is allowed inside the window and under the cap`, `a screen that comes back after the window starts no measurement`, `the attempt cap and an existing measurement still stop the loop` 추가.

## 6. 남은 확인

- 화면 꺼짐 재생 CPU 목표(7~8초/분 이하)는 이번에도 기기 측정을 하지 않았다.
  코드상 잔여 wake 후보는 크로스페이드 ticker(화면 끔 재생 60회/분),
  파티 ping/report(참여 시 분당 약 10회), Hq 자동 패스 enqueue다.
- 크로스페이드·곡 해석·노래 버전·가사 싱크 계산·분석 헤드 대기는 재생 핵심
  경로라 이번에 수정하지 않았다. 위 3절에 근거와 수정안만 남겼다.
