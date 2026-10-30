# 가사 영역 엄격 검토 (2026-10-31)

범위: 제공처 검색(lrc.red·LRCLIB·KuGou·VIBE·벅스·BetterLyrics·BiniLyrics·PaxSenix·LyricsPlus), 검색어(커버 제목·원곡 가수·번역 꼬리), 저장본(`file:<제공처>@<id>`) 재사용·갱신, Gemini 받아쓰기 조건, 번역, 다음 곡 준비.

기준: `task/fix-1030` 계열 현재 트리. 코드를 읽고 진입점→처리→결과를 따라갔으며, 아래 HIGH/MEDIUM 중 10건을 이번에 수정했다. 수정한 함수의 호출부와 타입은 모두 확인했고 `:app:compileProdReleaseKotlin`과 관련 테스트를 통과시켰다.

---

## 0. 성적표를 코드로 읽기

- **다음 곡 준비 가사 `none` 108/173**: `NextTrackPreparation`의 `Step.LYRICS`는 `AppSettings.syncedLyrics`가 켜져 있어도 `SyncSafetyPolicy.nextPrepLyrics`(기본 off, ko-101 보호 스위치)가 꺼져 있으면 `nextPrepSteps`에서 제거된다. 그러면 `found`가 항상 null이라 로그가 `none`이었다. 즉 108건 중 상당수는 “찾았지만 없음”이 아니라 “이 경로가 꺼져 있음”이었다. 이번에 로그를 `off`/`none`으로 분리했다(`NextPrepLog`). 실제 다음 곡 가사 선반입은 별도로 `TranslationAhead.nextTrack`이 담당하며, 이 경로는 `SyncSafetyPolicy`와 무관하게 `LYRICS_ONLY`/`FULL`로 돈다.
- **다음 곡 싱크 준비 29%**: 같은 스위치가 `Step.SYNC`도 제거하고, 켜져 있어도 `syncWanted`가 영상·한국어·기악·저전력·Deepgram 불가를 제외한다. 이 제외는 실제 실패 기록(한국어 Deepgram 5/5 “not enough”, 영상은 크로마 측정 경로)이 주석에 남아 있어 약화하지 않았다. 따라서 29%는 “고장”이 아니라 보호 정책의 결과다.
- **무손실 4% / 다운로드·고음질 보관함 실패 140회**: 가사 영역 밖이다. 이번 문서에서는 다루지 않았고, 가사 쪽 다운로드 실패와 겹치는 부분(가사 캐시 키·저장본 갱신)만 아래에 정리했다.

---

## 1. 이번에 수정한 결함

