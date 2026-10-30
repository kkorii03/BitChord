# 안정성 전수 검토 3차 — 2026-10-30 (review-stability-1031-r3)

범위: `app`의 다운로드 서비스 정지·재시작 경로와 그 주변 상태. 1·2차 문서
(`docs/review-1031/review-stability-1031.md`, `review-stability-1031-r2.md`)와
이전 감사 문서(`docs/audit/check-crash.md`, `check-concurrency.md`,
`check-data.md`, `check-flow.md`, `check-net.md`, `audit-battery.md`)를 먼저
읽고 현재 코드와 대조했다. 근거는 모두 지금 파일의 `파일:줄`이고, 기기에서
재현하지 않은 동작은 추측이라고 적었다. 재생 핵심 경로(곡 해석·노래 버전·
가사 싱크 계산·크로스페이드)는 이번에도 수정하지 않았다.

## 1. 3차 판정 3건 처리

| # | 심각도 | 지적 | 현재 근거 | 상태 |
|---|---|---|---|---|
| 1 | MED | `requeue`가 `automatic`/`mvSoundRepair`를 큐에 복원한 뒤 `run()`이 시작하면서 다시 지워, 재시도가 일반 다운로드로 바뀐다. | `Downloads.kt:1830-1837`의 `beginRun`이 현재 claim이고 `requeuedForShutdown`에 없을 때만 플래그를 지운다. `Downloads.kt:1928-1931`이 이 결과로만 `automatic`/`mvRepair`를 정한다. | **고침** |
| 2 | MED | `onStopped`가 `running`을 즉시 비워 새 서비스가 requeue 행을 먼저 claim할 수 있고, 늦게 끝난 옛 worker가 새 claim의 override/상태를 지우고 실패로 정산한다. | `Downloads.kt:1794-1805,1812-1824,1840-1848,1928-1931,1983-1999,2025-2062`에 claim 세대(`claimIds`, `nextClaimId`)를 넣었다. 옛 worker는 catch/finally/onIdle에서 자기 claim이 아니면 상태를 건드리지 않는다. `DownloadService.kt:137-157`이 claim id를 넘긴다. | **고침** |
| 3 | LOW | `requeueInFlightForShutdown`의 `DownloadSession`/`_active` 복원이 완료·취소와 원자적이지 않아 완료 행을 Queued로 덮거나 취소 행을 다시 Queued로 만들 수 있다. | `Downloads.kt:1867-1891`. `active` 검사와 쓰기는 [lock] 안이지만 `DownloadSession`의 보이는 행 갱신은 lock 밖의 snapshot 뒤에 있다. | **문서만** (아래 3.1) |

## 2. 이전 감사 문서 추적 표

2차 문서의 전체 표를 현재 코드에서 다시 확인했고, 이번 diff가 닿는
`Downloads`/`DownloadService` 항목은 새 상태로 갱신했다. 재생 핵심 경로는
수정하지 않았으므로 계속 **폰 검증 필요**다.

