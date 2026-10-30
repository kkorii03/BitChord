# 안정성 전수 검토 2차 — 2026-10-30 (review-stability-1031-r2)

범위: `app`·`shared`의 재생·다운로드·설정·서비스·화면 전환 경로. 1차 문서
(`docs/review-1031/review-stability-1031.md`)와 이전 감사 문서
(`docs/audit/check-crash.md`, `check-concurrency.md`, `check-data.md`,
`check-flow.md`, `check-sync-regress.md`, `audit-battery.md`)를 먼저 읽고 현재
코드와 대조했다. 근거는 모두 지금 파일의 `파일:줄`이며, 기기에서 재현하지 않은
동작은 "추측"이라고 적었다. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·
크로스페이드)는 이번에도 수정하지 않고 문서에만 남겼다.

## 1. 이번 판정 3건

| 심각도 | 지적 | 현재 근거 | 상태 |
|---|---|---|---|
| HIGH | `scheduleIfEnabled`의 `!ready` 분기가 `ensureLoaded`를 부르지 않아 IO에서 자기 자신을 무한히 다시 예약한다. | `HqLibraryUpgrade.kt:246-256`. 이전에는 `manualScope.launch { scheduleIfEnabled(app) }`만 했고 `ready`는 여전히 false라 매번 같은 분기로 돌아왔다. | **고침**: launch 안에서 `ensureLoaded(app)`를 먼저 호출한 뒤 `scheduleIfEnabled(app)`를 부른다. |
| MED | `rebuildAfterVerdict`가 `plan.size`만 봐서, worker가 `buildPlan` 안에 있을 때(plan 비었고 active 미기록) verdict가 오면 rebuild가 false가 되고 새 verdict가 유실된다. | `HqLibraryUpgrade.kt:65-71,371`. `workerRunning`은 worker 시작~종료 전체를 덮는다(`:1031-1037`, `:1165-1170`). | **고침**: predicate에 `workerRunning` 항을 추가했다. 기존 두 항은 그대로다. |
| MED | `shutdown(stopWork=true)`가 drain을 취소하고 `stopSelf`하면 `onDestroy`의 `scope.cancel()`이 per-track job을 취소한다. 그 `CancellationException` 경로가 행을 지우고 requeue하지 않아 최대 4개 다운로드가 사라지고 세션 행이 "Running"으로 남는다. | `DownloadService.kt:92-99,168-176`; `Downloads.kt:1938-1941,1974-2005`. 이전에는 `clear(id)`만 하고 `running`은 `onStopped`가 비웠다. | **고침**: 정지 전 `Downloads.requeueInFlightForShutdown()`로 in-flight 행을 pending에 되돌리고, 요청(quality/HQ pin/automatic)을 보존하며, 보이는 세션 행을 Queued로 되돌린다. 취소 경로는 requeue된 행의 정리를 건너뛴다. |

## 2. 이전 감사·1차 문서 추적 표

