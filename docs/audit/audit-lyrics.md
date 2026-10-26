# audit-lyrics — 가사·싱크 점검

점검 범위: 가사 제공처 선택·순서, 제목 정규화, 시간 정렬(`LyricsTiming`, `LyricsTimeMap`, `TimingFit`, `ChromaAlignment`, `Deepgram`), AI shift 판정, 저장된 가사·싱크 재사용, 번역. 코드는 수정하지 않았고, 아래는 실제 파일을 읽고 정리한 개선점이다. 우선순위는 사용자 기준(올바른 곡 → 0.2초 싱크 → 안정성 → 배터리 → 속도 → 음질 → UI)으로 매겼다.

분류 색인 — **버그**: 1, 3, 4, 8 / **위험**: 5, 9, 11, 12, 13 / **낭비**: 6, 7, 14 / **빠진 기능**: 2, 10, 15

---

## 1. AI shift 판정의 인라이어 허용폭이 1.5초라 0.2초 목표를 보장하지 못한다

- **분류**: 버그 (AI shift 판정)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTimeMap.kt:183,203,211`, `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1601`
  ```
  const val INLIER_MS = 1_500L
  val inliers = points.filter { (x, y) -> abs(y - first.predict(x)) <= INLIER_MS }
  val agreeing = points.count { (x, y) -> abs(y - (finalRate * x + offset)) <= INLIER_MS }
  ```
  ```
  val match = (line.inliers * 100.0 / indices.size).roundToInt().coerceIn(0, 100)
  ```
- **사용자에게 보이는 증상**: Gemini/Deepgram/Whisper 답이 1.4초 틀려도 "일치"로 집계되어 70% 문턱을 넘고 그대로 적용된다. 한 줄이 1초 이상 어긋난 채 표시되어 0.2초 목표를 달성할 수 없다. `shiftAllows`는 첫 소절의 이동만 보고, 곡 후반의 누적 오차는 이 1.5초 허용폭에 기댄다.
- **고치는 방법**: `TimingFit.fit`에 두 허용폭을 분리한다. `FIT_TOLERANCE_MS`(1.5초, Theil–Sen 초기 적합용)와 `ACCEPT_TOLERANCE_MS`(약 300~400ms)를 두고, `LyricsTiming.fitAi`의 `matchPercent`를 `ACCEPT_TOLERANCE_MS` 기준으로 다시 계산한다. 추가로 인라이어의 p80 잔차 ≤ 250ms(또는 median |잔차| ≤ 200ms)를 `LyricsAiGuard.allowsAiTiming`에 넣는다. 1.5초는 "답이 노래를 향하고 있는가"만 판단하고, 화면에 적용할지는 0.2초 기준으로 따로 판정해야 한다.
- **위험도**: 중간
- **예상 작업 크기**: M
- **우선순위 점수**: 9/10

---

## 2. 한국어 곡은 AI 단어 타이밍에서 전부 제외된다

- **분류**: 빠진 기능 — **알려짐**(코드 주석: 한국어는 "not enough"가 5/5였다는 기록)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsAiGuard.kt:67-68,88-89`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Deepgram.kt:189`
  ```
  fun wantsDeepgram(title: String, artist: String, lyricsTexts: List<String>): Boolean =
      !isKoreanSong(title, artist, lyricsTexts) && !isInstrumentalTitle(title)
  ```
  ```
  OriginalNames.worthTrying(title, artist) || Deepgram.languageFor(lyricsTexts) == "ko"
  ```
  ```
  hangul * 4 >= total -> "ko"
  ```
- **사용자에게 보이는 증상**: 제목이나 가사에 한글이 있으면 Deepgram nova-3, Whisper, 곡 검증(`verifySong`)까지 모두 건너뛴다. 한국 사용자의 주력 곡은 AI 단어 타이밍을 아예 받지 못하고, Apple 계열 제공처가 단어 타이밍을 주지 않으면 줄 단위에 머문다. 사용자 우선순위 2(0.2초 싱크)와 정면으로 충돌한다.
- **고치는 방법**: `wantsDeepgram`/`songTimingWanted`에서 한국어를 **Whisper-large에만** 허용한다(`language="ko"`, nova-3는 계속 제외). `LyricsTiming.askAi`의 모델 루프에서 한국어면 `MODEL_WHISPER`만/먼저 시도하도록 `LyricsAiGuard`에 `engineOrderFor(language)`를 추가한다. 429·비용은 `SongSyncRetry` 정책을 그대로 쓰고, 설정에서 "한국어도 AI 싱크"를 끌 수 있게 한다. nova-3가 못 듣는 것은 모델 한계이지 한국어 전체의 한계가 아니다.
- **위험도**: 중간
- **예상 작업 크기**: M
- **우선순위 점수**: 9/10

---

## 3. `placeLines`가 줄 시작을 고정 180ms/글자로 되짚어 0.2초 오차를 만든다

- **분류**: 버그 — **추측**(실제 오차 크기는 곡·언어·발음에 따라 다름)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsWordAlign.kt:34,37,121-124`
  ```
  private const val LEAD_LETTERS = 4
  private const val LETTER_MS = 180L
  starts[line] = (heardMs[heardFor[first]] - lyricPos[first] * LETTER_MS).coerceAtLeast(0L)
  ```