| 이전 문서 · 항목 | 현재 근거 | 상태 |
|---|---|---|
| check-crash 1 — NextPrep이 메인에서 `HqVerificationStore.bestTier` 조회 (HIGH) | `NextTrackPreparation.kt:179,406-424`; `PlaybackService.kt` Main scope | 남음 — 재생 핵심 경로, **폰 검증 필요** |
| check-crash 2 — HQ 두 디코드가 전역 `decodeGate`로 직렬화 (MED) | `AudioDecoder.kt:65,274`; `HqAudioVerify.kt:379-385` | 남음 |
| check-crash 3 — 설정 가져오기가 파일 전체 `readBytes` (MED) | `SettingsTransfer.kt`의 `readTextBounded` | 고쳐짐 |
| check-crash 4 — `AudioCache.hqHeadReady` 누수 (LOW) | `AudioCache.kt:1534,1544,1583` | 남음 |
| check-crash 5 — 앱 시작 HQ 계획 메인 파싱 (LOW) | `HqLibraryUpgrade.kt:246-256` schedule 경로는 IO | 고쳐짐 (schedule 경로) |
| check-concurrency 1 — 파일 교체와 song check 경합 (HIGH) | `LyricsTiming.kt` `songSyncGate`/`resetSongCheck` | 남음 — 가사 싱크 핵심 경로, **폰 검증 필요** |
| check-concurrency 2 — `songSyncGate`를 네트워크 내내 점유 (MED) | `LyricsTiming.kt:1980-2001` | 남음 — **폰 검증 필요** |
| check-concurrency 3 — HQ 후보 취소가 디코드를 멈추지 못함 (MED) | `HqAudioVerify.kt:753,1024-1027` | 고쳐짐 |
| check-concurrency 4 — HQ lock map 제거 경합 (MED) | `HqAudioVerify.kt:502,734,885` | 남음 |
| check-concurrency 5 — NextPrep 취소가 Whisper flight를 남김 (MED) | `LyricsTiming.kt:1372,1595-1606` | 남음 — **폰 검증 필요** |
| check-concurrency 6 — VersionAudioAligner abort가 화면 상태만 봄 (MED) | `VersionAudioAligner.kt:202,211` | 남음 — **폰 검증 필요** |
| check-concurrency 7 — `onVerified`와 `runOne` rebuild 유실 (MED) | `HqLibraryUpgrade.kt:65-71,371` | 고쳐짐 (2차) |
| check-concurrency 8 — HQ 계획 메인 prefs 읽기 (LOW) | `HqLibraryUpgrade.kt:246-256` | 고쳐짐 (schedule 경로) |
| check-concurrency 9 — `TrackAnalyzer.windowWaiters` timeout 누수 (LOW) | `TrackAnalyzer.kt:365-380` | 남음 |
| check-data 1 — NextPrep이 per-song override를 무시 (HIGH) | `TranslationAhead.kt:130-152` | 남음 — **폰 검증 필요** |
| check-data 2 — 설정/백업 숫자 범위 미검증 (HIGH) | `SettingsTransfer.kt`, `AppSettings.kt` | 고쳐짐 |
| check-data 3 — HQ 교체 `hqOffsetMs`/`hqRate` 미사용 (HIGH) | `LyricsTiming.kt:385-410`; `Downloads.kt:2738-2744` | 남음 — **폰 검증 필요** |
| check-data 4 — HQ 자동 패스 기본 ON + Wi-Fi 우회 (HIGH) | `AppSettings.kt` 마이그레이션, `HqLibraryUpgrade.kt` | 고쳐짐 |
| check-data 5 — 소스 편집이 HQ verdict를 무효화하지 않음 (MED) | `SourceRegistry.kt:303,427`은 search answer만 지움 | 남음 |
| check-data 6 — HQ 키가 설정 전송 화이트리스트에 없음 (MED) | `AppSettings.kt` | 고쳐짐 |
| check-data 7 — 자동 백업 규칙에 HQ 저장소 누락 (MED) | `backup_rules.xml:12-19`, `data_extraction_rules.xml:8-25` | 남음 |
| check-flow 1 — 자동 믹스 standby가 버전 교체를 못 따라감 (HIGH) | `CrossfadeController` standby 복사 경로 | 남음 — 크로스페이드 핵심 경로, **폰 검증 필요** |
| check-flow 2 — 같은 행 queueKey 변화를 무시 (HIGH) | `NextTrackPreparation.kt:100-107` | 남음 — **폰 검증 필요** |
| check-flow 3 — 행 단위 가사 캐시가 새 videoId에 재사용 (HIGH) | `NextTrackPreparation.kt:70-80,168-208` | 남음 — **폰 검증 필요** |
| check-flow 4 — 다음 곡 버전 교체가 다운로드 안전 규칙 우회 (HIGH) | `NextTrackPreparation.kt:350-351`, `SongVersionController.kt:290-315` | 남음 — 노래 버전 핵심 경로, **폰 검증 필요** |
| check-flow 5 — 스트리밍 곡을 로컬로 오판해 wake mode 하향 (HIGH) | `PlaybackService.kt:7206-7229` | 남음 — **폰 검증 필요** |
| check-flow 8 — 교체 뒤 늦은 recheck가 옛 sync를 씀 (MED) | `Downloads.kt:1115,1125`; `LyricsTiming.kt:1862` | 남음 — **폰 검증 필요** |
| check-net 1 — 답 없는 검사를 version=1로 기록 (HIGH) | `DownloadedSyncCheck.kt:105-124` | 남음 — **폰 검증 필요** |
| check-net 2 — 잘린 HQ 오디오를 영구 거부 (HIGH) | `HqAudioVerify.kt:381-391,417-453` | 남음 — **폰 검증 필요** |
| check-net 3 — 긴 Retry-After 뒤 같은 URL 재시도 (HIGH) | `HqRetryPolicy.kt:38,73-84`; `ChunkedDataSource.kt:456-475` | 남음 — **폰 검증 필요** |
| check-net 4 — 애드온 Retry-After 대기창 미반영 (MED) | `AddonClient.kt:328,352-362` | 남음 |
| check-net 5 — 실패한 HQ `hqOverrides` 잔존 (MED) | `Downloads.kt:312-315,1454,1907-1916` | 남음 |
| check-net 6 — HQ 실패 대상을 기억하지 않음 (MED) | `HqLibraryUpgrade.kt:296-325,343-345` | 남음 |
| check-net 7 — 프록시 URL 세대 경합 (MED) | `ProxyPlayback.kt:145-155`; `StreamResolver.kt:798-799` | 남음 — 추측 |
| audit-battery H1 — 저전력 AutoMix 분석 (HIGH) | `TrackAnalyzer.kt:117` | 고쳐짐 |
| audit-battery H2 — 저전력 다음 곡 선반입 (HIGH) | `AudioCache.kt:1704-1717` | 고쳐짐 |
| audit-battery H3 — MV 소리 복구 WorkManager REPLACE (HIGH) | `MvSoundRedownload.kt:111` | 고쳐짐 |
| audit-battery — 다운로드 빈 큐 100ms 폴링 (MED) | `DownloadService.kt:142-144,313-315` | 남음 (의도 유지, 아래 3.2) |
| 1차 — `dataSync` FGS `onTimeout` 미정의 (HIGH) | `DownloadService.kt:92-99` | 고쳐짐 |
| 1차 — `Backup` 무제한 `readBytes` (MED) | `Backup.kt:123-126` | 고쳐짐 |
| 1차 — `HqLibraryUpgrade` rebuild 유실 (MED) | `HqLibraryUpgrade.kt:65-71,371` | 고쳐짐 (2차) |
| 1차 — `HqLibraryUpgrade.onVerified`/`startReplacementPass`/`requestUpgrade` 메인 prefs (MED) | `HqLibraryUpgrade.kt:364,392,427,435` | 남음 |
| 1차 — `NextTrackPreparation.existingHq` 메인 prefs (MED) | `NextTrackPreparation.kt:179,416-424` | 남음 — **폰 검증 필요** |
| 1차 — `PlaybackService.onDestroy` 2초 대기 (MED) | `PlaybackService.kt:9992` | 남음 |
| 1차 — `MediaTagger` 파일 전체 `readBytes` (MED) | `MediaTagger.kt:117-118,205,470-472` | 남음 |
| 2차 — `HqLibraryUpgrade.finishRun`이 `rebuildRequested`를 남김 (LOW) | `HqLibraryUpgrade.kt:898-912` | 남음 |

