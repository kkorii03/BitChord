# review-features-1031-r5 — AI 타이밍 노출 조건 재검사(검토자 지적 처리)

- 점검일: 2026-10-31
- 기준: 현재 체크아웃(`dsq/review-features-1031-r5`, 이전 차수 `review-features-1031-r4` 위)
- 범위: 5차 검토자 VERDICT에 열거된 HIGH 1·LOW 1 처리, 그리고 같은 경로에서 새로 확인한 AI 타이밍 건너뛰기 조건. 기능 전체 표는 `review-features-1031.md` §1을 유지하며, 이번 diff는 AI 타이밍 오디오 공급 경로만 바꾼다.
- 방법: `LyricsController.startTiming`(`LyricsController.kt:661-680`) → `VideoAudio.audioReadyForTiming`(`VideoAudio.kt:108`) → `VideoAudio.wav`(`:61`) → `AudioCache.awaitAnalysisTrack`(`AudioCache.kt:2467`) → `VersionAudioAligner.renditionToMeasure`(`VersionAudioAligner.kt:328`) 순서로 실행 조건을 따라갔다. 기기 검증은 하지 않았고, 고친 항목은 컴파일·단위 테스트로 확인했다.
- 금지 경로 준수: `.github/`, `keys/`, 서명 설정, 업스트림 v1.8.1 코드는 건드리지 않았다. 곡 해석·노래 버전·가사 싱크 계산(`LyricsTiming` 본체)·크로스페이드는 수정하지 않았다.

---

## 1. 5차 검토자 지적 처리

VERDICT에는 `ISSUES 3`으로 적혔지만 본문에 열거된 것은 HIGH 1건과 LOW 1건이다. 남은 1건의 본문이 없어 별도로 처리할 수 없었고, HIGH의 뿌리인 `awaitAnalysisTrack`의 조기 반환은 HIGH 항목에 함께 기록했다.

| # | 지적 | 심각도 | 이번 처리 | 근거 |
|---|---|---|---|---|
| 1 | `audioReadyForTiming`이 캐시에 렌디션이 하나라도 있으면 "준비됨"으로 보지만, `wav`는 `renditionToMeasure(...).isComplete`를 요구한다. 플레이어가 엔트리를 쥐고 있는 스트리밍 곡에서는 `awaitAnalysisTrack`이 800KB 헤드만 보고 true를 돌려주므로, 버튼은 뜨는데 `wav`가 즉시 null이 되어 `aiFailed`로 끝난다. | **HIGH** | **고침** | `audioReadyForTiming`을 "선택된 렌디션이 완성됨"으로 되돌리고, `wav`가 고르는 바로 그 복사본을 같은 선택 함수로 고르게 했다(`VideoAudio.kt:108-149`, `VersionAudioAligner.kt:328-351`). 부분 스트림에서는 버튼이 열리지 않는다. |
| 2 | 위 HIGH 수정에 앱 단위 테스트가 없다. | **LOW** | **고침** | 캐시 없이 값을 받는 `timingAudioReady` seam을 만들고 `VideoAudioTimingReadyTest` 8건을 추가했다. 부분 스트림·완성본·`#alt` 대체본·화면 꺼짐·최근 디코드본을 모두 고정한다. |

### 조건 변경 전/후 진리표 (`audioReadyForTiming`)

| 입력 | r4 (수정 전) | r5 (수정 후) | `wav` 실제 결과 | 판정 |
|---|---|---|---|---|
| 최근 디코드 WAV 있음 | true | true | 즉시 그 바이트 | 일치 |
| 화면 꺼짐, 최근 WAV 없음 | false | false | null(로그) | 일치 |
| 캐시 비어 있음 | false | false | null(로그) | 일치 |
| 같은 소스 부분 복사(≥800KB, 미완성) | **true** | **false** | null(`copy is still …`) | r4 불일치 → 고침 |
| 같은 소스 완성 복사 | true | true | 디코드 | 일치 |
| `#alt`만 완성 | true | true | `#alt` 디코드 | 일치 |
| 같은 소스 부분(≥800KB) + `#alt` 완성 | **true** | **false** | 부분 같은 소스를 골라 null | r4 불일치 → 고침 |
| 같은 소스 부분(<800KB) + `#alt` 완성 | true | true | `#alt` 디코드 | 일치 |

기존 항 `recentCached`, `screenOn`은 그대로 유지했고, "아무 렌디션이나 존재"라는 항만 "선택된 렌디션의 완성"으로 바꿨다. 선택 순서 자체는 `VersionAudioAligner.chooseMeasurementCopy`로 `renditionToMeasure`와 공유해 두 곳이 갈라지지 않게 했다.

