# 재생 안정성 감사 (audit-playback)

- 범위: `PlaybackService`, `ChunkedDataSource`, `ProxyAwareDataSource`, `StreamResolver`, `ProxyPlayback`/`ProxyHealth`, `CrossfadeController`, `AudioCache`/`PrefetchController`, `DecoderMonitor`, `QueueShuffle`, `SongVersionController`, `PlayerConnection`.
- 방법: `grep`으로 좁힌 뒤 해당 함수만 읽었다. 코드는 한 줄도 수정하지 않았고 이 문서만 추가했다.
- 전제: 사용자 우선순위(정확한 곡 > 가사 싱크 > 안정성 > 배터리 > 속도 > 음질 > UI), 한국 비프리미엄 + 도쿄 프록시, S24 Ultra, 데이터 무제한·배터리 민감.
- 표시: `추측`은 코드에서 확인한 사실이 아니라 기기 동작 추정, `알려짐`은 기존 주석이나 `docs/`에 이미 적힌 문제다.

---

## 1. 429/503 Retry-After가 길면 로더 스레드가 사실상 무기한 멈춘다

- 분류: 위험
- 근거: `app/src/main/java/com/music/bitchord/playback/HqHostPolicy.kt:48-52`, `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:439-464`
  ```kotlin
  val wait = stated ?: backoffMs(attempt)          // HqHostPolicy.kt:50
  runBlocking { delay(decision.waitMs) }           // ChunkedDataSource.kt:462
  ```
  `retryAfterMs`는 서버가 준 초/HTTP-date를 상한 없이 그대로 반환한다(`HqHostPolicy.kt:30-40`).
- 사용자에게 보이는 증상: 서버가 `Retry-After: 3600`을 주면 ExoPlayer 로더 스레드가 `open()` 안에서 1시간을 기다린다. 3회 재시도이므로 최대 3시간. 그동안 해당 트랙은 재생도, 다음 곡으로 넘어가는 복구도 진행되지 않는다. 화면에는 계속 로딩/무음으로 보인다.
- 고치는 방법: `HqRetryPolicy.decide`에서 `waitMs`를 `MAX_WAIT_MS`(예: 30초)로 자른다. `ChunkedDataSource.openWithRateLimitRetry`는 상한을 넘는 `Retry-After`를 받으면 긴 `runBlocking` 대신 즉시 예외를 던져 Media3와 `recoverFrom`이 다른 소스/다음 시도로 넘어가게 한다. 로더 스레드의 `runBlocking`은 취소 가능한 경로로 바꾸는 것이 이상적이다.
- 위험도: 낮음~중간 (재시도 횟수가 줄어 일시적 429에서 실패가 늘 수 있음)
- 예상 작업 크기: S
- 우선순위 점수: 9

---

## 2. 휴대폰 플레이어에 setWakeMode가 없어 화면 꺼짐·도즈에서 스트리밍이 끊길 수 있다

