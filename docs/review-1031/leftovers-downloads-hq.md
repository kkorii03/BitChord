# downloads-hq 남은 지적 검토 (task/fix-1030 → review-1031)

기준: 현재 워크스페이스의 `app/src/main` 코드. 같은 원인의 중복 지적은 한 행에 묶고 원 지적 번호를 함께 적었다.
판정: **이미 고쳐짐** / **여전함→고침** / **지적이 틀림** / **남김**

| # | 지적 | 판정 | 근거 | 처리 |
|---|---|---|---|---|
| 1 | [439] 자동 복구 unverified fallback이 `allowMetered=true` 하드코딩이라 셀룰러에서 대기 없이 내려받음 | 이미 고쳐짐 | `download/HqLibraryUpgrade.kt:607,617,642,650` | fallback 직전 `hqUpgradeAllowedNow` 재검사 + `hqUpgradeAllowsMetered(automatic)` 전달 |
| 2 | [519] Wi-Fi-only 거부가 `DownloadHealth`에서 checked/cooldown으로 기록되어 영구 삼켜짐 | 이미 고쳐짐 | `download/DownloadHealth.kt:95-97,159-167,197-201,235` | `UpgradeRequest.REFUSED`는 cooldown 미기록, pending marker가 다음 허용 재생에서 재시도 |
| 3 | [487] MV 자동 복구가 metered에서 success 반환 후 Wi-Fi 전환에도 깨어나지 않음 | 이미 고쳐짐 | `download/MvSoundRedownload.kt:190,203-207`; `BitChordApplication.kt:277-299` | `NetworkType.UNMETERED` 제약 + 허용 전환 이벤트에서 `replace=true` 재예약 |
| 4 | [485] `MvSoundRedownload.cancel`이 실행 중 pass까지 취소해 대형 라이브러리 재시작 | 이미 고쳐짐 | `download/MvSoundRedownload.kt:255-283` | RUNNING이 하나라도 있으면 아무 것도 취소하지 않고 ENQUEUED/BLOCKED만 취소 |
| 5 | [438] 시작 시 `!AppForeground`면 MV 대기 work를 취소하지 않음 | 이미 고쳐짐 | `BitChordApplication.kt:253-258` | 백그라운드 시작에서 `pause(this)` 후 `scheduleIfEnabled` |
| 6 | [340] `!ready` 분기에서 `ensureLoaded` 없이 자기 자신을 재귀 실행 | 이미 고쳐짐 | `download/HqLibraryUpgrade.kt:310-311,1464` | `scheduleIfEnabled`가 먼저 `ensureLoaded` 호출; 재귀 경로 없음 |
| 7 | [439] `buildPlan` 중 verdict가 오면 `activeVideoId`가 아직 null이라 rebuild 요청이 사라짐 | 여전함→고침 | `download/HqLibraryUpgrade.kt:428-429,450` | `verdictNeedsRebuild(active, workerRunning)`로 in-flight worker까지 포함, 테스트 추가 |
| 8 | [536] `shutdown(stopWork=true)`가 실행 중 다운로드를 잃고 Running 행을 남김 | 이미 고쳐짐 | `download/DownloadService.kt:75-79,143-160,237-245`; `Downloads.kt:622-635` | cancel-all은 각 active를 먼저 `Downloads.cancel`(행/세션 정리)한 뒤 종료; 일반 drain은 busy가 아니면 시작 id 확인 후 종료 |
| 9 | [465] source invalidation 후 `runOne`의 settle guard 때문에 계획 target이 남아 무한 재시도 | 이미 고쳐짐 | `download/HqLibraryUpgrade.kt:997-1002,1226-1237` | verdict가 없어진 target은 `requireActive=false`로 계획에서 제거 |
| 10 | [493] `cleanupAbandoned`가 시작 직후 다운로드의 `.part`/IS_PENDING 행을 삭제 | 이미 고쳐짐 | `download/DownloadStore.kt:67,281-305` | 프로세스 시작 시각 이전 항목만 sweep |
| 11 | [363] `offline-hls` 미참조 패키지 디렉터리가 영구 잔류 | 이미 고쳐짐 | `download/DownloadStore.kt:287-292`; `download/OfflinePackage.kt:62-78` | 참조 root/active root/시작 이전 조건으로 `sweepUnreferenced` |
| 12 | [609] `fail()`가 일시적 네트워크 실패에도 `hqOverrides`를 버려 재시도가 예전 파일을 채택 | 이미 고쳐짐 | `download/Downloads.kt:2043,2762-2772` | transient failure는 `keepVerifiedPair=true`; 영구 실패에서만 override 제거 |
| 13 | [402] 비동기 `prepare()` 직후 release 대기 없이 캐시 discard | 이미 고쳐짐 | `playback/PlaybackService.kt:5915-5925` | prepare 뒤 `delay(RECOVERY_DELAY_MS)` 후 `AudioCache.discard` |
| 14 | [515] 403 재해석이 컨테이너를 바꿔도 `contentLength`만 비교해 MP4/WebM 혼합 | 여전함→고침 | `download/Downloader.kt:39-47,106,140-155` | 원래 `downloadExtension`을 보존하고 컨테이너·길이 모두 바뀌면 중단, 순수 helper 테스트 추가 |
| 15 | [464] `analysisHeads`가 clear된 뒤 in-flight head가 claim을 다시 잡아 analyzer가 재시도 못 함 | 이미 고쳐짐 | `playback/AudioCache.kt:2806-2830` | claim 판정에 in-flight 포함, `add` 실패 시 claim 제거 |
| 16 | [419] `clearPartialHead`가 complete와 live-reader lock을 구분 못 해 `mix=none` 고착 | 이미 고쳐짐 | `playback/AudioCache.kt:2860-2890` | `AnalysisHeadClear.HeldElsewhere`로 분리하고 reader release까지 claim 유지 |
| 17 | [490] `takeNext()`가 publish보다 먼저 행을 가져가면 session row가 생기지 않음 | 이미 고쳐짐 | `download/Downloads.kt:517-568,1886-1891` | publish가 `pending` 또는 `running` 모두 처리하고 insert와 같은 lock 안에서 실행 |
| 18 | [454] parked target의 24h cooldown을 `runOne`이 무시하고 즉시 선택 | 이미 고쳐짐 | `download/HqLibraryUpgrade.kt:1069-1080,1127-1149` | `nextRunnableTargetLocked`가 `automaticTargetEligible`(cooldown 포함)로 고르고 parked wait 예약 |
| 19 | [481] `NO_SOURCE` 미생성으로 영구 reference 실패가 계속 후보 다운로드/디코드 | 이미 고쳐짐 | `playback/smart/HqAudioVerify.kt:108-119,557-572,666-682` | 429 pending entry에 bounded transient attempt(3회) 추가 |
| 20 | [455] active same-row 조기 반환과 rowId key 때문에 다운로드/파일 교체 후 재준비 안 됨 | 이미 고쳐짐 | `playback/NextTrackPreparation.kt:114-127,183-197`; `NextPrepPolicy.kt:206-236` | `alreadyHandled`가 queueKey를 먼저 비교; attempted/lyrics는 queueKey+videoId key |
| 21 | [477] 동등 이상 사용자 다운로드가 verified pair 없으면 영구 실패로 settle | 이미 고쳐짐 | `download/HqLibraryUpgrade.kt:781-819,99-108` | provenance가 target과 같은 `hasCopyAtLeastAsGood`만 인정, 아니면 `retryActive`로 계획 유지 |
| 22 | [371] 200 응답을 `total-position` 없이 EOF까지 써서 저장소 고갈 | 이미 고쳐짐 | `download/Downloader.kt:191-202` | `remaining` 상한으로만 쓰고 초과 byte 즉시 실패 |
| 23 | [427] 1-byte range probe의 200 Content-Length를 전체 길이로 신뢰 | 이미 고쳐짐 | `download/Downloader.kt:340-372` | 200 probe는 `measureWhole`로 무range 재측정 |
| 24 | [389] busy 확인과 `stopSelf` 사이 enqueue가 서비스와 함께 멈춤 | 이미 고쳐짐 | `download/DownloadService.kt:130-161,425-460` | `StartIdLedger.stopIdAfter/stopConfirmed`로 최신 start만 종료 |
| 25 | [411] MV 백그라운드 취소 HIGH 수정의 실패-선행 테스트 없음 | 남김 | `app/src/test/.../MvSoundRedownloadPolicyTest.kt:1-80` | 취소 판정(`cancelBeforeStart`, `cancelTargets`)은 테스트됨. 실제 WorkManager seam은 계측이 필요해 이번 범위에서 미추가 |
| 26 | [426] async search 시작 후 caller가 checked/cooldown 먼저 기록 | 이미 고쳐짐 | `download/HqLibraryUpgrade.kt:528-536,586-653`; `DownloadHealth.kt:230-233` | `SEARCHING`은 pending marker + cooldown/attempt로 기록, checked는 QUEUED에서만 |
| 27 | [389] MV `APPEND_OR_REPLACE`가 retry backoff 뒤에 붙어 즉시 재개 실패 | 이미 고쳐짐 | `download/MvSoundRedownload.kt:199`; `BitChordApplication.kt:289-296` | 허용 전환은 `replace=true`로 REPLACE |
| 28 | [366] metered 검사가 foreground 검사보다 먼저라 백그라운드 셀룰러 retry | 이미 고쳐짐 | `download/MvSoundRedownload.kt:329-347` | worker가 foreground를 먼저 확인하고 백그라운드면 success |
| 29 | [369] 거부된 upgrade가 unchecked로 남아 매 metered 재생마다 디코드 | 이미 고쳐짐 | `download/DownloadHealth.kt:58-61,193-201,336-351` | pending upgrade marker가 decode 없이 다음 허용 재생까지 대기 |
| 30 | [526] CANCELLED leaf가 다음 APPEND_OR_REPLACE에서 실행 중 pass까지 교체 | 이미 고쳐짐 | `download/MvSoundRedownload.kt:255-283` | 체인에 RUNNING이 있으면 queued successor도 취소하지 않음 |
| 31 | [464] requeue가 `automatic`/`mvSoundRepair`를 잃음 | 지적이 틀림 | `download/Downloads.kt:622-635,1902-1905` | 현재 코드에 requeue 경로가 없고 cancel은 행/세션을 정리하므로 재시도 flag가 유실되는 경로가 없음 |
| 32 | [555] `onStopped`가 running을 즉시 비워 옛 worker가 새 claim을 덮어씀 | 지적이 틀림 | `download/Downloads.kt:622-635,1886-1905` | requeue/claim generation 이전 구조가 없고, 취소는 해당 행을 제거해 새 claim을 만들지 않음 |
| 33 | [321] 네트워크 capabilities null을 online으로 판정 | 이미 고쳐짐 | `playback/AudioCache.kt:661-680,3473-3481` | `offlineFromCapabilities(hasCapabilities=false)` → offline |
| 34 | [443] `SocketTimeoutException`이 InterruptedIOException이라 timeout도 성공 credit | 이미 고쳐짐 | `playback/ChunkedDataSource.kt:32-34,506-520` | timeout은 failed transfer, 실제 취소만 제외 |
| 35 | [461] wrong-recording search 후 cooldown 미기록으로 매 재생 재검색 | 이미 고쳐짐 | `download/DownloadHealth.kt:95-97,297-305` | `SEARCHING`도 요청으로 인정해 `markRepairRequested` |
| 36 | [418] pending upgrade marker가 cooldown/dedup/expiry 없이 매 재생 검색 | 이미 고쳐짐 | `download/DownloadHealth.kt:58-86,193-237` | 24h repair cooldown + `PENDING_UPGRADE_MAX_ATTEMPTS=3` |
| 37 | [649] running 행이 즉시 Failed/Done이 되면 Queued로 되돌림 | 이미 고쳐짐 | `download/Downloads.kt:531-568` | claim된 행의 Running/Failed/비Queued 세션은 덮지 않음 |
| 38 | [383] 로컬/다운로드 파일 재측정에 served 이벤트가 없어 15분 대기 | 이미 고쳐짐 | `ui/LyricsController.kt:946`; `shared/.../LocalTimingPolicy.kt:65` | 로컬 파일은 stream 대기 없이 즉시 다음 단계로 진행 |
| 39 | [670] HeldElsewhere에서 claim을 버려 idle tick마다 4Hz 재시도 | 이미 고쳐짐 | `playback/AudioCache.kt:2466-2478,2860-2890,1648-1656` | partial lock 보유 시 claim 유지, reader release 이벤트에서 1회 재시도 |
| 40 | [453] `open()`의 pre-release가 reader release로 오인되어 HEAD가 재생 reader와 경쟁 | 여전함→고침 | `playback/AudioCache.kt:1494,1518-1532` | open의 pre-release는 `retryAnalysisHead=false`, 실제 close만 재시도 |
| 41 | [418] rate-limited passthrough open이 host failure를 두 번 기록 | 여전함→고침 | `playback/ChunkedDataSource.kt:128-144,200-209` | 429/502/503/504는 gate가 기록했으므로 `countedByGate`로 중복 방지 |
| 42 | [400] openChunk 비rate-limit 실패가 close 없이는 host failure 미기록 | 이미 고쳐짐 | `playback/ChunkedDataSource.kt:174-209` | 실패 지점에서 `HqHostTracker.noteFailure` |
| 43 | [416] atLeastAsGood 채택이 wrong recording/cache를 replacement로 인정 | 이미 고쳐짐 | `download/HqLibraryUpgrade.kt:83-108,793-807` | source/track provenance 일치 + `wrongRecording` 제외 필요 |
| 44 | [410] 200 probe의 부분 fragment를 전체 길이로 신뢰 | 이미 고쳐짐 | `download/Downloader.kt:340-372` | 모든 200 probe를 `measureWhole`로 재측정 |
| 45 | [522] 새 start가 stopSelf 직전에 오면 foreground notification 없이 새 drain | 이미 고쳐짐 | `download/DownloadService.kt:231-245,448-460` | `stopSelfResult`가 실제 종료를 확인한 뒤에만 notifier/foreground 해제 |
| 46 | [451] `retryActive` HIGH 수정 경로 테스트 없음 | 이미 고쳐짐 | `app/src/test/.../HqRetryActiveTest.kt:1-90` | 계획 유지·재시도 예산 미소모를 직접 검증 |
| 47 | [578] 이전 Failed/Done 행을 새 enqueue가 Queued로 되돌리지 않음 | 여전함→고침 | `download/Downloads.kt:234-239,477,531-568,1889` | enqueue/claim generation으로 이번 enqueue가 claim되지 않았으면 Queued 강제, 테스트 추가 |
| 48 | [834] `mergeParkedPlan`이 정책/설정에서 제외된 target까지 부활 | 여전함→고침 | `download/HqLibraryUpgrade.kt:224-239,930-965,1053-1065,1327-1358` | cooldown만 무시한 `resumable` 집합에 있는 kept entry만 유지, 선택 테스트 추가 |
| 49 | [328] playback audio loop가 sleeping gateway(502/504)에서 rest 없이 429만 처리 | 이미 고쳐짐 | `playback/ChunkedDataSource.kt:535-583` | `isSleepingGateway`도 gate에 포함해 한 번 wake 후 rest |
| 50 | [332] `downloadPrefix`가 502/504를 일반 IOException으로 분류 | 이미 고쳐짐 | `playback/smart/HqAudioVerify.kt:1298-1318` | `isRateLimited || isSleepingGateway`를 `RateLimitedDownload`로 승격 |
| 51 | [551]/[541] pause의 generation/state 재확인이 취소 직전에 원자적이지 않음 | 여전함→고침 | `download/MvSoundRedownload.kt:159-166,203-207,250-278` | foreground schedule과 pause cancel을 `scheduleLock`으로 직렬화하고 lock 안에서 generation/state 재확인 |
| 52 | [179] `SocketTimeoutException` 제외 및 네 실패 경로 사용은 올바름 | 지적이 틀림 | `playback/ChunkedDataSource.kt:32-34,128,174,438,462` | 수정할 결함이 아니라 현재 구현 설명 |
| 53 | [648] 완료 후 `aiAvailable`을 재계산하지 않아 AI timing 버튼이 계속 숨음 | 여전함→고침 | `ui/LyricsController.kt:774-793`; `AudioCache.kt:765-820` | 완료 상태 StateFlow를 collect해 재계산(이벤트 기반, polling 없음) |
| 54 | [464] DownloadHealth의 새 UPGRADE/SEARCHING이 cooldown/attempt를 기록하지 않음 | 여전함→고침 | `download/DownloadHealth.kt:308-327` | SEARCHING에서 `markRepairRequested` + `markUpgradeAttempt` |
| 55 | [618] 다음 행 예약이 재생 중인 B의 lossless fetch를 취소 | 여전함→고침 | `playback/AudioCache.kt:2261-2268` | `mediaId == currentMediaId`면 취소하지 않음 |
| 56 | [354] mid-play recovery 성공 후에도 failed transfer로 기록 | 여전함→고침 | `playback/ChunkedDataSource.kt:174-199` | recovery 성공 시 `transferFailed=false`로 성공/throughput credit 유지 |
| 57 | [587] completion replay=0/버퍼 포화로 완료 이벤트 유실 | 여전함→고침 | `playback/AudioCache.kt:765-820`; `ui/LyricsController.kt:774-793` | 완료를 `StateFlow<Set>`로 보관하고 late collector도 현재 상태를 읽음 |
| 58 | [590] 완료 wiring을 실제 listener/collector 경로로 검증하는 테스트 없음 | 남김 | `app/src/test/.../VideoAudioTimingReadyTest.kt`, `shared/src/jvmTest/.../LyricsAiGuardTest.kt` | 동작은 StateFlow collect로 연결했지만 Android Cache.Listener를 구동하는 테스트는 계측 seam이 필요해 미추가 |
| 59 | [495] load-error policy가 502/504를 terminal로 보지 않아 Media3가 재시도 | 여전함→고침 | `playback/PlaybackService.kt:4881-4891,4980-4990` | `isSleepingGateway`를 rate-limit/terminal 판정에 포함 |
| 60 | [536] 취소된 replacement 후 다음 target/cooldown wake를 예약하지 않아 pass 정지 | 여전함→고침 | `download/HqLibraryUpgrade.kt:766-771,855-875` | `resumeAfterCancellation`이 다음 runnable 또는 parked wait를 예약 |
| 61 | [575] metered에서 `runOne`이 parked plan을 `finishRun`으로 지움 | 여전함→고침 | `download/HqLibraryUpgrade.kt:911-919,930-965` | metered 분기는 계획을 보존하고 반환; merge도 정책 선택만 유지 |
| 62 | [575] `shouldResumePlan`이 `plan.isNotEmpty()`로 붕괴 | 이미 고쳐짐 | `download/HqLibraryUpgrade.kt:420-421,1162-1186` | 위 61과 48의 보존/정리로 non-empty 계획은 cooldown deadline이 있는 항목만 남음; 불필요한 finishRun 없음 |

## 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.*' --console=plain -q` 통과 (106 tests).
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.*' --console=plain -q`에서 648개 중 1개 실패: `BluetoothAudioTrackerTest`의 기존 로케일 문자열(`System Managed` vs `시스템 관리`) 불일치로 이번 변경과 무관.
</content>