- **사용자에게 보이는 증상**: 첫 들린 글자가 줄의 2~4번째 글자일 때, 앞 글자들을 모두 180ms로 가정해 줄 시작을 되짚는다. 빠른 랩, 느린 발라드, 한국어/일본어 음절 길이 차이에서 줄 시작이 수백 ms 앞당겨지거나 늦어진다. 이후 `TimingFit`은 곡 전체의 직선만 맞추므로 이 줄별 오차는 남는다.
- **고치는 방법**: `LyricsWordAlign.placeLines`에서 첫 heard word의 실제 `startMs`를 기준으로 삼고, 앞 글자 시간은 그 단어의 `(endMs - startMs) / 글자수`로 계산한다. 더 나아가 `withHeardWords`가 이미 하는 단어별 실제 시간 매칭을 먼저 수행하고, 첫 단어의 `startMs`를 줄 시작으로 쓰면 `LETTER_MS` 추정 자체가 필요 없어진다. `LETTER_MS` 상수는 제거하거나 언어별 기본값으로 대체한다.
- **위험도**: 중간
- **예상 작업 크기**: M
- **우선순위 점수**: 8/10

---

## 4. 현재 곡 가사 메모(`lyricsFor`)가 제공처 순서·음절 설정을 무시하고, 실패 후에도 남는다

- **분류**: 버그 (가사 제공처 선택과 순서)
- **근거**: `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:188-193,207,319`, `app/src/main/java/com/music/bitchord/MainActivity.kt:1251-1262`, `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:8529-8537`
  ```
  val key = Triple(
      videoId,
      if (isVideo) null else LyricsTiming.audioSourceKey(videoId, localUri != null),
      sources,
  )
  if (lyricsFor == key) return
  ```
  ```
  lyricsFor = key
  ```
  ```
  ) { synced, sources, order, prio ->
      synced to sources
  }.distinctUntilChanged().collect {
  ```
- **사용자에게 보이는 증상**: 재생 중 설정에서 제공처 **순서**를 바꾸거나 "음절 우선"을 꺼도 현재 곡은 다시 조회하지 않는다(키에 `sources`만 들어가고 `order`/`prioritizeSyllableSync`는 빠짐). 또한 조회가 일시적 네트워크 실패로 null이 되어도 `lyricsFor`가 남아, 같은 곡을 다시 열거나 재생해도 재시도하지 않는다. 사용자는 설정이 먹히지 않는다고 느낀다.
- **고치는 방법**: `LyricsController.loadLyrics`의 `key`에 `AppSettings.lyricsSourceOrder.value`와 `AppSettings.prioritizeSyllableSync.value`를 포함한다. 조회 결과가 null이거나 실패 콜백이면 `lyricsFor = null`로 되돌리고 `_lyricsChecked`는 "실패" 상태로 둔다. `MainActivity`의 `LaunchedEffect` 키와 `PlaybackService`의 `combine`도 order/prio를 실제 키로 사용하도록 고친다(현재는 받은 뒤 버린다). 사용자가 명시적으로 다시 시도할 수 있는 "가사 다시 찾기" 액션도 추가한다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 8/10