- 분류: 버그/위험 (`추측`, 배터리 문서에는 `알려짐`)
- 근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:3842-3851` (없음), `wearos/src/main/java/com/music/bitchord/wear/playback/WatchPlaybackService.kt:43` (있음)
  ```kotlin
  .setLoadControl(farBufferingLoadControl())
  .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ ownsSession)
  // setWakeMode(...) 없음
  ```
  ```kotlin
  .setWakeMode(C.WAKE_MODE_LOCAL)   // WatchPlaybackService.kt:43
  ```
- 사용자에게 보이는 증상: 화면을 끄고 오래 들으면 CPU가 잠들어 버퍼가 마르고, 앱으로 돌아왔을 때 일시정지/버퍼링 상태로 보일 수 있다. 같은 앱의 워치 플레이어는 `WAKE_MODE_LOCAL`로 이 상황을 막고 있다. `docs/cleanup/battery.md:40`은 WakeLock 없음를 의도된 상태로 적고 있으나, 스트리밍 안정성과의 트레이드오프를 재검토할 가치가 있다.
- 고치는 방법: `PlaybackService.buildPlayer`의 세션 플레이어에 `.setWakeMode(C.WAKE_MODE_NETWORK)`를 추가하고, 일시정지 시 `C.WAKE_MODE_NONE`으로 내리거나 `onIsPlayingChanged`에서 해제한다. 재생 중에만 유지하면 배터리 비용은 재생 시간에 비례한다.
- 위험도: 중간 (재생 중 배터리 소모, 해제 누락 시 일시정지 후에도 유지)
- 예상 작업 크기: S
- 우선순위 점수: 8

---

## 3. 잘린 스트림을 EOF로 보고해 곡이 조용히 끝난다

- 분류: 버그
- 근거: `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:380,386-401`
  ```kotlin
  if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT   // 380
  repeat(MAX_EMPTY_RANGES) { ... }                          // 386
  return C.RESULT_END_OF_INPUT                              // 401
  ```
- 사용자에게 보이는 증상: 서버가 3개의 범위를 연속으로 빈 응답(-1)으로 끝내면 `bytesRemaining > 0`인데도 EOF를 반환한다. ExoPlayer는 곡이 끝난 것으로 보고 다음 곡으로 넘어가며, 오류 토스트·재시도 경로가 발동하지 않는다. 사용자에게는 "곡이 중간에 잘리고 다음 곡으로 넘어감"으로 보인다.
- 고치는 방법: `ChunkedDataSource.read`에서 루프를 모두 소진한 뒤 `bytesRemaining > 0`이면 `IOException("truncated stream at $position")`을 던진다. 그러면 `onPlayerError` → `recoverFrom`이 캐시 삭제/재해석/스킵을 판단한다.
- 위험도: 낮음
- 예상 작업 크기: S
- 우선순위 점수: 8

---

## 4. 프록시 경로·설정이 바뀌어도 기존 프록시 URL을 그대로 써서 403이 난다

- 분류: 버그
- 근거: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/ProxyPlayback.kt:206-207`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/YouTubeProxy.kt:76-87`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:276-286`, `app/src/main/java/com/music/bitchord/playback/ProxyAwareDataSource.kt:35-40`
  ```kotlin
  fun usesProxyFor(url: String): Boolean =
      proxiedUrls.containsKey(canonical(url)) && YouTubeProxy.config.usable   // ProxyPlayback.kt:206-207
  ```
  ```kotlin
  if (!ProxyPlayback.isProxiedUrl(cached.url) || ProxyPlayback.canResolveViaProxy()) {
      return cached.url                                                       // StreamResolver.kt:282-283
  ```
  `switchRoute`/`update`는 연결 풀만 비우고 `proxiedUrls`와 `StreamResolver.recent`의 프록시 URL은 건드리지 않는다.
- 사용자에게 보이는 증상: 기본→백업 전환, 프록시 끔, scope 변경 직후 재생 중인 곡이나 다음 곡이 이전 서버 IP에 묶인 URL을 새 경로로 요청해 403을 받는다. `recoverFrom`이 한 번 더 재해석해 복구하지만 그 사이 들리는 끊김/로딩이 생긴다. 프록시를 끈 경우에는 `usesProxyFor`가 false가 되어 프록시 전용 URL을 직접 요청하므로 같은 403이 난다.
- 고치는 방법: `ProxyPlayback`에 라우트 세대(또는 서버 식별자)를 기록하고, `YouTubeProxy.switchRoute`/`update`에서 `ProxyPlayback.invalidateUrls()`와 `StreamResolver`의 프록시 URL 캐시 제거를 호출한다. 또는 `usesProxyFor`가 URL을 만든 라우트와 현재 라우트가 같을 때만 true를 반환하게 한다.
- 위험도: 중간 (프록시 장애 시 곡이 잠깐 끊김)
- 예상 작업 크기: M
- 우선순위 점수: 7

---

## 5. 모듈 우선 해석 경로만 120초 상한 밖이다

