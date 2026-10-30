# review-features-1031-r3 — AI 타이밍 버튼 노출 조건 재검사

- 점검일: 2026-10-31
- 기준: 현재 체크아웃(`dsq/review-features-1031-r3`, 이전 차수 `review-features-1031-r2` 위)
- 범위: r2에서 고친 AI 타이밍 버튼 게이트가 실제 수동 요청 경로와 일치하는지. 이전 차수 HIGH·MED 잔여 항목은 상태만 다시 확인했다.
- 방법: `LyricsController.startTiming` → `LyricsTiming.placeWithAi` → `askAi` 순서로 실행 조건을 따라갔다. 실제 기기 검증은 하지 않았고, 이번에 고친 항목만 컴파일·단위 테스트로 확인했다.
- 금지 경로 준수: `.github/`, `keys/`, 서명 설정, 업스트림 v1.8.1 코드는 건드리지 않았다. 곡 해석·노래 버전·가사 싱크 계산·크로스페이드 본체는 수정하지 않았다.

---

## 1. 이전 차수·감사 문서 확인

| 이전 문서 | 항목 | 현재 상태 | 근거 |
|---|---|---|---|
| r2 §1 | AI 타이밍 버튼이 제공자 키만 보고 열림 | **남음 → 이번에 고침** | r2는 `geminiKeyUsable \|\| Deepgram.usable`만 봤다(`LyricsController.kt:666-667`). `askAi`는 한국어/연주곡 제목과 오디오 없음에서 Deepgram을 건너뛰고 Gemini가 필요하다(`LyricsTiming.kt:1389,1414-1418,1457`, `LyricsTranslation.kt:1309`). |
| r2 §1 | 다운로드 곡 WebDAV 업로드 행 | 고쳐짐(유지) | `SongOrigins.isLocalFile` 게이트 제거, `WebDavUploads.isUploadableSource` 유지. |
| r2 §1 | 반복 모드 저장 주석 | 고쳐짐(유지) | `PlaybackService.onRepeatModeChanged`와 주석 유지. |
| r2 §2 | `docs/AUDIT_FEATURES.md` #20 설정 AI 토글 키 게이트 없음 | 남음 | `SettingsSheet.kt:1442-1515` 그대로. |
| r2 §2 | `docs/audit/check-flow.md` #1-#5 재생 핵심 경로 | 남음 | 이번에도 수정하지 않았다. **폰 검증 필요**. |
| r2 §3 HIGH | `NextTrackPreparation` 같은 행 queueKey 변경 무시 | 남음 | `NextTrackPreparation.kt:101-107`. **폰 검증 필요**. |
| r2 §3 MED | 검색/상세 오류 재시도 없음 | 남음 | `SearchScreen.kt:374,533`, `DetailScreen.kt:804`. |
| r2 §3 MED | 고음질 재다운로드 무동작 안내 없음 | 남음 | `HqLibraryUpgrade.kt:415-471`. |
| r2 §3 MED | 다음 곡 SYNC 스킵 이유 로그 없음 | 남음 | `NextTrackPreparation.kt:460-466`. |
| r2 §3 LOW | 알림 액션 한국어 하드코딩 | 남음 | `PlaybackService.kt:2408-2435`. |
| r2 §3 LOW | 빈 다운로드 화면의 고음질 전체 행 | 남음 | `DetailScreen.kt:651-661`. |
| r2 §3 LOW | `aiAvailable`이 재생 중 키/오디오 변화를 반영하지 않음 | **일부 남음** | 여전히 `startTiming`에서 한 번 계산한다. 이번 오디오 조건도 같은 한계가 있다(아래 3장 LOW). |

---

## 2. 결과 표 — 이번에 확인한 기능

