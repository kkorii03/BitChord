# network — 프록시·게이트·봇 확인·PoToken·visitor id·타임아웃·재시도 심층 검토

기준: `task/fix-1030` 계열, 현재 작업 브랜치 `dsq/deep-network-1031`
범위: 프록시(MUSIC_ONLY/ALL_YOUTUBE), MusicGateFallback, `SignInConfirmNotBot`,
PoToken WebView/Generator, visitor id, 요청 타임아웃·재시도, IPv4/IPv6, 느린/끊긴 망.
코드는 실제 파일을 읽고 호출부까지 따라갔으며, 아래 줄 번호는 이번 수정 후 기준이다.

## 1. 진입점 → 처리 → 결과 표

| 흔한 상황 | 진입점 | 실제 처리 | 결과 / 판정 |
|---|---|---|---|
| 커버 영상 재생 | `StreamResolver.resolveUncached` → InnerTubeX → extraction | 직접 walk 실패가 bot/Premium/국가 게이트면 프록시 1회. 다운로드는 이번에 같은 신호로 프록시 1회 추가 | 정상. 단 bot은 이제 곡 판정이 아니라 네트워크 정지 |
| 비디오 노래(MV) | `browse`/`next` + `StreamResolver` | ALL이면 player 포함 전부 프록시, MUSIC_ONLY면 player는 직접 + 거부 시 1회 프록시 | 정상. ALL 캐시 세대 누락은 수정 |
| 셔플/다음 곡 | `loadAutoplayForCurrentTrack`, `NextTrackPreparation`, read-ahead | 이벤트(첫 소리·큐 변경)로만 시작. bot이면 read-ahead가 곡을 unplayable로 기록하지 않음 | 정상. 주기 폴링 없음 |
| 앱 재시작 후 | `ProxyPlayback.attachPersistence`, `MusicGateFallback.init` | 프록시 성공 videoId 30일 기억, 게이트 판정은 프록시 사용 시 즉시 재확인 | 정상. URL 캐시는 프로세스 한정 |
| 화면 꺼짐/백그라운드 | `PlaybackService`, `ChunkedDataSource` | 재생·다운로드 요청이 있을 때만 네트워크. 새 타이머 없음 | 정상 |
| 프록시 켬(MUSIC_ONLY) | `postMusic`, `StreamResolver.resolveViaProxy` | music/youtubei만 프록시, `/player` 제외. 거부된 곡만 player+media를 프록시로 | 정상 |
| 프록시 켬(ALL) | `ProxyScopeMatcher`, `Http.proxyMediaClient` | 모든 YouTube host+googlevideo 프록시. 캐시 URL에 라우트 세대를 이제 기록 | 정상. MUSIC_ONLY→ALL 전환 캐시 무효화 추가 |
| 프록시 서버 사망/백업 | `ProxyHealthMonitor`, `ProxyRoutePolicy` | 요청 실패 이벤트에서만 primary↔backup. 백업 실패 시 5분 직접 우회 | 정상. no-backup에서 direct 실패 전에 proxy를 condemn하던 순서 수정 |
| 느린/끊긴 망 | `Http`, `Innertube.withRetry`, `HqRetryPolicy` | connect 4s(메타)/20s(미디어), request 30s, 추출 5s, probe 6s. Ktor request timeout도 proxy 실패로 인식 | 수정. 긴 Retry-After는 URL 캐시에서 제거 |
| 다운로드된 곡 | `ProxyAwareDataSource`, `AudioCache` | URL을 mint한 route를 명시적으로 따라감. 직접 URL은 프록시 변경에도 유지 | 정상 |
| Android 14+ | WebView PoToken, foreground playback | 네트워크/스레드 제약 외 특이 경로 없음. WebView는 main thread에서 생성/파괴 | 정상 |

## 2. 이번에 고친 결함

