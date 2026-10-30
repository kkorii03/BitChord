# 안정성 전수 검토 — 2026-10-30 (review-stability-1031)

범위: `app`·`shared`의 재생·다운로드·설정·서비스·화면 전환 경로. 이전 감사 문서
(`docs/audit/check-crash.md`, `check-concurrency.md`, `check-data.md`,
`check-flow.md`, `check-sync-regress.md`, `audit-battery.md`)를 먼저 읽고 현재
코드와 대조했다. 근거는 모두 지금 파일의 `파일:줄`이며, 기기에서 재현하지 않은
동작은 "추측"이라고 적었다. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·
크로스페이드)는 이번에 수정하지 않고 문서에만 남겼다.

## 1. 이전 감사 추적 표

| 이전 문서 · 항목 | 현재 근거 | 상태 |
|---|---|---|
| check-crash 1 — NextPrep이 메인에서 `HqVerificationStore.bestTier` 조회 (HIGH) | `PlaybackService.kt:850` Main scope, `NextTrackPreparation.kt:179,193,416-424` | 남음 (재생 핵심 경로라 문서만) |
| check-crash 2 — HQ 두 디코드가 전역 `decodeGate`로 직렬화 (MED) | `AudioDecoder.kt:65,274`; `HqAudioVerify.kt:379-385` | 남음 |
| check-crash 3 — 설정 가져오기가 파일 전체 `readBytes` (MED) | `SettingsTransfer.kt:127,278-300`의 `readTextBounded` | 고쳐짐 |
| check-crash 4 — `AudioCache.hqHeadReady`가 곡마다 남음 (LOW) | `AudioCache.kt:1534,1544,1583`; 제거 경로 없음 | 남음 |
| check-crash 5 — 앱 시작 시 HQ 계획을 메인에서 파싱 (LOW) | `HqLibraryUpgrade.kt:239-247`에서 미로드 시 IO로 위임 | 고쳐짐 (시작·재생 schedule 경로) |
| check-concurrency 1 — 파일 교체와 song check 경합 (HIGH) | `LyricsTiming.kt:1862-1889`는 version/override를 지우지만 `songSyncGate`(`:1615`)를 잡지 않음, recheck는 `:1980`에서 잡음 | 남음 (version 초기화는 고쳐짐) |
| check-concurrency 2 — `songSyncGate`를 네트워크 내내 점유 (MED) | `LyricsTiming.kt:1980-2001` 안에서 `fetchDownloadedLyrics` 호출 | 남음 |
| check-concurrency 3 — HQ 후보 취소가 디코드를 멈추지 못함 (MED) | `HqAudioVerify.kt:753,1024-1027`에서 `!isActive`를 abort로 전달 | 고쳐짐 |
| check-concurrency 4 — HQ lock map 제거 경합 (MED) | `HqAudioVerify.kt:502,734,885`가 lock 해제 직후 map에서 제거 | 남음 |
| check-concurrency 5 — NextPrep 취소가 Whisper flight를 남김 (MED) | `LyricsTiming.kt:1372` `aiScope`, `:1595-1606` `songInFlight` | 남음 |
| check-concurrency 6 — VersionAudioAligner abort가 화면 상태만 봄 (MED) | `VersionAudioAligner.kt:202,211` | 남음 (재생 핵심 경로) |
| check-concurrency 7 — `onVerified`와 `runOne` rebuild 유실 (MED) | `HqLibraryUpgrade.kt:61,353-361` `rebuildAfterVerdict` | 고쳐짐 |
| check-concurrency 8 — HQ 계획 메인 prefs 읽기 (LOW) | `HqLibraryUpgrade.kt:239-247` | 고쳐짐 (schedule 경로) |
| check-concurrency 9 — `TrackAnalyzer.windowWaiters` timeout 누수 (LOW) | `TrackAnalyzer.kt:365-380`에 finally 제거 없음 | 남음 |
| check-data 1 — NextPrep이 per-song override를 무시 (HIGH) | `TranslationAhead.kt:130-152`는 override를 읽지 않고 `:186`만 읽음 | 남음 (가사 싱크 핵심 경로) |
| check-data 2 — 설정/백업 숫자 범위 미검증 (HIGH) | `SettingsTransfer.kt:461-467`; `AppSettings.kt:3140-3155`; 백업 테스트 | 고쳐짐 |
| check-data 3 — HQ 교체 `hqOffsetMs`/`hqRate` 미사용 (HIGH) | `LyricsTiming.kt:385-410`은 `StreamChoice`만 읽음; `Downloads.kt:2738-2744`는 저장만 | 남음 (가사 싱크 핵심 경로) |
| check-data 4 — HQ 자동 패스 기본 ON + Wi-Fi 우회 (HIGH) | `AppSettings.kt:1569-1581,3768` 마이그레이션, `HqLibraryUpgrade.kt:54,659` | 고쳐짐 |
| check-data 5 — 소스 편집이 HQ verdict를 무효화하지 않음 (MED) | `SourceRegistry.kt:422-428`은 search answer만 지움 | 남음 |
| check-data 6 — HQ 키가 설정 전송 화이트리스트에 없음 (MED) | `AppSettings.kt:3251-3253` | 고쳐짐 |
| check-data 7 — 자동 백업 규칙에 HQ 저장소 누락 (MED) | `backup_rules.xml:10-19`, `data_extraction_rules.xml:8-25` | 남음 |
| check-flow 1 — 자동 믹스 standby가 버전 교체를 못 따라감 (HIGH) | `CrossfadeController` standby 복사 경로 | 남음 (크로스페이드 핵심 경로) |
| check-flow 2 — 같은 행 queueKey 변화를 무시 (HIGH) | `NextTrackPreparation.kt:100-107`이 `shouldCancel`보다 먼저 return | 남음 (재생 핵심 경로) |
| check-flow 3 — 행 단위 가사 캐시가 새 videoId에 재사용 (HIGH) | `NextTrackPreparation.kt:70-80,168-208` rowId 키 | 남음 (가사 핵심 경로) |
| check-flow 4 — 다음 곡 버전 교체가 다운로드 안전 규칙 우회 (HIGH) | `NextTrackPreparation.kt:350-351`, `SongVersionController.kt:290-315` | 남음 (노래 버전 핵심 경로) |
| check-flow 5 — 스트리밍 곡을 로컬로 오판해 wake mode 하향 (HIGH) | `PlaybackService.kt:7206-7229` | 남음 |
| check-flow 8 — 교체 뒤 늦은 recheck가 옛 sync를 씀 (MED) | `Downloads.kt:1115,1125`와 `LyricsTiming.kt:1862`가 gate 밖 | 남음 |
| check-sync-regress 5 — `resetSongCheck`가 version/override를 남김 (HIGH) | `LyricsTiming.kt:1862-1889` | 고쳐짐 |
| check-sync-regress 8 — 답 없는 검사를 version=1로 기록 (MED) | `DownloadedSyncCheck.kt:105-124`에 `ownNoAnswer` 없음 | 남음 |
| check-sync-regress 10 — override가 videoId 단일 키 (MED) | `LyricsOverrideFile.kt:62-90` | 남음 |
| audit-battery H1 — 저전력 AutoMix 분석 (HIGH) | `TrackAnalyzer.kt:117` `BatteryPolicy.automixAnalysisActive` | 고쳐짐 |
| audit-battery H2 — 저전력 다음 곡 선반입 (HIGH) | `AudioCache.kt:1704-1717` `cachePrefetchBytesAllowed` | 고쳐짐 |
| audit-battery H3 — MV 소리 복구 WorkManager REPLACE (HIGH) | `MvSoundRedownload.kt:111` `APPEND_OR_REPLACE` | 고쳐짐 |
| audit-battery — 다운로드 빈 큐 100ms 폴링 (MED) | `DownloadService.kt:116-127,289-291` | 남음 |

