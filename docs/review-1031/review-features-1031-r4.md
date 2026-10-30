# review-features-1031-r4 — 이전 검토자 지적 3건 처리 + AI 타이밍 노출 조건 재검사

- 점검일: 2026-10-31
- 기준: 현재 체크아웃(`dsq/review-features-1031-r4`, 이전 차수 `review-features-1031-r3` 위)
- 범위: 4차 검토자 VERDICT(HIGH 1·MED 1·LOW 1) 처리. 기능 전체 표는 `review-features-1031.md` §1을 그대로 유지하며, 이번에 바뀐 것은 AI 타이밍 노출 경로뿐이다.
- 방법: `LyricsController.startTiming` → `LyricsAiGuard.manualTimingAvailable` → `VideoAudio.audioReadyForTiming` → `VideoAudio.wav` → `LyricsTiming.placeWithAi` 순서로 실행 조건을 따라갔다. 실제 기기 검증은 하지 않았고, 고친 항목은 컴파일·단위 테스트로 확인했다.
- 금지 경로 준수: `.github/`, `keys/`, 서명 설정, 업스트림 v1.8.1 코드는 건드리지 않았다. 곡 해석·노래 버전·가사 싱크 계산·크로스페이드 본체는 수정하지 않았다.

---

## 1. 4차 검토자 지적 처리

| # | 지적 | 심각도 | 이번 처리 | 근거 |
|---|---|---|---|---|
| 1 | `aiAvailable`이 `startTiming` 때 "이미 완성된 렌디션"만 보고, 스트리밍 중인 곡은 Deepgram만 쓰는 사용자에게 버튼이 영영 숨는다 | **HIGH** | **고침** | `VideoAudio.audioReadyForTiming`이 `isComplete`만 보던 것을 "캐시에 이 곡의 렌디션이 하나라도 있으면"으로 넓혔다(`VideoAudio.kt:91-96`). `wav`는 완성본을 기다린 뒤 디코드하므로(`VideoAudio.kt:61-74`) 스트리밍 중인 부분 렌디션도 버튼을 열 자격이 있다. |
| 2 | `audioReadyForTiming`이 `wav`의 디코드 실패 조건(7분 초과·10초 미만)과 `awaitAnalysisTrack` 규칙을 반영하지 않아 긴 곡·`#alt` 전용 캐시가 `aiFailed`로 끝난다 | **MED** | **부분 고침 + #alt 부분은 코드 근거로 반박** | 길이 상한·하한은 `LyricsAiGuard.audioFitsInline`으로 추가해 Deepgram 단독일 때 7분 초과/10초 미만이면 버튼을 숨긴다(`LyricsAiGuard.kt:97-105,117-131`). **`#alt` 전용은 숨기지 않는다**: `awaitAnalysisTrack`은 선검사만 비`#alt`를 보고, 이후 `awaitAnalysisHead`의 `alignmentPrefixBytes`가 `#alt` 접두사를 포함하며(`AudioCache.kt:2505-2518`), `wav`는 `renditionToMeasure`가 고른 완성 `#alt`를 실제로 디코드한다(`VideoAudio.kt:68-69`, `VersionAudioAligner.kt:328-341`). 따라서 `#alt`를 숨기면 프록시/대체 소스 사용자에게서 동작하는 기능을 가린다. |
| 3 | `manualTimingAvailable`이 `MIN_AI_LINES`(3)를 무시해 3줄 미만 곡에서도 버튼이 열리고 `placeWithAi`가 즉시 null을 돌려준다 | **LOW** | **고침** | `LyricsAiGuard.MIN_AI_LINES = 3`을 추가하고 비어 있지 않은 줄 수가 3 미만이면 `false`(`LyricsAiGuard.kt:85-91,117-131`). `placeWithAi`도 같은 하한에서 엔진을 묻기 전에 null이다(`LyricsTiming.kt:1325-1326`). |

### 조건 변경 전/후 진리표

| 함수 | 이전 | 이후 | 유지된 항 | 새 항 |
|---|---|---|---|---|
| `manualTimingAvailable` | `gemini \|\| (deepgram && audioAvailable && wantsDeepgram)` | `nonBlankLines >= 3 && (gemini \|\| (deepgram && audioAvailable && audioFitsInline(duration) && wantsDeepgram))` | `gemini`, `deepgram`, `audioAvailable`, `wantsDeepgram` | `nonBlankLines >= 3`, `audioFitsInline` |
| `audioReadyForTiming` | `recentHit \|\| (screenOn && renditionToMeasure?.isComplete == true)` | `recentHit \|\| (screenOn && renditionsOf(watch).isNotEmpty())` | `recentHit`, `screenOn` | 캐시에 있는 아무 렌디션(부분 포함). `isComplete` 요구 제거가 HIGH 수정점 |