### HIGH 1 — 봇 확인이 곡 고유 판정으로 저장되어 큐 전체를 건너뛰던 문제
- 파일: `shared/.../innertube/UnplayableReason.kt:103,169`, `shared/.../innertube/StreamResolver.kt:355,411,853,869,1593`, `app/.../playback/PlaybackService.kt:4619,4812,5338`
- 증거(수정 전): `definitive`에 `"sign in to confirm you are not a bot"`, `"봇 확인"`이 있어
  `UnplayableRecovery.isDefinitive`가 true → `switchToAlternateOrSkip` → 다음 곡. 동시에
  `permanentReason(e)`가 `rememberUnplayable(videoId, ...)`로 10분간 곡별 기억.
- 사용자 영향: 네트워크가 봇 확인을 요구하면 재생 가능한 곡까지 연속 skip. 로그에는 곡별
  "not playable"만 남아 원인(네트워크)이 보이지 않음.
- 흔한 정도: 한국 IP/프록시 미설정·프록시 사망 시 한 번에 발생. 362곡 성적표의 자동 재생
  보충 성공 24%와 다음 곡 준비 29%에 직접 기여.
- 수정: bot을 `definitive`에서 제거하고 `isBotChallenge`로 분리. `StreamResolver`는
  `BotChallengeException`을 던지고 unplayable 캐시를 건드리지 않음. visitor id는 10분에 1회만
  비동기 refresh. `PlaybackService`는 bot을 Media3 permanent로 취급해 재시도·skip 없이 정지하고
  토스트만 띄움.
- 진리표(수정 전 → 수정 후):
  - old: bot message → `fromMessage != null` → definitive → alternate/skip
  - new: bot message → `fromMessage == null`, `isBotChallenge == true` → stop(no skip)
  - bot이 아닌 region/Premium/claim의 판정은 그대로 유지.

### HIGH 2 — ALL_YOUTUBE URL 캐시가 라우트/설정 변경 후에도 살아남던 문제
- 파일: `shared/.../net/ProxyPlayback.kt:74,156,243,277`, `shared/.../innertube/StreamResolver.kt:287,2224`
- 증거(수정 전): `generationFor(url)`는 `markResolved`가 기록한 URL만 세대를 가졌다. ALL은
  `markResolved`를 거치지 않으므로 `proxyGeneration == null` → `cachedUrlUsable`이 항상 true.
  primary→backup 또는 설정 편집 후 옛 서버가 mint한 URL을 새 서버로 열어 403.
- 사용자 영향: ALL 사용자가 프록시 서버를 바꾸거나 백업으로 넘어간 직후 재생/탐색이 403.
- 흔한 정도: ALL 스코프 + 서버 변경/장애 시. 재현은 쉬우나 ALL 사용자 수에 비례.
- 수정: ALL에서도 YouTube host URL에 현재 route 세대를 기록. 캐시 검사는 MUSIC_ONLY/ALL
  공통 `canUseCachedProxyUrl()`을 사용. MUSIC_ONLY→ALL 전환 시 이전 direct URL을 재사용하지
  않도록 `mintedOnAllScope` 항을 추가.
- 진리표:
  - old: `gen == null || (gen == cur && canResolve)`
  - new: `!(allNow && !mintedAll) && (gen == null || (gen == cur && canResolve))`
  - 기존 두 항은 그대로 유지(삭제 없음).

### MEDIUM 3 — 프록시 URL 세대가 요청 완료 시점에 찍히던 경합
- 파일: `shared/.../net/ProxyPlayback.kt:156`, `shared/.../innertube/StreamResolver.kt:906,1578`
- 증거: `resolveViaProxy`/`proxyDownloadStream`이 walk가 끝난 뒤 `markResolved`에서 현재
  세대를 읽었다. walk 중 browse 성공이 primary→backup으로 바꾸면 새 세대가 찍혀 옛 서버 URL을
  새 route로 fetch.
- 사용자 영향: 드물게 403, mid-play 재해석 1회. 백업 전환 직후에 주로.
- 수정: walk 시작 전에 세대를 캡처해 `markResolved(videoId, url, generation)`로 전달.