## 3. 이번 라운드 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정 |
|---|---|---|---|---|
| LOW | `Downloads.kt:1867-1891` | requeue의 `pending`/`_active`/`DownloadSession` 복원이 완료·취소와 같은 임계 구역에 있지 않다. 완료가 그 사이 `Done`을 쓰면 requeue가 다시 `Queued`를 쓸 수 있고, 취소가 `forget`한 행을 다시 넣을 수 있다. | 다운로드 목록에 잠깐 잘못된 Queued 행이 남거나, 이미 끝난 행이 다시 대기로 보인다. 데이터 파일은 건드리지 않는다. | 문서만: `DownloadSession`에 "이 id가 아직 미정산일 때만 Queued로 바꾼다"는 원자 연산을 추가하고, `_active` 검사와 함께 호출. |

이번 라운드에서 새로 확인된 HIGH·MED는 없다. 3차 판정 1·2는 원인이
`Downloads` 한 파일의 claim 수명에 있었고, 안전 규칙(정지 시 requeue,
자동 패스 세션 행 없음, MV 복구 실패 표시)은 약화하지 않았다.

## 4. 이번 수정의 조건 진리표

### 4.1 `run` 시작 시 플래그 제거 (`beginRun`)

기존에는 `run`이 시작하면 무조건 `automaticIds`/`mvSoundRepairIds`에서 id를
지웠다. 새 조건은 **현재 claim일 때만** 지우고, **requeue된 행이면** 지우지
않는다. 두 항은 기존 동작을 보존하기 위한 것이고, claim 항만 새로 추가했다.

