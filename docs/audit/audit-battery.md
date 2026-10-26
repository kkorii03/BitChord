# 배터리 전수 감사 (audit-battery) — 2026-10-26, 수정 포함

기준: 현재 워크스페이스 HEAD. 이 체크아웃에는 `ko-101` 태그가 없어
`git diff ko-101..HEAD`를 직접 만들 수 없었다. 대신 `docs/audit/`,
`docs/cleanup/`의 ko-101 이후 기록과 현재 코드의 호출 경로를 대조했고,
아래 근거는 모두 지금 파일의 `파일:줄`이다. 기기 프로파일링 수치는 쓰지
않았고, 빈도와 영향은 코드 상수·조건에서 확인되는 범위만 적었다.

표시: **HIGH**는 코드 수정 + 회귀 테스트, **MEDIUM/LOW**는 재현 경로와
수정 제안만 남겼다. 이번 작업은 배터리 범위만 다뤘고, 사용자에게 보이는
문구·설정·저장 형식은 바꾸지 않았다.

## 결과 표

| 심각도 | 파일:줄 | 무엇이 고장 | 재현 경로 | 수정 |
|---|---|---|---|---|
| HIGH | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt:117`, `:332-334`, `:365` | 저전력에서도 AutoMix가 곡마다 전체 디코드 + ONNX 2회를 실행한다. `stopped`가 파티만 봤다. | 배터리 세이버를 켜거나 20% 이하로 방전한 채 스마트 페이드 + 다음 곡이 있는 큐를 재생. `power: decoders active=... automix`가 찍히고 `analysisMillis`가 증가한다. | **고침**. `BatteryPolicy.automixAnalysisActive(lowPower, inParty)`로 통일. 저장된 결과 복원은 유지하고 새 디코드만 미룬다. |
| HIGH | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1598-1612` | 저전력에서도 다음 곡의 `PRELOAD_BYTES` + `fetchWhole(next)`를 내려받고 디스크에 쓴다. | 저전력 상태에서 다음 곡이 있는 큐 재생. 10초 뒤 읽기 앞서기 바이트가 캐시에 쌓인다. | **고침**. `BatteryPolicy.cachePrefetchBytesAllowed(metered, lowPower)`를 큐 시점과 10초 지연 뒤에 다시 읽는다. URL 워밍업은 유지. |
| HIGH | `app/src/main/java/com/music/bitchord/download/MvSoundRedownload.kt:41`, `:54` | 앱 시작·재생·판정마다 `ExistingWorkPolicy.REPLACE`가 진행 중인 MV 소리 복구를 취소하고 처음부터 다시 시작한다. | `autoRedownloadMvSound`를 켜고 suspect가 여러 개인 상태에서 앱을 다시 열거나 곡을 넘긴다. WorkManager 고유 작업이 취소/재시작된다. | **고침**. `APPEND_OR_REPLACE`로 바꿔 진행 중인 패스 뒤에 큐잉한다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/stats/ArtistFacts.kt:192-206`, `:335` | 아티스트 백필이 1.5초 간격 네트워크를 화면 꺼짐·저전력에도 계속한다. | 보관함을 크게 가져오거나 새 아티스트를 많이 재생한 뒤 화면을 끈다. `requests`가 빌 때까지 1.5초마다 요청이 나간다. | 제안: `AppSettings.lowPower`/화면 켜짐 이벤트를 기다린 뒤 채널을 소비한다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:766-810`, `:3454`, `:3461` | 재생 중이면 전환이 불가능해도 화면 켜짐 250ms·꺼짐 1초 ticker가 계속 돈다. | 스마트 페이드 끔, 크로스페이드 0으로 두고 재생. `tickStepMs()`가 IDLE에서도 250/1000을 돌려준다. | 제안: 다음 전환 예정 시각까지의 timeout으로 바꾸고, 전환이 불가능하면 `tickerWake`만 기다린다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:2081`, `:2123`, `:2147` | 분석 헤드 대기가 250ms/40ms/100ms 폴링이다. | AutoMix·가사 타이밍이 같은 곡의 헤드 바이트를 기다릴 때 IO 스레드가 초당 25회 깨어난다. | 제안: 바이트 임계치/`analysisHeadsInFlight` 해제를 `CompletableDeferred`로 알린다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/playback/SongVersionController.kt:114-149`, `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:159-187`, `:424` | 곡 버전·HQ 후보 조회가 저전력을 보지 않는다. (`LYRICS`/`SYNC`만 `heavyWorkAllowed`를 본다.) | 저전력에서 음악 전용 큐를 재생. 곡마다 앞뒤 최대 4행의 버전 조회와 HQ 대기가 실행된다. | 제안: `heavyWorkAllowed`가 false면 버전/HQ 단계를 다음 재생으로 미룬다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/download/DownloadService.kt:113`, `:118-127`, `:278`, `:289-291` | 큐가 빈 동안 4개 worker가 100ms 간격으로 최대 2초 폴링한다. | 다운로드 서비스 시작 직후 큐가 잠깐 비면 worker마다 20회, 총 80회 깨운다. | 제안: `Downloads`의 채널/플로우 wake-up으로 바꾼다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/playback/smart/AudioDecoder.kt:65`, `app/src/main/java/com/music/bitchord/download/DownloadService.kt:113` | 다운로드 4 worker와 AutoMix/HQ 분석이 동시에 실행될 수 있다. 디코드만 전역 세마포어로 직렬화된다. | 다운로드 중 곡을 넘기면 4개 다운로드 worker와 분석 디코드가 같은 시간대에 CPU/네트워크를 쓴다. | 제안: 다운로드가 활성일 때 백그라운드 분석을 이벤트로 잠시 멈추거나 worker 수를 줄인다. |
| LOW | `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:741-746`, `:922` | 영상 가사 측정이 최대 30초 동안 250ms 간격으로 캐시를 확인한다. | 영상/커버 가사 측정에서 오디오가 늦게 도착하면 120회 확인한다. | 제안: 캐시 바이트 증가 이벤트로 대체한다. |
| LOW | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/presence/Presence.kt:55`, `:120` | 앱이 열려 있는 동안 5분마다 presence POST. | 앱을 포그라운드에 오래 둔다. | 의도된 서버 간격. 유지. |
| LOW | `app/src/main/java/com/music/bitchord/download/DownloadService.kt:146`, `:264` | 다운로드 알림을 250ms마다 갱신. | 다운로드 중 알림 progress가 4/초 갱신된다. | 알림 요구사항. 유지. |
| LOW | `app/src/main/java/com/music/bitchord/lockscreen/LockScreenLyricsActivity.kt:465-470` | 잠금화면 시계가 보이는 동안 1분마다 갱신. | 잠금화면 가사 화면을 연다. | 포그라운드 게이트가 이미 있다. 유지. |

## HIGH 수정 내용

### H1. AutoMix 분석이 저전력을 무시하던 회귀

- 이전: `TrackAnalyzer.stopped`는 `ListenTogether.state.value.inParty`만 봤다.
  `request()`는 저장된 결과를 복원한 뒤 곧바로 executor에 디코드를 넣었다.
  `AppSettings.lowPower`/`heavyWorkAllowed`는 검사하지 않았다.
- 지금: `BatteryPolicy.automixAnalysisActive(lowPower, inParty)`를 `stopped`의
  유일한 정의로 쓴다. `request()`는 파티면 즉시 반환하고, 저장된 결과 복원과
  `hasWindow` 확인 뒤 `heavyWorkAllowed`가 false면 새 디코드를 시작하지 않는다.
  `requestWindowNow`, `runWindow`, `AudioDecoder`의 abort, `awaitWindow`가 모두
  같은 `stopped`를 읽으므로 진행 중인 패스도 다음 단계에서 멈춘다.
- 조건 진리표 (기존 항을 모두 유지):

| lowPower | inParty | 기존 `stopped` | 새 `stopped` |
|---|---|---|---|
| false | false | false | false |
| false | true | true | true |
| true | false | false | **true (새로 미룸)** |
| true | true | true | true |

  기존에 true였던 경우는 모두 true로 남고, `lowPower=true, inParty=false`만
  새로 멈춘다. 저전력이 해제되면 Crossfade ticker가 다음 tick에 다시
  `request()`를 호출하므로 별도 타이머는 없다. `awaitWindow`는 미뤄진 작업을
  15초 기다리지 않고 즉시 false를 돌려 다음 곡 준비를 붙잡지 않는다.
- 회귀 테스트: `shared/src/jvmTest/kotlin/com/music/bitchord/playback/BatteryPolicyTest.kt`
  `automix decodes only when neither low power nor a party stops it`.

### H2. 다음 곡 전체 선반입이 저전력을 무시하던 문제

- 이전: `AudioCache.prefetchQueue`의 `cacheBytes`는 `!metered`만 봤다.
  저전력에서도 10초 뒤 `fetch(next, 0, PRELOAD_BYTES)`와 `fetchWhole(next)`가
  실행됐다.
- 지금: `cacheBytes = !metered && !lowPower && (!substitutable || warmed != null)
  && !pinnedToYouTube`. 기존 항(`!metered`, 대체본 조건, YouTube 고정 제외)은
  그대로 두고 `!lowPower`만 추가했다. 10초 지연 동안 저전력이 켜질 수 있으므로
  바이트를 실제로 쓰기 직전에 같은 정책을 다시 읽는다.
- 화면 꺼짐은 이 조건에 넣지 않았다. 화면이 꺼진 상태의 재생은 정상 사용
  경로이고, 다음 곡 선반입이 끊김 없는 전환을 만든다. 저전력은 사용자가
  선택했거나 시스템이 배터리를 아끼는 상태이므로 그때만 바이트를 미룬다.
  URL 해석은 저전력에서도 유지해 전환 때 client walk를 기다리지 않는다.
- 회귀 테스트: 같은 파일
  `read-ahead spends bytes only unmetered and out of low power`.

### H3. MV 소리 복구 작업이 매번 취소·재시작되던 문제

- 이전: `MvSoundRedownload.scheduleIfEnabled`가
  `ExistingWorkPolicy.REPLACE`를 썼다. 호출부는 앱 시작
  (`BitChordApplication.kt:226`), 재생 전환
  (`SongVersionController.kt:216`, `:308`), 다운로드 스캔
  (`Downloads.kt:869`), 설정 변경(`SettingsSheet.kt:740`)이다.
- 지금: `APPEND_OR_REPLACE`. 진행 중인 고유 작업은 취소하지 않고 뒤에
  새 작업을 붙인다. 체인이 이미 끝났거나 취소됐으면 새로 시작한다.
  `HqLibraryUpgrade`가 새 판정에 쓰는 정책과 같다.
- 회귀 테스트: `app/src/test/java/com/music/bitchord/download/MvSoundRedownloadPolicyTest.kt`
  `a new schedule queues behind a pass already in flight`.

## 화면 꺼짐 디코더 확인 (`power: decoders active`)

- 재생 중이면 세션 플레이어의 디코더는 당연히 활성이다. 화면 꺼짐에서
  추가로 도는 디코더는 AutoMix 분석뿐이며, 이는 화면이 꺼져도 들리는
  크로스페이드를 준비하기 위한 의도된 동작이다. 이번 수정으로 저전력에서는
  시작하지 않는다.
- HQ 검증/기준 WAV(`VideoAudio.kt:63,99,139,176`), 버전 정렬
  (`VersionAudioAligner.kt:169,202,211`), 가사 타이밍
  (`LyricsTiming.kt:849,862,883`), 품질 교체(`PlaybackService.kt:6208`),
  캔버스 프레임(`CanvasArtworkPlayer.kt:345-356`)은 모두 화면/포그라운드
  게이트가 있다. 화면 꺼짐에서 새로 시작하는 불필요한 디코더는 확인하지
  못했다.

## 유휴 보유·재연결 확인

- 유휴 보유: `DownloadService`는 큐가 2초 비면 종료
  (`DownloadService.kt:124,289`), 분석 헤드 keep은 15분
  (`AudioCache.kt:1135`), HQ 호스트 rest는 60초
  (`HqRetryPolicy.kt:29`)로 상한이 있다. 무기한 유휴 루프는 확인하지
  못했다.
- 재연결: `ListenTogether.runSocketLoop`은 1초→30초 지터 백오프와
  `bye`/`bad_token`/`no_such_party` 종료 처리가 있고
  (`ListenTogether.kt:1269-1312`, `:1481-1494`), `PartySync`의
  `ensureConnected()`는 소켓이 살아 있으면 즉시 반환한다. 반복 재연결
  버그는 확인하지 못했다. 이번에 찾은 반복 작업 버그는 H3뿐이다.

## 이전 감사에서 이미 고쳐진 것 (현재 코드로 확인)

- `PartySync` 700ms ticker: 파티 재생 중에만 시작
  (`PartySync.kt:226,235`).
- Listen Together health monitor: 화면·파티·소켓 상태로 시작/중지
  (`ListenTogether.kt:519`).
- 오디오 캐시 정리: 전체 키 스캔 대신 `purgeCandidates` 인덱스
  (`AudioCache.kt:1148-1190`).
- 로그인 WebView: YouTube/Discord 모두 `onRelease`에서 destroy
  (`YtMusicLoginScreen.kt:152`, `DiscordLoginScreen.kt:234`).
- YouTube 로그인 쿠키 폴링: 페이지 이벤트 기반으로 교체
  (`YtMusicLoginScreen.kt:90-95`).

## 테스트

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.BatteryPolicyTest'`
  통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.download.MvSoundRedownloadPolicyTest'`
  통과.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true`
  통과.

## 남긴 것

- MEDIUM/LOW 항목은 동작·타이밍을 바꿀 수 있어 이번 범위에서 손대지 않았다.
  특히 Crossfade ticker와 분석 헤드 폴링은 전환 정확도와 얽혀 있어 별도
  작업에서 이벤트 신호를 먼저 만드는 편이 안전하다.
- 다운로드와 분석의 동시 실행은 현재 디코드만 직렬화되어 있다. 다운로드
  worker 수를 줄이거나 분석을 멈추는 정책은 사용자 체감(다운로드 속도)과
  맞물려 결정이 필요하다.