### MEDIUM 4 — no-backup 프록시 실패가 direct 재시도 전에 5분 우회를 켜던 문제
- 파일: `shared/.../innertube/Innertube.kt:1830`
- 증거(수정 전): `postMusic`이 proxy 실패에서 곧바로 `recordUnreachable`을 호출했다. 백업이
  없으면 `ProxyHealthMemory`가 즉시 UNREACHABLE이 되고, 이어지는 direct 재시도가 실패해도
  5분 우회가 남았다. 주석은 "direct 실패 시 UNKNOWN 유지"라고 했지만 코드와 반대.
- 사용자 영향: 느린 망에서 프록시와 direct가 함께 흔들리면, 망이 회복된 뒤에도 5분간 프록시를
  쓰지 않아 한국 게이트에 다시 걸림.
- 흔한 정도: 이동 중/느린 망에서 프록시 사용자.
- 수정: 백업이 있을 때만 즉시 `recordUnreachable`(라우트 전환 목적). 백업이 없으면 direct
  재시도가 성공한 뒤에만 기록.

### MEDIUM 5 — Ktor request timeout이 프록시 실패로 인식되지 않던 문제
- 파일: `shared/.../net/ProxyHealth.kt:46`
- 증거(수정 전): `ProxyFailure.isProxyDown`은 `SocketTimeoutException`/메시지의 proxy·407·
  tunnel만 봤다. Ktor의 `HttpRequestTimeoutException`은 일반 IOException이라, 연결은 되지만
  응답이 없는 프록시가 30초를 태우고도 backup/direct로 넘어가지 않았다.
- 사용자 영향: 프록시가 절반쯤 죽은 상태에서 Home/재생이 30초씩 멈춤.
- 수정: "request timeout"/"timeout has expired"/"timed out" 메시지를 proxy 실패로 인정.
  no-backup은 MEDIUM 4 덕분에 direct 성공 전에는 5분 우회를 켜지 않는다.

### MEDIUM 6 — 긴 Retry-After 뒤 같은 URL을 다시 열던 문제
- 파일: `shared/.../innertube/StreamResolver.kt:1883`, `app/.../playback/ChunkedDataSource.kt:480`
- 증거: `ChunkedDataSource`는 8초 초과 Retry-After에서 즉시 throw했지만 `StreamResolver.recent`
  에는 같은 URL이 남아 있었다. `PlaybackService`는 350ms 뒤 같은 아이템을 prepare하고 resolving
  factory가 같은 URL을 돌려줘 429/503을 반복, 2회 예산을 1초 만에 소진.
- 사용자 영향: 서버가 30초 쉬라고 한 경우 곡 skip/정지. 다운로드·고음질 실패 140회의 한 축.
- 수정: `StreamResolver.onRateLimited(url)`로 recent/lastRefused에서 제거. 다음 open은 새 URL을
  mint한다. 403/404/410의 기존 client exclusion은 그대로.

### MEDIUM 7 — 다운로드가 국가/Premium 게이트에서 프록시를 시도하지 않던 문제
- 파일: `shared/.../innertube/StreamResolver.kt:981,1585`, `shared/.../innertube/UnplayableReason.kt:218`
- 증거(수정 전): 다운로드의 proxy fallback 조건은 bot 예외뿐이었다. `newPipeStream`이
  `YoutubeMusicPremiumContentException`/`GeographicRestrictionException`을 던지거나
  InnerTubeX가 지역 게이트로 catalog를 소진해도 `isRegionRefusal(false,false)`라 프록시를 쓰지 않음.
- 사용자 영향: 프록시를 켠 사용자도 다운로드/고음질 보관함이 실패. 성적표의 140회와 직접 연결.
- 수정: `isProxyWorthyRefusal()`(국가/Premium/bot, 단 claim/private 제외)와
  `UnplayableReason.isRegionGate`를 추가하고, InnerTubeX 실패 사유도 `onFailure`로 받아
  다운로드에서 정확히 1회 프록시 walk. bot이면 그 뒤 정지.