### HIGH 1. 길이 없는 조회 결과가 길이 있는 재생에 그대로 재사용됨

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:176`(`questionKey`), `:686`(`lyricsFor` memo key), `:214`(`DURATION_BUCKET_MS`)
- 증거: 수정 전 `questionKey`는 `videoId·title·artist·isrc·isVideo·artistKnown·alternatives·order·sources`만 포함했다. `lookUpAll`은 `answers`/`LyricsDiskCache`를 길이와 무관하게 먼저 읽었고, `LyricsTag.forTrack`은 앨범/재생목록 행의 길이가 0이어도 조회해 그 결과를 같은 키로 저장했다.
- 사용자 영향: 다운로드/앨범 행에서 길이 없이 고른 “다른 에디션” 가사가, 실제 재생 시 길이를 아는 조회에서 그대로 재사용된다. 사용자 우선순위 1(올바른 곡) 위반.
- 얼마나 흔한가: 앨범·재생목록 일괄 다운로드, 길이가 늦게 오는 행에서 흔함. 한 번 저장되면 재시작 후에도 남는다.
- 수정: 키에 `unknown` 또는 `durationMs / 10_000` 버킷과 `album`을 넣었다. 10초 버킷은 카탈로그 길이와 디코더 길이의 1~2초 차이는 공유하면서, 길이 없음과 길이 있음은 절대 섞지 않는다. `lyricsFor` 메모 키도 같은 규칙을 쓴다.
- 테스트: `LyricsQuestionKeyTest` (unknown≠known, 296s≈297.5s, 200s≠260s, 앨범 다름).

### HIGH 2. 다음 곡 준비가 재검증 override를 무시해 옛 파일 태그로 SYNC함

- 파일: `app/src/main/java/com/music/bitchord/playback/TranslationAhead.kt:130`
- 증거: 수정 전 `lyricsForPrep`은 `EmbeddedLyrics.withSource`만 읽었다. 재검증(`LyricsTiming.recheckDownloaded`)은 새 가사를 오디오 파일에 쓰지 않고 `LyricsOverrideStore`에만 쓴다. 플레이어는 override를 먼저 읽지만 다음 곡 번들은 파일의 옛 태그를 읽고, 그 옛 텍스트 키로 `verifySong`을 돌려 새 키의 AI 싱크를 덮어쓸 수 있었다.
- 사용자 영향: 재검증 스위치를 켠 사용자에게 다음 곡 첫 프레임이 옛 가사/옛 싱크로 뜨고, 재검증이 저장한 새 싱크가 사라진다.
- 얼마나 흔한가: `syncSafetyDownloadRecheck`가 기본 off라 기본 사용자에게는 없음. 스위치를 켠 사용자에게는 다운로드 곡마다.
- 수정: `lyricsForPrep`이 `LyricsOverrideStore.read`를 먼저 읽고, 있으면 그 lines/source를 `fromFile = true`로 반환한다.
- 테스트: `TranslationAheadOverrideTest` (수정 전 `expected:<override> but was:<null>`로 실패 확인, 수정 후 통과).

### MEDIUM 3. 제공처 순서·음절 우선 변경이 재생 중인 곡에 적용되지 않음 / 실패한 조회가 세션 동안 고정됨

- 파일: `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:203`, `:211`, `:421`; `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9409`; `app/src/main/java/com/music/bitchord/MainActivity.kt:1293`; 신규 `app/src/main/java/com/music/bitchord/ui/LyricsLoadKey.kt`
- 증거: 수정 전 키는 `Triple(videoId, audioSource, sources)`였고, `PlaybackService`의 `combine`은 `synced to sources`로 order/prio를 버렸으며, `MainActivity`의 `LaunchedEffect` 키에도 order/prio가 없었다. 또 조회가 null이어도 `lyricsFor`가 남아 같은 키의 재시도를 막았다.
- 사용자 영향: 재생 중 설정에서 순서나 “음절 우선”을 바꿔도 다음 곡까지 반영되지 않는다. 일시적 네트워크 실패 후에는 같은 곡에서 다시 시도할 방법이 없다.
- 얼마나 흔한가: 설정 변경, 느리거나 끊긴 망에서 흔함.
- 수정: `LyricsLoadKey`에 order/prio를 포함하고, 두 관찰 지점을 모두 그 키에 연결했다. 조회 결과가 null이면 `lyricsFor = null`로 풀어 다음 호출이 다시 묻게 했다.
- 테스트: `LyricsLoadKeyTest` (순서·음절·소스·파일/스트림·영상 구분).

### MEDIUM 4. 비디오 플래그를 잃은 커버 행이 화면과 잠금화면에서 다른 싱크 키를 씀

- 파일: 신규 `app/src/main/java/com/music/bitchord/playback/LyricsTimingSource.kt`; `PlaybackService.kt:10254`, `:10271`; `NextTrackPreparation.kt:229`, `:280`, `:466`, `:475`, `:484`
- 증거: 검색은 `CoverTitles.lyricsQuery(song).isVideo`를 썼지만, 타이밍은 `song.isVideo`를 썼다. Recents/저장 대기열 행은 비디오 플래그를 잃을 수 있다(코드 주석이 명시).
- 사용자 영향: 같은 커버가 화면에서는 영상 클럭·영상 이동 한도로, 잠금화면/다음 곡 준비에서는 곡 클럭·다른 `audioSource` 키로 처리된다. 저장된 싱크가 서로 보이지 않는다.
- 얼마나 흔한가: 커버 영상 중 Recents/저장 대기열에서 재생한 경우.
- 수정: `LyricsTimingSource.isVideo(song)` 하나로 통일했다(내부적으로 `CoverTitles.lyricsQuery`). 기존 `song.isVideo` 항은 `coverQuery.isVideo = song.isVideo || cover != null`에 그대로 포함되므로 조건을 약화하지 않았다.
- 테스트: `CoverLyricsVideoFlagTest` (수정 전 `song.isVideo` 구현으로 실패 확인, 수정 후 통과).

### MEDIUM 5. lrc.red가 `(Album Version)`·`(Radio Edit)`를 다른 테이크로 거부함

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcRed.kt:127`, `:161`
- 증거: 수정 전 `versionOf`는 `VERSION_WORDS`에 `version`, `edit`이 있으므로 `Song (Album Version)`을 `{version}`으로 만들고, `Song` 히트의 빈 버전 집합과 비교해 탈락시켰다. `TrackMatcher`는 같은 문구를 `NEUTRAL_SEGMENTS`로 이미 중립 처리한다.
- 사용자 영향: 1순위 제공처가 정확한 녹음을 놓치고 뒤 제공처로 내려간다. 잘못된 가사는 아니지만 커버/비영어곡에서 miss가 늘어난다.
- 얼마나 흔한가: 카탈로그 제목에 `(Album Version)`, `(Radio Edit)`, `(Original Mix)`가 붙은 행.
- 수정: `NEUTRAL` 집합을 추가하고 중립 문구만으로 이루어진 브래킷/대시 꼬리는 버전 집합에서 뺐다.
- 테스트: `LrcRedTest.album version and radio edit name the same recording` (수정 전 실패 확인, 수정 후 통과).