---

## 2. 이전 감사·차수 문서 확인

판정: **고쳐짐 / 남음 / 해당 없음**. 이번에 새로 고친 것은 #1·#3이며, #2는 위 표대로 일부 고침·일부 반박이다.

| 이전 문서 | 항목 | 현재 상태 | 근거 |
|---|---|---|---|
| r3 §3 HIGH | AI 타이밍 버튼이 이미 완성된 오디오만 봄 | **이번에 고침** | `VideoAudio.kt:91-96` |
| r3 §3 MED | `audioReadyForTiming`이 디코드 실패·`await` 규칙 미반영 | **이번에 고침(길이)·반박(`#alt`)** | `LyricsAiGuard.kt:97-105`, `AudioCache.kt:2504-2518`, `VideoAudio.kt:68-69` |
| r3 §3 LOW | `aiAvailable`이 재생 중 키/오디오 변화 미반영 | **남음** | 여전히 `startTiming`에서 한 번 계산(`LyricsController.kt:661-682`). 아래 3장 LOW. |
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
| r1/r2/r3의 나머지 "고쳐짐" 항목 | — | 고쳐짐(유지) | r3 §1 표와 동일하며 이번 diff에서 건드리지 않았다. |

---

## 3. 새로 찾은 문제 (이번 diff에서 고치지 않은 것)

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 |
|---|---|---|---|---|
| **LOW** | `app/.../ui/LyricsController.kt:661-682`, `app/.../playback/smart/VideoAudio.kt:91-96` | `aiAvailable`은 여전히 `startTiming`에서 한 번만 계산된다. 이번 변경으로 "캐시에 바이트가 하나라도 있으면"으로 넓혔지만, 트랙 시작 직후 캐시가 완전히 비어 있던 순간에 가사가 먼저 뜨면 다음 `startTiming`(가사 소스 재선택 등)까지 버튼이 숨는다. | 아주 빠른 가사 로드 + 아주 느린 첫 버퍼링에서 Deepgram 단독 사용자가 버튼을 늦게 본다. 새로 만든 회귀는 아니고 r3 LOW의 잔여분이다. | `AudioCache.contentsChanged`처럼 캐시 완료를 알리는 이벤트를 추가해 `aiAvailable`만 다시 계산한다(폴링 금지). 이벤트 훅이 없어 이번엔 문서만. |
| **LOW** | `app/.../playback/smart/VideoAudio.kt:61-74`, `LyricsTiming.kt:1389` | `wav`가 null이면 Deepgram은 조용히 건너뛰고 Gemini가 없으면 끝난다. `askAi`는 `wav` null에 대한 로그를 따로 남기지 않는다. | 다운로드/오프라인/화면 꺼짐에서 Deepgram 단독 사용자는 왜 실패했는지 로그로 알 수 없다. | `wav` null 시 `TrackLog.d("AI timing: no inline audio ...")`를 남긴다. 이번엔 문서만(로그 보강). |

---

## 4. 건너뛰는 조건 · 흔한 상황 · 로그 여부 (AI 타이밍)

| 단계 | 건너뛰는 조건 (코드) | 흔한 상황인가 | 건너뛸 때 로그 | 비고 |
|---|---|---|---|---|
| AI 타이밍 버튼 | 비어 있지 않은 줄 < 3 (`LyricsAiGuard.kt:117-131`) | 1~2줄짜리 가사/간주 | 없음(UI 숨김) | 이번에 고침(검토자 LOW) |
| AI 타이밍 버튼 | Gemini 없음 + (Deepgram 없음 또는 한국어/연주곡 또는 오디오 캐시 없음 또는 7분 초과/10초 미만) | Deepgram 단독 + 한국어/연주곡/다운로드 곡/긴 곡 | 없음(UI 숨김) | 이번에 길이 조건 추가(검토자 MED) |
| AI 타이밍 버튼 — 화면 꺼짐 | `!PowerState.screenOn` (`VideoAudio.kt:91-96`, `:61-74`) | 재생 중 화면 꺼짐 | 없음 | 의도된 절전. 사용자가 버튼을 누르는 순간은 화면이 켜져 있다 |
| Deepgram 스킵 | `!wantsDeepgram(title, artist, texts)` (`LyricsTiming.kt:1414-1418`) | 한국어 곡에 매우 흔함 | `Deepgram skipped (Korean/instrumental)` 있음 | 정상 |
| 오디오 없음 | `VideoAudio.wav(...)` null (`LyricsTiming.kt:1389`) | 다운로드/오프라인/캐시 없음 | 없음 | Gemini 키가 있으면 영상 주소로 폴백. **LOW**(3장) |
| Gemini 없음 | `!geminiKeyUsable` (`LyricsTranslation.kt:1309`) | Deepgram 단독 사용자 | 없음 | 버튼 단계에서 이미 숨김 |
| `#alt` 전용 캐시 | 숨기지 않음 | 프록시/대체 소스 사용자 | — | `wav`가 완성 `#alt`를 실제로 디코드하므로 숨기지 않는 것이 맞다(검토자 MED 일부 반박) |

