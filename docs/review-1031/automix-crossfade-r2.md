# 자동 믹스 / Crossfade 2차 검토 (1031-r2)

기준 브랜치: `task/fix-1030` 계열, 이번 브랜치 `dsq/deep-crossfade-1031-r2`.
1차 문서: `docs/review-1031/automix-crossfade.md` (F1–F8, R1–R9).
이번 문서는 **1차 VERDICT의 MEDIUM 3건**을 코드로 재확인하고, 각 수정의
수정 전/후 진리표와 실패 테스트를 남긴다. 1차에서 고친 F1–F8은 되돌리지 않았다.

수치(vc85, 362곡)는 저장소에 원본 로그가 없으므로, 이 영역의 코드가 그 숫자를
만들 수 있는 경로를 근거로 설명한다. 측정값을 재현했다고 주장하지 않는다.

---

## 1. 이번에 고친 결함 (VERDICT 3건, 모두 MEDIUM)

### G1. MEDIUM — in-flight HEAD 라운드의 claim이 다시 잡혀 라운드 없이 남던 문제

- 파일: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:2552`,
  `:2568-2575`; `app/src/main/java/com/music/bitchord/playback/AnalysisHeadRetry.kt:40`
- 수정 전 증거: `discardBadRendition`/`remove`/purge는 `analysisHeads`를 지우지만
  `analysisHeadsInFlight`는 그대로 둔다. 그 사이 `requestAnalysisHead`가
  `alreadyRequested=false`, `anotherWriterHas=false`로 claim을 통과하고
  `analysisHeads.putIfAbsent`를 다시 세운 뒤 `analysisHeadsInFlight.add`가 false라
  바로 return했다. 결과는 "claim은 있는데 라운드는 없는" 상태이며, 이후 tick은
  `alreadyRequested=true`에서 끝나 분석기가 영원히 재시도하지 않는다.
- 사용자 영향: 다음 곡 HEAD가 영구 `mix=none`. 1차 F4가 제거한 deferred dead-end와
  같은 종류의 영구 정지. 다음 곡 싱크 준비 29%를 더 떨어뜨리는 경로.
- 빈도: 캐시 삭제/용량 정리/다운로드 정리가 HEAD fetch와 겹치는 순간. 프록시·느린 망에서
  fetch가 길어질수록, 그리고 `discardBadRendition`이 자주 불릴수록 흔하다.
- 수정: claim 판정에 `inFlight` 항을 추가하고, 그래도 check→add 사이에 라운드가
  끼어들면 `analysisHeads.remove(videoId)`로 claim을 되돌린다.
- 회귀 테스트(수정 전 실패): `AnalysisHeadRetryTest.aHeadWhoseRoundIsStillInFlightIsNotReclaimedAfterItsClaimWasCleared`.

**진리표 (claim)**

| alreadyRequested | anotherWriterHas | inFlight | old | new |
|---|---|---|---|---|
| false | false | false | claim | claim |
| false | false | true | **claim(라운드 없음)** | **claim 안 함** |
| false | true | any | claim 안 함 | claim 안 함 |
| true | any | any | claim 안 함 | claim 안 함 |

### G2. MEDIUM — `clearPartialHead`가 "완료"와 "reader 점유"를 구분하지 못하던 문제

- 파일: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:2597-2610`,
  `:2705-2718`; `AnalysisHeadRetry.kt:56-75`
- 수정 전 증거: 반환형이 `Boolean`이라 `held >= want`(이미 충분)와
  `removeResource` 실패(살아 있는 reader가 부분 엔트리를 잡음)가 둘 다 `false`였다.
  두 경우 모두 코루틴이 `return@launch`했고 claim은 남았다. 후자는 부분 HEAD를
  가진 채 세션 내내 `mix=none`으로 굳는, 1차 F4가 지적한 것과 같은 영구 정지다.
- 사용자 영향: 플레이어가 다음 곡 파일을 읽는 동안 HEAD 라운드가 겹치면 그 곡은
  다시 분석되지 않는다. 다운로드된 곡·프록시·백그라운드 프리페치에서 흔하다.