### MEDIUM 6. 길이를 모를 때 제공처 히트를 “가장 짧은 순”으로 정렬함

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcLib.kt:80`, `:92`; `KuGou.kt:130`, `:140`
- 증거: 수정 전 `LrcLib.bestSearchHit`는 `abs(duration - seconds)`로 정렬해 `seconds = 0`이면 짧은 히트가 이겼고, `exactMatch`에는 `duration=0`을 보냈다. `KuGou.searchSongs`도 `sortedBy { abs(it.duration - seconds) }`로 같은 문제가 있었다.
- 사용자 영향: 길이 없는 다운로드/앨범 행에서 30초 인터루드나 짧은 라디오 에디트의 가사가 붙을 수 있다.
- 얼마나 흔한가: 길이 없는 일괄 다운로드·재생목록 행.
- 수정: `LrcLib.pickByDuration`/`KuGou.closestFirst`는 `seconds <= 0`이면 제공처 응답 순서를 유지하고, `LrcLib`은 길이 없으면 exact 호출을 생략한다.
- 테스트: `LrcLibDurationTest`, `KuGouDurationTest`.

### MEDIUM 7. word-synced 유예가 80ms 폴링 루프였음 (배터리)

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:853`
- 증거: 수정 전 `wordSyncedWithin`은 `delay(GRACE_POLL_MS = 80)`로 최대 8초 동안 약 100회 깨어났다.
- 사용자 영향: 줄 단위 제공처가 먼저 답하고 단어 단위 제공처를 기다리는 흔한 조회마다 불필요한 wake-up이 발생한다.
- 얼마나 흔한가: 대부분의 캐시 miss 조회.
- 수정: `select { job.onJoin { } }`와 `withTimeoutOrNull`로 완료 이벤트에만 깨어난다. 우선순위 규칙(높은 순서가 아직 실행 중이면 낮은 답을 기다림, 유예가 끝나면 도착한 최선)은 그대로 유지했다.
- 테스트: `WordSyncedWithinTest` (우선순위 역전, 유예 종료, line-only 상위, 단어 답 없음).

### MEDIUM 8. 다음 곡 로그가 “꺼짐”과 “찾지 못함”을 모두 `none`으로 기록함

- 파일: 신규 `app/src/main/java/com/music/bitchord/playback/NextPrepLog.kt`; `NextTrackPreparation.kt:312`
- 증거: 수정 전 `lyrics=${... ?: "none"}`, `sync=${if (timing != null) "applied" else "skipped"}`는 스텝이 계획에서 제외된 경우도 실패처럼 보이게 했다.
- 사용자 영향: 사용자 화면은 아니지만, 108/173 같은 성적표를 잘못 읽게 만든다.
- 얼마나 흔한가: `nextPrepLyrics` 기본 off인 모든 실행.
- 수정: 실제 시도 여부(`Step.LYRICS/SYNC in steps`)로 `off`와 `none/skipped`를 분리했다.
- 테스트: `NextPrepLogTest`.

### MEDIUM 9. 재검증 override의 새 싱크가 잠금화면·알림·차량에 적용되지 않음