---

## 5. 가사 캐시 키에 duration/album이 없어 0초 조회 결과가 실제 길이 조회에 재사용될 수 있다

- **분류**: 위험 (올바른 곡)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:188-194,224-232`, `app/src/main/java/com/music/bitchord/download/LyricsTag.kt:74-81`, `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9241-9247`
  ```
  append(videoId).append('\u0000').append(title).append('\u0000').append(artist)
  append('\u0000').append(prioritizeSyllableSync).append('\u0000').append(isrc.orEmpty())
  ```
  ```
  answers.get(key)?.let { kept ->
      kept.result?.let { onSourceResult?.invoke(it.source, it, null) }
      return kept.result
  }
  ```
- **사용자에게 보이는 증상**: 다운로드/앨범 행은 duration을 모른 채 0으로 조회하고(`LyricsTag`), 플레이어는 실제 duration으로 같은 key를 조회한다. 먼저 캐시된 "길이 검증 없이 고른" 결과가 그대로 재사용되어, 라이브/리믹스/다른 에디션의 가사가 재생 곡에 붙을 수 있다. 사용자 우선순위 1(올바른 곡)의 핵심 위험이다.
- **고치는 방법**: `LyricsRepository.questionKey`와 `LyricsDiskCache`의 키에 `durationMs`(예: ±2초 버킷)와 `album`을 포함한다. `durationMs <= 0`으로 찾은 결과는 `answers`/디스크에 저장하지 않거나 `durationUnknown=true`로 분리해, 실제 길이 조회가 그 캐시를 hit하지 않게 한다. `lookUpAll`의 `answers.get(key)`도 duration 조건을 확인한다.
- **위험도**: 중간
- **예상 작업 크기**: S
- **우선순위 점수**: 8/10

---

## 6. 활성화된 모든 제공처를 한 번에 호출한다 (배터리·네트워크)

- **분류**: 낭비 — **알려짐**(설계 의도 주석: 동시 호출로 지연을 줄인다)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:743-752`
  ```
  val racing: List<Pair<LyricsSource, Deferred<Result?>>> = sequence.map { source ->
      val startMode = if (source == LyricsSource.GENIUS) kotlinx.coroutines.CoroutineStart.LAZY else kotlinx.coroutines.CoroutineStart.DEFAULT
      source to async(Dispatchers.IO, start = startMode) {
  ```
- **사용자에게 보이는 증상**: 캐시 없는 곡마다 최대 17개 제공처에 동시에 요청한다. 1순위가 즉시 답해도 이미 시작된 요청은 취소 전까지 소켓·라디오를 깨워 배터리를 쓴다. 데이터는 무제한이어도 모바일 라디오 wakeup은 배터리 비용이다.
- **고치는 방법**: `LyricsRepository.lookUp`에서 상위 2~3개만 즉시 시작하고, 실패 또는 짧은 지연(예: 400ms) 후 다음 묶음을 시작하는 sliding window로 바꾼다. Genius처럼 하위 소스 전체에 `LAZY` 시작을 적용하고, 첫 답이 오면 남은 소스는 시작하지 않는다. `prioritizeSyllableSync=true`일 때의 word-synced grace는 유지한다.
- **위험도**: 중간
- **예상 작업 크기**: M
- **우선순위 점수**: 7/10

---

## 7. 영상 가사 재측정이 8초 폴링 루프다

- **분류**: 낭비 (배터리) — 앱 코드의 "주기 폴링 금지" 원칙과 충돌
- **근거**: `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:51-54,618-629`
  ```
  private val REMEASURE_CHECK_MS = 8_000L
  private val MEASURE_WINDOW_MS = 15 * 60_000L
  ```
  ```
  while (attempts < MAX_MEASURE_ATTEMPTS && System.currentTimeMillis() < giveUpAt &&
      !LyricsTiming.measured(context, request.videoId)
  ) {
      ...
      delay(REMEASURE_CHECK_MS)
  }
  ```