| 이전 문서 · 항목 | 현재 근거 | 상태 |
|---|---|---|
| check-crash 1 — NextPrep이 메인에서 `HqVerificationStore.bestTier` 조회 (HIGH) | `NextTrackPreparation.kt:179,406,416-424`; `PlaybackService.kt:868` Main scope | 남음 — 재생 핵심 경로, **폰 검증 필요** |
| check-crash 2 — HQ 두 디코드가 전역 `decodeGate`로 직렬화 (MED) | `AudioDecoder.kt:65,274`; `HqAudioVerify.kt:379-385` | 남음 |
| check-crash 3 — 설정 가져오기가 파일 전체 `readBytes` (MED) | `SettingsTransfer.kt`의 `readTextBounded` | 고쳐짐 (1차) |
| check-crash 4 — `AudioCache.hqHeadReady`가 곡마다 남음 (LOW) | `AudioCache.kt:1534,1544,1583`; 제거 경로 없음 | 남음 |
| check-crash 5 — 앱 시작 시 HQ 계획을 메인에서 파싱 (LOW) | `HqLibraryUpgrade.kt:246-256` schedule 경로는 IO | 고쳐짐 (schedule 경로) |
| check-concurrency 1 — 파일 교체와 song check 경합 (HIGH) | `LyricsTiming.kt` `songSyncGate`/`resetSongCheck` | 남음 — 가사 싱크 핵심 경로, **폰 검증 필요** |
| check-concurrency 2 — `songSyncGate`를 네트워크 내내 점유 (MED) | `LyricsTiming.kt:1980-2001` | 남음 — **폰 검증 필요** |
| check-concurrency 3 — HQ 후보 취소가 디코드를 멈추지 못함 (MED) | `HqAudioVerify.kt:753,1024-1027` | 고쳐짐 (1차) |
| check-concurrency 4 — HQ lock map 제거 경합 (MED) | `HqAudioVerify.kt:502,734,885` | 남음 |
| check-concurrency 5 — NextPrep 취소가 Whisper flight를 남김 (MED) | `LyricsTiming.kt:1372,1595-1606` | 남음 — **폰 검증 필요** |
| check-concurrency 6 — VersionAudioAligner abort가 화면 상태만 봄 (MED) | `VersionAudioAligner.kt:202,211` | 남음 — **폰 검증 필요** |
| check-concurrency 7 — `onVerified`와 `runOne` rebuild 유실 (MED) | `HqLibraryUpgrade.kt:65-71,371` | 1차 부분 고침 → **2차 완전 고침** (`workerRunning`) |
| check-concurrency 8 — HQ 계획 메인 prefs 읽기 (LOW) | `HqLibraryUpgrade.kt:246-256` schedule 경로 | 고쳐짐 (schedule 경로) |
| check-concurrency 9 — `TrackAnalyzer.windowWaiters` timeout 누수 (LOW) | `TrackAnalyzer.kt:365-380` | 남음 |
| check-data 1 — NextPrep이 per-song override를 무시 (HIGH) | `TranslationAhead.kt:130-152` | 남음 — **폰 검증 필요** |
| check-data 2 — 설정/백업 숫자 범위 미검증 (HIGH) | `SettingsTransfer.kt`, `AppSettings.kt` | 고쳐짐 (1차) |
| check-data 3 — HQ 교체 `hqOffsetMs`/`hqRate` 미사용 (HIGH) | `LyricsTiming.kt:385-410`; `Downloads.kt:2738-2744` | 남음 — **폰 검증 필요** |
| check-data 4 — HQ 자동 패스 기본 ON + Wi-Fi 우회 (HIGH) | `AppSettings.kt` 마이그레이션, `HqLibraryUpgrade.kt` | 고쳐짐 (1차) |
| check-data 5 — 소스 편집이 HQ verdict를 무효화하지 않음 (MED) | `SourceRegistry.kt:303,427`은 search answer만 지움 | 남음 |
| check-data 6 — HQ 키가 설정 전송 화이트리스트에 없음 (MED) | `AppSettings.kt` | 고쳐짐 (1차) |
| check-data 7 — 자동 백업 규칙에 HQ 저장소 누락 (MED) | `backup_rules.xml:12-19`, `data_extraction_rules.xml:8-25`에 `hq_` 없음 | 남음 |
| check-flow 1 — 자동 믹스 standby가 버전 교체를 못 따라감 (HIGH) | `CrossfadeController` standby 복사 경로 | 남음 — 크로스페이드 핵심 경로, **폰 검증 필요** |
| check-flow 2 — 같은 행 queueKey 변화를 무시 (HIGH) | `NextTrackPreparation.kt:100-107` | 남음 — **폰 검증 필요** |
| check-flow 3 — 행 단위 가사 캐시가 새 videoId에 재사용 (HIGH) | `NextTrackPreparation.kt:70-80,168-208` | 남음 — **폰 검증 필요** |
| check-flow 4 — 다음 곡 버전 교체가 다운로드 안전 규칙 우회 (HIGH) | `NextTrackPreparation.kt:350-351`, `SongVersionController.kt:290-315` | 남음 — 노래 버전 핵심 경로, **폰 검증 필요** |
| check-flow 5 — 스트리밍 곡을 로컬로 오판해 wake mode 하향 (HIGH) | `PlaybackService.kt:7206-7229` | 남음 — **폰 검증 필요** |
| check-flow 8 — 교체 뒤 늦은 recheck가 옛 sync를 씀 (MED) | `Downloads.kt:1115,1125`; `LyricsTiming.kt:1862` | 남음 — **폰 검증 필요** |
| check-sync-regress 5 — `resetSongCheck`가 version/override를 남김 (HIGH) | `LyricsTiming.kt:1862-1889` | 고쳐짐 (1차) |
| check-sync-regress 8 — 답 없는 검사를 version=1로 기록 (MED) | `DownloadedSyncCheck.kt:105-124` | 남음 — **폰 검증 필요** |
| check-sync-regress 10 — override가 videoId 단일 키 (MED) | `LyricsOverrideFile.kt:62-90` | 남음 — **폰 검증 필요** |
| audit-battery H1 — 저전력 AutoMix 분석 (HIGH) | `TrackAnalyzer.kt:117` | 고쳐짐 (1차) |
| audit-battery H2 — 저전력 다음 곡 선반입 (HIGH) | `AudioCache.kt:1704-1717` | 고쳐짐 (1차) |
| audit-battery H3 — MV 소리 복구 WorkManager REPLACE (HIGH) | `MvSoundRedownload.kt:111` | 고쳐짐 (1차) |
| audit-battery — 다운로드 빈 큐 100ms 폴링 (MED) | `DownloadService.kt:142-144,313-315` | 남음 — 아래 4.3 참고 |
| 1차 — `dataSync` FGS `onTimeout` 미정의 (HIGH) | `DownloadService.kt:92-99` | 고쳐짐 (1차) + **2차 requeue 보강** |
| 1차 — `Backup` 무제한 `readBytes` (MED) | `Backup.kt:123-126` `readTextBounded` | 고쳐짐 (1차) |
| 1차 — `HqLibraryUpgrade` rebuild 유실 (MED) | `HqLibraryUpgrade.kt:65-71,371` | **2차 고침** |
| 1차 — `HqLibraryUpgrade` `onVerified`/`startReplacementPass`/`requestUpgrade` 메인 prefs (MED) | `HqLibraryUpgrade.kt:364,392,427,435` | 남음 |
| 1차 — `NextTrackPreparation.existingHq` 메인 prefs (MED) | `NextTrackPreparation.kt:179,416-424` | 남음 — **폰 검증 필요** |
| 1차 — `PlaybackService.onDestroy` 2초 대기 (MED) | `PlaybackService.kt:9992` | 남음 |
| 1차 — `MediaTagger` 파일 전체 `readBytes` (MED) | `MediaTagger.kt:117-118,205,470-472` | 남음 |
| 1차 LOW — HQ lock map / `hqHeadReady` / `windowWaiters` / `SourceRegistry` / backup rules | 위 근거와 동일 | 남음 |