| 현재 claim인가 | requeuedForShutdown에 있나 | 기존 | 새 |
|---|---|---|---|
| true | false | 플래그 제거 | 플래그 제거 |
| true | true | **플래그 제거 (재시도 요청 유실)** | **플래그 유지 (재시도가 요청을 읽음)** |
| false | false | **플래그 제거 (새 claim 훼손)** | **아무것도 안 함 (run 종료)** |
| false | true | **플래그 제거 (새 claim 훼손)** | **아무것도 안 함 (run 종료)** |

### 4.2 `run` 종료 정리 (`catch`/`finally`/`onIdle`)

기존 정리는 어떤 handler든 `requeuedForShutdown.remove(id)`를 하고, 필요하면
`clear`/`fail`/`HqLibraryUpgrade.onDownloadSettled`를 호출했다. 새 정리는
`claimIds[id] == claimId`일 때만 그 일을 한다. 기존의 "requeued면 큐 유지,
완료면 pending 제거, 실패면 Queued 복원" 항은 모두 그대로다.

| 현재 claim인가 | requeued | 기존 | 새 |
|---|---|---|---|
| true | false | 일반 완료/실패/취소 정리 | 일반 완료/실패/취소 정리 |
| true | true | pending 제거(완료) 또는 Queued 복원(미완료) | 같음 |
| false | false | **옛 handler가 새 claim을 clear/fail** | **아무것도 안 함** |
| false | true | **옛 handler가 새 claim의 marker를 제거** | **아무것도 안 함** |

### 4.3 배터리

새 타이머·주기 폴링은 추가하지 않았다. 기존 `DownloadService`의 빈 큐
100ms 대기는 2차 문서의 이유(`IDLE_GRACE_MS`가 시작 직후 enqueue 경합을
닫는 안전 규칙)대로 이번에도 유지했다.

## 5. 이번에 바꾼 파일

- `app/src/main/java/com/music/bitchord/download/Downloads.kt`
- `app/src/main/java/com/music/bitchord/download/DownloadService.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadsShutdownRequeueTest.kt` (기존 테스트 갱신 + 2건 추가)
- `docs/review-1031/review-stability-1031-r3.md` (이 문서)

## 6. 테스트

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.DownloadsShutdownRequeueTest' --console=plain -q` 통과 (6건).
- 추가한 테스트:
  - `a requeued transfer keeps its request when its old worker starts late`
  - `a stale worker leaves the claim that replaced it alone`

## 7. 남은 우선순위

1. 3.1의 LOW: `DownloadSession`에 미정산 행만 Queued로 되돌리는 원자 연산을
   추가해 requeue와 완료·취소를 한 임계 구역으로 묶는다.
2. `NextTrackPreparation.existingHq`의 메인 스레드 `bestTier` — 재생 핵심
   경로, **폰 검증 필요**.
3. `DownloadService` 빈 큐 폴링을 batch-close 이벤트로 교체(배터리).
4. `resetSongCheck`와 `recheckDownloaded`를 같은 gate/세대 토큰으로 묶기 —
   가사 싱크 핵심 경로, **폰 검증 필요**.
