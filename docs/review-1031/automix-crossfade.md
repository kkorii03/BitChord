# 자동 믹스 / Crossfade 상태 기계 심층 검토 (1031)

작업 브랜치: `dsq/deep-crossfade-1031` (기준 `task/fix-1030` 계열)
검토 범위: `CrossfadeController` 상태 기계(ARMING → FADING → handoff → finish/abort),
`TransitionPlanner`/`TransitionPolicy`, 음량 곡선·loudness·resampler, 분석 HEAD/TAIL,
사용자 skip/seek/pause 중 전환, 플래너가 고른 스타일과 실제 렌더.

이 문서는 코드를 따라간 결과만 적는다. 실기기 로그(vc85, 362곡)의 수치는 저장소에
원본이 없으므로, 각 숫자가 나올 수 있는 코드 경로를 근거로 설명한다. 측정값 자체를
재현했다고 주장하지 않는다.

---

## 1. 이번에 고친 결함 (HIGH/MEDIUM)

### F1. HIGH — 신뢰할 수 없는 템포로 인커밍 곡을 늘이거나 줄이던 문제

- 파일: `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt:1796`,
  `:1817` (`mixPlan`)
- 수정 전 증거: `adaptiveOverlap`이 계산한 `incomingPlaybackRate`를 그대로 plan에 넣었고,
  `sameBeatBlend`는 `confidentGrid = out >= 0.2 || in >= 0.2`만 봤다. 정책의
  `MIN_BEATMATCH_CONFIDENCE = 0.55`와 무관하게 한쪽 grid가 0.05여도 다른 쪽이 0.9면
  `DJ_BLEND` + 1.081배 속도 변경이 나왔다.
- 사용자 영향: 분석이 실패한 곡을 8% 늘이거나 줄여 재생. 보컬/드럼이 금속성으로
  들리거나 비트가 어긋난다. 특히 커버 영상·희귀 업로드처럼 분석 신뢰도가 낮은 곡.
- 빈도: 분석 HEAD가 성공했지만 confidence가 낮은 곡. 362곡 중 비트 추적이 약한
  곡(라이브·발라드·비정형 편곡)에서 흔하다.
- 수정: `incomingPlaybackRate`는 `1.0`이거나 양쪽 `beatConfidence >= 0.55`일 때만
  적용. `sameBeatBlend`에도 `(!tempoNeedsStretch || beatMatchTrusted)`를 추가해
  stretch가 필요한데 신뢰도가 낮으면 `DJ_FILTER`(rate 1.0)로 내린다.
- 회귀 테스트: `AutomixSafety1031Test.anUntrustedIncomingTempoIsNotBeatMatchedOrStretched`
  (classic/advanced 양쪽), 수정 전 실패 확인.

### F2. HIGH — phrase 모드에서도 같은 신뢰도 우회가 있던 문제

- 파일: `TransitionPlanner.kt:560` (`phraseBarPlan`), 수정 지점 `:618`
- 수정 전 증거: `beatmatched`가 false여도 `incomingPlaybackRate = incomingRateFor(mediaRatio, advanced)`를
  그대로 사용했다. `assessTransitionTier`가 `DJ_ASSISTED`로 내려도 8% stretch가 plan에 남았다.
- 사용자 영향: 8/16마디 고정 믹스에서 신뢰도 낮은 곡의 템포가 바뀐다. 현재 앱에서
  `phraseBars`는 컨트롤러가 넘기지 않아 실제 UI 경로는 없지만, 플래너 계약상 같은
  안전 규칙이 필요하다.
- 수정: `beatmatched`일 때만 `incomingRateFor`, 아니면 1.0.
- 회귀 테스트: `AutomixSafety1031Test.phraseModeDoesNotStretchAnUntrustedIncomingTempo`.

### F3. HIGH — 플래너는 `ECHO_RIDE`를 고르는데 렌더는 에코를 끄던 문제

- 파일: `TransitionPlanner.kt:1957` (수정), `app/.../CrossfadeController.kt:461`
  (`Render.echo = false`), `:501` (`echoAt`)
- 수정 전 증거: 플래너는 instrumental exit + tail room이 있으면 `echoSeconds > 0`과
  `filterVariant = ECHO_RIDE`를 plan에 실었지만, 컨트롤러의 `Render.echo`는 상수
  `false`였다. 로그에는 `variant=echo_ride|echo=0.5`가 찍히고 실제로는 sweep만 났다.
  기존 `AdvancedAutomixPlannerTest`가 이 불일치를 "정상"으로 고정하고 있었다.