### r3 HIGH에 대한 판정 변경

r3의 HIGH("스트리밍 중인 곡은 Deepgram 단독 사용자에게 버튼이 영영 숨는다")는 **r5 기준으로 해당 없음(반박)** 이다. `wav`는 완성된 복사본만 디코드하고(`VideoAudio.kt:73-85`), 플레이어가 엔트리를 쥔 동안 분석 전문 페치는 보류된다(`AudioCache.kt:2543-2546`). 따라서 부분 스트림에서 버튼을 여는 것이 곧 `aiFailed`였고, r4의 확대가 오히려 잘못이었다. 완성본이 생기면 다음 `startTiming`에서 버튼이 열린다(아래 3장 LOW).

---

## 2. 이전 감사·차수 문서 확인

판정: **고쳐짐 / 남음 / 해당 없음**.

| 이전 문서 | 항목 | 현재 상태 | 근거 |
|---|---|---|---|
| r4 §1 #1 (HIGH) | AI 타이밍 버튼이 부분 렌디션에서 열림 | **이번에 고침** | `VideoAudio.kt:108-149` |
| r4 §1 #3 (LOW) | HIGH 수정의 단위 테스트 없음 | **이번에 고침** | `VideoAudioTimingReadyTest` 8건 |
| r4 §3 LOW | `wav` null 시 로그 없음 | **이번에 고침** | `VideoAudio.kt:63-85`에 화면 꺼짐/분석 오디오 없음/캐시 복사 없음/부분 복사 로그 추가 |
| r3 §3 LOW | `aiAvailable`이 재생 중 캐시 완료를 반영하지 않음 | **남음** | 여전히 `startTiming`에서 한 번 계산(`LyricsController.kt:661-680`). 아래 3장 LOW |
| r2 §1 | 다운로드 곡 WebDAV 업로드 행 | 고쳐짐(유지) | `SongActionsSheet`/`MainActivity`의 `isLocalFile` 게이트 없음 |
| r2 §1 | 반복 모드 저장 주석 | 고쳐짐(유지) | `PlaybackService.onRepeatModeChanged` |
| `docs/AUDIT_FEATURES.md` #20 | 설정 AI 토글 키 게이트 없음 | 남음 | `SettingsSheet.kt:1442-1515` |
| `docs/AUDIT_FEATURES.md` #24 | 인앱 업데이트 다이얼로그 도달 불가 | 남음 | `AppUpdateChecker.kt:108,181`의 `CHECK_UPSTREAM_RELEASES = false` |
| `docs/audit/check-flow.md` #1-#5 | 재생 핵심 경로 | 남음 | 이번에도 수정하지 않음. **폰 검증 필요** |
| r2 §3 MED | 검색/상세 오류 재시도 없음 | 남음 | `SearchScreen.kt:374,533`, `DetailScreen.kt:804`가 `MessageState(message)`만 그림 |
| r2 §3 MED | 고음질 재다운로드 무동작 안내 없음 | 남음 | `HqLibraryUpgrade.kt:415-471` |
| r2 §3 MED | 다음 곡 SYNC 스킵 이유 로그 없음 | 남음 | `NextTrackPreparation.kt:460-466` |
| r2 §3 LOW | 알림 액션 한국어 하드코딩 | 남음 | `PlaybackService.kt:2416,2427` |
| r2 §3 LOW | 빈 다운로드 화면의 고음질 전체 행 | 남음 | `DetailScreen.kt:651-661` |
| r2 §3 HIGH | 같은 행 queueKey 변경 무시 | 남음 | `NextTrackPreparation.kt:101-107`. **폰 검증 필요** |
| `docs/audit/audit-song.md` #7 | 세션 변경에도 `songVersionMissing` 고정 | 남음 | `StreamResolver.kt:407-416` |

---