## 3. 이번 라운드 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정 |
|---|---|---|---|---|
| LOW | `HqLibraryUpgrade.kt:898-912` | `finishRun()`은 plan/done/total/active/manualPass를 지우지만 `rebuildRequested`는 지우지 않는다. `onVerified`가 workerRunning 때문에 rebuild를 예약한 뒤 그 worker가 `finishRun()`으로 끝나면 플래그가 남는다. | 이후 plan이 빈 채로 settle되면 한 번 더 빈 패스를 예약한다(다운로드 1회 추가 시도). 유실은 아니다. | 문서만: `finishRun`에서 `rebuildRequested = false`를 함께 지우는 후속 수정. 단, 취소 직후 도착한 verdict를 살릴지 정책 결정 필요. |

이번 라운드에서 새로 확인된 HIGH·MED는 없다. 1차 판정 3건은 모두 원인이 코드
한두 곳에 있고 안전 규칙을 약화하지 않는 범위에서 고쳤다.

## 4. 이번 수정의 조건 진리표

### 4.1 `HqLibraryUpgrade.scheduleIfEnabled` 첫 로드

| ready | 기존 | 새 |
|---|---|---|
| false | IO에서 `scheduleIfEnabled`만 재호출 → `ready`가 그대로 false → 무한 재예약 | IO에서 `ensureLoaded` 후 `scheduleIfEnabled` → `ready` true로 한 번만 로드하고 진행 |
| true | 즉시 진행 | 즉시 진행 |

`runAllowed`, foreground, `PlaybackActivity.active`, debounce, failure retry 조건은
모두 그대로다.

### 4.2 `HqLibraryUpgrade.rebuildAfterVerdict`

기존 `activeVideoId != null || planSize > 0`의 모든 항을 유지하고
`workerRunning` 항만 추가했다.

| active != null | planSize > 0 | workerRunning | 기존 rebuild | 새 rebuild |
|---|---|---|---|---|
| true | true | true | true | true |
| true | true | false | true | true |
| true | false | true | true | true |
| true | false | false | true | true |
| false | true | true | true | true |
| false | true | false | true | true |
| false | false | true | **false (유실)** | **true (새로 예약)** |
| false | false | false | false | false |