- **사용자에게 보이는 증상**: 커버/뮤직비디오에서 가사 패널이 열려 있는 동안 최대 15분간 8초마다 깨어 측정을 재시도한다. 오디오가 더 이상 도착하지 않아도 루프는 계속 돌아 배터리를 쓴다.
- **고치는 방법**: `AudioCache`/`VersionAudioAligner`의 다운로드 진행 콜백(바이트 증가·완료)에서 `LyricsTiming.measure`를 트리거하고, `LyricsTiming.changes`/`measured` 이벤트로 종료한다. 폴링을 제거하고, 이벤트가 없는 플랫폼에서만 1회 지연 재시도를 남긴다.
- **위험도**: 낮음
- **예상 작업 크기**: M
- **우선순위 점수**: 7/10

---

## 8. EnhancedLrc 마지막 단어가 다음 줄까지 늘어나 간주 동안 하이라이트된다

- **분류**: 버그
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/EnhancedLrc.kt:44-52`
  ```
  val lineEnd = rows.getOrNull(index + 1)?.timeMs
      ?: (stamp(row.words.last()) + TAIL_MS)
  ...
  val wordEnd = row.words.getOrNull(i + 1)?.let { stamp(it) } ?: lineEnd
  ```
- **사용자에게 보이는 증상**: 10~30초 간주가 뒤따르는 줄의 마지막 단어가 그 전체 구간 동안 서서히 채워진다. `withInstrumentalGaps`도 `silence = next.timeMs - line.endMs`가 0이 되어 간주 표시를 넣지 못한다. 마지막 단어의 성장/블룸 애니메이션이 간주 내내 이어진다.
- **고치는 방법**: `EnhancedLrc.parse`에서 마지막 단어의 `wordEnd`를 다음 줄 시작이 아니라 `min(nextLineMs, lastStart + 추정 단어 길이)`로 캡한다. 추정 길이는 그 줄 단어들의 중앙값 또는 `TAIL_MS`를 쓴다. `sungUntilMs`가 있는 소스는 그 값을 우선하고, 캡 후 `withInstrumentalGaps`가 간주를 넣도록 한다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 7/10

---

## 9. AI 시도가 네트워크 실패·429에도 "사용됨"으로 기록되어 자동 재시도가 막힌다

- **분류**: 위험 — **알려짐**(주석의 의도: "지불한 요청은 다시 하지 않는다")
- **근거**: `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1039-1044,1058,1092`, `app/src/main/java/com/music/bitchord/playback/smart/AiTimingPolicy.kt:124-129`
  ```
  suspend fun noteAttempt(name: String) {
      store(context).update(videoId) { entry ->
          ...
          aiAttemptedEngines = ((if (sameLyrics) entry.aiAttemptedEngines else emptyList()) + name).distinct(),
  ```
  ```
  noteAttempt(name)
  ...
  noteAttempt(ENGINE_GEMINI)
  ```
- **사용자에게 보이는 증상**: 비행기 모드, 일시적 5xx, 429에서도 Deepgram·Whisper·Gemini가 "소진" 처리된다. 같은 가사로 다시 재생해도 자동 AI 싱크를 재시도하지 않아, 그 곡은 계속 어긋난 채 남는다. 곡 검증(`verifySong`)은 429를 재시도/보류하는데 영상 경로는 정책이 다르다.
- **고치는 방법**: `askAi`에서 `noteAttempt`를 실제 응답이 온 뒤로 옮긴다. 401/403(키 거부)만 즉시 소진하고, 네트워크/타임아웃/429는 `SongSyncRetry`처럼 보류·재시도한다. `AiTimingPolicy.spentOn`에 "응답 없음"과 "실제 시도"를 구분하는 상태를 추가한다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 6/10

---

## 10. LRC `[offset:]` 메타데이터를 무시한다

- **분류**: 빠진 기능 — **추측**(제공처별 포함 빈도는 확인하지 못함)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcLib.kt:91-93,202`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/EnhancedLrc.kt:16-17`
  ```
  fun parseLrc(lrc: String): List<LyricLine> {
      val all = lrc.lineSequence().mapNotNull { line ->
          val match = STAMP.find(line) ?: return@mapNotNull null
  ```
  ```
  private val LINE = Regex("""^\[(\d{1,3}):(\d{2})[.:](\d{2,3})](.*)$""")
  ```
- **사용자에게 보이는 증상**: 파일에 `[offset:+500]`이 있으면 모든 줄이 0.5초 어긋난 채 표시된다. 0.2초 목표를 바로 깨는 상수 오차이며, 사용자가 수동 보정으로만 고칠 수 있다.
- **고치는 방법**: `LrcLib.parseLrc`와 `EnhancedLrc.parse`에서 `[offset:(-?\d+)]`를 먼저 파싱해 모든 `timeMs`와 word time에 더하고 0으로 clamp한다. `KaraokeLrc`도 동일하게. 부호 규약(양수 = 가사 늦게)을 주석과 테스트로 고정한다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 6/10

---

## 11. ISRC 식별이 BiniLyrics가 켜져 있을 때만 돌아간다

- **분류**: 위험 (올바른 곡) — **추측**(Bini가 꺼진 사용자 비율은 미확인)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:686,742,756,934`
  ```
  private val USES_RECORDING = setOf(LyricsSource.LRC_RED, LyricsSource.LYRICS_PLUS, LyricsSource.BINI_LYRICS)
  ```
  ```
  if (LyricsSource.BINI_LYRICS !in sequence) return null
  ```
- **사용자에게 보이는 증상**: 사용자가 BiniLyrics를 끄면(또는 Bini가 다운) LRC_RED/LyricsPlus가 ISRC 없이 이름만으로 매칭한다. 라이브/리믹스/다른 에디션을 고를 위험이 커져, 사용자 우선순위 1(올바른 곡)과 직접 충돌한다. Bini를 끈 것은 "그 제공처를 쓰지 않겠다"는 뜻이지 "녹음 식별을 포기하겠다"는 뜻은 아니다.
- **고치는 방법**: ISRC를 Bini 외 경로에서 확보한다. 플레이 중인 `Song`의 song version/YouTube Music 메타데이터, 다운로드 파일 태그, 또는 `LrcRed` 응답이 주는 recording id를 쓴다. `identify`를 `BINI_LYRICS` 의존에서 분리하고, 비활성 소스는 건드리지 않는 keyless 조회로 대체한다.
- **위험도**: 중간
- **예상 작업 크기**: M
- **우선순위 점수**: 6/10

---

## 12. AI 바이어스가 3곡만으로 최대 1.5초까지 전곡에 적용된다

- **분류**: 위험 — **추측**(샘플 품질에 따른 실제 오차는 곡마다 다름)
- **근거**: `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1510,1583-1584`
  ```
  private const val BIAS_MIN_SAMPLES = 3
  private const val BIAS_MAX_MS = 1_500L
  val bias = if (samples.size < BIAS_MIN_SAMPLES) 0L else samples[samples.size / 2].coerceIn(-BIAS_MAX_MS, BIAS_MAX_MS)
  ```
- **사용자에게 보이는 증상**: 서로 다른 테이크/측정 오차가 섞인 3개 샘플의 중앙값이 모든 AI 답에서 빠진다. 잘 맞던 곡까지 1초 이상 앞/뒤로 밀 수 있고, 반대로 학습이 늦어 계속 늦게 나오는 곡도 있다.
- **고치는 방법**: `BIAS_MIN_SAMPLES`를 8~10으로 올리고, trimmed mean/median absolute deviation 게이트를 둔다. 엔진·언어별로 분리하고, `BIAS_MAX_MS`를 500~700ms로 줄인다. `sampleBias`의 measurement/AI `matchPercent` 하한도 올린다.
- **위험도**: 중간
- **예상 작업 크기**: S
- **우선순위 점수**: 5/10

---

## 13. 디스크 가사 캐시가 파서·정규화 로직 변경을 무효화하지 않는다

- **분류**: 위험 (저장된 가사 재사용) — **추측**(업데이트 주기별 영향)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsDiskCache.kt:58-61,114`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:175-194`
  ```
  if (entry.version != VERSION) return null
  ...
  private const val VERSION = 1
  ```
- **사용자에게 보이는 증상**: 특정 제공처의 타이밍 파싱 버그를 고쳐도, 이미 저장된 잘못된 가사가 같은 key로 계속 사용된다. 사용자는 앱을 업데이트해도 같은 싱크 오류를 본다. `VERSION`은 직렬화 스키마만 지키고 파서/정규화 로직 버전은 아니다.
- **고치는 방법**: `LyricsRepository.questionKey`에 `LYRICS_CACHE_LOGIC_VERSION`(파서·정규화·타이밍 로직 변경 시 증가)을 포함하거나, `LyricsDiskCache.VERSION`을 올리고 구버전 항목을 재조회한다. `Result`에 parser version을 기록해 소스별로 무효화할 수 있게 한다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 5/10

---

## 14. word-synced 대기 루프가 80ms 폴링이다

- **분류**: 낭비 (배터리)
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:706,830-851`
  ```
  private const val GRACE_POLL_MS = 80L
  ...
  if (!waitingAbove || timeUp) return null
  delay(GRACE_POLL_MS)
  ```
- **사용자에게 보이는 증상**: 줄 단위 답이 먼저 와 word-synced 소스를 8초 기다리는 동안 80ms마다 깨어 최대 100회 가까이 스케줄링한다. 화면이 켜진 상태에서 작은 wakeup이 반복되어 배터리·지터에 기여한다.
- **고치는 방법**: `wordSyncedWithin`에서 `select`로 각 `Deferred.onAwait`를 기다리거나, `withTimeoutOrNull(graceMs) { rest.map { it.second }.awaitAll() }` 후 순서대로 판정한다. 폴링을 완전히 제거한다.
- **위험도**: 낮음
- **예상 작업 크기**: S
- **우선순위 점수**: 5/10

---

## 15. 번역 가사는 단어 타이밍이 한 덩어리로 붕괴된다

- **분류**: 빠진 기능 — **알려짐**(`docs/LYRICS_TRANSLATION.md`의 "approximate reading guide")
- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt:899-906,918-922`
  ```
  words = retimeWords(line, leadText),
  timingSource = line.takeIf { it.isWordSynced },
  ```
  ```
  if (source.words.isEmpty()) return emptyList()
  if (translated.isEmpty()) return emptyList()
  val start = source.words.first().startMs
  return listOf(LyricWord(start, source.words.last().endMs, translated))
  ```
- **사용자에게 보이는 증상**: 번역 보기에서는 원문의 단어별 상승·블룸·단어 하이라이트가 사라지고 한 줄 전체가 한 단어로 취급된다. `timingSource`로 글자 스윕만 근사되므로, 번역문에서 0.2초 단위 단어 동기화는 불가능하다. 사용자 우선순위 2를 번역 화면에서도 원한다면 빠진 기능이다.
- **고치는 방법**: `retimeWords`에서 번역문을 공백 토큰으로 나누고, 원문 word span을 토큰 길이/음절 가중치로 비례 배분해 `LyricWord` 목록을 만든다. 또는 `withEstimatedWords`를 번역문에 적용하되 `timingSource`의 원문 envelope를 사용한다. 결과는 `wordsEstimated=true`로 표시해 저장·내보내기에서 제외한다.
- **위험도**: 중간
- **예상 작업 크기**: M
- **우선순위 점수**: 4/10

---

## 요약 (상위 5개)

1. **AI shift 판정의 1.5초 인라이어 허용폭** — 9/10
2. **한국어 곡의 AI 단어 타이밍 전면 제외** — 9/10
3. **`placeLines`의 고정 180ms/글자 줄 시작 추정** — 8/10
4. **`lyricsFor` 메모가 제공처 순서·음절 설정을 무시하고 실패 후 남음** — 8/10
5. **가사 캐시 키에 duration/album 누락** — 8/10