---

## 5. 이번 수정과 테스트

### 수정 파일

1. `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsAiGuard.kt`
   - `MIN_AI_LINES`, `MAX_TIMING_AUDIO_MS`, `MIN_TIMING_AUDIO_MS`, `audioFitsInline` 추가.
   - `manualTimingAvailable`에 3줄 하한과 길이 조건 추가(기존 `gemini`/`deepgram`/`audioAvailable`/`wantsDeepgram` 항은 유지).
2. `app/src/main/java/com/music/bitchord/playback/smart/VideoAudio.kt`
   - `audioReadyForTiming`이 `isComplete` 대신 캐시에 있는 렌디션 유무를 본다(스트리밍 중·`#alt` 포함). `wav`의 대기 동작과 맞춘다.
3. `app/src/main/java/com/music/bitchord/ui/LyricsController.kt`
   - `startTiming`에서 `request.durationMs`를 `manualTimingAvailable`에 전달. 주석을 새 조건에 맞게 갱신.

### 테스트 파일

- `shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsAiGuardTest.kt`
  - `aHandTimingNeedsAnEngineThatCanAnswerForThisSong`(기존, 새 시그니처·3줄 반영)
  - `noEngineCanMoveFewerThanThreeSungLines`(신규)
  - `deepgramOnlyHidesWhenTheInlineAudioWindowCannotHoldTheSong`(신규)

### 테스트 결과

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 성공.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.LyricsAiGuardTest' --console=plain -q` — 성공(`tests="13" failures="0" errors="0"`).

---

## 6. 미해결 — 기기 검증 필요

- `docs/audit/check-flow.md`의 HIGH 5건(다음 곡 준비 queueKey/가사 캐시/standby 버전 교체/다운로드 안전 우회/로컬 오판)은 재생 핵심 경로라 이번에 고치지 않았다. **폰 검증 필요**.
- `docs/AUDIT_FEATURES.md` #20 설정 AI 토글 키 게이트, #24 인앱 업데이트 플래그.
- 검색/상세 오류 재시도, 고음질 재다운로드 무동작 안내, 다음 곡 SYNC 스킵 이유 로그, 알림 액션 한국어 하드코딩, 빈 다운로드 화면의 고음질 전체 행.
- `aiAvailable`의 재생 중 캐시 완료 반영(3장 LOW)과 `wav` null 로그(3장 LOW).

---

## 요약 (10줄 이내)

1. 수정 파일: `LyricsAiGuard.kt`, `VideoAudio.kt`, `LyricsController.kt`, 테스트 `LyricsAiGuardTest.kt`(+이 문서).
2. HIGH(지적 1) 고침: 스트리밍/부분 캐시에서도 AI 타이밍 버튼이 열리도록 `isComplete` 요구 제거.
3. MED(지적 2) 부분 고침: 7분 초과/10초 미만이면 Deepgram 단독 버튼 숨김. `#alt`는 `wav`가 실제 디코드하므로 숨기지 않음(코드 근거 반박).
4. LOW(지적 3) 고침: 비어 있지 않은 줄 3개 미만이면 버튼 숨김(`MIN_AI_LINES`).
5. 테스트: `:app:compileProdReleaseKotlin` 성공, `:shared:jvmTest`(LyricsAiGuardTest, 13건) 성공.
6. 미해결: check-flow HIGH 5건은 재생 핵심 경로라 `docs/audit/check-flow.md`에 남기고 폰 검증 필요.
7. 미해결: `aiAvailable`의 캐시 완료 재계산·`wav` null 로그는 이 문서 3장에 남김.