### 4.3 `DownloadService` 정지 시 in-flight 처리

| 정지 시점의 행 | 기존 | 새 |
|---|---|---|
| `_active`가 Running/Queued (전송 중 또는 claim 직후) | cancel → `clear(id)`로 행/override 제거, 세션은 Running 유지, `running`은 onStopped가 비움 → 큐에서 사라짐 | pending에 되돌리고 `_active`/세션을 Queued로, quality·HQ pin·automatic 플래그 보존. 취소 경로는 `clear`/settle을 건너뛰고 재시도가 답한다. |
| `_active` 없음 (이미 완료) | 영향 없음 | requeue하지 않음 |
| `_active` Failed | 영향 없음 | requeue하지 않음 |
| `ACTION_CANCEL_ALL` (사용자 취소) | 행 제거 | `requeueInFlight=false`라 requeue하지 않음 |

`DownloadService.kt:142-144`의 빈 큐 100ms 폴링은 **의도적으로 유지**했다.
`IDLE_GRACE_MS` 주석이 설명하는 시작 직후 enqueue 경합(첫 enqueue가 서비스를
깨우고 나머지 행이 뒤따라 들어오는 구간)을 닫아 주는 안전 규칙이라, batch-close
이벤트 없이 지우면 그 경합으로 다운로드가 유실된다. 배터리 후속 작업은
`Downloads`에 "이 배치의 enqueue가 끝났다"는 이벤트를 추가한 뒤 폴링을 그
이벤트로 교체해야 한다.

## 5. 이번에 바꾼 파일

- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`
- `app/src/main/java/com/music/bitchord/download/DownloadService.kt`
- `app/src/main/java/com/music/bitchord/download/Downloads.kt`
- `app/src/test/java/com/music/bitchord/download/HqLibraryRebuildPolicyTest.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadsShutdownRequeueTest.kt` (새 테스트)
- `docs/review-1031/review-stability-1031-r2.md` (이 문서)

## 6. 테스트

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.*' --console=plain -q` 통과.
- 새/수정 테스트:
  - `HqLibraryRebuildPolicyTest` — active, plan, workerRunning 3항 진리표 (workerRunning만 true인 창 포함).
  - `DownloadsShutdownRequeueTest` — in-flight requeue와 요청 보존, 완료 행 미복귀, automatic 무세션행 유지, 재claim 방지.
- 판정 HIGH(scheduleIfEnabled 첫 로드)는 `Context`/`SharedPreferences`가 필요해
  Robolectric 없이 JVM 단위 테스트를 둘 수 없었다. 대신 launch 블록이
  `ensureLoaded(app)` → `scheduleIfEnabled(app)` 순서인지 코드로 확인했고, 컴파일과
  기존 `HqLibraryUpgradeBackgroundPruneTest`로 회귀를 확인했다.
- 전체 `:app:testProdReleaseUnitTest`는 1,692건 중 22건이 실패했으나, 모두 이 diff와
  무관한 기존 로케일/번역 테스트다: `LocaleStringsTest`(비영어 strings 누락 키),
  `ArtistNameMatchTest`(키릴 문자 fold), `BluetoothAudioTrackerTest`("시스템 관리"
  vs "System Managed"), `SourceFormatsTest`(한국어 메시지 기대), `ProviderLyricsTest`
  (provider 수 21 vs 20). 다운로드/서비스 변경이 닿는 테스트는 모두 통과했다.

## 7. 남은 우선순위

1. `NextTrackPreparation.existingHq`의 메인 스레드 `bestTier` — 재생 핵심 경로,
   **폰 검증 필요**. `withContext(Dispatchers.IO)` 분리안 유지.
2. `HqLibraryUpgrade.onVerified`/`startReplacementPass`/`requestUpgrade`의 메인
   `ensureLoaded`/`bestEntryFor` — IO 진입점으로 옮기는 후속 작업.
3. `DownloadService` 빈 큐 폴링 — batch-close 이벤트를 먼저 추가한 뒤 교체(배터리).
4. `resetSongCheck`와 `recheckDownloaded`를 같은 gate/세대 토큰으로 묶기 —
   가사 싱크 핵심 경로, **폰 검증 필요**.
5. HQ lock map, `hqHeadReady`, `windowWaiters` 정리.
6. `PlaybackService.onDestroy`의 `awaitTermination(2s)`와 `MediaTagger` 전체 읽기.