- 파일: `shared/src/commonMain/kotlin/com/music/bitchord/playback/LocalTimingPolicy.kt`(신규 `baseAllowed`), `PlaybackService.kt:10254`
- 증거: 수정 전 `timeable = timed && (!fromFile || coverQuery.isVideo)`라서, 다운로드 곡의 RECHECK override는 `fromFile = true`이므로 `timeable = false`가 되었다. 화면(`LyricsController`)은 `downloadRecheck`일 때 adjustable로 처리해 서로 달랐다.
- 사용자 영향: 재검증이 새 가사에 맞춰 저장한 싱크가 화면에만 보이고, 화면이 꺼지면 옛 시각으로 돌아간다.
- 얼마나 흔한가: `syncSafetyDownloadRecheck`를 켠 사용자.
- 수정: `LocalTimingPolicy.baseAllowed(timed, fromFile, isVideo, overrideRecheck)`로 RECHECK override만 파일 곡에서도 허용한다. USER override는 기존처럼 수동 보정만 적용한다.
- 테스트: `LocalTimingPolicyTest.aRecheckOverrideOnADownloadedSongIsApplied`.

### MEDIUM 10. `NextPrepCache`가 videoId만 키로 써서 파일/스트림·영상 구분을 잃음

- 파일: `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:139`(`queueKey`), `:546`(`get`); 소비처 `LyricsController.kt:273`, `PlaybackService.kt:10183`
- 증거: `Ready`에는 `isVideo`/`fromFile`이 있었지만 `get(videoId)`만 비교했고, `queueKey`에도 `localUri`가 없었다.
- 사용자 영향: 다음 곡 준비 후 파일↔스트림이 바뀌거나 다운로드가 완료돼도 같은 키라 취소/clear되지 않고, 다른 소스용 가사를 첫 프레임에 게시할 수 있다.
- 얼마나 흔한가: `nextPrepLyrics` 기본 off라 기본 사용자 영향은 없지만, 스위치를 켜면 위험하다.
- 수정: `Ready`에 `localFile`을 추가하고 `get(videoId, localFile, isVideo)`로 세 항을 모두 비교한다. `queueKey`에도 `localUri != null`을 넣어 소스가 바뀌면 번들을 취소한다.
- 테스트: `NextPrepCacheKeyTest`.

---

## 2. 진입점 × 흔한 상황 매트릭스

| 상황 | UI(`LyricsController`) | 잠금화면/백그라운드(`PlaybackService`) | 다음 곡(`NextTrackPreparation`/`TranslationAhead`) | 다운로드(`LyricsTag`) | 수동 제공처 | Gemini 받아쓰기 |
|---|---|---|---|---|---|---|
| 커버 영상 | `CoverTitles.lyricsQuery`로 원곡명·가수, `isVideo` 통일(수정 4) | 검색은 `coverQuery`, 타이밍은 `LyricsTimingSource`(수정 4) | 검색·타이밍 모두 `LyricsTimingSource`(수정 4) | `CoverTitles.lyricsQuery` 사용 | 요청의 `title/artist` 사용, `isVideo` 미전달(수동은 YouTube 자막 우선순위만 영향) | 원곡 가사가 없을 때만 |
| 비디오 노래(뮤직비디오) | `isVideo=true`, YouTube 자막 우선 | 동일 | 동일, `syncWanted`는 영상 제외 | 동일 | 동일 | 대상 |
| 셔플 | videoId 키라 정상 | 정상 | rowId/queueKey로 취소·재개 | 정상 | 정상 | 정상 |
| 앱 재시작 후 | 디스크 캐시 hit(수정 1로 길이 구분) | 동일 | 디스크 캐시 hit | 파일 태그/사이드카 | 캐시 hit | 디스크 캐시에 저장된 받아쓰기 |
| 화면 꺼짐 | 화면 없음 | `lyricMetadataWanted`가 lock-screen/차량/포그라운드일 때만 ticker | 백그라운드 조회 가능 | 다운로드 큐 | 수동 불가 | 가능(네트워크) |
| 백그라운드 시작 | 해당 없음 | `loadLyricsForCurrentTrack`가 현재 곡 조회 | 첫 소리 이벤트 후 준비 | 다운로드 시 조회 | 해당 없음 | 가능 |
| 프록시 켬 | `Http.client` 경유, 제공처별 6초/15초 상한 | 동일 | 동일 | 동일 | 동일 | Gemini 클라이언트 경유 |
| 다운로드된 곡 | override → 파일 태그 → 네트워크 순 | override → 파일 태그 → 네트워크, RECHECK 싱크 적용(수정 9) | override → 파일 태그(수정 2) | 파일에 기록 | 파일 태그 우선 | 파일 곡은 `transcribe=false` |
| 느린/끊긴 망 | 제공처별 budget/timeout, 실패 후 `lyricsFor` 해제(수정 3) | 제공처별 budget | `TranslationAhead` 60초 상한 | `LyricsTag` 30초 상한 | 수동 60초 상한 | 실패 시 세션 동안 재시도 안 함(`AiLyrics.failedThisSession`) |
| Android 14+ | 포그라운드 서비스/알림 제약은 재생 서비스가 담당 | 동일 | 동일 | 동일 | 동일 | 동일 |