## 2. 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정 |
|---|---|---|---|---|
| HIGH | `app/build.gradle.kts:92`, `AndroidManifest.xml:506-509`, `DownloadService.kt:38-101` | targetSdk 36, `dataSync` foreground service인데 `onTimeout` 재정의가 없었다. Android 15+는 rolling 24시간 제한 뒤 `onTimeout`을 호출하고, AOSP 기본 구현은 비어 있어 몇 초 안에 멈추지 않으면 앱을 ANR/크래시로 종료한다. | 대용량 다운로드 큐를 오래 돌리면 앱이 죽는다. | **고침**: `onTimeout` 두 오버로드에서 `shutdown(stopWork = true)`로 즉시 정지. 남은 행은 큐에 남겨 다음 foreground에서 재개. |
| MED | `Backup.kt:123-126` | 파일 선택기로 받은 백업을 `openInputStream().readBytes()`로 크기 제한 없이 읽었다. `SettingsTransfer`는 이미 1MB 상한이 있지만 `Backup`은 없었다. | 큰 파일을 백업으로 열면 `OutOfMemoryError`로 프로세스 종료. | **고침**: `readTextBounded`와 4MB 상한. 초과 시 가져오기 실패. |
| MED | `HqLibraryUpgrade.kt:61,353-361,626-651,820-830` | `onVerified`가 `activeVideoId != null`일 때만 rebuild를 예약했다. worker가 `plan.firstOrNull()`을 읽고 아직 `activeVideoId`를 쓰기 전에 verdict가 오면 plan은 비워지고 rebuild는 false가 되어, settle 뒤 새 plan을 실행할 worker가 없다. | 새 HQ 판정이 다음 앱 시작/재생까지 적용되지 않는다. | **고침**: `rebuildAfterVerdict(active, plan.size)`로 plan이 이미 잡힌 경우도 rebuild. |
| MED | `HqLibraryUpgrade.kt:353-354,380-381,410-416` | `onVerified`·`startReplacementPass`·`requestUpgrade`는 여전히 호출 스레드에서 `ensureLoaded`와 `bestEntryFor`(HQ verdict prefs)를 읽는다. 시작·재생 schedule 경로는 IO로 옮겼지만 이 세 경로는 남았다. | 설정/다운로드 화면에서 업그레이드를 누르거나 verdict가 도착할 때 메인 스레드가 큰 prefs를 파싱할 수 있다. | 문서만: 이 세 진입점도 `manualScope`로 옮기는 후속 작업 필요. |
| MED | `NextTrackPreparation.kt:179,193,416-424` + `PlaybackService.kt:850,8580` | 다음 곡 준비 scope가 `Dispatchers.Main`이고 `existingHq`가 `bestTier`를 동기 호출한다. 첫 호출은 `hq_verification` prefs를 파싱한다. | 곡 전환 때 ANR. | 문서만(재생 핵심 경로). `existingHq`만 `withContext(IO)`로 분리하는 후속 수정 제안. |
| MED | `DownloadService.kt:116-127,289-291` | worker 4개가 빈 큐에서 100ms마다 `takeNext`를 폴링하고 최대 2초 기다린다. | 다운로드 시작·종료 시 불필요한 wake-up. | 문서만: `Downloads`의 채널/플로우 신호로 교체 제안. |
| MED | `PlaybackService.kt:9992` | `onDestroy`가 메인 스레드에서 `progressWork.awaitTermination(2, SECONDS)`로 최대 2초 기다린다. | 서비스 종료가 느려지고, 다른 teardown과 겹치면 ANR 위험. | 문서만: sampler 마지막 tick을 이벤트로 넘기거나 상한을 낮추는 후속 작업. |
| MED | `MediaTagger.kt:117-118,205,470-472` | 태깅 경로가 오디오 파일 전체를 `readBytes()`로 메모리에 올린다. 동시 실행은 lock으로 1개지만 40MB FLAC 기준 여러 복사본이 동시에 존재한다. | 저메모리 기기에서 대용량 FLAC 태깅 중 OOM. | 문서만: 파일 전체를 메모리에 올리지 않는 스트리밍 태거가 필요(다운로드 핵심 경로). |
| LOW | `HqAudioVerify.kt:502,734,885` | 작업 종료 직후 lock을 map에서 제거한다. 이미 lock을 기다리던 caller가 있는데 새 caller가 새 Mutex를 만들면 같은 pair/reference를 동시에 디코드할 수 있다. | 드물게 중복 디코드/네트워크. | 문서만: waiter refcount 또는 세션 유지. |
| LOW | `AudioCache.kt:1534,1544,1583` | `hqHeadReady`에 넣은 `CompletableDeferred`를 완료/취소 뒤 제거하지 않는다. | 긴 세션에서 곡 수만큼 작은 객체가 남는다. | 문서만: `invokeOnCompletion`에서 같은 flight일 때만 remove. |
| LOW | `TrackAnalyzer.kt:365-380` | `awaitWindow`가 timeout으로 false를 내도 `windowWaiters` 항목을 지우지 않는다. | 분석이 끝나지 않는 곡마다 waiter가 쌓인다. | 문서만: finally에서 자신이 만든 waiter일 때만 remove. |
| LOW | `SourceRegistry.kt:422-428` | 소스 편집/교체 시 `SourceResolver.clearSearchAnswers()`만 하고 `HqVerificationStore`는 지우지 않는다. | 같은 config id의 새 URL에 옛 verdict가 쓰일 수 있다. | 문서만: sourceConfigId 단위 무효화. |
| LOW | `backup_rules.xml:10-19`, `data_extraction_rules.xml:8-25` | `hq_library`/`hq_verification` 저장소가 자동 백업·기기 이전에서 제외되지 않는다. | 소스 없는 기기에서 옛 plan/verdict가 복원될 수 있다. | 문서만: 새 저장소를 제외하거나 복원 후 활성 소스와 대조. |