## 3. 새로 찾은 문제 (이번 diff에서 고치지 않은 것)

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 |
|---|---|---|---|---|
| **MED** | `app/.../playback/smart/VideoAudio.kt:61-91`, `app/.../ui/LyricsController.kt:661-680` | AI 타이밍 오디오는 `wav`가 `AudioCache`만 읽는다(`AudioCache.awaitAnalysisTrack`, `renditionToMeasure`). `Song.localUri`(다운로드 파일)는 `songWav`만 쓴다(`VideoAudio.kt:156-190`, 호출부 `LyricsTiming.kt:577,606,1683`). 다운로드는 캐시에서 복사하지 않으면 `DownloadStore`에만 저장된다(`Downloads.kt:2038-2072,2258-2278`). | 캐시에 완성본이 없는 다운로드 곡은 Deepgram 단독 사용자에게 AI 타이밍 버튼이 숨는다. 로컬 파일이 분명히 있는데도 기능이 사라진다. Gemini 키가 있으면 영상 폴백은 동작한다. | `audioReadyForTiming`/`wav`에 `localUri`를 넘겨 `songWav`처럼 로컬 파일을 먼저 읽게 한다. 버튼과 실제 처리 경로를 함께 바꿔야 하므로 이번에는 문서만. **폰 검증 필요** |
| **LOW** | `app/.../ui/LyricsController.kt:661-680` | `aiAvailable`은 `startTiming`에서 한 번만 계산된다. 캐시 완성 이벤트를 구독하지 않는다. | 가사를 먼저 연 뒤 트랙이 완전히 캐시되면, 다음 `startTiming`(가사 소스 재선택·곡 전환) 전까지 버튼이 늦게 뜬다. 거짓 양성은 없어졌다. | `SimpleCache.addListener(key, …)`의 `onSpanAdded`(커밋 시점)로 완료를 알리는 흐름을 만들어 `aiAvailable`만 재계산한다. 주기 폴링 금지. 이번에는 새 이벤트 표면을 늘리지 않으려고 문서만. |
| **LOW** | `app/.../playback/smart/VideoAudio.kt:227-232` | `decode`가 10초 미만이면 로그 없이 null이다. `audioFitsInline`은 알려진 길이에만 버튼을 숨기고, 길이를 모르는 곡은 통과시킨다(`LyricsAiGuard.kt:110-113`). | 아주 짧은 곡/잘린 캐시에서 Deepgram이 조용히 끝날 수 있다. | 10초 미만 거절에도 `TrackLog.d("AI timing: under ten seconds", …)`를 남긴다. |

---

## 4. 건너뛰는 조건 · 흔한 상황 · 로그 여부 (AI 타이밍)

| 단계 | 건너뛰는 조건 (코드) | 흔한 상황인가 | 건너뛸 때 로그 | 비고 |
|---|---|---|---|---|
| 버튼 | 비어 있지 않은 줄 < 3 (`LyricsAiGuard.kt:117-131`) | 1~2줄 가사/간주 | 없음(UI 숨김) | r4에서 고침(유지) |
| 버튼 | Gemini 없음 + (Deepgram 없음 또는 한국어/연주곡 또는 완성 오디오 없음 또는 7분 초과/10초 미만) | Deepgram 단독 + 스트리밍/다운로드/긴 곡 | 없음(UI 숨김) | r5에서 "완성 오디오"로 강화 |
| 버튼 — 화면 꺼짐 | `!PowerState.screenOn` (`VideoAudio.kt:111-114`) | 재생 중 화면 꺼짐 | 없음(UI 숨김) | 의도된 절전 |
| `wav` — 화면 꺼짐 | `!PowerState.screenOn` (`VideoAudio.kt:63-66`) | 버튼 누른 뒤 화면이 꺼짐 | `AI timing: the screen is off` | r5에서 로그 추가 |
| `wav` — 분석 오디오 없음 | `!AudioCache.awaitAnalysisTrack` (`VideoAudio.kt:70-73`) | 오프라인/캐시 없음/화면 꺼짐 | `AI timing: no analysis audio on disk` | r5에서 로그 추가 |
| `wav` — 캐시 복사 없음 | `renditionToMeasure == null` (`VideoAudio.kt:74-77`) | 캐시 엔트리가 방금 삭제됨 | `AI timing: no cache copy to decode` | r5에서 로그 추가 |
| `wav` — 부분 복사 | `!copy.isComplete` (`VideoAudio.kt:78-85`) | **스트리밍 중인 곡에 매우 흔함** | `AI timing: copy is still X/Y bytes` | r5에서 로그 추가. 이제 버튼도 숨김 |
| `wav` — 7분 초과 | `decode`의 `MAX_SECONDS` (`VideoAudio.kt:227-232`) | 긴 곡(라이브/명상) | `too long to send inline` | 있음 |
| `wav` — 10초 미만 | `decode`의 최소 길이 (`VideoAudio.kt:228`) | 잘린 캐시/아주 짧은 곡 | 없음 | 3장 LOW |
| Deepgram 스킵 | `!wantsDeepgram(title, artist, texts)` (`LyricsTiming.kt:1414-1418`) | 한국어 곡에 매우 흔함 | `Deepgram skipped (Korean/instrumental)` | 정상 |
| Gemini 없음 | `!geminiKeyUsable` (`LyricsTranslation.kt:1309`) | Deepgram 단독 사용자 | 없음 | 버튼 단계에서 숨김 |