- 사용자 영향: 마커/로그/플래너가 약속한 전환이 실제 소리와 다르다. 리뷰·디버깅에서
  "왜 ECHO_RIDE인데 에코가 없나"가 반복된다.
- 수정 방향: 에코 렌더를 다시 켜지 않았다. `Render.echo=false`의 주석이 설명하는 실제
  실패(중간 dry kill로 아웃고잉이 사라짐, tail이 fade보다 길어져 18초 overlap)를
  되살리지 않기 위해, 플래너가 `ECHO_RIDE`를 광고하지 않도록 `BASS_HANDOFF`로
  해석하고 `echoSeconds=0`으로 고정했다. 렌더러가 실제로 재생하는 제스처와 plan이
  일치한다.
- 회귀 테스트: `AutomixSafety1031Test.echoRideIsNeverAdvertisedWhileTheRendererKeepsEchoOff`,
  `AdvancedAutomixPlannerTest.anInstrumentalFilterExitUsesTheRenderedBassHandoffNotAnEcho`
  (기존 테스트를 새 계약으로 교체), `fallbackGestureIsStablePerPairButVariesAcrossPairs`
  (광고 가능한 variant 집합을 `{SWEEP, BASS_HANDOFF}`로 고정).

### F4. HIGH — 분석 HEAD가 read-ahead에 막히면 영원히 재시도되지 않던 문제