### MEDIUM 8 — PoToken 생성 outer timeout이 WebView init보다 짧던 문제
- 파일: `shared/.../potoken/PoTokenGenerator.kt:69,149`
- 증거(수정 전): `getWebClientPoToken` 전체에 8초 `withTimeout`, WebView init은 45초. 느린 망에서
  Create/GenerateIT가 8초를 넘으면 매번 outer timeout이 먼저 터져 페이지를 버렸고, 45초 예산은
  도달 불가능했다.
- 사용자 영향: 느린 망/콜드 스타트에서 PoToken이 영원히 mint되지 않아 BotGuard 클라이언트가
  빠지고 재생·다운로드 성공률이 떨어짐.
- 수정: outer를 45초로. 살아 있는 페이지의 mint는 내부 15초 timeout이 계속 담당.

### MEDIUM 9 — 취소된 PoToken mint가 WebView 재생성으로 이어지던 문제
- 파일: `shared/.../potoken/PoTokenGenerator.kt:213`
- 증거(수정 전): 재사용 페이지의 `generatePoToken`이 `CancellationException`을 던져도
  `catch (Throwable)`이 "페이지 고장"으로 보고 `forceRecreate=true`로 재귀. 이미 포기한 작업에
  BotGuard 페이지를 다시 로드.
- 사용자 영향: 큐 이동/검색 취소 때 배터리·네트워크 낭비, 느린 망에서 체감 지연.
- 수정: `CancellationException`을 먼저 rethrow.

## 3. 남긴 결함 (이번에 고치지 않은 것)

| 심각도 | 파일:줄 | 내용 | 이유 / 필요한 작업 |
|---|---|---|---|
| LOW | `app/.../playback/ProxyAwareDataSource.kt:21`, `shared/.../net/ProxyPlayback.kt:243` | ALL에서 proxy health bypass 중 mint한 direct URL은 캐시 무효화로 재해석되지만, 이미 `StreamChoice`에 고정된 URL은 bypass 해제 후 Http.client selector로 프록시를 탈 수 있음 | 이미 열린 트랙의 1회 403은 mid-play recovery가 처리. 근본 수정은 ALL URL도 `proxiedUrls`에 명시 기록하고 media factory를 direct/proxy로 URL 단위 고정하는 작업 |
| LOW | `app/.../data/innertube/potoken/PoTokenWebView.kt:411` | `close()`가 pending continuation을 실패시키지 않아, 닫힌 페이지의 mint 호출자가 내부 15초 timeout까지 대기 가능 | Android WebView 단위 테스트가 어려워 이번엔 제외. `close()`에서 `popAllPoTokenContinuations()`를 예외로 resume하면 됨 |
| LOW | `shared/.../net/ProxyHealth.kt:46` | read timeout도 proxy down으로 보므로, 아주 느린 프록시는 5분 bypass될 수 있음 | direct 재시도 성공 전에는 no-backup에서 bypass를 켜지 않도록 고쳐 피해를 제한. 추가 완화는 connect/read timeout 구분 + 성공 시 즉시 clear |

## 4. 실제 사용 성적표와 이 영역의 연결

- 다음 곡 싱크 준비 29%: `NextTrackPreparation`은 다음 행 1개만 준비하고 실패한 네트워크 step을
  같은 행에서 재시도하지 않는다. 여기에 봇 확인이 곡별 unplayable로 기록되면 read-ahead가
  다음 곡을 포기해 준비 자체가 사라졌다. bot을 네트워크 정지로 바꿔 큐 전체 포기를 막았다.
- 무손실 확인 후 실제 무손실 재생 4%: HQ 검증/교체는 별도 소스 네트워크지만, 다운로드·고음질
  보관함이 프록시 게이트에서 실패하면 검증할 후보 자체가 줄어든다. MEDIUM 7이 이 경로의
  프록시 미시도를 제거했다.
