# deep-hq-1031 — 무손실 영역 엄격 검토

기준: `task/fix-1030` 계열의 현재 작업 트리 (`dsq/deep-hq-1031`).
범위: `HqAudioVerify`, 재생 중 교체(`hq-swap`), 다음 곡 무손실 재준비,
`hq-prefetch`/`AudioCache` 사본 선택, `QualityUpgrade`·되돌리기·최고 음질 안내·배지,
`HqLibraryUpgrade`(고음질 보관함), 애드온 429.

검토 방식: 진입점(재생 시작, 다음 곡 준비, 수동 업그레이드, 다운로드/보관함, 429)에서
결과(교체/보류/거부/실패)까지 코드를 따라갔고, 조건이 거짓일 때 로그 없이 반환하는
경로를 별도로 표시했다. 이번 커밋은 HIGH/MEDIUM 중 코드로 확정 가능한 것을 먼저
고치고, 나머지는 아래에 이유와 필요한 작업을 남긴다.

---

## 1. 이번에 수정한 결함

### 1.1 MEDIUM — 검증 타임아웃이 증명으로 둔갑하던 문제 (수정)

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:7520`,
  `app/src/main/java/com/music/bitchord/playback/HqSwapPolicy.kt:105`
- 증거(수정 전): `awaitPlayerCondition(UPGRADE_PROVE_MS, …)` 의 Boolean 결과를 버리고,
  조건이 타임아웃으로 끝난 뒤에도 `player.duration` 을 읽어 길이만 맞으면
  `onProven()` 을 호출했다. 옛 루프는 타임아웃 자체를 실패로 보고 되돌렸다.
- 사용자 영향: 교체본이 제때 준비되지 않았는데도 옛 사본을 지우고, 다음 구간에서
  소리가 끊기거나 곡이 사라질 수 있다.
- 흔한 정도: 느린/끊긴 망, 화면 전환, 7초 안에 버퍼가 차지 않는 FLAC에서 흔함.
- 수정: 결과를 `proven` 으로 보존하고 `HqSwapPolicy.provesDuration(proven, …)` 로
  판정한다. pin/URI bail-out 뒤에 적용하므로 사용자가 누른 되돌리기는 그대로 존중된다.

조건 진리표(수정 전 → 수정 후):

| proven | duration 일치 | 수정 전 onProven | 수정 후 onProven |
|---|---|---|---|
| true | true | true | true |
| true | false | false | false |
| false | true | **true(버그)** | false(되돌림) |
| false | false | false | false |

### 1.2 MEDIUM — 스냅샷이 YouTube라고 말하는데 낡은 대체 choice로 시계를 만들던 문제 (수정)

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:5776`,
  `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:84`
- 증거(수정 전): `currentPlayingVerification` 의 fallback 조건이
  `snapshot?.sourceConfigId == null` 뿐이었다. `sourceName == "YouTube"` 인
  스냅샷도 `StreamChoice` 의 낡은 대체 스트림을 빌려 verified offset/rate를 붙였다.
- 사용자 영향: 직접 YouTube로 되돌린 곡에서 교체 seek 위치가 어긋나 다른 구간이
  재생되거나, 배지/통계가 잘못된 소스를 가리킨다.
- 흔한 정도: 업그레이드 실패 후 되돌리기, 화면 꺼짐으로 보류 후 재생, 소스 전환.
- 수정: `shouldFallBackToChoice(sourceConfigId, kind, sourceName)` 로 kind가
  YouTube이거나 id 없이 이름이 YouTube이면 fallback을 금지한다.

조건 진리표(수정 전 → 수정 후):

| snapshot id | kind | sourceName | 수정 전 fallback | 수정 후 fallback |
|---|---|---|---|---|
| null | null | null | true | true |
| null | null | "YouTube" | **true(버그)** | false |
| null | YOUTUBE | "YouTube" | **true(버그)** | false |
| "qobuz" | MODULE | "Qobuz" | false | false |

### 1.3 MEDIUM — 교체 준비 중 중단이 lossless 상태를 남기던 문제 (이미 현재 트리에서 해결됨, 확인)

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:6019-6035`
- 확인 결과: `NerdStats.onSourceStream`/`recordSource`/`StreamChoice.remember`/
  `QualityUpgrade.unshelve` 기록은 **pin/URI/플레이어 bail-out 이후**에 있다
  (`6032-6035`). 즉 준비/블렌드 중 중단 경로는 이 상태를 쓰지 않는다. 주석
  `6028-6031` 도 같은 이유를 명시한다. 별도 수정은 하지 않았다.
- 남은 위험: `adoptPlayerForVersionSwap` 이후 `watchUpgrade` 되돌리기는
  `QualityUpgrade.forgetReverted` + 선언 포맷 복원으로 처리된다(`7568-7576`).

### 1.4 MEDIUM — 고음질 보관함 prune이 24시간 쿨다운 대상을 삭제하던 문제 (수정)

- 파일: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:707-734`
- 증거(수정 전): `prunePlanLocked` 가 `automaticTargetEligible(..., foreground = true)` 를
  호출했다. `foreground` 는 창 제약만 우회하고, 내부의
  `AutomaticRetryPolicy.mayRetry` 는 여전히 24시간 쿨다운에서 `false` 를 돌려준다.
  그래서 “일시 보류” 대상이 “영구 제외”처럼 plan에서 삭제됐다. 기존 테스트는
  `attempts=0` 이라 이 경우를 놓쳤다.