- 파일: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:2531`
  (`requestAnalysisHead`), `app/.../AnalysisHeadRetry.kt:36` (`analysisHeadMayClaim`)
- 수정 전 증거: `analysisHeads.putIfAbsent(videoId, true)`를 `anotherWriterHas(videoId)`
  검사보다 먼저 했다. read-ahead가 같은 캐시 엔트리를 쓰고 있으면 "deferred" 로그만
  남기고 return했는데, claim은 이미 소비된 상태였다. 이후 tick의 `requestAnalysisHead`는
  `putIfAbsent`에서 바로 return하므로 read-ahead가 끝나도 HEAD fetch가 다시 시작되지
  않았다. `TrackAnalyzer`는 `awaitAnalysisHead`를 쓰지 않으므로 claim을 풀어 줄 주체도
  없었다.
- 사용자 영향: 다음 곡의 HEAD 분석이 영구히 `mix=none`이 된다. `next-prep`의
  `mix=ready` 비율(29%)과 직접 연결되는 경로.
- 수정: 다른 writer가 있으면 claim을 소비하지 않고, 로그만 1회 dedupe. writer가
  풀린 다음 tick에 claim을 잡고 fetch를 시작한다. reset/forget/discard 경로에서
  dedupe set도 함께 지운다.
- 회귀 테스트: `AnalysisHeadRetryTest.aHeadDeferredToAnotherWriterDoesNotConsumeTheClaim`.

### F5. MEDIUM — 사용자 skip이 handoff 전에 같은 다음 곡을 두 번 재생하던 문제

- 파일: `app/.../CrossfadeController.kt:944` (`onSkipRequested`),
  `app/.../CrossfadeArmPolicy.kt:78` (`skipSilencesIncoming`)
- 수정 전 증거: FADING + handoff 전에는 세션 플레이어가 아직 outgoing(이전 곡)이다.
  서비스가 `onSkipRequested()` 직후 세션 플레이어를 다음 곡으로 seek하면, standby가
  이미 같은 다음 곡을 cue 지점에서 재생 중이다. bail은 standby를 120ms 동안
  `bailFromGain * fallGain`으로 줄이므로, 그 120ms 동안 같은 곡이 두 위치에서 겹친다.
- 사용자 영향: "다음"을 누르면 다음 곡이 두 겹으로 들리거나 위상이 어긋난다. 빠른
  skip에서 특히 두드러진다.
- 수정: FADING + !handedOff일 때만 standby를 즉시 mute하고 `bailFromGain=0`으로
  고정. handoff 후에는 기존 120ms ramp를 유지한다(그때는 standby가 이전 곡 tail).
- 회귀 테스트: `CrossfadeArmPolicyTest.aSkipBeforeTheHandoffSilencesTheArmedIncomingTrack`.
- 남은 한계: 컨트롤러 전체를 JVM에서 띄우는 fake ExoPlayer가 없어 정책 함수만
  단위 테스트했다. 볼륨 0 대입 자체는 컴파일과 코드 경로로 검증했다.

### F6. MEDIUM — 중단(bail) 시 살아남는 쪽 볼륨이 1.0으로 튀던 문제

- 파일: `app/.../CrossfadeController.kt:2322` (`bail`), `:2293` (`driveBail`),
  `:3672` (`CrossfadeGain.bailSurvivor`)
- 수정 전 증거: `if (handedOff) incoming?.volume = 1f else outgoing?.volume = 1f`.
  fade가 90% 진행된 뒤 seek/오류/큐 편집으로 bail이 걸리면 살아남는 쪽은
  `mixFall(0.9)=0.156`에서 즉시 1.0으로 점프했다. 주석은 "Glided open"이라고
  말하지만 실제로는 필터만 glide였고 볼륨은 snap이었다.
- 사용자 영향: 전환 중 seek/오류/큐 편집 시 클릭 또는 갑작스러운 음량 상승.
- 수정: 중단 시점의 실제 gain을 저장하고, BAIL_MS 동안 `bailSurvivor`로 1.0까지
  올린다. headroom trim도 양쪽 실제 gain으로 계산한다.
- 회귀 테스트: `CrossfadeGainTest.bailSurvivorReturnsFromTheFadedLevelToUnityWithoutSnapping`.

### F7. MEDIUM — 인커밍 드롭 후보가 다운비트에 스냅되지 않던 문제

- 파일: `TransitionPlanner.kt:463` (`incomingCuePoint`)
- 수정 전 증거: `rankMixInCandidates(...).firstOrNull { it.time <= latest }?.let { return it.time }`.
  fallback 경로(`mixInTime`, pickup, phrase, downbeat)는 모두 다운비트에 스냅하는데
  candidate 경로만 raw time을 반환했다.
- 사용자 영향: 분석이 drop을 다운비트에서 0.2초 벗어나 표시하면 beat-aligned 믹스가
  그만큼 flam한다. `DJ_BLEND`에서 특히 들린다.
- 수정: candidate도 fallback과 같은 2박 tolerance로 가까운 다운비트에 스냅하고,
  `latest` 밖으로 나가면 기존 `within` 규칙으로 되돌린다.
- 회귀 테스트: `AutomixSafety1031Test.anIncomingDropCandidateIsSnappedOntoTheDownbeatGrid`.

### F8. MEDIUM — 콘텐츠 끝을 넘어선 mix-out 후보가 "비용 0"으로 최고점을 받던 문제

- 파일: `shared/src/commonMain/.../TransitionPolicy.kt:520` (`mixOutCandidatesOf`),
  `:589` (`rankMixOutCandidates`)
- 수정 전 증거: `mixOutCandidates`를 `time > 0`만으로 받아들였다. `audibleSecondsBetween`
  은 `end <= start`이면 null을 반환하고, 그 null은 `discardedMusicSeconds = max(0, end - time)`
  로 0이 된다. 즉 contentEnd(100s) 뒤의 120s 후보가 "버린 음악 0초"로 최고점을 받아
  anchor가 될 수 있었다.
- 사용자 영향: 곡이 끝난 뒤(또는 파일 밖)로 페이드가 예약되어, 아웃고잉이 침묵 속에서
  사라지거나 다음 곡 진입이 늦어진다. 분석이 튄 곡에서 발생.
- 수정: `contentEnd + 0.05`를 넘는 후보는 공급 단계와 랭킹 단계 모두에서 제외.
- 회귀 테스트: `AutomixSafety1031Test.mixOutCandidatesPastTheContentEndAreNeverChosen`.

---

## 2. 조건 변경 전/후 진리표

### F1/F2: tempo stretch / beat blend

`rate`는 `adaptiveOverlap`이 계산한 인커밍 재생 속도, `stretch = rate != 1`.
`trusted = outConf >= 0.55 && inConf >= 0.55`.

| outConf/inConf | stretch | tempoBlendable | old `sameBeatBlend` | new `sameBeatBlend` | old rate | new rate |
|---|---|---|---|---|---|---|
| 둘 다 >=0.55 | 아니오 | 참 | 참(조건 충족 시) | 참(같음) | 1.0 | 1.0 |
| 둘 다 >=0.55 | 예 | 참 | 참 | 참 | rate | rate |
| 한쪽 <0.55 | 예 | 참 | **참** | **거짓** | **rate** | **1.0** |
| 한쪽 <0.55 | 아니오 | 참 | 참 | 참 | 1.0 | 1.0 |
| 어느 쪽이든 | 예 | 거짓 | 거짓 | 거짓 | rate(보통 1.0) | 1.0 |

기존 항 `(traits == null || rate >= 0.94)`, `tempoBlendable`, `confidentGrid`,
`phaseLockable`은 모두 유지했다. 새 항은 `(!tempoNeedsStretch || trusted)`와 rate
게이트뿐이다.

`phraseBarPlan`은 `beatmatched`(정책 BEATMATCHED + beatMatchable)일 때만 rate를
적용하고, 아니면 1.0이다.

### F3: ECHO_RIDE

| 조건 | old filterVariant | old echoSeconds | new filterVariant | new echoSeconds |
|---|---|---|---|---|
| vocalSafeFallback | SWEEP | 0 | SWEEP | 0 |
| ECHO_RIDE + room | ECHO_RIDE | 0.5 등 | **BASS_HANDOFF** | **0** |
| ECHO_RIDE + no room | BASS_HANDOFF | 0 | BASS_HANDOFF | 0 |
| SWEEP/BASS_HANDOFF | 그대로 | 0 | 그대로 | 0 |

### F4: analysis head claim

| alreadyRequested | anotherWriterHas | old | new |
|---|---|---|---|
| false | false | claim 후 시작 | claim 후 시작 |
| false | true | **claim 소비, 시작 안 함** | **claim 안 함, 다음 tick 재시도** |
| true | false | 시작 안 함 | 시작 안 함 |
| true | true | 시작 안 함 | 시작 안 함 |

### F5: skip

| phase | handedOff | old | new |
|---|---|---|---|
| FADING | false | bail, standby 120ms ramp | bail + standby 즉시 mute, `bailFromGain=0` |
| FADING | true | bail, outgoing 120ms ramp | 같음 |
| ARMING | false | bail → finish | 같음 |
| IDLE | any | 아무 것도 안 함 | 같음 |

### F6: bail survivor

| 상황 | old survivor volume | new survivor volume |
|---|---|---|
| bail 시작 | 즉시 1.0 | bail 시점 gain |
| bail 중 | 1.0 유지 | `bailSurvivor(from, progress)`로 1.0까지 상승 |
| bail 끝 | 1.0 | 1.0 |

---

## 3. 진입점 → 처리 → 결과 (흔한 상황)

| 상황 | 진입점 | 처리 | 결과 | 판정 |
|---|---|---|---|---|
| 커버 영상 | `planAutoTransition` → `videoPair` → `considerSmartTransition` | `automixVideos`가 켜져 있으면 분석·플래너, 꺼져 있으면 plain fade | 켜짐: 분석 준비 시 믹스, 아니면 fallback fade. 꺼짐: 항상 plain fade | 동작. 단, `automixVideos=false`면 커버 영상 믹스 없음(의도) |
| 비디오 노래 | 위와 같음 | `requestAnalysisAround`가 video origin이면 분석 요청을 건너뜀(`automixVideos=false`) | plain fade | 동작. 분석 없이 스타일 없음 |
| 셔플 | `onTimelineChanged`/`HandoffQueuePolicy` | 인커밍 row가 여전히 next인지 검사, handoff에서 tail reconcile | 유지 또는 bail | 동작 |
| 앱 재시작 후 | `TrackAnalyzer.restoreOnce` / `AnalysisStore` | 디스크 분석을 비동기 복원, 첫 tick은 fallback | 첫 1~2회는 plain fade 가능, 이후 분석 사용 | 부분 동작. F4로 HEAD fetch는 개선, 복원 race는 남음 |
| 화면 꺼짐 | `tickStepMs` → `SCREEN_OFF_IDLE_STEP_MS=1000` | 1초 tick, 분석은 계속(저전력 제외) | 전환 가능, 마커만 느림 | 동작. 주기 tick은 배터리 지적(남음) |
| 백그라운드 | `screenOn`, `ListenTogether` | 파티면 믹스 안 함, 아니면 동일 | 전환 가능 | 동작 |
| 프록시 켬 | `canMix`, `AudioCache.requestAnalysisHead`, `StreamResolver` | 프록시 경유로 HEAD fetch/재생 | 프록시가 느리면 ARM_TIMEOUT/분석 지연 | 부분 동작. F4로 deferred dead-end는 개선 |
| 다운로드된 곡 | `copyToRead` local 경로 | 로컬 파일은 `analyzeWindow(HEAD)` 직접 실행, tail은 현재 곡이 될 때 | incoming 분석 가능, outgoing tail은 늦음 | 동작. tail 지연은 남음 |
| 느린/끊긴 망 | `AutomixWorkPolicy.STANDBY_LEAD_MS=15s`, `CrossfadeArmPolicy.ARM_TIMEOUT_MS=3s` | standby 준비 실패 시 `ARM_TIMEOUT` bail, plain advance | 믹스 없이 넘어감 | 동작(안전). 로그 있음 |
| Android 14+ direct/lossless | `crossfadeFormatFor`, `CrossfadeArmPolicy.canMix` | direct + 다른 sample rate, channel 불일치, non-PCM 거부 | USB/일부 lossless 경로에서 믹스 없음 | 동작(안전). 사용자에겐 "자동 믹스가 안 됨"으로 보임 |
| 사용자 건너뛰기 | `onSkipRequested` | F5 수정 | handoff 전 double-play 제거, 후엔 tail ramp | 수정됨 |
| 전환 중 seek | `onPositionDiscontinuity(SEEK)` → `bail` | F6 수정 | 살아남는 쪽 볼륨 ramp, 필터 open | 수정됨 |
| 전환 중 일시정지 | `tick`의 playWhenReady mirror | FADING/BAILING은 양쪽 동기화. ARMING 중 pause면 `driveArming`이 bail | ARMING 중 pause는 전환 포기, resume 후 재-arm이 늦으면 믹스 없음 | 부분 동작(남은 MEDIUM) |
| sleep timer | `beginSleepFadeOut`/`driveSleepFade` | 다음 곡 없이 fade-out 후 pause | 동작 | 동작 |
| repeat one | `planAutoTransition` | 분석만 요청, 마커 제거, return | 믹스 없음 | 의도 |
| 파티 | `planAutoTransition` | 분석/믹스 중지, 마커 제거 | 믹스 없음 | 의도 |
| 45초 미만 곡 | `MIN_SMART_DURATION_SECONDS` | `blocked("short-duration-guard")` | smart fade에서 전환 없음 | 남은 MEDIUM(짧은 곡) |
| speech/live | `BLOCKED_TEXT`/`GAPLESS_TEXT` | 차단 또는 0.12s gapless | 의도된 동작 | 동작 |

---

## 4. 조용히 건너뛰는 경로 (로그 없이 return)

`planAutoTransition`(`CrossfadeController.kt:1023`)에는 조건이 거짓이면 로그 없이
return하는 경로가 많다. 이번에 F4로 분석 HEAD deferred 로그를 1회 남기게 했지만,
아래는 여전히 침묵한다.

| 위치 | 조건 | 결과 | 상태 |
|---|---|---|---|
| `:1029` | `versionSwapActive()` | return | 남음(버전 스왑 소유권, 로그 없음) |
| `:1047-1058` | 파티 / next 없음 / duration 미확인 | 마커 제거 또는 return | 남음 |
| `:1064-1066` | current/next item 없음, next index unset | return | 남음 |
| `:1074-1078` | `skippedPair == pair` | 마커 제거, return | 남음 |
| `:1117-1123` | repeat one | 분석 요청 후 return | 남음 |
| `:1155-1158` | slider 0, fade <= 0 | return | 남음 |
| `:1166` | sleep timer가 아직 아님 | return | 남음 |
| `:1190` | `shouldArmStandby` 거짓 | return | 남음(정상 대기) |
| `:1206-1213` | `considerSmartTransition`의 video/next 없음 | return | 남음 |
| `:1333` | `plan.blocked` | return (plain fallback 없음) | 남은 MEDIUM |
| `:1335-1336` | `plan.fadeMs <= 0` | return | 남음 |
| `:1359` | smart arm 시점 아님 | return | 남음(정상 대기) |

`requestAnalysisAround`(`:1395`)도 video/next 없음이면 로그 없이 return한다.
`TrackAnalyzer.request`(`TrackAnalyzer.kt:315`)는 짧은 곡, 파티, low power,
이미 진행 중, window 완료에서 로그 없이 return한다. 이 중 low power/party는
정책상 의도지만, "왜 mix=none인지"를 로그만으로 알 수 없는 점은 남은 결함이다.

---

## 5. 남은 HIGH/MEDIUM (이번에 고치지 못한 것)

### R1. MEDIUM — next-prep MIX가 20초 timeout 뒤 `mix=none`으로 굳는다

- 파일: `NextTrackPreparation.kt:284-302`, `:309`; `PlaybackService.kt:8585-8592`
- 근거: MIX 단계는 `awaitMix`(= `awaitWindow(HEAD, NEXT_PREP_MIX_WAIT_MS=20_000)`)가
  false면 끝나고, `steps += Step.MIX`가 이미 실행되어 같은 row에서 재시도하지 않는다.
  `schedule`은 row 변경/첫 소리에서만 불리므로, 다음 곡 캐시가 20초 뒤에 준비되면
  로그는 `mix=none`으로 남는다.
- 영향: "다음 곡 싱크 준비 29%"의 직접 원인 후보. 실제 믹스는 이후
  `CrossfadeController`의 tick 요청으로 성공할 수 있지만, next-prep 로그/지표는 none.
- 필요한 작업: `awaitWindow`를 timeout 없이 이벤트로 기다리거나, timeout 후에도
  row가 아직 next면 MIX를 attempted에서 빼고 분석 publish 이벤트로 재시도.
- 왜 안 했나: TrackAnalyzer/코루틴 이벤트 배선이 필요하고, Android 의존이라
  JVM 단위 테스트를 먼저 만들기 어렵다. F4로 deferred dead-end는 제거했다.

### R2. MEDIUM — HEAD 분석이 `vocalProbability`를 버려 보컬 충돌을 놓친다

- 파일: `TrackAnalyzer.kt:913-929` (`analyzeHead` 반환)
- 근거: `analyzeWindow`(`:783-813`)는 `vocalProbability`를 복사하지만 `analyzeHead`는
  `energyCurve`, `vocalActivityMask`와 함께 `vocalProbability`도 복사하지 않는다.
  인커밍 곡은 보통 HEAD만 있고, `PairTraits.vocalConflict`가 false가 되어
  `VOCAL_MASK_MISSING_PROTECTION`이 발동하지 않는다.
- 영향: 보컬이 많은 인커밍 곡을 instrumental로 오판해 긴 blend/bass swap을 고를 수 있다.
- 필요한 작업: `analyzeHead`에 `vocalProbability = entry?.vocalProbability ?: 0.0`을
  추가하고, HEAD 스칼라가 인커밍 overlap에만 쓰인다는 주석을 붙인다. 앱 테스트는
  decoder/Context가 필요해 별도 계측 테스트가 필요.
- 왜 안 했나: 단위 테스트 선행 조건을 지키기 어려워 남겼다.

### R3. MEDIUM — smart fade에서 `plan.blocked`면 plain fade로 내려가지 않는다

- 파일: `CrossfadeController.kt:1333`
- 근거: `considerSmartTransition`는 `plan.blocked`에서 그냥 return한다. video pair만
  `planAutoTransition`에서 plain fade로 내려간다. speech/live/45초 미만 곡은
  smart fade가 켜져 있으면 전환이 아예 없다.
- 영향: 사용자가 자동 믹스를 켠 상태에서 짧은 곡/스피치 곡이 끊기듯 넘어간다.
- 필요한 작업: `blocked` 이유별로 plain fade 허용 여부를 정하는 정책 함수를 만들고,
  `short-duration-guard`/`blocked-speech-or-live`를 명시적으로 결정.

### R4. MEDIUM — ARMING 중 일시정지 후 재개하면 전환이 늦거나 사라진다

- 파일: `CrossfadeController.kt:1780-1790` (`driveArming`의 `!out.playWhenReady` bail)
- 근거: ARMING 중 pause는 standby decoder를 오래 잡지 않으려고 bail한다. resume 후
  남은 시간이 `STANDBY_LEAD_MS`보다 짧으면 다시 arm하지 못해 plain advance.
- 영향: 곡 끝 직전에 잠깐 멈췄다 재생하면 크로스페이드가 사라진다.
- 필요한 작업: pause 시 arm을 유지할지, resume 이벤트에서 남은 lead를 보고 즉시
  arm할지 정책화.

### R5. MEDIUM — 검증된 lossless가 ARMING 이후에 도착하면 standby에 반영되지 않을 수 있다

- 파일: `PlaybackService.kt:6119` (`losslessAheadItem`), `:6144`
  (`refreshArmedIncomingLossless`), `:2242` (`StreamChoice.revisions`),
  `HqVerificationStore.kt:115` (`put`)
- 근거: 재무장 트리거는 `StreamChoice.revisions`뿐이다. `HqVerificationStore.put`은
  revision을 올리지 않고, `losslessAheadItem`은 `StreamChoice.of(id)`가 non-YouTube
  lossless일 때만 동작한다. 검증 결과가 Store에만 먼저 쓰이면 ARMING 중 재무장이
  일어나지 않는다.
- 영향: "무손실 확인 후 실제 무손실 재생 4%"의 후보 원인. 확인은 됐지만 다음 곡
  standby가 lossy로 남는다.
- 필요한 작업: `HqVerificationStore`에 revision/Flow를 추가하거나, 검증 완료 시
  `StreamChoice`를 명시적으로 갱신하고 `refreshArmedIncomingLossless`를 호출.

### R6. LOW/MEDIUM — 화면 꺼짐 idle tick이 1초 주기다

- 파일: `CrossfadeController.kt:896-910`, `:3613`, `:3620`
- 근거: IDLE에서 `SCREEN_OFF_IDLE_STEP_MS = 1000`, 화면 켜짐 `IDLE_STEP_MS = 250`.
  배터리 우선 규칙(주기 폴링 금지)과 충돌한다. 전환 시각을 한 번 예약하고
  seek/pause/queue 이벤트로 재예약하는 방식이 필요.
- 이번 작업에서는 새 주기 타이머를 추가하지 않았고, 기존 동작을 유지했다.

### R7. LOW — `onBlendHeadroom`이 반대쪽 진행도로 계산된다

- 파일: `CrossfadeController.kt:2086-2091`
- 근거: 첫 인자가 `mixRise(outgoingAt)`이고 둘째가 `outgoingLevel(elapsed/wallSpan + ...)`다.
  인커밍 쪽은 `incomingAt`, 아웃고잉 쪽은 `outgoingAt`을 써야 한다. stretch가 있는
  전환에서 trim이 몇 % 어긋난다. 사용자 영향은 작다.

### R8. LOW — `phraseBars`(8/16마디) 모드가 컨트롤러에서 연결되지 않는다

- 파일: `CrossfadeController.kt:1246-1257` (planTransition 호출에 `phraseBars` 없음),
  `TransitionPlanner.kt:560` (플래너는 지원)
- 근거: 플래너와 테스트는 있지만 서비스가 `phraseBars`를 넘기지 않아 실제 재생에서는
  항상 adaptive plan이다. 기능 공백.

### R9. 다운로드/고음질 보관함 실패 140회

- 자동 믹스 영역 밖이다. `Downloads`/`HqLibraryUpgrade`/`HqAudioVerify` 경로이며
  이번 변경 파일에 포함되지 않는다. 코드상 자동 믹스와 공유하는 것은
  `StreamChoice`/`HqVerificationStore`뿐이고, R5가 그 접점이다.

---

## 6. 성적표 숫자를 코드로 설명

### 다음 곡 싱크 준비 29%

- `NextTrackPreparation`는 현재 곡 첫 소리 뒤에 다음 row의 VERSION → LYRICS → HQ →
  SYNC → MIX를 순서대로 시도한다(`NextTrackPreparation.kt:159-315`).
- MIX는 `mixReady = trackAnalyzer.analysisFor(id).isUsable`(`PlaybackService.kt:8585`)로
  판정하고, `awaitMix`는 HEAD window를 20초만 기다린다(`:8586-8592`).
- 다음 곡이 아직 캐시에 없으면 `AudioCache.requestAnalysisHead`가 필요하다. 이 요청이
  read-ahead writer와 겹치면 수정 전에는 claim만 소모하고 fetch를 시작하지 않았다(F4).
- 20초 안에 HEAD가 publish되지 않거나, HEAD decode가 `MIN_HEAD_SECONDS=12s` 미만이거나,
  곡이 `MIN_ANALYSIS_SECONDS=60s` 미만이면 `mix=none`이다.
- 따라서 29%는 "다음 곡 캐시가 첫 소리 시점에 거의 없고, read-ahead와 경쟁하며,
  20초 예산을 넘긴 경우"로 설명된다. F4로 dead-end는 줄였지만 R1(timeout)은 남았다.

### 무손실 확인 후 실제 무손실 재생 4%

- `prepareIncomingItem`은 ARMING 때 `losslessAheadItem`을 호출한다
  (`PlaybackService.kt:2368`, `:6119`).
- `losslessAheadItem`은 `StreamChoice.of(id)`가 non-YouTube + `HqQuality.isLosslessTier`
  일 때만 URI를 `q=` marker로 바꾼다. 검증 결과가 `HqVerificationStore`에만 있고
  `StreamChoice`에 없으면 아무 일도 하지 않는다.
- ARMING 이후 도착은 `StreamChoice.revisions`로만 재무장한다(`:2242`, `:6144`).
  `HqVerificationStore.put`은 revision을 올리지 않는다(R5).
- 이 조합이면 "검증 완료"와 "standby가 lossless" 사이가 끊긴다.

### 자동 재생 보충 성공 24%

- `loadAutoplayForCurrentTrack`는 `autoplayTopUpNeeded`(대기 곡 수 < 최소)일 때만
  시작하고, `needed = MAX_QUEUED_AUTOPLAY - queuedAutoplay`이며 `queuedAutoplay`는
  `fromAutoplay` row만 센다(`PlaybackService.kt:3546-3600`).
- `loadAutoplayTracks`는 radio에서 커버/카라오케를 제거하고 제목·가수 없는 row를
  버린다(`Autoplay.kt:330-390`). radio가 얇으면 빈 결과.
- 크로스페이드 handoff는 `adoptPlayer`에서 `loadAutoplayForCurrentTrack`를 호출하지만
  (`:3988-3991`), 큐에 일반 row가 많이 남아 있으면 `queuedAhead`가 커서 top-up 자체를
  건너뛴다. 24%는 "자동 재생이 필요한 상황 자체가 적고, 필요한 때도 radio가 얇았던"
  경우로 설명된다.

### 사용자 조작 없는 백그라운드 시작 11회

- 크로스페이드로 시작한 곡은 `onIsPlayingChanged`가 발생하지 않는다. 서비스는
  `adoptPlayer` → `onTrackBecameCurrent(alreadyAudible=true)`에서
  `TIMING first audio: 0ms, the crossfade covered it`만 남긴다
  (`PlaybackService.kt:4320-4360`).
- 기본값은 `smartFadeEnabled=false`, `crossfadeSeconds=0`(`AppSettings.kt:304-316`)이라
  사용자가 켜지 않으면 자동 시작 자체가 없다. 11회는 "대부분 수동 시작, 자동 믹스는
  소수"라는 설정 상태와 일치한다.
- 자동 재생이 큐를 채우지 못하면 다음 곡이 없어 백그라운드 자동 시작도 없다
  (위 24%와 같은 원인).

---

## 7. 검증

실행한 명령과 결과:

```text
./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.smart.*' --console=plain -q
→ 통과 (44 tests, 0 failed)