---

## 3. 조용히 건너뛰는 경로(로그 없이 return)

전부 결함으로 본다. 이번에 고친 것은 8번 로그뿐이고, 나머지는 아래 “남긴 결함”에 이유와 함께 적었다.

- `LyricsController.kt:219` — `localUri == null && durationMs <= 0`이면 로그 없이 return. (길이가 곧 오는 정상 경로라 로그가 시끄러울 수 있음)
- `LyricsController.kt:395` — 로컬 파일인데 길이도 없고 임베디드 가사도 없으면 `lyricsFor = null`만 하고 return.
- `TranslationAhead.kt:374` — `sources.isEmpty() || durationMs <= 0`이면 로그 없이 null. 다음 곡 선반입이 조용히 아무 일도 안 한 것처럼 보인다.
- `LyricsRepository.kt:972` — BiniLyrics가 꺼져 있으면 ISRC 식별을 로그 없이 포기.
- `LyricsHttp.kt:44-98` — 403/429/408을 모두 null로 삼켜 `LyricsFailure.BLOCKED/TIMEOUT`이 실제로는 거의 나오지 않는다.
- `AiLyrics.transcribe` — `aiLyrics` off/키 없음/15분 초과/이 세션 실패/무음 기억은 로그 없이 null. 일부는 의도된 비용 가드.
- `LyricsTranslation.translate` — 빈 대상 언어/빈 가사는 `Unavailable`로 조용히 반환.

---

## 4. 남긴 HIGH/MEDIUM (이유와 필요한 작업)

### MEDIUM R1. 영상 가사 재측정이 8초 폴링 루프 (배터리)

- 파일: `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:56`, `:793-804`
- 증거: `while (... ) { ...; delay(REMEASURE_CHECK_MS = 8_000) }`가 최대 `MEASURE_WINDOW_MS = 15분`, 최대 10회.
- 영향: 커버/뮤직비디오 가사 패널이 열린 동안 8초마다 깨어 측정을 재시도한다. 오디오가 더 이상 늘지 않아도 루프는 유예 시간까지 돈다.
- 왜 못 고쳤나: `AudioCache`는 현재 “바이트가 늘었다” 이벤트를 내보내지 않는다(`contentsChanged`는 비우기/삭제 때만). 루프를 지우면 긴 영상의 후반부 측정이 영영 안 될 수 있다.
- 필요한 작업: `AudioCache`에 rendition별 prefix 증가 `SharedFlow<String>`을 추가하고(쓰기 완료 시 emit), `LyricsController`가 그 이벤트에서 `worthMeasuring`일 때만 `measure`를 호출하도록 바꾼다. 테스트는 가짜 flow로 “이벤트 1회당 measure 1회, 이벤트 없으면 0회”를 검증한다.

### MEDIUM R2. ISRC 식별이 BiniLyrics를 켠 사용자에게만 동작

- 파일: `LyricsRepository.kt:709`, `:972`
- 증거: `identify`가 `LyricsSource.BINI_LYRICS !in sequence`이면 null. LRC_RED/LyricsPlus는 이름 매칭으로 내려간다.
- 영향: Bini를 끈 사용자는 라이브/리믹스/다른 에디션을 고를 위험이 커진다.
- 왜 못 고쳤나: Bini를 끈 사용자에게 Bini 요청을 보내는 것은 “끈 제공처는 접촉하지 않는다”는 기존 규칙과 충돌한다. 제품 결정이 필요하다.
- 필요한 작업: `Song`의 song-version/YouTube Music 메타데이터나 다운로드 태그에서 ISRC를 먼저 얻고, 없을 때만 명시적 동의 하에 keyless Bini 식별을 허용한다.