- 빈도: HEAD fetch와 플레이어/read-ahead reader가 같은 엔트리를 동시에 잡는 경우.
  캐시가 작고 다음 곡이 이미 버퍼링 중일 때 자주 겹친다.
- 수정: `AnalysisHeadClear { Proceed, AlreadyComplete, HeldElsewhere }`로 결과를
  나눈다. `AlreadyComplete`는 claim을 유지(정말 끝남), `HeldElsewhere`는
  `analysisHeads.remove(videoId)`로 claim을 풀어 다음 tick이 재시도하게 한다.
- 회귀 테스트(수정 전 실패): `AnalysisHeadRetryTest.aPartialHeadHeldByAReaderIsDistinguishedFromACompleteOne`,
  `aCompleteHeadNeedsNoRoundEvenWhenTheClaimWasReleased`.

**진리표 (clear 결과)**

| held | want | removeResource | old caller | new |
|---|---|---|---|---|
| 0 | any | any | fetch | `Proceed` → fetch |
| >= want | any | any | return, claim 유지 | `AlreadyComplete` → return, claim 유지 |
| 0 < held < want | any | 성공 | fetch | `Proceed` → fetch |
| 0 < held < want | any | 실패 | **return, claim 유지(영구 정지)** | `HeldElsewhere` → return, **claim 해제** |

### G3. MEDIUM — handoff 전 skip이 standby 볼륨을 0으로 계단 강하하던 문제

- 파일: `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:959-971`
  (`onSkipRequested`), `:2313-2326` (`driveBail`), `:3725` (`CrossfadeGain.skipSilence`),
  `:606` (`bailSilenceLeaving`)
- 수정 전 증거: 1차 F5는 double-play를 없애려고 `incoming?.volume = 0f`를 대입했다.
  fade 전반부에서 인커밍은 `mixRise(0.3)=0.45` 정도로 들리고 있으므로, skip마다
  파형이 0으로 점프한다. `BAIL_MS=120` 램프와 `bailSurvivor`가 피하려던 바로 그
  클릭 아티팩트다.
- 사용자 영향: fade 도중 "다음"을 누를 때마다 딸깍/팝. 특히 무손실·저음이 강한 곡.
- 빈도: 크로스페이드가 켜져 있고 사용자가 fade 전반부(약 3초)에 skip하는 모든 경우.
  실제 사용에서 skip은 흔하므로 재현 빈도가 높다.
- 수정: 계단 대입을 없애고 `bailSilenceLeaving` 플래그로 `SKIP_SILENCE_MS = 8ms`
  동안 `CrossfadeGain.skipSilence`(equal-power fall)로 0까지 내린다. 세션 쪽
  survivor는 기존 120ms 램프를 유지한다. 8ms는 램프로 들리지 않을 만큼 짧고,
  같은 row가 두 번 재생되는 구간도 사실상 없다.
- 회귀 테스트(수정 전 실패): `CrossfadeGainTest.aSkipBeforeTheHandoffRampsTheArmedIncomingToSilenceInsteadOfSnapping`.

**진리표 (skip)**

| phase | handedOff | old leaving gain | new leaving gain |
|---|---|---|---|
| FADING | false | 즉시 `0f` (계단) | `skipSilence(gain, 0..8ms)` → 0 |
| FADING | true | `bailFromGain * fall(progress)` | 같음 (기존 120ms) |
| ARMING | any | 즉시 finish | 같음 |
| IDLE | any | 없음 | 같음 |

---

## 2. 영역 진입점 → 처리 → 결과 (이번 수정 반영)