| 기능 | 진입점 파일:줄 | 실제 처리 파일:줄 | 판정 |
|---|---|---|---|
| AI 타이밍 버튼 노출 | `sharedUi/.../player/LyricsTimingUi.kt:26,57-58` (`aiTimingActionEnabled`) ← `app/.../ui/LyricsController.kt:661-681` | `LyricsController.toggleAiLyricsTiming` → `LyricsTiming.placeWithAi` → `askAi` (`LyricsTiming.kt:1375-1487`) | **이번에 고침 — 정상** |
| AI 타이밍 실제 엔진 선택 | `LyricsController.kt:1091-1136` | `LyricsTiming.kt:1389` 오디오, `:1414-1418` Deepgram 스킵, `:1457` Gemini 폴백 | 정상 (가드 규칙 자체는 유지) |
| AI 타이밍 되돌리기 | `LyricsController.kt:1146-1178` | `LyricsTiming.clearAi` | 정상 (`byAi`면 키 없이도 열림) |

---

## 3. 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 | 처리 |
|---|---|---|---|---|---|
| **MED** | `app/.../ui/LyricsController.kt:661-681`, `shared/.../LyricsAiGuard.kt:84-99` | `aiAvailable = geminiKeyUsable \|\| Deepgram.usable`는 키 존재만 봤다. 수동 요청은 한국어/연주곡 제목이면 Deepgram을 건너뛰고(`LyricsAiGuard.wantsDeepgram`, `LyricsTiming.kt:1414-1418`), 오디오가 없으면 Deepgram을 못 쓰며(`LyricsTiming.kt:1389`), Gemini 키가 없으면 `placeLinesInVideo`가 null을 돌려준다(`LyricsTranslation.kt:1309`). | Deepgram만 설정한 사용자가 한국어/연주곡 또는 오디오가 아직 없는 곡에서 버튼을 누르면 `aiFailed`만 보게 된다. | 수동 경로와 같은 조건으로 노출을 계산한다: Gemini 키가 있거나, Deepgram 키 + Deepgram이 이 곡을 들을 수 있음(제목/가사 가드 통과) + `VideoAudio`가 지금 오디오를 돌려줄 수 있음. | **고침** |
| **LOW** | `app/.../ui/LyricsController.kt:661` | `aiAvailable`은 `startTiming` 때 한 번 계산된다. Deepgram만 있는 사용자가 곡을 재생해 오디오가 나중에 디스크에 완성돼도, 가사가 다시 로드되기 전에는 버튼이 계속 숨겨질 수 있다. | 처음 몇 초에 가사를 연 사용자는 AI 타이밍 버튼을 늦게 보거나 못 볼 수 있다. | 오디오 캐시 완료 이벤트에서 `aiAvailable`을 다시 계산하거나, 버튼을 열 때 실제 가용성을 재확인한다. 이번에는 재생 중 폴링을 피하려고 문서에만 남겼다. | 문서만 |

---

## 4. 건너뛰는 조건 · 흔한 상황 · 로그 여부

| 기능/단계 | 건너뛰는 조건 (코드) | 흔한 상황인가 | 건너뛸 때 로그 | 비고 |
|---|---|---|---|---|
| AI 타이밍 버튼 | Gemini 키 없음 + (Deepgram 키 없음 또는 `!wantsDeepgram` 또는 오디오 없음) (`LyricsAiGuard.kt:84-99`, `LyricsController.kt:671-679`) | Deepgram만 쓰는 한국어/연주곡 사용자, 오디오가 아직 없는 곡 | 없음(UI 숨김) | 이번에 고침. 숨김이 의도된 상태다. |
| AI 타이밍 — Deepgram 스킵 | `!wantsDeepgram(title, artist, texts)` (한국어/연주곡) (`LyricsTiming.kt:1414-1418`) | 한국어 곡 사용자에게 매우 흔함 | `Deepgram skipped (Korean/instrumental)` 있음 | 자동 경로도 같은 로그를 남긴다. |
| AI 타이밍 — 오디오 없음 | `VideoAudio.wav(...)` null (`LyricsTiming.kt:1389`) | 다운로드 곡, 캐시 미완성, 화면 꺼짐 | 없음 | Gemini 키가 있으면 영상 주소로 폴백한다. Deepgram만 있으면 조용히 끝난다. **문서에만 남김(로그 보강 후보)** |
| AI 타이밍 — Gemini 없음 | `!geminiKeyUsable` (`LyricsTranslation.kt:1309`) | Deepgram만 설정한 사용자 | `AI timing ... no answer` 없음 | 수동 요청은 이제 버튼부터 숨긴다. |

