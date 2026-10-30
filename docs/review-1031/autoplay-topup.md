# 자동 재생(AutoPlay) 심층 검토 — 1031

대상: `Autoplay.kt`, `VideoRadioAutoplay.kt`, `VideoRadioSources.kt`, `Recommender.kt`,
`AutoplayOrder.kt`, `AutoplayQueuePolicy.kt`, 큐 보충/반복/셔플/파티 경로.
기준: `task/fix-1030` (`2ccab48`).

## 1. 결론

- 성적표의 `보충 444회 / 곡 추가 107회`는 **분모가 잘못 잡힌 값**이었다. `loadAutoplayForCurrentTrack()`은
  곡 전환·타임라인 변경·파티 프레임마다 호출되지만, 그 대부분은 “앞에 5곡 이상 남음” 또는
  “bounded 앨범 컨텍스트가 아직 안 끝남”으로 **정상적으로** 반환한다. 그 반환들이 로그 없이
  끝나서 “시도했는데 실패”로 집계됐다. 이번에 모든 조기 반환에 이유를 남겼다.
- 실제로 실패를 만들던 결함 5건을 고쳤다(아래 표). 특히 비디오 라디오의 세션 중복, 얇은 비디오
  배치 + 라디오 실패 시 전곡 유실, 파티 echo 오판, 큐에 안 들어간 곡을 “이미 추천됨”으로
  기록하던 문제, repeat-one에서의 무의미한 라디오 요청이다.
- 남은 HIGH는 없다. 남은 MEDIUM 2건은 문서 하단에 이유와 필요한 작업을 적었다.

## 2. 진입점 → 처리 → 결과 (흔한 상황)

| 상황 | 진입점 | 처리 | 실제 결과 | 판정 |
|---|---|---|---|---|
| 일반 곡, 큐 5곡 이상 남음 | `onMediaItemTransition` → `loadAutoplayForCurrentTrack` (`PlaybackService.kt:3546`) | `autoplayTopUpNeeded`가 false | 요청 없음 | 정상(이제 `autoplay: skip … ahead=` 로그) |
| 일반 곡, 큐 4곡 이하 | 같은 경로 | 라디오 1회 + 취향 소스 → `AutoplayOrder.order` → `addMediaItems` | 10곡까지 보충 | 정상 |
| 커버/비디오 노래 | `videoRadioModeFor` → `loadAutoplayTracks` (`Autoplay.kt:143`) | mix+channel → 부족하면 Music radio → 그래도 부족하면 normal | 3곡 이상이면 비디오 유지 | 정상, 단 아래 V1/V2 |
| 비디오 + 끊긴 망(라디오 실패) | `Autoplay.kt:247-280` | 비디오 픽이 1~2곡인데 normal 라디오가 실패 | **수정 전: 전곡 유실**, 수정 후: PARTIAL로 1~2곡 유지 | 수정됨 (V2) |
| 셔플 켜짐 | `QueueShuffle.shuffle` (`QueueShuffle.kt:253`) | USER_QUEUE 고정, CONTEXT만 섞고 AUTOPLAY는 꼬리 유지 | 보충 조건은 전체 앞곡 수로 계산 | 정상 |
| 앱 재시작 후 | 서비스 생성 `PlaybackService.kt:2224` | 복원 큐의 현재 곡이 마지막이면 보충 | 정상. 단 세션 id는 비어 있어 과거 추천을 다시 낼 수 있음 | 정상(경미) |
| 화면 꺼짐/백그라운드 | 서비스 코루틴 | 이벤트(전환) 기반, 폴링 없음 | 정상 | 정상 |
| 프록시 켬 | `VideoRadioSources`/`YtMusicRepository` | 기존 browse 경로 그대로 | 정상 | 정상 |
| 다운로드된 곡만 있음(오프라인) | `loadOfflineVideoRadio` (`Autoplay.kt:319`) | 저장된 비디오 행만 필터, 1~2곡도 반환 | 정상 | 정상 |
| repeat-all | `onRepeatModeChanged` (`PlaybackService.kt:1511`) | stash 후 보충 금지, 해제 시 복원 | 정상 | 정상 |
| repeat-one | 같은 경로 | **수정 전: 매 반복마다 라디오 요청 가능**, 수정 후: 보충/새로고침 금지 | 낭비 제거 | 수정됨 (V3) |
| 파티(공급자) | `topUpPartyAutoplay` (`PlaybackService.kt:3802`) | 서버 queueAdd 후 echo 대기 | **수정 전: 히스토리에 같은 id가 있으면 거부도 성공으로 오판** | 수정됨 (V4) |
| 사용자가 넣은 곡 | `QueueTimeline.findUserQueueInsertionIndex`, `addMediaItems` | USER_QUEUE는 CONTEXT/AUTOPLAY 앞, AutoPlay는 맨 뒤 추가 | 사용자 곡은 밀리지 않음 | 정상 |
| 362곡 앨범(bounded) | `boundedAutoplayEligible` (`PlaybackService.kt:3785`) | 컨텍스트가 남아 있으면 AutoPlay 금지 | 정상. 로그 없이 끝나 집계에 실패로 잡힘 | 로그 추가 |