| 상황 | 진입점 | 처리 | 결과 | 판정 |
|---|---|---|---|---|
| 커버 영상 | `planAutoTransition` → `videoPair` | `automixVideos`에 따라 분석/플래너 또는 plain fade | 의도대로 | 동작 |
| 비디오 노래 | `requestAnalysisAround` | video origin이면 분석 요청 생략 | plain fade | 동작 |
| 셔플 | `onTimelineChanged` / `HandoffQueuePolicy` | 인커밍 row가 next인지 확인, handoff에서 tail reconcile | 유지 또는 bail | 동작 |
| 앱 재시작 후 | `TrackAnalyzer.restoreOnce` | 디스크 분석 비동기 복원, 첫 tick fallback | 1~2회 plain 가능 | 부분(R1) |
| 화면 꺼짐 | `tickStepMs` | `SCREEN_OFF_IDLE_STEP_MS=1000` | 전환 가능 | 동작(배터리 R6 남음) |
| 백그라운드 | `screenOn` / `ListenTogether` | 파티면 믹스 안 함 | 전환 가능 | 동작 |
| 프록시 켬 | `requestAnalysisHead` | 프록시 경유 HEAD fetch | 느리면 ARM_TIMEOUT | 동작(안전) |
| 다운로드된 곡 | `copyToRead` local | 로컬 HEAD 직접 분석 | 분석 가능 | 동작 |
| 느린/끊긴 망 | `STANDBY_LEAD_MS=15s`, `ARM_TIMEOUT_MS=3s` | 준비 실패 시 bail → plain advance | 믹스 없이 넘어감 | 동작(안전, 로그 있음) |
| Android 14+ direct/lossless | `canMix` | rate/channel/non-PCM 불일치 거부 | 믹스 없음 | 동작(안전) |
| 사용자 skip | `onSkipRequested` | G3: handoff 전 8ms ramp, 후 120ms ramp | 클릭 없이 다음 곡 | 수정됨 |
| 전환 중 seek | `onPositionDiscontinuity(SEEK)` → `bail` | F6 survivor ramp | 볼륨 점프 없음 | 1차 수정 유지 |
| 전환 중 일시정지 | `tick`의 `playWhenReady` 미러 | ARMING 중 pause면 bail | resume 후 늦으면 plain | 남음(R4) |
| HEAD reader 경합 | `requestAnalysisHead` / `clearPartialHead` | G1/G2로 claim 누수 제거 | 다음 tick 재시도 | 수정됨 |
| 파티 / repeat one | `planAutoTransition` | 분석만 요청, 믹스 없음 | 의도 | 동작 |
| 45초 미만 곡 | `MIN_SMART_DURATION_SECONDS` | `blocked("short-duration-guard")` | smart fade 없음 | 남음(R3) |

---

## 3. 1차 문서의 남은 HIGH/MEDIUM 재검증 상태

| 항목 | 이번 상태 | 근거 |
|---|---|---|
| R1 next-prep MIX 20초 timeout | 남음 (재검증 필요) | `NextTrackPreparation.kt:284-309` 그대로. 이벤트 기반 재시도 배선 필요. |
| R2 HEAD가 `vocalProbability` 버림 | 남음 (재검증 필요) | `TrackAnalyzer.kt` 미수정. |
| R3 smart `plan.blocked` 시 plain fade 없음 | 남음 (재검증 필요) | `CrossfadeController.kt:1333` 그대로. |
| R4 ARMING 중 pause 후 전환 소실 | 남음 (재검증 필요) | `driveArming` 그대로. |
| R5 검증 lossless의 standby 반영 | 남음 (재검증 필요) | `HqVerificationStore.put` revision 미발행. |
| R6 화면 꺼짐 1초 tick | 남음 (LOW/MEDIUM) | 기존 동작 유지, 새 타이머 추가 없음. |
| R7 `onBlendHeadroom` 인자 뒤바뀜 | 남음 (LOW) | 미수정. |
| R8 `phraseBars` 미연결 | 남음 (LOW) | 미수정. |
| R9 다운로드/고음질 실패 140회 | 영역 밖 | `Downloads`/`HqLibrary` 경로, 이번 변경 없음. |

1차에서 고친 F1–F8(특히 F4 deferred dead-end, F5 skip double-play, F6 survivor ramp,
F3 ECHO_RIDE 미광고)은 되돌리지 않았다. G1/G2는 F4의 잔여 경로를 닫고, G3은 F5가
만든 계단 강하만 램프로 바꾼다(double-play 제거는 유지).

---

## 4. 성적표 숫자와 이 영역의 연결 (이번 수정 반영)