- 사용자 영향: 429/일시 실패 후 다음 실행에서 보관함 작업이 사라져, 사용자가 다시
  “모두 고음질로”를 누르기 전까지 영원히 재시도되지 않는다.
- 흔한 정도: 애드온 429, 느린 망, 백그라운드→포그라운드 전환 직후.
- 수정: 영구성만 직접 검사한다 — `attempts >= AutomaticRetryPolicy.MAX_FAILURES`
  또는 `hasCopyAtLeastAsGood(...)`. 쿨다운은 plan에 남기고 실행만 미룬다.

조건 진리표(수정 전 → 수정 후):

| attempts | alreadyAtTargetTier | 쿨다운 중 | 수정 전 prune | 수정 후 prune |
|---|---|---|---|---|
| 0 | false | – | false(유지) | false(유지) |
| 1 | false | 예 | **true(삭제, 버그)** | false(유지) |
| 1 | false | 아니오 | false(유지) | false(유지) |
| MAX | false | – | true | true |
| any | true | – | true | true |

### 1.5 MEDIUM — Queued 게시가 거부/서비스 인수를 덮어쓰던 경쟁 (수정)

- 파일: `app/src/main/java/com/music/bitchord/download/Downloads.kt:416`, `:451-466`
- 증거(수정 전): `publishQueuedIfPending(id)` 는 `pending` 확인과 `_active` 갱신만
  같은 lock에서 했고, 뒤이은 `DownloadSession.queued(song, from)` 는 lock 밖이었다.
  `onForeground` 의 `abandonPending`+`fail` 또는 서비스가 행을 가져간 직후에
  다운로드 관리자만 Queued로 남을 수 있었다.
- 사용자 영향: 다운로드 관리자에 Queued/Running 행이 남지만 실제 pending 행이나
  서비스가 없어 영원히 멈춘 것처럼 보인다.
- 흔한 정도: 백그라운드에서 enqueue 후 즉시 포그라운드 전환, Android 14+ 서비스 거부.
- 수정: pending 확인, `_active`, `DownloadSession` 게시를 모두 같은 lock 안에서
  수행한다. 자동 패스(`automatic=true`)는 관리자 행을 게시하지 않는 기존 규칙 유지.

### 1.6 MEDIUM — 429 대기 항목이 일시 실패 한 번에 소모되던 문제 (수정)

- 파일: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:139-154`,
  `:162-181`, `:375-378`, `:432-472`, `:530-549`
- 증거(수정 전): `settlesPendingRetry()` 가 `Abandoned` 만 예외로 두고 모든
  `NoReference`/`Unavailable` 을 확정으로 봤다. 실제 live 경로의
  `NoReference` 는 화면 꺼짐/head timeout/decode 실패(`:924`)와 취소(`:919`)라
  일시적이며, 호스트 휴식으로 시도조차 못 한 `Unavailable`(`:357`)도 포함됐다.
- 사용자 영향: 429 한 번 뒤 재시도가 “시도도 못 하고” 소모되어, 그 곡은 이번
  재생 동안 영원히 무손실 확인을 못 한다.
- 흔한 정도: 화면을 끈 채 다음 곡 준비, 느린 망에서 head timeout, 동시 다운로드로
  호스트 휴식 연장.
- 수정:
  - 호스트 휴식은 `Outcome.Deferred` 로 분리.
  - `NoReferenceReason.transient`(NO_SOURCE만 영구)와
    `Unavailable(transient=...)` 로 일시 실패를 표시.
  - `settlesPendingRetry` 는 Deferred/일시 실패를 유지하고, 두 번째 429·측정
    결과·영구 거부만 소모.

조건 진리표(수정 전 → 수정 후):

| outcome | 수정 전 소모 | 수정 후 소모 |
|---|---|---|
| Verified / Mismatch | true | true |
| Abandoned | false | false |
| Deferred (호스트 휴식) | – (Unavailable=true) | false |
| NoReference(HEAD_TIMEOUT/DECODE_FAILED/CANCELLED) | true | **false** |
| NoReference(NO_SOURCE) | true | true |
| Unavailable(transient=false) | true | true |
| Unavailable(transient=true) | true | **false** |
| null(예상 밖 예외) | true | true |

### 1.7 LOW — 호스트 휴식 재확인과 실제 검증 사이의 경쟁 (수정, 1.6에 포함)

- 파일: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:375-378`,
  `:600-634`