### LOW/MEDIUM R3. HTTP 실패가 전부 `NOT_FOUND`로 분류됨

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsHttp.kt:44-98`
- 영향: 제공처 시트에서 “차단/타임아웃/오프라인”을 구분할 수 없어 사용자가 재시도 가치를 판단할 수 없다. `LyricsFailure.BLOCKED`는 사실상 도달 불가.
- 필요한 작업: `lyricsGet*`이 403/429/408에서 `LyricsHttpStatusException`을 던지거나 상태를 반환하고, 각 제공처가 그대로 `fetch`의 catch로 보내게 한다. MockWebServer로 403/429/408 분류 테스트.

### LOW R4. 디스크 가사 캐시 버전이 파서 로직 변경을 무효화하지 않음

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsDiskCache.kt:114`
- 영향: 제공처 파서/정규화 버그를 고쳐도 이미 저장된 잘못된 가사가 같은 키로 남는다.
- 필요한 작업: 파서 로직 버전을 `questionKey`에 포함하거나 `VERSION`을 올려 구버전 항목을 재조회.

### LOW R5. 번역 가사의 단어 타이밍이 한 덩어리로 붕괴

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt`의 `retimeWords`
- 영향: 번역 보기에서 원문의 단어별 하이라이트가 한 단어로 취급된다. `timingSource`로 글자 스윕만 근사한다. 문서화된 알려진 한계.

### LOW R6. 위 3장의 무로그 return들

- 필요한 작업: 각 경로에 한 줄짜리 `TrackLog.d`(이유 포함)를 넣되, 반복 호출은 세션 1회로 제한한다. 특히 `TranslationAhead.lookUp`의 길이 없음/소스 없음은 다음 곡 선반입 실패를 진단할 수 있게 해야 한다.

### LOW R7. `showFirstUsable`의 1.5초 지연

- 파일: `LyricsController.kt:50`, `:547`
- 주기 폴링은 아니지만 한 번의 타이머다. 제공처 job을 직접 기다리는 `select`로 바꿀 수 있다. 사용자 체감상 1.5초 지연은 의도된 patience라 급하지 않다.

---

## 5. 조건 변경 전/후 진리표

- `syncWanted` / `Step.SYNC`
  - 수정 전: `song.isVideo == false`이면 실행.
  - 수정 후: `CoverTitles.lyricsQuery(song).isVideo == false`이면 실행.
  - `lyricsQuery.isVideo = song.isVideo || cover != null`이므로 수정 전 실행 집합은 수정 후 실행 집합에 그대로 포함된다(커버 행만 추가로 제외). 기존 항을 지우지 않았다.
- `PlaybackService` 설정 관찰
  - 수정 전: `(synced, sources)`가 바뀔 때만 재조회.
  - 수정 후: `(synced, sources, order, prio)` 중 하나라도 바뀌면 재조회. 기존 두 항의 동작은 유지.
- `LyricsController` 메모 키
  - 수정 전: `(videoId, audioSource, sources)` 동일 → 재사용.
  - 수정 후: 위 세 항이 같고 `order`·`priority`도 같아야 재사용. 기존 동일 조건은 유지되고 두 항이 추가됨.
- `wordSyncedWithin`
  - 수정 전: 80ms마다 완료 여부를 폴링. 결과 규칙은 “높은 순서가 미완이면 대기, 유예 종료 시 도착한 최선”.
  - 수정 후: 완료 이벤트(`onJoin`)에만 깨어남. 결과 규칙은 동일.
- `LrcRed.versionOf`
  - 수정 전: 중립 라벨도 `VERSION_WORDS`에 포함.
  - 수정 후: `NEUTRAL` 문구만인 세그먼트는 버전에서 제외. `Live`/`Remix`/`Acoustic` 등은 그대로 버전.
- `NextPrepLog`
  - 수정 전: `found == null` → `none`, `timing == null` → `skipped`.
  - 수정 후: 스텝이 실제 시도된 경우에만 `none/skipped`, 아니면 `off`.

---

## 6. 테스트 결과

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.*' --tests 'com.music.bitchord.playback.LocalTimingPolicyTest'` → 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.LrcRedTest' --tests 'com.music.bitchord.LyricsLoadKeyTest' --tests 'com.music.bitchord.NextPrepLogTest' --tests 'com.music.bitchord.CoverLyricsVideoFlagTest' --tests 'com.music.bitchord.TranslationAheadOverrideTest' --tests 'com.music.bitchord.NextPrepCacheKeyTest'` → 통과.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 통과.
- 참고: `ProviderLyricsTest`의 기존 `LyricsSource.entries.size == 21` 단언은 현재 트리(20개)에서 실패한다. 이번 변경과 무관한 기존 불일치이며, 제공처를 추가/삭제하지 않았다. 별도로 21번째 제공처가 의도된 것인지 확인이 필요하다.