- 자동 재생 보충 성공 24%: AutoPlay는 `Innertube.next` 한 번으로 보충한다. MUSIC_ONLY에서 이
  요청은 프록시 대상이고, 프록시가 느리거나 죽으면 `withRetry`가 30초를 태운 뒤 실패했다.
  MEDIUM 4·5로 direct/backup 전환이 빨라졌고, bot은 이제 큐를 비우지 않는다.
- 사용자 조작 없는 백그라운드 시작 11회: 네트워크 요청은 첫 소리·큐 변경·재생 이벤트에서만
  발생한다. 이번 수정은 타이머/폴링을 추가하지 않았고, PoToken 취소 재생성(MEDIUM 9)을 없애
  백그라운드 낭비를 줄였다.
- 다운로드·고음질 보관함 실패 140회: 다운로드가 국가/Premium 게이트에서 프록시를 아예 시도하지
  않던 MEDIUM 7, 긴 Retry-After에서 같은 URL을 반복하던 MEDIUM 6, 느린 망 PoToken 실패
  MEDIUM 8이 직접 기여한다.

## 5. 검증

- `./gradlew :shared:jvmTest` — 전체 통과.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` — 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.SkipSegmentsTest'`
  `--tests 'com.music.bitchord.playback.DirectAudioStreamingRegressionTest'`
  `--tests 'com.music.bitchord.data.innertube.potoken.PoTokenSlowCreationTest'` — 통과.
- 전체 `:app:testProdReleaseUnitTest`는 1683개 중 22개 실패. 실패는
  `LocaleStringsTest`(번역 키 누락), `ProviderLyricsTest`, `SourceFormatsTest`,
  `BluetoothAudioTrackerTest`(한국어 로케일)로 이번 네트워크 변경과 무관한 기존 실패다.

## 6. 새 테스트

- `shared/src/jvmTest/.../innertube/UnplayableReasonTest.kt` — bot은 definitive가 아님,
  LOGIN_REQUIRED로 위장한 bot, region gate 판별.
- `shared/src/jvmTest/.../innertube/StreamResolverRateLimitTest.kt` — rate-limited URL 제거.
- `shared/src/jvmTest/.../innertube/potoken/PoTokenGeneratorCancellationTest.kt` — 취소 시 재생성 금지.
- `shared/src/jvmTest/.../net/ProxyRouteGenerationTest.kt` — ALL URL 세대, 명시 세대 stamp.
- `shared/src/jvmTest/.../net/ProxyPlaybackPolicyEdgeTest.kt` — MUSIC_ONLY→ALL 캐시 무효화.
- `shared/src/jvmTest/.../net/ProxyFailureEdgeTest.kt` — Ktor request timeout.
- `app/src/test/.../playback/SkipSegmentsTest.kt` — bot은 skip 판정이 아님.
- `app/src/test/.../innertube/potoken/PoTokenSlowCreationTest.kt` — 8초 넘는 페이지 생성.

## 7. 2차 검토 (이전 리뷰 VERDICT: ISSUES 5 반영)

`dsq/deep-network-1031-r2`. 이전 차수의 지적 5건을 실제 코드로 다시 따라가 처리했다.
모든 판정은 수정 전 코드에서 실패하고 수정 후 통과하는 테스트로 고정했다.

### MEDIUM 10 — 다운로드의 proxy/bot 판정 사유가 뒤의 timeout에 덮이던 문제 (수정)
- 파일: `shared/.../innertube/UnplayableReason.kt:267`, `shared/.../innertube/StreamResolver.kt:1558,1603,1611`
- 증거(수정 전): 다운로드의 `itxFailureReason`은 `keepTrackSpecific`만 거쳤다. 이 함수는
  claim/takedown 계열만 보존하므로, 첫 client가 `not available in your country` 또는
  `sign in to confirm you're not a bot`으로 거부되고 다음 client가 `Read timed out`이면
  사유가 timeout으로 교체됐다. 그 결과 `isRegionGate`도 `isBotChallenge`도 false가 되어
  프록시 walk와 봇 정지가 모두 사라졌다.