- 증거(수정 전): 재시도 루프가 `restRemainingMs <= 0` 을 확인한 뒤 `verifyOutcome`
  안에서 다시 휴식을 확인해 `Unavailable` 을 돌려주면, 그 사이 동시 다운로드가
  휴식을 연장한 경우에도 항목이 삭제됐다.
- 수정: 그 경로를 `Outcome.Deferred` 로 바꿔 항목을 유지한다.

---

## 2. 흔한 상황별 추적표

| 상황 | 진입점 → 처리 | 실제 결과 / 근거 |
|---|---|---|
| 커버 영상 | `HqAudioVerify.verifyOutcome` 배열 검사 `:336-345` → `reject` 저장 | 교체 불가, `Mismatch`. 이후 같은 쌍은 저장된 거부로 즉시 거부 |
| 비디오 노래 | `NextTrackPreparation.hqWanted` `:410-414` (`song.isVideo`면 false), `syncWanted` `:460-465` | 다음 곡 무손실/싱크 준비 자체를 건너뜀. 재생 중 수동 업그레이드만 가능 |
| 셔플/빠른 스킵 | `NextTrackPreparation.schedule` `:95-115`, `AudioCache.prefetchQueue` `:1577-1585` | 다음 행이 바뀌면 진행 중 번들을 취소. 준비가 끝나기 전에 넘어가면 29% 실패로 이어짐 |
| 앱 재시작 후 | `HqVerificationStore`는 영구, `StreamChoice`는 메모리+TTL(`StreamChoice.kt:54-75`) | 검증 결과는 남지만 실제 스트림 선택은 사라져, 첫 재생에서 read-ahead/재검증 전까지 lossless 재준비 불가 |
| 화면 꺼짐 | `VideoAudio.songWav` `:68` (`!PowerState.screenOn` → null), `swapIn` `:6832` (shelve), 배터리 정책 | 검증은 `NoReference(DECODE_FAILED)`, 교체는 보류. 1.6 수정으로 429 재시도는 보존됨 |
| 백그라운드 | `Downloads.enqueue` `:425` (포그라운드일 때만 서비스 시작), `HqLibraryAutoRetryPolicy` `:37` | 큐에만 남고 서비스는 포그라운드 이벤트에서 시작. 사용자 조작 없는 시작이 11회로 제한되는 이유 |
| 프록시 켬 | `ProxyPlayback.mediaClientFor` (candidate/playback 공통), `HqHostTracker.hostOf` | 같은 클라이언트/헤더로 후보 head를 받아 검증. 호스트 휴식은 프록시 URL 기준 |
| 다운로드된 곡 | `NextTrackPreparation.hqWanted` `:410` (`localUri != null`이면 false), `timed` `:467-489` (`fromFile`+비비디오면 `baseAllowed=false`) | 무손실 업그레이드·싱크 준비 제외. 저장된 파일은 다운로드 경로가 담당 |
| 느린/끊긴 망 | `candidateFetch` 429/backoff `:1040-1110`, `NEXT_PREP_HQ_WAIT_MS=15s`, `watchUpgrade` 7s prepare/10s proof | 후보 head/전체 rendition이 제때 안 오면 교체 실패 또는 되돌림 |
| Android 14+ | `Downloads.enqueue` `:425-435`, `onForeground` `:457-471` | 서비스 시작 거부 시 pending을 되돌리고 실패 행 표시(무한 Queued 방지) |
| 고음질 보관함 429 | `HqLibraryUpgrade.settle` `:844-872`, `AutomaticRetryPolicy` 24h/2회 | 일시 실패는 쿨다운 동안 plan에 유지(1.4 수정), 2회 후 수동 패스 전까지 정지 |

---

## 3. 성적표 원인 분석 (vc85, 362곡)

### 다음 곡 싱크 준비 29%
- `NextTrackPreparation.syncWanted` 는 비디오·`heavyWorkAllowed`·`aiTimingAhead`·
  `Deepgram.usable` 네 조건을 모두 요구한다(`:460-465`). 하나라도 거짓이면 로그 없이
  건너뛴다.
- `timed()` 의 `baseAllowed = timeable` 은 다운로드 파일(`fromFile=true`)이고
  검색어가 비디오가 아니면 false다(`:467-489`). 저장한 곡은 사실상 제외된다.
- `attempted` 는 단계를 실행 전에 기록하고 같은 행에서는 재시도하지 않는다
  (`:71-76`, `:169-206`). 일시적 429/망 실패 한 번이 그 행의 준비를 끝낸다.
- 셔플/빠른 스킵은 `schedule` 에서 번들을 취소한다(`:95-115`).
→ 위 네 가지가 겹쳐 “준비됨” 비율이 29%로 낮다.

