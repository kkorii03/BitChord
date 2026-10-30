# 자동 믹스 / Crossfade 3차 검토 (1031-r3)

기준 브랜치: `task/fix-1030` 계열. 이번 브랜치 작업 영역: 자동 믹스 —
`CrossfadeController` 상태 기계, 음량 곡선·loudness, `TransitionPlanner`,
분석 HEAD/TAIL, 사용자 skip/seek/pause 중 전환.

이번 차수의 검토 지적은 2건(HIGH 1, MEDIUM 1)이며 둘 다 코드로 재현 가능한
경로를 확인하고 고쳤다. 1·2차에서 고친 F1–F8, G1–G3는 되돌리지 않았다.
수치는 저장소에 원본 로그가 없으므로 측정을 재현했다고 주장하지 않고, 해당
숫자를 만들 수 있는 코드 경로만 설명한다.

---

## 1. 이번에 고친 결함

### H1. HIGH — `HeldElsewhere`에서 claim을 버려 250 ms마다 재시도하던 문제

- 파일: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:2651`
  (`clearPartialHead` 결과 처리), `:2584-2613` (`requestAnalysisHead` claim
  게이트), `:1510` (`retryAnalysisHeadIfReaderReleased`), `:1415`,
  `:1778` (player/read-ahead release 이벤트)
- 수정 전 증거: 부분 엔트리가 reader에 잡혀 `removeResource`가 실패하면
  `analysisHeads.remove(videoId)`로 claim을 풀었다. idle tick은
  `CrossfadeController.requestAnalysisAround` →
  `PlaybackService`의 `requestAnalysis` → `TrackAnalyzer.request` →
  `AudioCache.requestAnalysisHead`를 계속 호출한다. claim이 없으므로 매번
  새 코루틴을 띄우고 `StreamResolver.contentLength`를 다시 풀고 같은 잠긴
  `removeResource`를 다시 시도했다. 플레이어/read-ahead가 엔트리를 잡고 있는
  동안(수 분) 4 Hz 네트워크·코루틴·캐시 잠금 재시도가 발생했다.
- 사용자 영향: 전환 대기 중 배터리·데이터 소모. 느린 망에서는 다음 곡 분석이
  다른 네트워크 요청과 경쟁해 더 늦어진다. 재생 자체는 안전하지만
  배터리 우선 규칙(주기 폴링 금지)을 위반한다.
- 빈도: 다음 곡이 이미 플레이어 read-ahead로 버퍼링 중이고 HEAD가 부분
  엔트리를 보는 경우. 다운로드된 곡·프록시·백그라운드 프리페치에서 흔하다.
- 수정: `HeldElsewhere`에서 추적 가능한 writer(`anotherWriterHas`)가 잡고
  있으면 claim을 유지하고 `analysisHeadsHeldElsewhere`에 표시한다. 재시도는
  player/read-ahead의 close 이벤트(`retryAnalysisHeadIfReaderReleased`)에서
  한 번만 일어난다. 이벤트를 놓쳐도 다음 요청이 writer 해제를 확인한 뒤에만
  같은 claim을 재사용한다. writer를 추적할 수 없는 경우(내부 fetch)만 기존처럼
  claim을 풀어 다음 요청이 재시도한다.
- 회귀 테스트(수정 전 컴파일 실패): `AnalysisHeadRetryTest.aClaimKeptWhileAReaderHeldThePartialIsNotRetriedUntilTheReaderLetsGo`.

**진리표 (HeldElsewhere 결과 처리)**

| held | want | removeResource | anotherWriterHas | old | new |
|---|---|---|---|---|---|
| 0 | any | any | any | `Proceed` | `Proceed` |
| >= want | any | any | any | `AlreadyComplete`, claim 유지 | 같음 |
| 0 < held < want | any | 성공 | any | `Proceed` | 같음 |
| 0 < held < want | any | 실패 | true | claim 해제 → tick마다 재시도 | **claim 유지 + held 표시, release 이벤트 1회 재시도** |
| 0 < held < want | any | 실패 | false | claim 해제 → 다음 tick 재시도 | 같음(추적 불가 writer는 다음 요청이 재시도) |

**진리표 (claim 게이트: `analysisHeadMayClaimOrRetry`)**

| alreadyRequested | heldForReader | anotherWriterHas | inFlight | old `MayClaim` | new |
|---|---|---|---|---|---|
| false | false | false | false | claim | claim |
| false | false | true | any | 안 함 | 안 함 |
| false | false | false | true | 안 함 | 안 함 |
| true | false | false | false | 안 함(1회 제한) | 안 함 |
| true | true | true | false | — (이 상태가 없었음) | **안 함(writer 유지)** |
| true | true | false | false | — | **같은 claim으로 1회 재시도** |
| true | true | false | true | — | 안 함(중복 라운드 금지) |

### M1. MEDIUM — skip silence 램프(8 ms)가 bail tick(15 ms)보다 짧아 계단이 남던 문제

- 파일: `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:2330-2337`
  (`driveBail`), `:614` (`skipSilenceStartedAt`), `:3709`
  (`CrossfadeGain.SKIP_SILENCE_MS`), `:2405` (`bail`에서 초기화)
- 수정 전 증거: `SKIP_SILENCE_MS = 8`, `BAIL_STEP_MS = 15`. `bail()`이
  `bailStartedAt`을 찍은 뒤 첫 `driveBail` 샘플은 0~15 ms 사이이고, 두 번째
  샘플은 15 ms 뒤다. 8 ms span은 첫 샘플과 두 번째 샘플 사이에 이미 끝나므로
  `skipSilence`가 blend gain에서 곧바로 `0f`로 점프했다. F5/G3가 없애려던
  클릭이 그대로 남는다.
- 사용자 영향: 크로스페이드 fade 전반부에서 "다음"을 누를 때 딸깍/팝.
- 빈도: 크로스페이드가 켜져 있고 fade 전반부(약 3초)에 skip하는 모든 경우.
- 수정: ramp span을 45 ms(3 bail tick)로 늘리고, ramp 시계를 bail 시작이 아니라
  `driveBail`이 flag를 처음 본 시점에 시작한다. 첫 샘플은 항상 실제 blend
  gain이고, 최소 두 개의 중간 샘플을 거쳐 0에 닿는다. 45 ms는 같은 큐 row가
  두 번 들릴 만한 길이가 아니다.
- 회귀 테스트(수정 전 컴파일 실패): `CrossfadeGainTest.theSkipSilenceRampOutlivesTheRealBailTickCadence`.

**진리표 (skip ramp 샘플, from=0.5, 15 ms 간격)**

| elapsed(ms) | old span 8 ms | new span 45 ms |
|---|---|---|
| 0 | 0.5 (시작) | 0.5 (시작) |
| 15 | **0 (즉시 계단)** | 0.433 |
| 30 | 0 | 0.25 |
| 45 | 0 | 0 |
| 60 | 0 | 0 |

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
| 사용자 skip | `onSkipRequested` | handoff 전 45 ms ramp, 후 120 ms ramp | 클릭 없이 다음 곡 | **M1 수정** |
| 전환 중 seek | `onPositionDiscontinuity(SEEK)` → `bail` | F6 survivor ramp | 볼륨 점프 없음 | 1·2차 수정 유지 |
| 전환 중 일시정지 | `tick`의 `playWhenReady` 미러 | ARMING 중 pause면 bail | resume 후 늦으면 plain | 남음(R4) |
| HEAD reader 경합 | `requestAnalysisHead` / `clearPartialHead` | claim 유지 + release 이벤트 재시도 | 다음 라운드 1회 | **H1 수정** |
| 파티 / repeat one | `planAutoTransition` | 분석만 요청, 믹스 없음 | 의도 | 동작 |
| 45초 미만 곡 | `MIN_SMART_DURATION_SECONDS` | `blocked("short-duration-guard")` | smart fade 없음 | 남음(R3) |
| 플래너 style vs 렌더 | `TransitionPlanner.mixPlan` / `Render.echo` | ECHO_RIDE → BASS_HANDOFF, echoSeconds 0 | 광고와 렌더 일치 | 1차 F3 유지 확인 |

---

## 3. 이전 차수 문서에 남은 HIGH/MEDIUM 재검증

| 항목 | 이번 상태 | 근거 / 필요한 작업 |
|---|---|---|
| R1 next-prep MIX 20초 timeout | 남음 — 재검증 필요 | `NextTrackPreparation.kt:283-310`의 `awaitMix`가 timeout이면 같은 row에서 다시 시도하지 않는다. `awaitWindow` 이벤트 대기 또는 timeout 후 attempted 제거가 필요하며, TrackAnalyzer/코루틴 배선이라 JVM 단위 테스트 선행이 어렵다. |
| R2 HEAD가 `vocalProbability` 버림 | 남음 — 재검증 필요 | `TrackAnalyzer.analyzeHead` 반환에 `vocalProbability` 복사가 없다. decoder/Context 계측 테스트가 필요해 이번 범위에서는 고치지 않았다. |
| R3 smart `plan.blocked` 시 plain fade 없음 | 남음 — 재검증 필요 | `CrossfadeController.kt:1349`가 `plan.blocked`에서 return한다. short-duration/speech별 정책 결정이 필요해 사용자 가시 동작을 임의로 바꾸지 않았다. |
| R4 ARMING 중 pause 후 전환 소실 | 남음 — 재검증 필요 | `driveArming`의 `!out.playWhenReady` bail 경로 그대로. resume 이벤트에서 남은 lead로 재-arm하는 정책이 필요하다. |
| R5 검증 lossless의 standby 반영 | 남음 — 재검증 필요 | `HqVerificationStore.put`이 `StreamChoice.revisions`를 올리지 않는다. 별도 Flow/revision 배선이 필요하다. |
| R6 화면 꺼짐 1초 idle tick | 남음(LOW/MEDIUM) | 이번 변경은 새 타이머를 추가하지 않았고 기존 1초 cadence도 건드리지 않았다. 전환 시각 1회 예약 + 이벤트 재예약 작업이 남아 있다. |
| R7 `onBlendHeadroom` 인자 뒤바뀜 | 남음(LOW) | 미수정. |
| R8 `phraseBars` 미연결 | 남음(LOW) | 미수정. |
| R9 다운로드/고음질 실패 140회 | 영역 밖 | `Downloads`/`HqLibrary` 경로. 이번 변경 없음. |

---

## 4. 성적표 숫자(vc85, 362곡)와 이 영역

### 다음 곡 싱크 준비 29%

- 경로: `NextTrackPreparation` → `requestAnalysisHead` → `clearPartialHead` →
  `alignmentHeadComplete` → `analysisFor(id).isUsable`.
- H1 이전에는 reader가 HEAD 엔트리를 잡는 동안 claim이 풀려 tick마다 새
  라운드가 시작됐고, 그 라운드는 잠긴 엔트리를 지우지 못해 다시 짧게 끝났다.
  이제 그 구간은 네트워크를 쓰지 않고 reader 해제 이벤트를 기다린다. 다음 곡
  분석이 실제로 끝날 확률은 올라가지만, R1의 20초 timeout은 여전히 지표를
  `mix=none`으로 남길 수 있다.

### 무손실 확인 후 실제 무손실 재생 4%

- 이번 두 수정과 직접 접점 없음. 1차 문서의 R5가 후보 원인으로 남아 있다.

### 자동 재생 보충 성공 24% / 백그라운드 자동 시작 11회

- `loadAutoplayForCurrentTrack`는 `queuedAhead`가 크면 top-up을 건너뛰고,
  기본값 `smartFadeEnabled=false`, `crossfadeSeconds=0`이면 자동 시작 자체가
  드물다. H1은 handoff 자체의 분석 성공률을 올리는 데 기여하지만 radio가 얇은
  경우의 top-up 실패는 별도 작업이다.

### 다운로드·고음질 보관함 실패 140회

- 자동 믹스 영역 밖(`Downloads`/`HqLibraryUpgrade`/`HqAudioVerify`). 공유
  접점은 `StreamChoice`/`HqVerificationStore`뿐이고 R5로 남아 있다.

---

## 5. 검증

```text
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.playback.AnalysisHeadRetryTest' \
  --tests 'com.music.bitchord.playback.CrossfadeGainTest' \
  --tests 'com.music.bitchord.playback.CrossfadeArmPolicyTest' \
  -Pbitchord.arm64Only=true --console=plain -q