### 다음 곡 싱크 준비 29%

- 경로: `NextTrackPreparation` → `requestAnalysisHead` → `clearPartialHead` →
  `alignmentHeadComplete`/`awaitAnalysisHead` → `analysisFor(id).isUsable`.
- G1/G2 이전에는 read-ahead/플레이어와 HEAD가 겹치거나 캐시 정리가 in-flight와
  겹치면 claim만 남아 `mix=none`이 영구화됐다. 이번 수정으로 그 두 dead-end는
  다음 tick에서 재시도된다.
- 그래도 29%가 낮은 주 원인은 R1(20초 timeout 후 attempted 고정)이다. HEAD가
  20초 뒤에 준비되면 next-prep 지표는 none으로 남는다.

### 무손실 확인 후 실제 무손실 재생 4%

- 이번 영역의 G1–G3과 직접 접점 없음. 1차 문서의 R5(`HqVerificationStore.put`이
  `StreamChoice.revisions`를 올리지 않음)가 후보 원인으로 남아 있다.

### 자동 재생 보충 성공 24% / 백그라운드 자동 시작 11회

- `loadAutoplayForCurrentTrack`는 handoff(`adoptPlayer`)에서 호출되지만, 큐에 일반
  row가 남아 `queuedAhead`가 크면 top-up을 건너뛴다. 기본 설정이
  `smartFadeEnabled=false`, `crossfadeSeconds=0`인 점과 함께 24%/11회를 설명한다.
- 이번 수정은 분석 dead-end를 줄여 handoff 자체의 성공률을 올리는 데 기여하지만,
  radio가 얇은 경우의 top-up 실패는 별도 작업이다.

### 다운로드·고음질 보관함 실패 140회

- 자동 믹스 영역 밖(`Downloads`/`HqLibraryUpgrade`/`HqAudioVerify`). 코드 접점은
  `StreamChoice`/`HqVerificationStore`뿐이며 R5로 남아 있다.

---

## 5. 검증

```text
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
→ 성공

./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.playback.AnalysisHeadRetryTest' \
  --tests 'com.music.bitchord.playback.CrossfadeGainTest' \
  --tests 'com.music.bitchord.playback.CrossfadeArmPolicyTest' \
  -Pbitchord.arm64Only=true --console=plain -q
→ 성공 (AnalysisHeadRetryTest 10, CrossfadeGainTest 9, CrossfadeArmPolicyTest 통과)
```

새 테스트:
`AnalysisHeadRetryTest.aHeadWhoseRoundIsStillInFlightIsNotReclaimedAfterItsClaimWasCleared`,
`AnalysisHeadRetryTest.aPartialHeadHeldByAReaderIsDistinguishedFromACompleteOne`,
`AnalysisHeadRetryTest.aCompleteHeadNeedsNoRoundEvenWhenTheClaimWasReleased`,
`AnalysisHeadRetryTest.anEmptyHeadAlwaysProceeds`,
`CrossfadeGainTest.aSkipBeforeTheHandoffRampsTheArmedIncomingToSilenceInsteadOfSnapping`.

수정 전 실패 확인 방법: G1/G2 테스트는 `analysisHeadMayClaim`의 `inFlight` 인자와
`AnalysisHeadClear`가 없어 컴파일 단계에서 실패하고, G3 테스트는
`CrossfadeGain.skipSilence`가 없어 컴파일 단계에서 실패한다. 각 계약(재claim 금지,
완료/점유 구분, 계단 없는 ramp)은 수정 전 구현으로는 만족할 수 없다.

---

## 6. 바꾼 파일

- `app/src/main/java/com/music/bitchord/playback/AudioCache.kt`
- `app/src/main/java/com/music/bitchord/playback/AnalysisHeadRetry.kt`
- `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt`
- `app/src/test/java/com/music/bitchord/playback/AnalysisHeadRetryTest.kt`
- `app/src/test/java/com/music/bitchord/playback/CrossfadeGainTest.kt`

`.github/`, `keys/`, 서명 설정, 업스트림 v1.8.1 파일은 건드리지 않았다.