### 무손실 확인 후 실제 무손실 재생 4%
- 확인과 재생 사이에 게이트가 많다: 화면 꺼짐 shelve(`:6832`), 20초 미만
  (`:6838`, `:7027`), 크로스페이드 중(`:7005`), pin/URI 변경(`:6019`, `:7524`),
  substitute 시계 불명(`:5697`), 강제 스트림 유실(`:7039`).
- `smoothSwapToLossless` 는 spare prepare 7초/start 600ms를 기다린다
  (`HqSwapPolicy.PREPARE_TIMEOUT_MS`, `START_TIMEOUT_MS`). 느린 망에서는 여기서 실패.
- `downloadVerifiedRendition` 은 **전체 rendition이 디스크에 완료**돼야 true다
  (`AudioCache.kt:2094-2121`). 검증만 되고 다운로드가 덜 되면 교체하지 않는다.
- 다음 곡 재준비는 `StreamChoice` 가 lossless일 때만 동작한다
  (`losslessAheadItem` `:6131-6147`). 검증이 ARMING 이후/블렌드 이후에 끝나면
  `refreshArmedIncomingLossless` 가 개입할 시점을 놓친다(`:6156+`).
→ “확인됨”과 “실제 lossless로 들림” 사이의 간극이 4%의 주원인이다.

### 자동 재생 보충 성공 24%
- `loadAutoplayForCurrentTrack` 는 `autoplaySeed == current.videoId` 이면 즉시
  반환한다(`:3593`). 네트워크 실패/빈 결과 뒤에도 seed를 지우지 않아 같은 곡에서는
  다시 시도하지 않는다. 실패는 로그만 남기고 빈 목록으로 끝난다(`:3623-3630`).
- `needed <= 0`, 파티 공급자 아님, repeat-all, bounded context 등 게이트도 많다
  (`:3565-3594`).
→ 끊긴 망에서 한 번 실패한 곡은 다음 곡으로 넘어가기 전까지 보충되지 않는다.

### 사용자 조작 없는 백그라운드 시작 11회
- `Downloads.enqueue` 는 `AppForeground.isForeground` 일 때만 서비스를 시작하고
  (`:425`), 아니면 pending으로 남긴다. `onForeground` 가 다시 시도한다(`:457-471`).
- `HqLibraryUpgrade` 는 WorkManager + 포그라운드 창 규칙을 따른다
  (`HqLibraryAutoRetryPolicy:37`). Android 14+의 FGS 제약이 그대로 반영된다.
→ “사용자 조작 없는 시작”은 설계상 포그라운드 이벤트/WorkManager에 묶여 11회가 한계.

### 다운로드·고음질 보관함 실패 140회
- Wi-Fi 전용 거부도 사용자 실패로 집계된다(`Downloads.enqueue:390-401`).
- 서비스 시작 거부는 즉시 실패 행이 된다(`:432-434`, `:468-470`).
- 429는 후보 검증에서 1회 재시도 후 pending, 다운로드/보관함에서는
  `MAX_TRANSIENT_ATTEMPTS` 후 실패로 기록되고, `AutomaticRetryPolicy` 의 24시간
  쿨다운·`MAX_FAILURES=2` 로 자동 재시도가 멈춘다.
- 1.4의 prune 버그는 “일시 보류”를 삭제해 plan을 재구성/재시도하게 만들었고,
  이는 같은 서버를 다시 두드려 429를 늘리는 악순환이었다.
→ 429 + FGS 제약 + 24시간 쿨다운 + 거부 집계가 140회의 구조적 원인.

---

## 4. 고치지 못한/범위 밖 결함

| 심각도 | 파일:줄 | 내용 | 이유 / 필요한 작업 |
|---|---|---|---|
| MEDIUM | `NextTrackPreparation.kt:71-76`, `:169-206` | 일시 실패 단계를 같은 행에서 영구 포기 | 의도된 배터리 절약(주석). 다만 429/망 실패만 `attempted`에서 제거하는 재시도 정책이 필요. 제품 결정 필요 |
| MEDIUM | `PlaybackService.kt:3593` | autoplay 실패/빈 결과 후 seed 유지로 같은 곡 재시도 없음 | 보충 성공률 24%의 원인. 빈 결과에서만 seed를 지우는 수정이 가능하나, 이벤트마다 재요청하는 폭주 방지 정책과 함께 설계해야 함 |
| MEDIUM | `PlaybackService.kt:6131-6147` | `losslessAheadItem` 이 여러 조건에서 로그 없이 null | ② 규칙상 결함. 다만 매 null마다 로그하면 로그가 폭증하므로 디버그 전용 집계가 필요 |
| MEDIUM | `PlaybackService.kt:9218` | `while (crossfade?.isTransitioning() == true) delay(50)` | 출력 재구성 경로의 주기 폴링. 무손실 영역 밖이며 업스트림 코드. 이벤트 기반 대기로 바꾸려면 CrossfadeController 콜백 추가 필요 |
| LOW | `PlaybackService.kt:7495` | `previousDuration <= 0` 이면 증명 없이 `onProven` | 옛 길이를 모를 때는 증명 자체가 불가능. 되돌릴 URI가 없는 경우와 함께 정책 결정 필요 |
| LOW | `HqAudioVerify.kt:541-549` | `null`(예상 밖 예외)은 여전히 재시도 소모 | 영구 버그와 일시 예외를 구분할 정보가 없음. 무한 재시도 방지를 위해 현 동작 유지 |