## 3. 결함과 수정

| # | 심각도 | 파일:줄 | 증거 | 사용자 영향 | 빈도 | 수정 |
|---|---|---|---|---|---|---|
| V1 | MEDIUM | `VideoRadioAutoplay.kt:138-155` | `pickVideoRadio`의 최근 창이 `existing.takeLast(50)` 뿐이고 `sessionIds`를 보지 않았다. 일반 경로는 `Autoplay.kt:413/553`에서 세션 id를 제외한다. | 비디오 방송에서 이미 재생한 곡이 다시 큐에 들어옴 | 비디오 시드 세션에서 흔함 | `sessionIds`를 `recentVideoIds`에 합집합. 테스트: `a song already played this session is not offered by video radio again` |
| V2 | HIGH | `Autoplay.kt:247-280` | 비디오 픽 < 3이고 normal 라디오가 실패/빈 응답이면 `Result.failure` 또는 빈 목록. 오프라인 경로는 “침묵보다 2곡” 원칙으로 부분 배치를 반환한다. | 끊긴 망에서 비디오 자동 재생이 전혀 보충되지 않음 | 느린/끊긴 망에서 흔함 | `videoRadioFallbackStage(partialAvailable=true)`로 1~2곡 유지. 테스트: `a thin video pick is kept when the normal fallback cannot answer` |
| V3 | MEDIUM | `AutoplayQueuePolicy.kt:32-38`, `Autoplay.kt:91-101`, `PlaybackService.kt:3567-3836` | 보충/새로고침 조건이 `repeatAll`만 봤다. repeat-one은 큐가 전진하지 않는데 `autoplaySeed`가 전환마다 초기화돼 같은 곡에서 다시 요청했다. | 배터리/라디오 낭비, 지표 왜곡 | repeat-one 사용 시 | `repeatOne` 항 추가, 서비스 호출부 전달. 테스트: `repeat one never fetches because the same track plays again`, `repeat one does not refresh a queue it will not advance through` |
| V4 | MEDIUM | `PlaybackService.kt:3792-3825`, `PartyQueueReading.kt:36-46` | echo 확인이 `state.queue.items.any { it.videoId == added }`였다. 파티 큐는 히스토리를 보존하므로 이전에 재생한 id면 즉시 true. 거부된 배치를 성공으로 기록하고 `autoplaySessionIds`에 넣어 재시도도 막았다. | 파티에서 자동 재생이 조용히 멈춤 | 파티에서 같은 곡이 히스토리에 있을 때 | 새 발생 횟수 비교 `partyTopUpEchoed`로 교체. 테스트: `an id already in the queue history is not proof that a top-up landed` 등 3건 |
| V5 | MEDIUM | `PlaybackService.kt:3694-3725` | `rememberSessionSongs(resolved)`가 `fresh`/`landed`가 비어도 실행됐다. 큐에 안 들어간 곡이 “이미 추천됨”이 되어 다음 보충에서 제외됐다. | 보충 성공률 하락, 같은 곡 재추천 불가 | `dontRepeatSuggestions`가 켜진 사용자, 파티 | 실제로 큐에 들어간 `fresh`/`landed`만 기록 |
| V6 | MEDIUM | `TastePool.kt:140-160` | rediscover 조건이 `lastSuggestedAt`만 보고 `lastPlayedAt`을 보지 않았다. `plays <= 1`이라 방금 들은 좋아요 곡이 곧바로 rediscover로 다시 나온다. favourite 쪽에는 같은 1일 가드가 이미 있었다. | 방금 들은 곡이 자동 재생으로 다시 나옴 | 좋아요 + 1회 재생 곡에서 흔함 | 1일 `lastPlayedAt` 가드 추가. 테스트: `a liked track played today is not offered again as rediscover` |
| V7 | MEDIUM(관측) | `PlaybackService.kt:3567-3628`, `Autoplay.kt:141-151` | 조건 거짓 → 로그 없이 return 경로가 7곳. | “보충 444회 중 107회” 같은 오해를 만든 직접 원인 | 항상 | 각 조기 반환에 `autoplay: skip … reason=` / `no youtube seed` / `nothing new` / `empty … retry=` 로그 추가 |

### 조건 변경 전/후 진리표

`autoplayTopUpNeeded` (구 → 신, `!repeatOne` 추가)