---

## 5. 이번 수정과 테스트

### 수정 파일

1. `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsAiGuard.kt`
   - `manualTimingAvailable(...)` 추가: Gemini 키, Deepgram 키, 오디오 가용성, 한국어/연주곡 스킵을 한 판정으로 묶었다.
2. `app/src/main/java/com/music/bitchord/playback/smart/VideoAudio.kt`
   - `audioReadyForTiming(videoId)` 추가: `wav`가 디코딩 전에 보는 캐시 조건(최근 WAV, 화면 켜짐, 완성된 렌디션)을 그대로 노출한다. 폴링 없이 캐시 상태만 읽는다.
3. `app/src/main/java/com/music/bitchord/ui/LyricsController.kt`
   - `startTiming`의 `aiAvailable`을 `manualTimingAvailable(...)`로 계산하도록 변경. 기존 주석도 실제 조건에 맞게 고쳤다.
4. `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsTimingUi.kt`
   - `aiAvailable`/`aiTimingActionEnabled` 설명을 새 조건에 맞게 갱신(동작 변경 없음).

### 테스트 파일

- `shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsAiGuardTest.kt`
  - `aHandTimingNeedsAnEngineThatCanAnswerForThisSong`
    - Deepgram만 + 영어 + 오디오 있음 → 노출
    - Deepgram만 + 한국어 → 숨김
    - Deepgram만 + 연주곡 → 숨김
    - Deepgram만 + 오디오 없음 → 숨김
    - Gemini만 + 한국어 + 오디오 없음 → 노출

### 테스트 결과

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 성공.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.LyricsAiGuardTest' --console=plain -q` — 성공.
- `./gradlew :sharedUi:jvmTest --tests 'com.music.bitchord.ui.player.LyricsControlPolicyTest' --console=plain -q` — 성공.

---

## 6. 미해결 — 기기 검증 필요

- `docs/audit/check-flow.md`의 HIGH 5건(다음 곡 준비 queueKey/가사 캐시/standby 버전 교체/다운로드 안전 우회/로컬 오판)은 재생 핵심 경로라 이번에 고치지 않았다. **폰 검증 필요**.
- `docs/AUDIT_FEATURES.md` #20의 설정 AI 토글 키 게이트, #24 인앱 업데이트 플래그.
- 검색/상세 오류 재시도, 고음질 재다운로드 무동작 안내, 다음 곡 SYNC 스킵 이유 로그, 알림 액션 한국어 하드코딩, 빈 다운로드 화면의 고음질 전체 행.
- AI 타이밍 `aiAvailable`의 재생 중 키/오디오 변화 반영(3장 LOW).

---

## 요약 (10줄 이내)

1. 수정 파일: `LyricsAiGuard.kt`, `VideoAudio.kt`, `LyricsController.kt`, `LyricsTimingUi.kt`(주석), 테스트 1개.
2. 핵심 변경: AI 타이밍 버튼을 "키 존재"가 아니라 실제 수동 경로(한국어/연주곡 스킵 + 오디오 가용성 + Gemini)로 노출.
3. 테스트: `:app:compileProdReleaseKotlin`, `:shared:jvmTest`(LyricsAiGuardTest), `:sharedUi:jvmTest`(LyricsControlPolicyTest) 모두 성공.
4. 미해결: check-flow HIGH 5건은 재생 핵심 경로라 `docs/audit/check-flow.md`에 남기고 폰 검증 필요.
5. 미해결: 오디오 없음 자동 스킵 로그, 검색/상세 재시도, 고음질 재다운로드 안내, AI 설정 토글 게이트는 이 문서 3장·4장·6장에 남김.