---

## 5. 테스트

새/수정 테스트(수정 전 동작을 고정하던 기대는 새 계약에 맞게 바꿨다):

- `app/src/test/java/com/music/bitchord/playback/smart/HqAudioVerifyPendingRetryTest.kt`
  - `a transient unavailable answer keeps the pending retry`
  - `a live transient no-reference keeps the pending retry`
  - `a missing source is permanent and consumes the pending retry`
  - `a host still resting keeps the pending retry`
  - `a permanent unavailable answer consumes the pending retry`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeBackgroundPruneTest.kt`
  - `a target resting inside the retry cooldown is parked, not pruned`
  - `a target with exhausted automatic retries is pruned`
- `app/src/test/java/com/music/bitchord/download/DownloadsQueuedPublishRaceTest.kt`
  - `a queued publish does not overwrite a refusal after the row was taken back`
    (다운로드 관리자 행까지 검증하도록 확장; 헬퍼가 두 게시를 함께 수행하는
    새 계약을 고정한다)
- `app/src/test/java/com/music/bitchord/playback/HqSwapPolicyTest.kt`
  - `a proof that timed out is not rescued by a late duration`
  - `a proved duration inside the slack keeps the replacement`
  - `a proved but short replacement is reverted`
- `app/src/test/java/com/music/bitchord/playback/smart/HqVerificationSourceIdentityTest.kt`
  - `a silent snapshot may fall back to the recorded choice`
  - `a snapshot that names YouTube never borrows a substitute choice`
  - `a snapshot with a source id has spoken and does not fall back`

실행 결과:
- `:app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` → 성공
- 위 5개 테스트 클래스 → 32 tests, 0 failures
- `:shared:jvmTest --tests AutomaticRetryPolicyTest --tests HqLibraryAutoRetryPolicyTest` → 성공
- `:app:testProdReleaseUnitTest` 전체 중 `BluetoothAudioTrackerTest` 1건 실패는
  로케일 차이(`System Managed` vs `시스템 관리`)로 이번 변경과 무관.

---

## 6. 변경 파일

- `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `app/src/main/java/com/music/bitchord/playback/HqSwapPolicy.kt`
- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`
- `app/src/main/java/com/music/bitchord/download/Downloads.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/HqAudioVerifyPendingRetryTest.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/HqVerificationSourceIdentityTest.kt`
- `app/src/test/java/com/music/bitchord/playback/HqSwapPolicyTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeBackgroundPruneTest.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadsQueuedPublishRaceTest.kt`

---

## 7. 2차 재심 (2026-10-30, 기준 = 1차 결과)

1차에서 고친 세 경로를 다시 따라가며, 그 수정이 만든 새 경합/누락을 코드로 확인했다.
이번 재심에서 확정한 HIGH는 없다. 아래 MEDIUM 3건은 모두 수정했고, 각각 수정 전에
실패하는 테스트를 먼저 추가했다.

### 7.1 MEDIUM — 서비스가 행을 가져간 뒤 Queued 게시가 사라지던 문제 (수정)

- 파일: `app/src/main/java/com/music/bitchord/download/Downloads.kt:456`
- 증거(수정 전): `publishQueuedIfPending` 이 `song.videoId !in pending` 이면 반환했다.
  `enqueue` 가 `pending` 을 넣은 뒤 lock 을 놓친 사이 `DownloadService` 가
  `takeNext()`(`:1767`)로 행을 `pending` → `running` 으로 옮기면, 이 게시는 아무것도
  하지 않았다. 뒤이은 `DownloadSession.running/done`(`DownloadSession.kt:220`)은
  행이 없어 갱신할 대상이 없고, 다운로드 관리자에서 그 곡이 조용히 사라졌다.
- 사용자 영향: 다운로드가 실제로 진행/완료돼도 관리자에 행이 없어 진행률·완료를
  볼 수 없다.
- 흔한 정도: 백그라운드 enqueue 직후 서비스가 이미 떠 있거나, 연속 다운로드에서
  다음 행을 즉시 집는 경우.
- 수정: 같은 lock 안에서 `pending` **또는** `running` 인 동안 게시한다. `onForeground`
  의 거부는 `pending` 만 제거하므로 `running` 이면 거부가 이긴 상태가 아니고,
  서비스가 가져간 행은 반드시 세션 행을 만들어야 한다.

조건 진리표(수정 전 → 수정 후):

| pending | running | 수정 전 게시 | 수정 후 게시 |
|---|---|---|---|
| true | false | true | true |
| false | true (takeNext 승) | **false(행 유실)** | true |
| false | false (거부 승) | false | false |

### 7.2 MEDIUM — 재시작 후 24시간 쿨다운 대상이 즉시 재실행되던 문제 (수정)

- 파일: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:608`,
  `:713`, `:733`