→ EXIT 0

./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
→ EXIT 0
```

새 테스트:
- `AnalysisHeadRetryTest.aClaimKeptWhileAReaderHeldThePartialIsNotRetriedUntilTheReaderLetsGo`
- `CrossfadeGainTest.theSkipSilenceRampOutlivesTheRealBailTickCadence`

수정 전 실패 확인: H1 테스트는 `analysisHeadMayClaimOrRetry`가 없어 컴파일
단계에서 실패하고, M1 테스트는 `CrossfadeGain.SKIP_SILENCE_MS`가 없어
컴파일 단계에서 실패한다. 두 계약(reader 점유 중 재라운드 금지, 실제 15 ms
cadence에서 중간 샘플 2개 이상)은 수정 전 구현으로 만족할 수 없다.

---

## 6. 바꾼 파일

- `app/src/main/java/com/music/bitchord/playback/AudioCache.kt`
- `app/src/main/java/com/music/bitchord/playback/AnalysisHeadRetry.kt`
- `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt`
- `app/src/test/java/com/music/bitchord/playback/AnalysisHeadRetryTest.kt`
- `app/src/test/java/com/music/bitchord/playback/CrossfadeGainTest.kt`
- `docs/review-1031/automix-crossfade-r3.md` (신규)

`.github/`, `keys/`, 서명 설정, 업스트림 v1.8.1 파일은 건드리지 않았다.
