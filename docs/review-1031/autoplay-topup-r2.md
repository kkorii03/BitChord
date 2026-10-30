# 자동 재생(AutoPlay) 심층 검토 2차 — 1031-r2

대상: `Autoplay.kt`, `VideoRadioPolicy.kt`, `SimilarCandidates.kt`,
`PartyQueueReading.kt`, `PlaybackService.kt`의 자동 재생 보충·파티 echo 경로.
기준: `dsq/deep-autoplay-1031-r2b` (이전 차수 `autoplay-topup.md` 결과 위).

## 1. 결론

이번 차수는 이전 문서가 지적한 5건을 코드로 다시 따라가 모두 재현했고, 그중
MEDIUM 4건과 LOW 1건을 직접 고쳤다. 추가로 이전 문서의 "남긴 결함"에 있던
MEDIUM 1건(YouTube 검색 실패를 7일 캐시)도 고쳤다. 남은 HIGH는 없다.

핵심은 두 가지다.

1. 비디오 라디오의 "빈 응답"과 "실패"를 같은 것으로 보던 분기 때문에, 라디오가
   성공했지만 비어 있을 때 로컬 취향/유사곡/세션 라디오가 통째로 건너뛰어졌다.
   이제 성공한 빈 응답은 정상 경로를 먼저 돌리고, 정상 경로가 필터로 전부 비면
   이미 재생 가능한 1~2곡의 비디오 픽을 살린다.
2. 파티 서버는 `queueAdd`를 남은 슬롯만큼 자르는데, 클라이언트는 첫 곡의 echo만
   보고 배치 전체가 들어갔다고 기록했다. 이제 before/after의 **새 발생 횟수**로
   실제 들어간 곡만 계산해 기록하므로, 서버가 거절한 나머지는 다음 보충에서
   다시 시도된다.

## 2. 진입점 → 처리 → 결과 (흔한 상황)

| 상황 | 진입점 | 처리 | 실제 결과(수정 후) | 판정 |
|---|---|---|---|---|
| 일반 곡, 큐 5곡 이상 남음 | `onMediaItemTransition` → `loadAutoplayForCurrentTrack` (`PlaybackService.kt:3557`) | `autoplayTopUpNeeded` 거짓 | 요청 없음, `autoplay: skip … ahead=` 로그 | 정상 |
| 일반 곡, 큐 4곡 이하 | 같은 경로 | 라디오 1회 + 취향 소스 → `AutoplayOrder.order` | 최대 10곡 보충 | 정상 |
| 커버/비디오 노래, 소스 충분 | `videoRadioModeFor` → `loadAutoplayTracks` (`Autoplay.kt:144`) | mix+channel → 3곡 이상이면 유지 | 비디오 행 반환 | 정상 |
| 비디오 + 라디오 **성공·빈 응답** | `Autoplay.kt:272` | 실패로 보지 않고 `loadNormalAutoplayTracks` 실행 | 로컬 취향/유사/세션 라디오가 살아남음 | 수정됨 (R1) |
| 비디오 + 라디오 행이 전부 커버/중복/무메타 | `Autoplay.kt:308` | 정상 경로가 0곡이면 비디오 픽 유지 | 1~2곡이라도 재생 | 수정됨 (R2) |
| 비디오 + 끊긴 망(라디오 실패) | `Autoplay.kt:272` | `fallback.isFailure`일 때만 PARTIAL | 1~2곡 유지, 0곡이면 실패 | 정상 |
| 셔플 켜짐 | `QueueShuffle.shuffle` | USER_QUEUE 고정, AUTOPLAY 꼬리 유지 | 보충 조건은 전체 앞곡 수 | 정상 |
| 앱 재시작 후 | 서비스 생성 (`PlaybackService.kt:2224`) | 복원 큐 현재 곡이 마지막이면 보충 | 정상 | 정상 |
| 화면 꺼짐/백그라운드 | 서비스 코루틴 | 전환 이벤트 기반, 폴링 없음 | 정상 | 정상 |
| 프록시 켬 | `VideoRadioSources`/`YtMusicRepository` | 기존 browse 경로 | 정상 | 정상 |
| 다운로드된 곡만 있음 | `loadOfflineVideoRadio` (`Autoplay.kt:349`) | 저장 비디오 행만 필터, 부분 배치 유지 | 정상 | 정상 |
| repeat-all | `onRepeatModeChanged` (`PlaybackService.kt:1514`) | stash 후 보충 금지, 해제 시 복원 | 정상 | 정상 |
| repeat-one → 해제 | 같은 경로 (`PlaybackService.kt:1554`) | job 취소 + `autoplaySeed = null` 후 재로드 | 실제로 보충됨 | 수정됨 (R4) |
| 파티 공급자, 배치 일부만 수용 | `topUpPartyAutoplay` (`PlaybackService.kt:3847`) | echo의 새 발생 횟수로 실제 수용분만 반환 | 거절분은 기록되지 않아 재시도 가능 | 수정됨 (R3) |
| 사용자가 넣은 곡 | `QueueTimeline.findUserQueueInsertionIndex` | AutoPlay는 맨 뒤 추가 | 사용자 곡 밀리지 않음 | 정상 |
| 362곡 bounded 앨범 | `boundedAutoplayEligible` (`PlaybackService.kt:3877`) | 컨텍스트가 남아 있으면 보충 금지 | `reason=bounded-context` 로그 | 정상 |
| 플레이어/현재 곡 없음 | `loadAutoplayForCurrentTrack` (`PlaybackService.kt:3557`) | 조기 반환 | 이제 `reason=no-player` / `no-current-song` 로그 | 수정됨 (R5) |