- 증거(수정 전): `prunePlanLocked` 는 의도적으로 `attempts=1` 이고 24시간 쿨다운 중인
  대상을 plan 에 남긴다. 그런데 `runOne` 은 `plan.firstOrNull()` 로 그 대상을 골랐고,
  `notBeforeMs` 만 확인했으므로 `AutomaticRetryPolicy.mayRetry` 의 쿨다운을 보지 않고
  즉시 서버를 다시 두드렸다.
- 사용자 영향: 429/일시 실패 뒤 앱을 다시 열거나 재생을 시작할 때마다 같은 서버를
  재차 호출해 429를 늘리고, “24시간 쉰다”는 보관함 정책이 지켜지지 않는다.
- 흔한 정도: 애드온 429, 느린 망, 앱 재시작/포그라운드 복귀 직후.
- 수정: `nextRunnableTargetLocked` 가 `buildPlan` 과 동일한
  `automaticTargetEligible`(포그라운드·이미 목표 음질·시도 횟수·쿨다운)을 적용해
  실제로 실행 가능한 첫 대상만 고른다. 모두 쿨다운 중이면 `nextParkedWaitLocked` 가
  가장 이른 해제 시각을 계산해 WorkManager 에 그 지연으로 맡긴다(폴링·sleep 없음).

조건 진리표(수정 전 → 수정 후):

| attempts | 쿨다운 중 | 수정 전 선택 | 수정 후 선택 |
|---|---|---|---|
| 0 | – | 선택 | 선택 |
| 1 | 예 | **선택(즉시 재실행)** | 건너뜀, 쿨다운 만료 시 재예약 |
| 1 | 아니오 | 선택 | 선택 |
| MAX | – | (prune 후 plan 에 없음) | (동일) |

### 7.3 MEDIUM — 영구 참조 실패가 429 재시도를 영원히 소모하지 않던 문제 (수정)

- 파일: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:108`,
  `:119`, `:563`, `:666`
- 증거(수정 전): `NO_SOURCE` 는 유일한 비일시 `NoReference` 사유인데 실제 live 경로에서
  만들어지지 않았다. WAV 공급(`VideoAudio.songWav`)은 화면 꺼짐·head timeout·디코드
  실패·소스 없음을 모두 `null` 로 돌려주고, `HqAudioVerify` 는 이를 전부
  `DECODE_FAILED`/`Unavailable(transient=true)` 로 기록했다. 그래서 진짜 소스가 없는
  곡도 429 pending 항목이 매 재생 이벤트마다 후보 다운로드·디코드를 반복했다.
- 사용자 영향: 재생할 때마다 후보를 내려받고 디코드해 배터리·데이터·시간을 낭비하고,
  로그에도 같은 실패가 계속 쌓인다.
- 흔한 정도: 소스가 사라졌거나 지역 제한인 곡, 화면을 오래 끈 채 다음 곡 준비.
- 수정: `PendingRateLimit.transientAttempts` 를 두고, 일시 실패는
  `MAX_PENDING_TRANSIENT_ATTEMPTS=3` 까지만 유지한다. `Abandoned`/`Deferred` 는 예산을
  쓰지 않고, 3번째 일시 실패 뒤에는 항목을 정리하며 로그를 한 번 남긴다. 주기 타이머는
  없고 기존 재생 이벤트 기반 루프에서만 센다.

조건 진리표(수정 전 → 수정 후):

| outcome | transientAttempts | 수정 전 소모 | 수정 후 소모 |
|---|---|---|---|
| Verified / Mismatch | – | true | true |
| Abandoned / Deferred | 임의 | false | false |
| NoReference(HEAD_TIMEOUT/DECODE_FAILED/CANCELLED) | < 3 | false(무한 유지) | false(유지, +1) |
| NoReference(HEAD_TIMEOUT/DECODE_FAILED/CANCELLED) | ≥ 3 | false(무한 유지) | **true(정리)** |
| Unavailable(transient=true) | < 3 | false(무한 유지) | false(유지, +1) |
| Unavailable(transient=true) | ≥ 3 | false(무한 유지) | **true(정리)** |
| NoReference(NO_SOURCE) / Unavailable(transient=false) | – | true | true |
| null(예상 밖 예외) | – | true | true |

### 7.4 남은 HIGH·MEDIUM

이번 재심에서 코드로 확정한 새 HIGH/MEDIUM 은 위 3건이며 모두 수정했다. 1차 문서
4절의 미수정 MEDIUM(`NextTrackPreparation` 단계 영구 포기, autoplay seed 유지,
`losslessAheadItem` 무로그 null, crossfade delay 폴링)은 이번 범위에서도 그대로
남아 있다. 각 항목의 이유와 필요한 작업은 4절에 적혀 있으며, 이번 변경으로 새로
생긴 것은 없다.

### 7.5 2차 테스트

새 테스트(수정 전 실패 확인):
- `app/src/test/java/com/music/bitchord/download/DownloadsQueuedPublishRaceTest.kt`
  - `a queued publish still reaches the manager after the service claimed the row`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeSelectionTest.kt`
  - `a target resting inside the retry cooldown is not selected`
  - `a target past its retry cooldown is selected`
  - `a fully parked plan waits until the cooldown ends`