## 3. 이번 수정의 조건 진리표

### 3.1 `HqLibraryUpgrade.onVerified` rebuild 조건

기존 `rebuildRequested = activeVideoId != null`의 모든 항은 유지하고,
`plan.isNotEmpty()` 항만 추가했다.

| activeVideoId != null | plan.isNotEmpty() | 기존 rebuild | 새 rebuild |
|---|---|---|---|
| true | true | true | true |
| true | false | true | true |
| false | true | **false (유실)** | **true (새로 예약)** |
| false | false | false | false |

### 3.2 `HqLibraryUpgrade.scheduleIfEnabled` 로딩 시점

| ready | 기존 | 새 |
|---|---|---|
| false | 호출 스레드에서 `ensureLoaded` 후 계속 | IO에서 `ensureLoaded` 후 계속 (호출 스레드는 즉시 반환) |
| true | 즉시 계속 | 즉시 계속 |

기존의 `runAllowed`, foreground, `PlaybackActivity.active`, debounce, failure retry
조건은 모두 그대로다.

## 4. 이번에 바꾼 파일

- `app/src/main/java/com/music/bitchord/data/stats/Backup.kt`
- `app/src/main/java/com/music/bitchord/download/DownloadService.kt`
- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`
- `app/src/test/java/com/music/bitchord/data/stats/BackupImportBoundsTest.kt` (새 테스트)
- `app/src/test/java/com/music/bitchord/download/HqLibraryRebuildPolicyTest.kt` (새 테스트)

## 5. 테스트

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.data.stats.BackupImportBoundsTest' --tests 'com.music.bitchord.download.HqLibraryRebuildPolicyTest' --console=plain -q` 통과.
- 기존 회귀: `HqLibraryUpgradeBackgroundPruneTest`, `SettingsTransferEdgeCasesTest`,
  `BackupSecretsAndPrefsTest` 통과.

## 6. 남은 우선순위

1. `NextTrackPreparation.existingHq`의 메인 스레드 prefs 읽기(ANR) — 재생 핵심 경로라
   기기 검증과 함께 `withContext(Dispatchers.IO)`로 분리.
2. `HqLibraryUpgrade.requestUpgrade`/`startReplacementPass`의 메인 파싱.
3. `DownloadService` 빈 큐 폴링을 이벤트 기반으로 교체(배터리).
4. `resetSongCheck`와 `recheckDownloaded`를 같은 gate/세대 토큰으로 묶기.
5. HQ lock map, `hqHeadReady`, `windowWaiters` 정리.