./gradlew :app:testProdDebugUnitTest \
  --tests 'com.music.bitchord.playback.AnalysisHeadRetryTest' \
  --tests 'com.music.bitchord.playback.CrossfadeArmPolicyTest' \
  --tests 'com.music.bitchord.playback.CrossfadeArmPolicyBoundaryTest' \
  --tests 'com.music.bitchord.playback.CrossfadeGainTest' \
  --tests 'com.music.bitchord.EchoOutTest' \
  --tests 'com.music.bitchord.playback.LoudnessProcessorTest' \
  --tests 'com.music.bitchord.playback.audio.ResamplingAudioProcessorTest' \
  --console=plain -q
→ 통과

./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
→ 통과
```

수정 전 실패 확인:
`AutomixSafety1031Test` 5개 중 4개(당시 기준)가 수정 전 실패했고, 이후 통과.
`CrossfadeGainTest`/`CrossfadeArmPolicyTest`/`AnalysisHeadRetryTest`의 새 테스트는
수정 전 함수/계약이 없어 실패(또는 컴파일 실패)함을 확인한 뒤 구현했다.

---

## 8. 변경 파일

- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt`
- `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/AutomixSafety1031Test.kt` (신규)
- `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/AdvancedAutomixPlannerTest.kt`
- `app/src/main/java/com/music/bitchord/playback/AnalysisHeadRetry.kt`
- `app/src/main/java/com/music/bitchord/playback/AudioCache.kt`
- `app/src/main/java/com/music/bitchord/playback/CrossfadeArmPolicy.kt`
- `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt`
- `app/src/test/java/com/music/bitchord/playback/AnalysisHeadRetryTest.kt`
- `app/src/test/java/com/music/bitchord/playback/CrossfadeArmPolicyTest.kt`
- `app/src/test/java/com/music/bitchord/playback/CrossfadeGainTest.kt`
- `docs/review-1031/automix-crossfade.md` (신규)

새 테스트 이름:
- shared: `AutomixSafety1031Test`의 `anUntrustedIncomingTempoIsNotBeatMatchedOrStretched`,
  `phraseModeDoesNotStretchAnUntrustedIncomingTempo`,
  `mixOutCandidatesPastTheContentEndAreNeverChosen`,
  `anIncomingDropCandidateIsSnappedOntoTheDownbeatGrid`,
  `echoRideIsNeverAdvertisedWhileTheRendererKeepsEchoOff`
- app: `AnalysisHeadRetryTest.aHeadDeferredToAnotherWriterDoesNotConsumeTheClaim`,
  `CrossfadeArmPolicyTest.aSkipBeforeTheHandoffSilencesTheArmedIncomingTrack`,
  `CrossfadeGainTest.bailSurvivorReturnsFromTheFadedLevelToUnityWithoutSnapping`