- `app/src/test/java/com/music/bitchord/playback/smart/HqAudioVerifyPendingRetryTest.kt`
  - `transient failures are bounded so a permanent reference failure eventually settles`
  - `abandoned and deferred attempts never spend the transient budget`

실행 결과:
- `:app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` → 성공
- 위 3개 클래스 + 1차 테스트 3개 클래스(`HqSwapPolicyTest`,
  `HqVerificationSourceIdentityTest`, `HqLibraryUpgradeBackgroundPruneTest`) → 성공
- `:shared:jvmTest --tests AutomaticRetryPolicyTest --tests HqLibraryAutoRetryPolicyTest`
  → 성공

### 7.6 2차 변경 파일

- `app/src/main/java/com/music/bitchord/download/Downloads.kt`
- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadsQueuedPublishRaceTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeSelectionTest.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/HqAudioVerifyPendingRetryTest.kt`

---

## 8. 3차 재심 (2026-10-30, 기준 = 2차 결과)

2차 수정이 만든 새 경합과 플랜 유실을 다시 코드로 확인했다. 이번 차수에서 새로 확정한
HIGH는 없다. 아래 MEDIUM 1건과 LOW 1건을 모두 수정했고, 각각 수정 전에 실패하는
테스트를 먼저 고정했다.

### 8.1 MEDIUM — 서비스가 이미 지나간 행을 Queued로 되돌리던 문제 (수정)

- 파일: `app/src/main/java/com/music/bitchord/download/Downloads.kt:405-427`, `:460-479`
- 증거(수정 전): 2차에서 `publishQueuedIfPending`의 `pending`/`running` 확인과 두 게시를
  같은 lock에 넣었지만, 그 lock은 `enqueue`가 행을 넣은 lock과 *다른 구간*이었다.
  `enqueue`가 pending을 넣고 lock을 놓친 뒤 서비스가 `takeNext()`로 행을 가져가
  `Running`→`Done`/`Failed`까지 진행하면, 늦게 들어온 게시가
  `id in running`만 보고 `_active`와 `DownloadSession`을 Queued로 덮었다. 성공 경로는
  `clear()`가 `_active`를 지우므로 Done 행이, 실패 경로는 Failed 행이 Queued로
  되돌아가 아무도 다시 진행시키지 않아 영원히 Queued로 남았다.
- 사용자 영향: 캐시에서 즉시 끝나는 곡이나 빠르게 실패한 곡이 다운로드 관리자에서
  “대기 중”으로 멈춘 것처럼 보인다. 진행률·완료·실패를 볼 수 없다.
- 흔한 정도: 캐시 사본 다운로드, 연속 다운로드에서 다음 행을 즉시 집는 경우,
  백그라운드 enqueue 직후 포그라운드 전환.
- 수정 두 가지:
  1. `enqueue`의 pending 삽입과 Queued 게시를 **같은 `synchronized(lock)` 안**으로
     옮겼다. `takeNext()`도 같은 lock을 쓰므로, 게시가 끝나기 전에 서비스가 행을
     가져갈 수 없다.
  2. 그래도 늦게 호출되는 경우를 위해 게시 직전에 `_active`가 `Running`/`Failed`이거나
     세션 행이 Queued가 아니면 쓰지 않는다(`DownloadSession.progressOf` 추가).
- 조건 진리표(수정 전 → 수정 후):

| 서비스 상태(게시 시점) | _active | 세션 행 | 수정 전 | 수정 후 |
|---|---|---|---|---|
| 아직 pending | – | – | Queued | Queued |
| takeNext만 함 | null | null | Queued | Queued |
| Running | Running | Queued/Running | Queued(덮음) | 유지 |
| Failed | Failed | Failed | **Queued(덮음)** | 유지 |
| Done(성공, clear 후) | null | Done | **Queued(덮음)** | 유지 |

### 8.2 LOW — 재계획이 쿨다운 중인 plan 항목을 지우던 문제 (수정)

- 파일: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:633-648`,
  `:713-729`
- 증거(수정 전): `runOne`은 실행 가능한 항목이 없고 `buildPlan`이 새 항목을 내면
  `plan = built`로 통째로 교체했다. `buildPlan`은 `automaticTargetEligible`로 거르므로
  기존 plan의 “쿨다운 중” 항목은 결과에 없고, 그 항목의 `notBeforeMs`/쿨다운 마감이
  plan에서 사라졌다. `nextParkedWaitLocked`는 `built`가 비었을 때만 호출되므로 그
  항목의 지연 기상은 예약되지 않았다.