- 분류: 위험/속도
- 근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:2008-2013`, `6862-6871`, `11087-11101`
  ```kotlin
  val won = runBlocking(about) {
      resolveWithModulePriority(                 // 2008-2013, withTimeout 없음
  ```
  ```kotlin
  val fallback = scope.async(Dispatchers.IO + TrackLog.about(videoId)) {
      runCatching { StreamResolver.resolve(videoId) }   // 6869-6870, 자체 상한 없음
  ```
- 사용자에게 보이는 증상: 모듈 대체가 켜진 상태에서 YouTube 해석이 늦으면 이 분기만 `RESOLVE_TIMEOUT_MS`(120초) 밖에서 기다릴 수 있다. 다른 분기는 `withTimeout(RESOLVE_TIMEOUT_MS)`로 감싸져 있어 상한이 실제로 걸린다. 첫 소리까지의 시간이 분기마다 달라지는 원인이다.
- 고치는 방법: `resolveWithModulePriority` 호출을 `withTimeout(RESOLVE_TIMEOUT_MS)`로 감싸고, 타임아웃 시 `lookup`/`fallback`을 취소한 뒤 `IOException`으로 실패시킨다. `runBlocking`은 로더 스레드에서 유지하되 상한을 둔다.
- 위험도: 낮음~중간
- 예상 작업 크기: S
- 우선순위 점수: 7

---

## 6. 삭제된 로컬 파일을 Media3가 3번 재시도한 뒤에야 스트리밍으로 복구한다

- 분류: 낭비/버그
- 근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4447-4461`, `4478-4485`, `4613-4617`
  ```kotlin
  if (isPermanent(loadErrorInfo.exception)) return C.TIME_UNSET   // 4460
  if (cause is StreamResolver.PermanentlyUnplayableException) return true  // 4482
  if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&  // 4613
  ```
- 사용자에게 보이는 증상: 다운로드 파일이 파일 관리자/클리너로 사라진 경우, `recoverFrom`의 `restreamMissingLocalFile`이 즉시 스트리밍으로 전환할 수 있는데도 Media3 기본 정책이 같은 죽은 경로를 1s/2s/3s로 3번 재시도한다. 사용자는 몇 초의 무음 뒤에야 재생이 시작된다.
- 고치는 방법: `PermanentAwareLoadErrorPolicy.isPermanent`에서 `java.io.FileNotFoundException`(또는 `FileDataSource` 계열 cause)을 영구 실패로 분류해 `C.TIME_UNSET`을 반환한다. 그러면 `onPlayerError`의 `restreamMissingLocalFile`로 바로 넘어간다.
- 위험도: 낮음
- 예상 작업 크기: S
- 우선순위 점수: 6

---

## 7. 곡 중간에 복구 예산을 다 쓰면 큐가 그 곡에 주차된다

- 분류: 위험/버그 (의도된 정지, `알려짐`)
- 근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4670`, `5183-5200`, `4784-4793`
  ```kotlin
  val givingUp = verdict != null || attempts > MAX_RECOVERIES   // 4670
  if (!neverStarted) return null                                // 5190
  if (reason != null) { skipPastUnplayable(...) } else { reportPlaybackFailure(...) }  // 4785-4792
  ```
- 사용자에게 보이는 증상: 이미 소리가 나던 곡이 403/네트워크 오류로 3번 실패하면 `skipReason`이 null이라 다음 곡으로 넘어가지 않고 플레이어가 오류 상태에 남는다. 알림에는 곡이 남아 있고, 사용자가 직접 다음을 눌러야 한다. 주석은 "듣던 곡을 빼앗지 않는다"는 의도라고 설명하지만, 체감은 멈춤이다.
- 고치는 방법: `skipReason`에 `midSongFailure` 정책을 추가해 `MAX_RECOVERIES` 소진 시 "이 곡을 건너뛸까요?" 토스트/알림 액션을 제공하거나, 사용자 설정으로 자동 `skipPastUnplayable`을 호출한다. 또는 수동 재생 시 `recoveries`를 즉시 리셋해 다시 시도할 수 있게 한다.
- 위험도: 중간 (듣던 곡을 빼앗을 수 있음)
- 예상 작업 크기: S~M
- 우선순위 점수: 5

---

## 8. 실패한 전송도 호스트 건강도에 성공으로 기록된다

- 분류: 버그
- 근거: `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:415-431`
  ```kotlin
  HqHostTracker.noteTransfer(
      currentHost, bytesRead, System.currentTimeMillis() - openedAtMs,
      ok = true,
  )
  ```
- 사용자에게 보이는 증상: `close()`는 정상 종료와 오류 종료를 구분하지 않고 항상 `ok=true`를 넘긴다. 403/타임아웃으로 중단된 호스트도 성공 카운트와 처리량을 얻어 `HqHostGate.penalty`가 낮게 유지되고, 다음 후보 선택에서 계속 앞선다. 반복적인 느린 곡/끊김이 생길 수 있다.
- 고치는 방법: `ChunkedDataSource`에 `transferFailed` 플래그를 두고 `openChunk`/`recoverMidPlay`/`read`에서 실패 시 세운 뒤, `close()`에서 `ok = !transferFailed`로 전달한다. 실패 시 `HqHostTracker.noteFailure(currentHost)`도 함께 호출한다.
- 위험도: 낮음
- 예상 작업 크기: S
- 우선순위 점수: 5

---

## 9. 수면 타이머 마감이 서비스 파괴 후에도 남는다

- 분류: 버그 (`알려짐`)
- 근거: `app/src/main/java/com/music/bitchord/playback/SleepTimer.kt:22`, `PlaybackService.kt:7892-7900`, `onDestroy`(`9041-9159`)에 `SleepTimer.cancel()` 없음
  ```kotlin
  val deadline = MutableStateFlow<Long?>(null)      // SleepTimer.kt:22
  if (remaining > 0) delay(remaining)               // 7897
  player?.pause(); SleepTimer.cancel()              // 7898-7899
  ```
- 사용자에게 보이는 증상: 수면 타이머를 켠 채 앱을 스와이프/서비스 종료하면 정적 `SleepTimer.deadline`이 남는다. 같은 프로세스에서 서비스가 다시 시작되면 `watchSleepTimer`가 남은 시간(또는 이미 지난 deadline)을 보고 새 재생을 곧바로 일시정지할 수 있다. `docs/AUDIT_FEATURES.md:61`에 이미 지적된 문제다.
- 고치는 방법: `PlaybackService.onDestroy`에서 `SleepTimer.cancel()`을 호출한다. 타이머를 서비스 수명과 무관하게 유지하려면 `LastPlayed`처럼 명시적인 복원/만료 정책을 둔다.
- 위험도: 낮음
- 예상 작업 크기: S
- 우선순위 점수: 5

---

## 10. 크로스페이드 컨트롤러가 재생 중 250ms·화면 꺼짐 1s로 계속 깨어난다

- 분류: 낭비 (`알려짐`)
- 근거: `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:749-791`, `3418-3426`
  ```kotlin
  Phase.IDLE -> if (active().isPlaying) {
      if (screenOn()) IDLE_STEP_MS else SCREEN_OFF_IDLE_STEP_MS
  } else { 0L }
  ```
  ```kotlin
  const val IDLE_STEP_MS = 250L
  const val SCREEN_OFF_IDLE_STEP_MS = 1_000L
  ```
- 사용자에게 보이는 증상: 화면이 꺼진 채 음악을 오래 들으면 1초마다, 화면이 켜져 있으면 250ms마다 코루틴이 깨어나 `planTransition`/`publishAnalysisState`를 재평가한다. 배터리 우선순위에서 장시간 청취 시 누적 비용이 크다. `docs/cleanup/battery.md:14,101`에 제안으로 기록되어 있다.
- 고치는 방법: `tickStepMs`의 IDLE 분기를 0(이벤트 대기)으로 두고, `planAutoTransition`이 계산한 fade 시작 시각(`fadeEndMs - fadeMs - armLeadMs`)에 한 번만 깨도록 `tickerWake`/`delay`를 예약한다. seek/pause/track change/queue change 이벤트에서 재예약한다.
- 위험도: 중간 (전환 타이밍이 어긋나면 크로스페이드가 늦거나 사라짐)
- 예상 작업 크기: M
- 우선순위 점수: 5

---

## 11. 화면 꺼짐·백그라운드에서도 다음 곡 전체를 선반입한다

- 분류: 낭비 (`알려짐`)
- 근거: `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1478-1511`, `PlaybackService.kt:1203-1205`
  ```kotlin
  val cacheBytes = !metered && (!substitutable || warmed != null) && !pinnedToYouTube
  if (cacheBytes) { launch { delay(PREFETCH_DELAY_MS); ... fetchWhole(next) } }
  ```
- 사용자에게 보이는 증상: 비미터드 연결에서 재생 중이면 화면이 꺼져 있어도 다음 곡 전체를 디스크에 받는다. 데이터는 무제한이어도 배터리·라디오 사용은 계속된다. `docs/cleanup/battery.md:48,111`에 정책 조정 제안으로 기록되어 있다.
- 고치는 방법: `AudioCache.prefetchQueue`에서 `PowerState`/화면 상태 또는 `AppSettings.lowPower`를 읽어, 화면 꺼짐·저전력에서는 `cacheBytes=false`로 URL 워밍업만 하거나 `PRELOAD_BYTES`만 받는다. `PlaybackService.prefetchAround`가 이미 화면 상태를 알고 있으므로 인자로 넘길 수 있다.
- 위험도: 낮음 (다음 곡 첫 소리가 느려질 수 있음)
- 예상 작업 크기: S
- 우선순위 점수: 5

---

## 12. 첫 소리 상한이 120초이고 진행 표시가 없다

- 분류: 빠진 기능/속도 (`알려짐`: 주석)
- 근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:11087-11101`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:1656-1657`
  ```kotlin
  const val RESOLVE_TIMEOUT_MS = 120_000L   // 11101
  ```
  ```kotlin
  * a shaped response can hold this call open for the better part of a minute.  // 1656-1657
  ```
- 사용자에게 보이는 증상: 모든 클라이언트가 bot-check되고 NewPipe 추출까지 가면 한 곡이 최대 2분 동안 BUFFERING에 머물 수 있다. 사용자는 "멈춘 앱"으로 느끼고, 그 사이 다른 곡/캐시로 우회할 방법이 없다. 주석도 NewPipe가 45~90초 걸리는 상태가 흔해졌다고 인정한다.
- 고치는 방법: `RESOLVE_TIMEOUT_MS`를 그대로 두더라도 15~20초 시점에 "계속 시도 중" 상태를 노출하고, 캐시/다른 소스로 먼저 재생을 시작하는 2단계 정책을 둔다. 또는 `resolveWithModulePriority`의 fallback을 `withTimeout`으로 감싸 120초를 실제 상한으로 만든다(항목 5).
- 위험도: 중간 (짧은 상한은 재시도 루프를 늘릴 수 있음 — 주석이 경고)
- 예상 작업 크기: M
- 우선순위 점수: 4

---

## 13. onDestroy가 메인 스레드에서 최대 2초 대기한다

- 분류: 위험
- 근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9112-9113`
  ```kotlin
  progressWork.shutdown()
  runCatching { progressWork.awaitTermination(2, TimeUnit.SECONDS) }
  ```
- 사용자에게 보이는 증상: 서비스 종료/스와이프 시 메인 스레드가 최대 2초 블록된다. 샘플러가 느리면 UI가 멈칫하고, 다른 종료 작업과 겹치면 ANR 위험이 커진다.
- 고치는 방법: `awaitTermination`을 메인에서 제거하고, 마지막 스냅샷/통계 저장을 `Dispatchers.IO`의 별도 작업으로 넘긴다. 최소한 대기를 메인 밖으로 옮기고 종료는 비동기로 완료한다.
- 위험도: 낮음
- 예상 작업 크기: S
- 우선순위 점수: 4

---

## 14. 429/폴백 복구 작업에 중복 실행 가드가 없다

- 분류: 위험 (경쟁 상태, `추측`)
- 근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4824-4949`, `4956-5000`, `5265`
  ```kotlin
  private fun resumeRateLimited(...): Boolean { ... scope.launch(...) ... }
  private fun fallbackFailedAlternativeToYouTube(...): Boolean { ... scope.launch(...) ... }
  if (!alternateLookups.add(mediaId)) return   // 5265, 이 경로에만 가드가 있음
  ```
- 사용자에게 보이는 증상: 같은 곡에서 `onPlayerError`가 연속으로 오면 두 복구 함수가 각각 코루틴을 띄운다. 둘 다 350ms 뒤 `currentMediaItem?.mediaId`만 확인하고 `replaceMediaItem`을 호출하므로, 늦게 끝난 쪽이 이미 복구된 아이템을 다시 교체하거나 불필요한 캐시 삭제를 반복할 수 있다. 증상은 짧은 재로딩/끊김의 반복이다.
- 고치는 방법: `alternateLookups`처럼 `recoveryJobs = ConcurrentHashMap.newKeySet<String>()`를 두고 `resumeRateLimited`/`fallbackFailedAlternativeToYouTube` 진입 시 `add(mediaId)`에 실패하면 return, `finally`에서 remove한다. `swappingMediaId` 설정도 교체 직전 메인에서 원자적으로 확인한다.
- 위험도: 낮음~중간
- 예상 작업 크기: S
- 우선순위 점수: 4

---

## 검토 결과 메모

- `QueueShuffle`은 현재 곡/히스토리 보존, USER_QUEUE 고정, imminent 시 `cur+1` 보존 규칙이 코드와 주석에 일치했고, 이번 범위에서 즉시 고칠 결함은 찾지 못했다.
- `DecoderMonitor`는 이벤트 기반 집계만 하고 타이머를 만들지 않는다.
- `PlayerConnection`의 500ms 위치 폴링은 포그라운드+재생 중으로 제한되어 있어 이번 재생 안정성 범위에서는 제외했다.
- `AudioCache`의 read-ahead는 일시정지 시 `cancelPrefetch`로 취소되므로, 위 11번은 "화면 꺼짐" 조건에 한정한 지적이다.

## 요약 (상위 5개)

1. 429/503 Retry-After가 길면 로더 스레드가 사실상 무기한 멈춘다 — 9
2. 휴대폰 플레이어에 setWakeMode가 없어 화면 꺼짐·도즈에서 스트리밍이 끊길 수 있다 — 8
3. 잘린 스트림을 EOF로 보고해 곡이 조용히 끝난다 — 8
4. 프록시 경로·설정이 바뀌어도 기존 프록시 URL을 그대로 써서 403이 난다 — 7
5. 모듈 우선 해석 경로만 120초 상한 밖이다 — 7