- 사용자 영향: 프록시를 켠 사용자의 다운로드/고음질 보관함이 국가/Premium 게이트에서
  실패하고, 봇 확인은 곡별 재시도 폭주로 이어질 수 있었다. 성적표의 다운로드·고음질 실패
  140회와 직접 연결된다.
- 흔한 정도: 느린 망에서 첫 client가 게이트로 거부되고 두 번째가 timeout이면 바로 발생.
- 수정: `UnplayableReason.keepProxyWorthy`를 추가하고 다운로드가 별도 사유로 유지한다.
  같은 실패가 claim/takedown이면 그쪽이 더 강한 판정이므로 그대로 이긴다.
- 진리표(수정 전 → 수정 후):
  - old: `keepTrackSpecific(region, timeout)` → `timeout` → proxy 미시도, bot 미정지
  - new: `keepProxyWorthy(region, timeout)` → `region` → proxy 1회, bot이면 정지
  - claim/takedown이 오면 old/new 모두 그 문구가 최종 사유.

### MEDIUM 11 — ALL resolve의 URL stamp가 완료 시점 상태로 찍히던 문제 (수정)
- 파일: `shared/.../innertube/StreamResolver.kt:308,316,2292`, `shared/.../net/ProxyPlayback.kt:93,120`
- 증거(수정 전): `resolve()`는 `coalescedResolve()`가 끝난 뒤 `remember()`에서
  `generationFor(url)`과 현재 scope를 읽었다. walk 도중 primary→backup 전환이나
  MUSIC_ONLY→ALL 전환이 일어나면, 옛 서버가 발급한 URL이 새 세대로, 직접 발급한 URL이
  ALL 발급으로 기록됐다. 다음 open이 새 route 프록시로 옛 URL을 요청해 403.
- 사용자 영향: ALL 사용자가 프록시 서버를 바꾸거나 scope를 바꾼 직후 재생/탐색 403.
- 흔한 정도: ALL + 느린 walk + route/scope 변경. 서버 장애 전환 시 재현.
- 수정: `resolve()` 시작 시 `mintedGeneration`과 `mintedOnAllScope`를 캡처해
  `remember()`에 전달한다. `ProxyPlaybackPolicy.stampFor`가 캡처값을 우선하고,
  Music-only 프록시 URL은 `markResolved`의 등록 세대를 그대로 쓴다.
- 진리표(수정 전 → 수정 후):
  - old: completion의 `generationFor(url)`, completion의 scope
  - new: 시작 시 캡처한 generation/scope. 값이 없으면(재시도가 URL을 돌려준 경우) 기존 live-read 유지.
  - 캡처 ALL=true → 시작 세대 + `mintedOnAllScope=true`
  - 캡처 ALL=false → 등록된 Music-only 세대만 인정, 없으면 `null/false`

### MEDIUM 12 — PoToken outer timeout이 init 예산과 같던 문제 (수정)
- 파일: `shared/.../potoken/PoTokenGenerator.kt:151`
- 증거(수정 전): outer `POTOKEN_TIMEOUT_MS=45_000`이 WebView init 예산 45초와 같고,
  최초 mint 15초를 더하면 60초가 필요했다. 45초에 outer가 먼저 취소되어 콜드 페이지가
  token을 만들 기회를 잃었다.
- 사용자 영향: 느린 망/콜드 스타트에서 PoToken이 영원히 없어 BotGuard client가 빠지고
  재생·다운로드 성공률이 떨어진다.
- 흔한 정도: 콜드 스타트 + 느린 망. prewarm이 늦으면 첫 곡마다.
- 수정: outer를 `75_000ms`(45s init + 15s mint + 여유)로. 살아 있는 페이지의 mint는 여전히
  내부 15초 timeout이 담당한다.