---

## 5. 이번 수정과 테스트

### 수정 파일

1. `app/src/main/java/com/music/bitchord/playback/smart/VideoAudio.kt`
   - `audioReadyForTiming`: "아무 렌디션 존재" → "`wav`가 고르는 렌디션이 완성"으로 변경. `timingAudioReady` seam 추가.
   - `wav`: 화면 꺼짐·분석 오디오 없음·캐시 복사 없음·부분 복사 거절에 트랙 로그 추가(동작 변경 없음).
2. `app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt`
   - `renditionToMeasure`의 선택 로직을 `chooseMeasurementCopy`로 분리. 동작 동일, 테스트 seam만 추가.

### 테스트 파일

- `app/src/test/java/com/music/bitchord/playback/smart/VideoAudioTimingReadyTest.kt` (신규, 8건)
  - `anEmptyCacheIsNotReady`
  - `aPartiallyStreamedCopyIsNotReady`
  - `aCompleteCopyIsReady`
  - `aCompleteSubstituteIsReadyWhenThereIsNoMeasurableSameSource`
  - `aMeasurablePartialSameSourceHidesTheCompleteSubstitute`
  - `aTinyPartialSameSourceFallsBackToTheCompleteSubstitute`
  - `screenOffHidesTheAction`
  - `anAlreadyDecodedWavStaysReadyEvenWithTheScreenOff`

### 테스트 결과

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 성공.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.smart.VideoAudioTimingReadyTest' -Pbitchord.arm64Only=true --console=plain -q` — 성공(`tests="8" failures="0" errors="0"`).
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.LyricsAiGuardTest' --console=plain -q` — 성공(기존 가드 13건 유지).

---

## 6. 미해결 — 기기 검증 필요

- `docs/audit/check-flow.md`의 HIGH 5건(다음 곡 준비 queueKey/가사 캐시/standby 버전 교체/다운로드 안전 우회/로컬 오판)은 재생 핵심 경로라 이번에 고치지 않았다. **폰 검증 필요**.
- 다운로드 곡의 로컬 파일을 AI 타이밍 오디오로 쓰지 않는 문제(3장 MED). **폰 검증 필요**.
- `aiAvailable`의 캐시 완료 이벤트 재계산(3장 LOW), 10초 미만 거절 로그(3장 LOW).
- `docs/AUDIT_FEATURES.md` #20 설정 AI 토글 키 게이트, #24 인앱 업데이트 플래그.
- 검색/상세 오류 재시도, 고음질 재다운로드 무동작 안내, 다음 곡 SYNC 스킵 이유 로그, 알림 액션 한국어 하드코딩, 빈 다운로드 화면의 고음질 전체 행.

---

## 요약 (10줄 이내)

1. 수정 파일: `VideoAudio.kt`, `VersionAudioAligner.kt`, 테스트 `VideoAudioTimingReadyTest.kt`(+이 문서).
2. 지적 HIGH 고침: `audioReadyForTiming`을 "아무 렌디션"이 아니라 `wav`가 고르는 복사본의 완성 여부로 판정. 부분 스트림에서 버튼이 뜨고 `aiFailed` 되던 경로 제거.
3. 지적 LOW 고침: 캐시 없이 검증하는 `timingAudioReady` seam + 앱 테스트 8건.
4. r3 HIGH는 반박: `wav`는 완성본만 디코드하므로 부분 스트림에 버튼을 여는 것이 오히려 오류. 근거는 `VideoAudio.kt:73-85`, `AudioCache.kt:2543-2546`.
5. 추가 수정: `wav`의 화면 꺼짐/오디오 없음/부분 복사 거절에 로그 추가(r4 §3 LOW였음).
6. 테스트: 앱 컴파일 성공, `VideoAudioTimingReadyTest` 8건 성공, `LyricsAiGuardTest` 13건 성공.
7. 미해결 MED: 다운로드 곡 로컬 파일을 AI 타이밍이 읽지 않음(3장, 폰 검증 필요).
8. 미해결 LOW: 캐시 완료 이벤트에서 `aiAvailable` 재계산, 10초 미만 거절 로그(3장).
9. VERDICT의 `ISSUES 3` 중 본문에 없는 1건은 처리 불가로 기록.