- 사용자 영향: 429로 쉬던 곡이 새 검증 결과가 하나 들어오는 순간 plan에서 유실되어,
  쿨다운이 끝나도 다음 임의 이벤트(재생/포그라운드) 전까지, 또는 영원히 재시도되지
  않는다.
- 흔한 정도: 애드온 429 뒤 다른 곡의 검증이 새로 수락될 때, 앱 재시작/포그라운드 복귀.
- 수정: `mergeParkedPlan(built, kept)`로 `buildPlan`이 돌려주지 않은 기존 항목을
  유지한다(같은 `videoId`는 새 항목이 우선). 실행 대상은 여전히 `built.first()`이며,
  쉬는 항목은 `nextRunnableTargetLocked`가 계속 건너뛰고 `nextParkedWaitLocked`가
  쿨다운 만료를 WorkManager에 맡긴다.
- 조건 진리표(수정 전 → 수정 후):

| 기존 plan | buildPlan 결과 | 수정 전 plan | 수정 후 plan |
|---|---|---|---|
| 비어 있음 | [A] | [A] | [A] |
| [P(쿨다운 중)] | [A] | **[A] (P 유실)** | [A, P] |
| [P, A] | [A] | [A] (P 유실) | [A] |
| [P] | [] | [P] (기존 유지) | [P] (기존 유지) |

### 8.3 지적 번호별 처리

1. MEDIUM `Downloads.kt:463-465` — **고침**. 게시를 pending 삽입과 같은 lock 안으로
   옮겨 서비스가 먼저 가져갈 수 없게 했고, 추가로 `_active`/세션 행이 Queued가
   아니면 게시하지 않는다. 근거: 8.1 진리표와 `DownloadsQueuedPublishRaceTest`의
   `a claimed row advanced past queued is not published back to queued`,
   `a claimed row that already failed is not republished as queued`.
2. LOW `HqLibraryUpgrade.kt:634` — **고침**. `mergeParkedPlan`으로 쿨다운 중 항목을
   새 계획에 병합해 마감을 보존한다. 근거: 8.2 진리표와
   `HqLibraryUpgradeSelectionTest`의
   `a rebuilt plan keeps parked entries so their cooldown wake survives`.

### 8.4 이번 차수의 상황 추적표

| 상황 | 진입점 → 처리 | 실제 결과 / 근거 |
|---|---|---|
| 캐시 사본이 즉시 완료 | `enqueue` lock 안 Queued 게시 → `takeNext` → `run` `Running`/`clear`+`done` | 늦은 게시가 없어 Done 유지(8.1) |
| 빠른 실패 | `run` `fail` → `_active`/세션 Failed | 늦은 게시가 Failed를 덮지 않음(8.1) |
| 백그라운드 enqueue→포그라운드 거부 | `enqueue` lock 게시 → `onForeground` `join` 후 `abandonPending`+`fail` | 게시와 거부가 같은 lock으로 직렬화 |
| 429로 쉬는 plan + 새 검증 | `runOne` → `buildPlan` → `mergeParkedPlan` | 쉬는 항목 보존, 쿨다운 만료 시 WorkManager 기상(8.2) |
| 전부 쉬는 plan | `runOne` → `nextParkedWaitLocked` → `enqueue(delay)` | 기존 동작 유지, 폴링 없음 |
| 앱 재시작 후 | `ensureLoaded` → `prunePlan`(영구성만) → `nextRunnableTargetLocked` | 쿨다운 항목은 plan에 남고 선택만 미룸 |

### 8.5 3차 테스트

수정 전 실패를 확인한 새 테스트:
- `app/src/test/java/com/music/bitchord/download/DownloadsQueuedPublishRaceTest.kt`
  - `a claimed row advanced past queued is not published back to queued`
  - `a claimed row that already failed is not republished as queued`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeSelectionTest.kt`
  - `a rebuilt plan keeps parked entries so their cooldown wake survives`

실행 결과:
- `:app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` → 성공
- `:app:testProdReleaseUnitTest` (위 2개 클래스) → 8 tests, 0 failures
- 1·2차 회귀: `HqLibraryUpgradeBackgroundPruneTest`, `HqSwapPolicyTest`,
  `HqVerificationSourceIdentityTest`, `HqAudioVerifyPendingRetryTest` → 성공
- `:shared:jvmTest --tests AutomaticRetryPolicyTest --tests HqLibraryAutoRetryPolicyTest`
  → 성공

### 8.6 3차 변경 파일

- `app/src/main/java/com/music/bitchord/download/Downloads.kt`
- `app/src/main/java/com/music/bitchord/download/DownloadSession.kt`
- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadsQueuedPublishRaceTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeSelectionTest.kt`