| enabled | repeatAll | repeatOne | queuedAhead < minAhead | 구 | 신 |
|---|---|---|---|---|---|
| F | * | * | * | F | F |
| T | T | * | * | F | F |
| T | F | T | T | **T** | **F** |
| T | F | T | F | F | F |
| T | F | F | T | T | T |
| T | F | F | F | F | F |

`autoplayQueueNeedsRefresh`도 같은 방식으로 `!repeatOne`만 추가했고, 차이는
`enabled=T, repeatAll=F, repeatOne=T, !loadInProgress, currentIndex>=0, currentIndex==itemCount-1`
한 줄(구 T → 신 F)뿐이다.

`videoRadioFallbackStage` (구 → 신, `partialAvailable && picked > 0` 추가)

| picked | sameModeExhausted | normalAvailable | partialAvailable | 구 | 신 |
|---|---|---|---|---|---|
| >= min | * | * | * | NONE | NONE |
| < min | F | * | * | SAME_MODE | SAME_MODE |
| < min | T | T | * | NORMAL | NORMAL |
| < min | T | F | F | EMPTY | EMPTY |
| 1..min-1 | T | F | T | **EMPTY** | **PARTIAL** |
| 0 | T | F | T | EMPTY | EMPTY |

## 4. 성적표 숫자 설명

- `보충 444회`: `loadAutoplayForCurrentTrack()` 호출 수. 전환·타임라인·파티 상태마다 불린다.
  대부분은 `<5곡` 게이트나 bounded 컨텍스트에서 정상 반환한다. 이 반환이 무로그여서
  “보충 시도 실패”로 보였다.
- `곡 추가 107회`: 실제 `queue: autoplay-append` 수. 정상 게이트를 통과한 시도 중에서도
  V1(비디오 세션 중복), V2(끊긴 망에서 부분 배치 폐기), V4(파티 echo 오판),
  V5(안 들어간 곡을 추천됨으로 기록)가 성공을 깎았다.
- 362곡 컨텍스트는 bounded 앨범/재생목록일 가능성이 높다. 그 동안 AutoPlay가 보충하지 않는 것은
  설계상 맞고, 이제 `reason=bounded-context`로 구분된다. 따라서 새 지표는
  “bounded/게이트로 skip”과 “실제 시도 실패”를 분리해야 한다.

## 5. 남긴 결함

| 심각도 | 파일:줄 | 내용 | 이유 / 필요한 작업 |
|---|---|---|---|
| MEDIUM | `SimilarCandidates.kt:210-223` | ListenBrainz는 답했지만 YouTube 검색이 전부 네트워크 실패한 경우에도 빈 결과를 7일 캐시한다. 일시 장애가 그 시드의 similar 소스를 일주일 동안 비운다. | `resolveOnYouTube`가 “실패”와 “일치 없음”을 구분하지 않는다. `Failed/None/Found` 결과 타입을 도입해 하나라도 실패하고 아무것도 못 찾으면 캐시하지 않도록 해야 한다. |
| LOW | `PlaybackService.kt:8414-8423` | `autoplaySessionIds`/`sessionSongHistory`는 명시적 라디오 시작에서만 초기화된다. 일반 새 앨범에서는 이전 큐의 추천 이력이 남는다. | 새 큐 설치 시 초기화하면 이전 큐에서 방금 들은 곡을 다시 추천할 수 있어, “세션”의 정의(앱 프로세스 vs 큐)를 먼저 정해야 한다. |
| LOW | `VideoRadioSources.kt:53-68` | 채널 해석 실패를 캐시하지 않아 같은 시드에서 매 보충마다 재시도한다. | 짧은 negative cache가 배터리에 좋지만, 캐시 만료 전 복구를 막을 수 있어 정책 결정이 필요하다. |

## 6. 새 테스트

- `AutoplayQueuePolicyTest`: `repeat one never fetches because the same track plays again`,
  `a top-up drops waiting rows and keeps only the room left`, `a full tail takes no suggestions at all`
- `AutoplayFallbackTest`: `repeat one does not refresh a queue it will not advance through`
- `VideoRadioAutoplayTest`: `a song already played this session is not offered by video radio again`
- `VideoRadioPolicyTest`: `a thin video pick is kept when the normal fallback cannot answer`
- `TastePoolTest`: `a liked track played today is not offered again as rediscover`
- `PartyQueueReadingTest`: `an id already in the queue history is not proof that a top-up landed`,
  `a second occurrence of the first suggestion is the accepted add`,
  `a suggestion never seen before lands with its first occurrence`

## 7. 검증

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.net.Autoplay*' --tests 'com.music.bitchord.data.videoradio.*' --tests 'com.music.bitchord.data.listentogether.PartyQueueReadingTest'`
- `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.data.taste.*' --tests 'com.music.bitchord.VideoRadioAutoplayTest' --tests 'com.music.bitchord.AutoplayFallbackTest' --tests 'com.music.bitchord.playback.PartyAutoplayTest'`
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true`

모두 통과.