## 3. 이번 차수 수정

| # | 심각도 | 파일:줄 | 증거 | 사용자 영향 | 빈도 | 수정 |
|---|---|---|---|---|---|---|
| R1 | MEDIUM | `Autoplay.kt:272` | 기존 `fallbackSongs.isNullOrEmpty()`가 `Result.failure`와 성공한 빈 목록을 모두 같은 분기로 보냈다. 성공한 빈 라디오면 `loadNormalAutoplayTracks`를 아예 호출하지 않아 로컬 좋아요/유사곡/세션 라디오를 버렸다. | 비디오 시드에서 라디오가 빈 응답이면 로컬 후보가 있어도 보충 0곡 | 비디오·느린 망에서 흔함 | 실패일 때만 PARTIAL 단축, 성공한 빈 응답은 정상 경로 실행 |
| R2 | MEDIUM | `Autoplay.kt:308` | PARTIAL 보존을 정상 경로 **전에** 결정했다. 라디오 행이 있어도 `loadNormalAutoplayTracks`가 커버/무메타/중복으로 전부 걸러 0곡이 되면 유효한 1~2곡 비디오 픽을 버렸다. | 비디오 자동 재생이 곡 없이 멈춤 | 비디오·커버 많은 장르 | 정상 경로 결과가 0곡이고 비디오 픽이 있으면 `videoRadioRows` 반환 (`videoRadioFallbackAction`) |
| R3 | MEDIUM | `PlaybackService.kt:3847` | echo가 `fresh.first()`의 새 발생만 확인했다. 서버는 `backend/party/party.go:331-345`에서 남은 슬롯만큼 `toAdd`를 자른다. 부분 수용인데 `fresh` 전체를 반환해 거절분까지 `autoplaySessionIds`/`sessionSongHistory`에 기록했다. | 파티에서 거절된 추천이 "이미 추천됨"이 되어 다시 시도되지 않음 | 파티 큐가 거의 찼을 때 | `partyTopUpLanded(before, suggestionIds, after)`로 실제 새 발생분만 반환·기록 |
| R4 | MEDIUM | `PlaybackService.kt:1554` | repeat-one을 벗어날 때 `loadAutoplayForCurrentTrack`만 호출하고 `autoplaySeed`를 지우지 않았다. seed가 현재 곡에 latch되어 있으면 `:3636`의 same-seed 가드가 반환해 약속한 보충이 없었다. | repeat-one 해제 후 큐가 빈 채로 멈춤 | repeat-one을 자주 쓰는 사용자 | `repeatOneSeedShouldClear`일 때 job 취소 + seed 해제 후 재로드 |
| R5 | LOW | `PlaybackService.kt:3557` | 플레이어 없음/현재 곡 없음 반환이 무로그였다. "모든 skip은 로그" 주장과 불일치. | 지표에서 skip과 실패를 구분할 수 없음 | 항상 | 두 반환에 `autoplay: skip reason=…` 로그 추가 |
| R6 | MEDIUM(이전 문서 잔여) | `SimilarCandidates.kt:227` | ListenBrainz는 답했지만 YouTube 검색이 전부 네트워크 실패한 경우에도 빈 결과를 7일 캐시했다. `resolveOnYouTube`가 "검색 실패"와 "일치 없음"을 구분하지 않았다. | 일시 장애가 그 시드의 similar 소스를 일주일 동안 비움 | 느린/끊긴 망 | `SearchOutcome.Failed`를 세어 0곡+실패가 있으면 캐시하지 않음 (`shouldCacheSimilarAnswer`) |

### 조건 변경 전/후 진리표

비디오 폴백 선택 (구 → 신)