- 진리표: old `outer==init` → new `outer>init+mint`.

### MEDIUM 13 — rate limit 후 앱 레벨 pin이 남아 같은 URL을 다시 열던 문제 (수정)
- 파일: `shared/.../innertube/StreamResolver.kt:1908,1921,1934`,
  `app/.../data/innertube/AndroidStreamHooks.kt:57`
- 증거(수정 전): `onRateLimited`는 `recent`/`lastRefused`만 지웠다. 재생 계층은
  `StreamChoice.of(videoId)`와 `QualityUpgrade.forcedStream()`에서 URL을 다시 꺼내므로,
  resolver가 새 URL을 mint해도 다음 open은 rate-limited URL을 그대로 재사용했다.
  또한 URL에서 videoId를 찾지 못하면 아무 로그 없이 return했다(조용한 skip).
- 사용자 영향: Retry-After가 긴 429/503에서 같은 URL을 반복해 재시도 예산을 소진하고
  곡이 skip/정지. 다운로드·고음질 실패의 한 축.
- 흔한 정도: 429/503을 받는 모든 스트림. HQ 전환 직후 특히.
- 수정: `onRateLimited`가 항상 `rateLimitedListener(videoId?, url)`를 호출한다. 앱은
  videoId가 있으면 `StreamChoice.forget`/`QualityUpgrade.forget`으로 pin을 버리고,
  videoId가 없으면 URL로 `StreamChoice.forgetUrl`/`QualityUpgrade.forgetUrl`을 호출한다
  (강제 모듈 업그레이드 URL에는 YouTube id가 없다). videoId를 못 찾아도 조용히 return하지
  않고 로그를 남긴다.
- 진리표:
  - old: `recent.remove + lastRefused.remove`(videoId 없으면 즉시 return)
  - new: 위 + `rateLimitedListener(videoId?,url)`; 앱이 id 또는 URL로 pin 제거; unknown이면 로그
  - 기존 `recent`/`lastRefused` 삭제는 그대로 유지.

### LOW 14 — ALL bypass 중 direct URL이 캐시 미스로 재해석되던 문제 (수정)
- 파일: `shared/.../net/ProxyPlayback.kt:101`, `shared/.../innertube/StreamResolver.kt:292`
- 증거(수정 전): `allScopeNow`가 `config.usable && scope==ALL`만 봤다. ALL 프록시가
  bypass 중일 때 직접 mint한 URL은 `mintedOnAllScope=false`로 저장되고, 캐시 검사에서
  ALL로 판정되어 매 open마다 버려졌다.
- 사용자 영향: ALL 프록시 장애 중 매 재생/탐색이 불필요하게 다시 resolve되어 느려짐.
- 흔한 정도: ALL + 프록시 장애/bypass.
- 수정: `allScopeNow(configUsable, allScope, bypassing)`로 bypass를 항에 포함.
- 진리표(수정 전 → 수정 후):
  - old: `usable && all`
  - new: `usable && all && !bypass`
  - 기존 두 항은 삭제 없이 유지.

### 2차 검증
- `./gradlew :shared:jvmTest` — 전체 통과.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` — 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.data.innertube.potoken.PoTokenSlowCreationTest'` — 통과.
- 새/확장 테스트:
  - `shared/.../innertube/UnplayableReasonTest.kt` — region/bot 사유가 timeout에 덮이지 않음.
  - `shared/.../innertube/StreamResolverRateLimitTest.kt` — rate limit 시 playback listener 호출.
  - `shared/.../net/ProxyRouteGenerationTest.kt` — 시작 시점 ALL 세대 stamp, scope 전환, bypass 캐시.
  - `shared/.../potoken/PoTokenGeneratorTimeoutBudgetTest.kt` — outer 예산 > init+mint.
  - `app/.../potoken/PoTokenSlowCreationTest.kt` — 46초 init 페이지도 mint.
