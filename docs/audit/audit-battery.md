# 배터리·성능 감사 (audit-battery)

읽기 전용 감사다. 코드는 수정하지 않았다. 기준은 현재 워크스페이스의 `app/`,
`shared/`, `sharedUi/`이며, 빈도·영향은 코드 상수와 호출 경로에서 읽은
**추측**이다. 기기 프로파일링 수치는 아니다.

표시: **[버그] 실제로 틀린 동작**, **[위험] 널·경쟁·누수·무한 재시도**,
**[낭비] 배터리·네트워크·CPU 낭비**, **[빠진 기능] 사용자가 원할 만한 것**,
**알려짐**은 기존 `docs/cleanup/battery.md` 또는 `docs/AUDIT_PERFORMANCE.md`에
이미 적힌 항목, **추측**은 코드만으로 단정할 수 없는 영향이다.

---

## 1. [버그] PartySync가 파티 밖·일시정지에도 700ms마다 깨어난다 — 9

근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:2159`,
`app/src/main/java/com/music/bitchord/playback/PartySync.kt:224-225`,
`PartySync.kt:1022`, `PartySync.kt:474-489`

```kotlin
jobs += scope.launch {
    while (true) {
        delay(TICK_MS)
```

`PartySync`는 `PlaybackService.onCreate`에서 조건 없이 `start()`되고,
`reconcile()`는 파티가 아니면 곧바로 return한다. 그런데도 ticker는 서비스가
살아 있는 동안 계속 700ms마다 깨어난다. 파티 중이라도 일시정지 상태면 같은
no-op tick이 반복된다. `docs/cleanup/battery.md`는 "파티 중 700ms"로 적었지만
실제 코드는 파티 여부와 무관하게 시작한다(알려짐의 일부만 해당).

증상: 음악을 멈춰 두거나 파티에 들어가지 않은 채 앱을 백그라운드에 두면
초당 약 1.4회의 코루틴 wake-up이 서비스 수명 내내 발생한다. **추측**으로
하루 종일 두면 수만~수십만 회의 no-op 깨우기와 CPU wake가 쌓인다.

고치는 방법: `PartySync.start()`의 두 번째 `launch`를
`ListenTogether.state.map { it.inParty to it.playback.isPlaying }
.distinctUntilChanged().collectLatest { ... }`로 바꾼다. 파티 밖이거나
일시정지면 `reconcile()`을 호출하지 않고 상태 변경을 기다린다. 파티가
재생 중일 때만 700ms tick을 돌리고, 정지·버퍼링·화면 꺼짐에서는 이벤트
(`ListenTogether.state`, player 이벤트)로 깨운다.

위험도: 낮음 · 예상 작업 크기: S~M · 우선순위: 9

---

## 2. [버그] Listen Together health monitor가 '화면 꺼짐'을 모른 채 10초마다 돈다 — 9

근거: `app/src/main/java/com/music/bitchord/ui/screens/ListenTogetherScreen.kt:242-247`,
`app/src/main/java/com/music/bitchord/data/listentogether/ListenTogether.kt:492`,
`ListenTogether.kt:515-521`

```kotlin
DisposableEffect(Unit) {
    ListenTogether.setScreenActive(true)
    onDispose { ListenTogether.setScreenActive(false) }
}
```

`screenActive`는 실제 화면 상태가 아니라 **Listen Together 컴포저블이 살아
있는지**다. 화면을 끄거나 앱을 백그라운드로 보내도 컴포지션은 남아 있으므로
`isScreenActive`가 true로 유지된다. 그 결과 `shouldSkipIdleProbe()`가
`screenActive=false`로 막아 주지 못하고, Wi-Fi에서는 파티가 없어도 10초마다
`/healthz`를 요청한다. 파티 중 화면이 꺼진 경우에는 `connectionLive`가
true라 요청은 생략되지만, 10초마다 깨어나 검사만 하는 no-op 루프가 남는다.
기존 #788은 미터드 데이터만 막았고 이 생명주기 문제는 다루지 않았다.

증상: Listen Together 탭을 열어 둔 채 잠금 화면으로 두면 밤새 10초 간격의
health 요청(또는 no-op wake)이 발생한다. **추측**으로 Wi-Fi 환경에서 시간당
360회, 8시간이면 약 2,880회의 불필요한 요청/wake다.

고치는 방법: `ListenTogetherScreen`의 `DisposableEffect`에서
`rememberIsForeground()` 또는 `ProcessLifecycleOwner`를 관찰해
`setScreenActive(lifecycle.currentState.isAtLeast(RESUMED))`로 바꾼다.
`ListenTogether.startHealthMonitor()`도 `PowerManager.isInteractive`를
직접 읽거나, `syncHealthMonitor()`를
`!connectionLive && (foreground || (inParty && !metered))`일 때만 시작하도록
바꾼다. 상태 변경 시 `stopHealthMonitor()`로 즉시 취소한다.

위험도: 낮음 · 예상 작업 크기: S · 우선순위: 9

---

## 3. [낭비] 곡이 바뀔 때마다 오디오 캐시 전체 키를 스캔한다 — 8

근거: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1099-1104`,
`app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4132`,
`PlaybackService.kt:7699-7702`, `AudioCache.kt:299`

```kotlin
cache.keys.toList().forEach { key ->
    val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: return@forEach
```

`scheduleForeignCachePurge()`는 트랙 전환마다 30초 뒤
`AudioCache.dropForeignEntries(keep)`를 호출한다. 이 함수는 캐시의 **모든
키**를 순회하며 `getContentMetadata()`를 읽고, 필요하면 삭제한다. 앱 시작
때도 `keep = emptySet()`으로 한 번 더 전체 스캔한다. 사용자는 저장공간이
무제한이라 캐시가 10GB까지 커질 수 있고, 그 경우 키 수가 수천~수만 개가 된다.

증상: 곡 전환마다 전체 캐시 인덱스/메타데이터를 읽는 IO와 CPU가 발생한다.
**추측**으로 대용량 캐시에서 곡당 수십~수백 ms의 백그라운드 IO가 생기고,
배터리와 저장장치 수명에 불리하다. 사용자에게는 "곡 넘길 때 미세한 렉/발열"로
보일 수 있다.

고치는 방법: `AudioCache`에 `foreignKeys`/`analysisOnlyKeys` 같은 인덱스를
두고, 엔트리를 쓸 때 메타데이터에 따라 추가/제거한다. `dropForeignEntries`는
전체 `cache.keys`가 아니라 이 후보 집합만 순회한다. 시작 시 스윕은 매번이
아니라 하루 1회 또는 앱 버전 변경 시로 제한하고, `keep`에 인접 곡을 넣는
현재 정책은 유지한다. 바꿀 함수: `AudioCache.dropForeignEntries`,
`PlaybackService.scheduleForeignCachePurge`.

위험도: 중간(캐시가 지워지지 않거나 너무 일찍 지워질 수 있음) ·
예상 작업 크기: M · 우선순위: 8

---

## 4. [위험] 로그인 WebView가 dispose 후에도 destroy되지 않는다 — 8

근거: `app/src/main/java/com/music/bitchord/auth/YtMusicLoginScreen.kt:109-115`,
`app/src/main/java/com/music/bitchord/auth/DiscordLoginScreen.kt:125-128`,
대조: `app/src/main/java/com/music/bitchord/auth/SpotifyLoginScreen.kt:137`

```kotlin
AndroidView(
    modifier = modifier.fillMaxSize(),
    factory = { context ->
        WebView(context).apply {
```

YouTube 로그인과 Discord 로그인 화면은 `AndroidView`에 `onRelease`가 없다.
Spotify 로그인만 `onRelease = { it.destroy() }`를 쓴다. WebView는 Activity
컨텍스트, 렌더러 프로세스, JS 타이머, 네트워크 상태를 붙잡으므로 화면을 닫아도
회수되지 않는다.

증상: 로그인 화면을 열고 닫을 때마다 WebView가 누적된다. **추측**으로 반복
로그인/계정 전환 시 메모리 증가와 함께, 파괴되지 않은 WebView의 JS/네트워크
작업이 배터리를 조금씩 더 쓴다.

고치는 방법: 두 `AndroidView`에
`onRelease = { view -> runCatching { view.stopLoading(); view.destroy() } }`를
추가하고 `webView = null`로 참조를 끊는다. `webViewClient`/`webChromeClient`가
늦게 도착하는 콜백에서 이미 destroy된 뷰를 만질 수 있으므로 `runCatching`과
`captured`/`webView == null` 가드를 함께 둔다. 바꿀 곳:
`YtMusicLoginScreen`, `DiscordLoginScreen`.

위험도: 중간(destroy 시점의 JS 콜백) · 예상 작업 크기: S · 우선순위: 8

---

## 5. [낭비] 로그인 화면이 1초마다 쿠키를 읽고, 화면이 꺼져도 멈추지 않는다 — 7

근거: `app/src/main/java/com/music/bitchord/auth/YtMusicLoginScreen.kt:95-106`,
`YtMusicLoginScreen.kt:242-251`

```kotlin
while (true) {
    val ready = authCookieHeader() != null
    ...
    kotlinx.coroutines.delay(1_000)
}
```

`authCookieHeader()`는 `CookieManager.getCookie()`를 youtube.com과
music.youtube.com 두 번 호출하고, 문자열을 split/distinct/join한 뒤 세션
완성 여부를 검사한다. 주석은 "화면이 존재하는 몇 초"라고 가정하지만, 실제로는
`ready == true`가 된 뒤에도 루프가 끝나지 않고 `rememberIsForeground` 게이트도
없다.

증상: 로그인 화면을 열어 둔 채 잠금 화면으로 두면 초당 2회의 CookieManager
조회와 문자열 파싱이 무기한 계속된다. **추측**으로 화면이 꺼진 상태에서도
분당 120회, 한 시간 7,200회의 불필요한 IPC/할당이 발생한다.

고치는 방법: `val foreground = rememberIsForeground()`를 추가해
`if (!foreground) break`로 루프를 멈추고, `ready`가 true가 되면 루프를
종료한다(로그인 완료 후에는 화면이 곧 닫히므로 재확인이 필요 없다). 더
근본적으로는 `WebViewClient.onPageFinished`/`doUpdateVisitedHistory`와
`CookieManager` 콜백으로 대체하고, 폴링이 꼭 필요하면 2~3초 간격과 지수
백오프를 쓴다. 바꿀 함수: `YtMusicLoginScreen`의 `LaunchedEffect(mode)`.

위험도: 낮음 · 예상 작업 크기: S · 우선순위: 7

---

## 6. [빠진 기능] 저전력·화면 꺼짐에서도 다음 곡 전체를 선반입한다 — 7

근거: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1479-1483`,
`AudioCache.kt:1482-1483`, `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:489`

```kotlin
val metered = AppSettings.meteredConnection.value == true
val cacheBytes = !metered && (!substitutable || warmed != null) && !pinnedToYouTube
if (cacheBytes) {
    launch(TrackLog.about(next)) {
        delay(PREFETCH_DELAY_MS)
```

선반입은 미터드 여부만 본다. `AppSettings.lowPower`(배터리 절약/시스템 세이버/
20% 이하)나 `PowerState.screenOn`은 조건에 없다. 그래서 화면이 꺼져 있거나
배터리 세이버 중에도 10초 뒤 다음 곡의 `PRELOAD_BYTES`와 `fetchWhole(next)`가
실행되어 수 MB를 내려받고 디스크에 쓴다. 사용자는 데이터·저장공간이
무제한이지만 배터리를 최우선으로 둔다.

증상: 저전력 모드에서 다음 곡 전체 다운로드와 디스크 쓰기가 계속된다.
**추측**으로 곡당 수 MB, 앨범 한 장이면 수십~수백 MB의 백그라운드 전송이
배터리를 소모한다. 끊김 없는 전환은 좋아지지만 저전력 사용자의 의도와 반대다.

고치는 방법: `cacheBytes`에 `PowerState.screenOn && !AppSettings.lowPower.value`를
추가한다. 저전력/화면 꺼짐에서는 바이트 선반입을 생략하고 URL 해석
(`StreamResolver.resolve`)과 필요하면 `PRELOAD_BYTES` 헤드만 유지한다.
곡이 실제로 시작될 때 나머지를 이어 받게 한다. 바꿀 함수:
`AudioCache.prefetchQueue`. (알려짐: `docs/cleanup/battery.md` 제안 4)

위험도: 중간(곡 전환 시 첫 소리 지연) · 예상 작업 크기: S · 우선순위: 7

---

## 7. [낭비] 아티스트 정보 백필이 1.5초 간격 네트워크를 화면 꺼짐에도 계속한다 — 6

근거: `app/src/main/java/com/music/bitchord/data/stats/ArtistFacts.kt:160-167`,
`ArtistFacts.kt:193-206`, `ArtistFacts.kt:335`

```kotlin
for (name in requests) {
    ...
    delay(REQUEST_SPACING_MS)
}
```

`ArtistFacts.noticed()`는 재생 기록에서 새 아티스트를 큐에 넣고, `worker()`는
아티스트당 카드 조회 + 장르 조회를 한 뒤 1.5초 쉰다. `REQUEST_SPACING_MS =
1_500L`이다. 화면·저전력·미터드 게이트가 없다. 대규모 보관함을 처음 열거나
가져온 직후에는 큐가 수백~수천 개가 될 수 있다.

증상: 새 아티스트가 많을 때 1.5초마다 네트워크 요청이 수십 분~수 시간
계속되고, 화면이 꺼져도 멈추지 않는다. **추측**으로 라디오 wake와 배터리
소모가 발생하며, 사용자는 "가만히 두는데 발열/배터리 감소"로 느낄 수 있다.

고치는 방법: `worker()`에서 요청을 꺼내기 전에
`PowerState.screenOn`, `AppSettings.lowPower.value`, `meteredConnection`을
확인하고, 조건이 맞지 않으면 `requests`를 소비하지 않은 채 이벤트(화면 켜짐/
저전력 해제/충전)를 기다린다. 또는 이 백필만 WorkManager의
`requiresCharging`/`unmetered` 작업으로 옮긴다. 바꿀 함수:
`ArtistFacts.worker`, `ArtistFacts.noticed`.

위험도: 낮음(정보 표시만 지연) · 예상 작업 크기: S~M · 우선순위: 6

---

## 8. [낭비] 분석 헤드 대기가 40ms/100ms 폴링이다 — 6

근거: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1914`,
`AudioCache.kt:1947`, `AudioCache.kt:1989`, `AudioCache.kt:2012-2013`

```kotlin
while (SystemClock.elapsedRealtime() < deadline) {
    ...
    delay(40)
}
```

`awaitAnalysisHead()`는 기본 20초, `awaitAnalysisTrack()`은 기본 60초 동안
40ms/100ms 간격으로 깨어나 `alignmentPrefixBytes`/`analysisHeadsInFlight`를
검사한다. 40ms는 초당 25회다. 주석은 250ms 구간은 완화했지만, 실제 바쁜
구간은 여전히 40ms다.

증상: AutoMix/가사 타이밍 분석이 헤드를 기다리는 동안 IO 스레드가 초당
25회 깨어난다. 재생 자체와 경쟁하며 **추측**으로 배터리·CPU에 불리하다.

고치는 방법: `AudioCache`가 헤드 바이트 임계치 도달 또는
`analysisHeadsInFlight` 비움을 `CompletableDeferred`/`MutableStateFlow`로
알리게 하고, `awaitAnalysisHead`/`awaitAnalysisTrack`은 그 신호를
`suspend`로 기다린다. 폴링을 유지해야 한다면 40ms → 250ms → 500ms 지수
백오프로 바꾼다. 바꿀 함수: `AudioCache.awaitAnalysisHead`,
`AudioCache.awaitAnalysisTrack`.

위험도: 낮음 · 예상 작업 크기: M · 우선순위: 6

---

## 9. [낭비] Crossfade ticker가 스마트 페이드가 꺼져도 재생 내내 250ms마다 돈다 — 6

근거: `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:753-765`,
`CrossfadeController.kt:783`, `CrossfadeController.kt:3418`,
`CrossfadeController.kt:851-872`

```kotlin
while (isActive) {
    tick()
    val step = tickStepMs()
```

`tickStepMs()`는 `Phase.IDLE`에서 재생 중이면 `IDLE_STEP_MS = 250L`을
돌려준다. 스마트 페이드가 꺼져 있거나 크로스페이드 길이가 0인 일반 gapless
재생에서도 매 tick `publishAnalysisState()`와 `considerAutoTransition()`이
실행된다. 화면이 꺼지면 1초로 줄지만 여전히 주기적이다.

증상: 재생 중 초당 4회의 CPU wake-up과 상태 검사가 발생한다. **추측**으로
한 시간 재생 시 14,400회, 화면 꺼짐에서도 3,600회의 no-op에 가까운 tick이
쌓인다. 사용자에게는 직접 보이지 않지만 배터리·발열에 기여한다.

고치는 방법: `tickStepMs()`가 `Phase.IDLE && (!AppSettings.smartFadeEnabled.value
|| configuredFadeMs() == 0L)`이고 대기 중인 분석/마커가 없으면 `0L`을
돌려주게 한다. 이때 `tickerWake.receive()`로 player 이벤트(재생 시작, 트랙
전환, seek, 남은 시간 임계)만 기다린다. `planAutoTransition()`의 계산 결과를
(곡 쌍, 분석 버전) 단위로 캐시한다. 바꿀 함수:
`CrossfadeController.tickStepMs`, `planAutoTransition`.
(알려짐: `docs/cleanup/battery.md` 제안 1)

위험도: 중간(크로스페이드 타이밍) · 예상 작업 크기: M · 우선순위: 6

---

## 10. [빠진 기능] 저전력에서도 AutoMix 분석(디코드+ONNX)을 그대로 실행한다 — 6

근거: `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt:293-301`,
`TrackAnalyzer.kt:419-423`, `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:489`

```kotlin
// Low power lowers the worker's thread priority ...
restoreOnce(trackId)
...
if (!PowerState.screenOn) return
```

`TrackAnalyzer.request()`는 화면 꺼짐만 막는다. `AppSettings.lowPower`는
검사하지 않는다. 낮은 우선순위는 `AutomixPerformanceMode.EFFICIENT` 설정에서만
적용되고, 이는 저전력 모드와 별개다. 주석의 "Low power lowers..."와 실제
동작이 다르다.

증상: 배터리 세이버/20% 이하에서도 곡마다 전체 디코드와 ONNX 추론 2회가
실행된다. **추측**으로 곡당 수 초의 CPU와 발열이 발생하며, AutoMix가 필요
없는 사용자에게도 비용이 든다.

고치는 방법: `restoreOnce(trackId)` 뒤에
`if (AppSettings.lowPower.value) return`을 추가한다(저장된 결과 복원은 유지).
저전력이 해제되면 Crossfade ticker가 다시 `request()`를 호출하므로 별도
타이머는 필요 없다. 또는 `AppSettings.heavyWorkAllowed`를 그대로 사용한다.
바꿀 함수: `TrackAnalyzer.request`. (알려짐: `docs/cleanup/battery.md` 제안 6)

위험도: 중간(AutoMix 분석 지연) · 예상 작업 크기: S · 우선순위: 6

---

## 11. [빠진 기능] 화면 꺼짐·저전력에서도 품질 업그레이드/곡 버전 조회가 네트워크를 쓴다 — 6

근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:1206`,
`PlaybackService.kt:5521-5536`, `app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt:474`,
`app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:156`

```kotlin
if (isPlaying) prefetchAround(exoPlayer) else cancelPrefetch()
if (isPlaying) lookForBetterCopy(exoPlayer)
```

`lookForBetterCopy()`는 재생 중이면 화면 상태를 보지 않고 시작된다. 내부의
`QualityUpgrade.lookAgain()`도 화면 게이트가 없어 카탈로그 검색과 소스 경쟁을
계속한다. `HqAudioVerify`는 검증 단계만 화면 꺼짐에서 중단하므로, 검색·후보
탐색 네트워크는 이미 나간 뒤다. `scheduleUpcomingSongVersions()`도 같은
경로다.

증상: 화면을 끄고 음악만 들을 때도 다음 곡 버전 조회, 소스 검색, 품질 후보
탐색이 라디오를 깨운다. **추측**으로 곡당 수 회의 요청이 발생하고, 배터리
우선 사용자에게는 불필요하다.

고치는 방법: `lookForBetterCopy()`와 `scheduleUpcomingSongVersions()`의
자동 경로를 `PowerState.screenOn || AppSettings.charging.value`로 게이트한다.
화면이 다시 켜지면 `ACTION_SCREEN_ON`에서 한 번만 재시도한다. 사용자가 직접
누른 수동 업그레이드는 예외로 둔다. 바꿀 함수:
`PlaybackService.lookForBetterCopy`, `SongVersionController.scheduleUpcomingSongVersions`.
(일부 알려짐: 곡 버전 미리 찾기는 `docs/cleanup/battery.md` 제안 5)

위험도: 중간(업그레이드가 다음 화면 켜짐까지 지연) · 예상 작업 크기: S · 우선순위: 6

---

## 12. [낭비] 캔버스 재생 중 3초마다 GPU 텍스처를 읽어 온다 — 5

근거: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt:291`,
`app/src/main/java/com/music/bitchord/ui/player/CanvasArtworkPlayer.kt:351-361`,
`NowPlayingScreen.kt:2367`, `NowPlayingScreen.kt:3251`

```kotlin
while (isActive) {
    delay(interval)
    val view = textureView ?: continue
    val bitmap = view.captureAt(...)
```

플레이어가 열려 있고 캔버스(움직이는 커버)가 재생 중이면 `MESH_REFRESH_MS =
3_000L`마다 `TextureView.getBitmap()`으로 GPU→CPU readback을 한다. 포그라운드
게이트는 이미 있지만, 화면을 보고 있는 동안 계속 발생한다.

증상: 캔버스 곡을 볼 때 3초마다 GPU 동기화/비트맵 할당이 발생한다.
**추측**으로 스크롤·애니메이션 중 미세한 프레임 끊김과 배터리 소모가 생긴다.

고치는 방법: 저전력에서는 `refreshFrameEveryMs`를 6~10초로 늘리거나 null로
한다. 캔버스가 정지 화면에 가까운 경우(움직임이 적은 클립)에는 첫 프레임만
사용한다. `shouldRefreshFrames()`에 `!AppSettings.lowPower.value`를 추가한다.
바꿀 함수: `AndroidCanvasArtworkPlayer`의 refresh 루프,
`NowPlayingScreen.meshRefreshMs`.
(알려짐: `docs/AUDIT_PERFORMANCE.md` #3, `docs/cleanup/battery.md` 캔버스 항목)

위험도: 낮음 · 예상 작업 크기: S · 우선순위: 5

---

## 13. [낭비] 재생 중 5초 샘플러가 Main 스레드에서 prefs/통계 갱신을 한다 — 5

근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:848`,
`PlaybackService.kt:7743-7755`, `PlaybackService.kt:7789-7796`,
`PlaybackService.kt:11029`

```kotlin
private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
...
sampleWhilePlaying()
...
savePlaybackState(player)
publishNerdStats()
```

`scope`가 Main이고 `reportProgress()`가 그 위에서 5초마다
`sampleWhilePlaying()`을 호출한다. 이 안에서 `savePlaybackState`(30초 스로틀),
`publishNerdStats`, `ListeningRecorder.onSample` 큐잉이 일어난다. 무거운
디스크/JSON 일부는 `progressWork`로 넘기지만, Main에서 prefs/상태/스냅샷
작업이 반복된다.

증상: 재생 중 5초마다 Main 스레드에 작은 작업이 쌓여 UI jank 가능성이 있고,
`ListeningStats` 월간 JSON 쓰기가 주기적으로 발생한다(알려짐: B2/B3).
**추측**으로 배터리와 스크롤 부드러움에 기여한다.

고치는 방법: `reportProgress()`를 `Dispatchers.Default`에서 돌리고 player
읽기만 `withContext(Dispatchers.Main)`으로 감싼다. `publishNerdStats()`는
오디오 포맷이 바뀔 때만 호출한다. `ListeningStats.flush`는 30초 고정이 아니라
일시정지/트랙 종료/백그라운드 전환 시로 옮기고, 재생 중에는 5분 상한을 둔다.
바꿀 함수: `PlaybackService.reportProgress`, `sampleWhilePlaying`.
(알려짐: `docs/AUDIT_PERFORMANCE.md` B2, B3)

위험도: 중간(스레드 순서/통계 정확도) · 예상 작업 크기: M · 우선순위: 5

---

## 14. [낭비] 비디오 가사 타이밍이 250ms 폴링으로 최대 30초를 기다린다 — 5

근거: `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:704-711`,
`LyricsTiming.kt:886`

```kotlin
while (true) {
    val copy = VersionAudioAligner.renditionToMeasure(video.videoId)
    ...
    delay(250)
}
```

`awaitVideoOpening()`은 비디오의 오디오가 캐시에 충분히 도착할 때까지
250ms마다 `renditionToMeasure`를 호출한다. `VIDEO_TIMEOUT_MS = 30_000L`이다.
화면이 켜져 있을 때만 실행되지만, 30초 동안 초당 4회 깨어난다.

증상: 비디오 가사 타이밍을 준비하는 동안 최대 30초간 4/s wake-up이
발생한다. **추측**으로 가사 타이밍 정확도는 그대로 두고 배터리만 줄일 수
있는 구간이다.

고치는 방법: `AudioCache`가 `cachedPrefix` 임계치 도달 또는 완료를
`CompletableDeferred`로 알리게 하고 `awaitVideoOpening`은 그 신호를 기다린다.
폴링을 유지해야 하면 250ms → 500ms → 1s 백오프로 바꾸고, 저전력에서는
측정 자체를 미룬다. 바꿀 함수: `LyricsTiming.awaitVideoOpening`.

위험도: 낮음 · 예상 작업 크기: S~M · 우선순위: 5

---

## 15. [낭비] Onboarding/로그인 카드의 메시 팔레트 타이머가 포그라운드 게이트가 없다 — 4

근거: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MeshGradient.kt:258-266`,
`sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt:883-888`

```kotlin
while (isActive) {
    delay(stepMillis.toLong())
    step++
}
```

`cyclingMeshPalette()`는 3.6초마다 팔레트 인덱스를 올린다. `rememberIsForeground`
게이트가 없어 온보딩/로그인 화면 컴포지션이 백그라운드에 남아 있으면 계속
깨어난다. 같은 카드의 `SignInBanner`는 `continuous = true`로 블러 애니메이션도
항상 켠다.

증상: 로그인/온보딩 화면을 열어 둔 채 앱을 백그라운드로 보내면 3.6초마다
상태 변경과 (카드가 보이는 동안) 지속 블러가 발생한다. **추측**으로 작지만
불필요한 Compose recomposition과 GPU 작업이 남는다.

고치는 방법: `cyclingMeshPalette()`에 `val foreground = rememberIsForeground()`를
추가해 false면 루프를 멈추고, `SignInBanner`의 `continuous`를
`foreground && !lowPower`로 바꾼다. `MeshGradientBackground`는 이미 내부에서
`foreground`를 읽으므로 `continuous`만 게이트해도 된다. 바꿀 함수:
`cyclingMeshPalette`, `SignInBanner`.

위험도: 낮음 · 예상 작업 크기: S · 우선순위: 4

---

## 요약 (상위 5개)

1. [버그] PartySync가 파티 밖·일시정지에도 700ms마다 깨어난다 — 9
2. [버그] Listen Together health monitor가 화면 꺼짐을 모른 채 10초마다 돈다 — 9
3. [낭비] 곡이 바뀔 때마다 오디오 캐시 전체 키를 스캔한다 — 8
4. [위험] 로그인 WebView가 dispose 후에도 destroy되지 않는다 — 8
5. [낭비] 로그인 화면이 1초마다 쿠키를 읽고, 화면이 꺼져도 멈추지 않는다 — 7