| fallback | pick 수 | 정상 경로 결과 | 구 | 신 |
|---|---|---|---|---|
| 실패 | >0 | 실행 안 함 | PARTIAL 픽 | PARTIAL 픽 |
| 실패 | 0 | 실행 안 함 | 실패/빈 성공 | 실패 |
| 성공·빈 목록 | >0 | 실행 안 함 | PARTIAL 픽 (정상 경로 버림) | 정상 경로 실행 → 0곡이면 PARTIAL 픽 |
| 성공·빈 목록 | 0 | 실행 안 함 | 빈 성공 | 정상 경로 실행 |
| 성공·행 있음 | >0 | >0 | 정상 경로 | 정상 경로 |
| 성공·행 있음 | >0 | 0 | 빈 성공 (픽 버림) | PARTIAL 픽 |
| 성공·행 있음 | 0 | 0 | 빈 성공 | 빈 성공 |

`shouldCacheSimilarAnswer` (구 → 신)

| found | failedSearches | 구 | 신 |
|---|---|---|---|
| 0 | 0 | 캐시 | 캐시 |
| 0 | >0 | **캐시** | **캐시 안 함** |
| >0 | * | 캐시 | 캐시 |

## 4. 성적표 숫자

- `보충 444회`: `loadAutoplayForCurrentTrack()` 호출 수. 전환·타임라인·파티
  프레임마다 불리며 대부분은 `<5곡` 게이트, bounded 컨텍스트, 파티 비공급자,
  repeat-all/one에서 정상 반환한다. 이 skip이 이전에는 무로그여서 "시도 실패"로
  집계됐다. 지금은 `autoplay: skip … reason=`으로 분리된다.
- `곡 추가 107회`: 실제 `queue: autoplay-append` 수. 게이트를 통과한 시도 중
  R1(성공한 빈 라디오로 로컬 소스 유실), R2(필터 후 0곡에서 비디오 픽 유실),
  R3(파티 echo 부분 수용 오판), R4(repeat-one seed latch), R6(일시 검색 실패를
  7일 캐시)가 성공을 깎았다. 이번 수정은 이 다섯 경로를 직접 줄인다.
- `자동 재생 보충 성공 24%`는 위 분모 문제와 R1/R2가 겹친 값이다. 앞으로는
  `skip` 로그와 `autoplay-append`/`party` landed 로그를 나눠 봐야 한다.
- `다운로드·고음질 보관함 실패 140회`는 이 영역의 보충 경로가 아니라 다운로드/
  품질 선택 경로다. 다만 다운로드된 비디오만 있는 오프라인 보충은
  `loadOfflineVideoRadio`가 부분 배치를 유지하므로 보충 자체는 실패하지 않는다.

## 5. 남긴 결함

| 심각도 | 파일:줄 | 내용 | 이유 / 필요한 작업 |
|---|---|---|---|
| LOW | `PlaybackService.kt:8414-8423` | `autoplaySessionIds`/`sessionSongHistory`는 명시적 라디오 시작에서만 초기화된다. 일반 새 앨범에서는 이전 큐의 추천 이력이 남는다. | 세션 정의(프로세스 vs 큐)를 먼저 정해야 한다. 이번 변경은 "큐에 실제로 들어간 곡만 기록"까지다. |
| LOW | `VideoRadioSources.kt:53-68` | 채널 해석 실패를 캐시하지 않아 같은 시드에서 매 보충마다 재시도한다. | 짧은 negative cache는 배터리에 좋지만 캐시 만료 전 복구를 막을 수 있어 정책 결정이 필요하다. |
| LOW | `PlaybackService.kt:3648-3678` | 코루틴 내부의 조기 반환(플레이어 소실, 모드/현재 곡/공급자 변경)은 여전히 무로그다. | 취소성 반환이라 오류가 아니지만, 지표를 더 엄밀히 하려면 `reason=`을 붙일 수 있다. 이번에는 상위 진입점의 무로그 반환(R5)만 고쳤다. |

## 6. 새 테스트

- `AutoplayFallbackTest`: `leaving repeat one releases the seed so the queue can refill`
- `VideoRadioPolicyTest`: `a successful empty station still runs the ordinary path`,
  `a thin video pick survives when the ordinary path filters every row`
- `PartyQueueReadingTest`: `onlyTheRowsTheServerKeptAreReportedAsLanded`,
  `theLandedRowsCountNewOccurrencesNotHistory`,
  `duplicateSuggestionIdsAreConsumedOnlyAsOftenAsTheyLanded`
- `SimilarCandidatesTest`: `an empty similar answer is not cached when a YouTube search failed`

## 7. 검증

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.listentogether.PartyQueueReadingTest' --tests 'com.music.bitchord.data.videoradio.VideoRadioPolicyTest' --console=plain -q` — 통과
- `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.AutoplayFallbackTest' --tests 'com.music.bitchord.data.taste.SimilarCandidatesTest' --tests 'com.music.bitchord.playback.PartyAutoplayTest' --console=plain -q` — 통과
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과
