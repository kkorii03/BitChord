# audit-lyrics-sync — 가사 찾기·표시·번역·싱크 감사

범위: 가사 찾기·표시·번역·싱크(`LyricsController`, `LyricsTiming`, 가사 제공처, `SyncTestMode`). 특히 저장된 가사 파일의 `@unknown` 처리, 가사 화면 성능, 싱크 가사 인식·되돌리기 경로를 읽었습니다. 정렬 수학(`TimingFit`, `LyricsTimeMap`, `LyricsWordAlign`의 맞춤 계산)은 바꾸지 않았습니다.

기준: 이 클론은 이력이 잘린(grafted) 저장소라 `ko-101` 태그가 없습니다(`git tag` 비어 있음). 그래서 태그 diff 대신 현재 코드와 기존 감사 문서(`docs/audit/check-sync-regress.md`, `docs/audit/audit-lyrics.md`)의 근거를 대조했습니다. HIGH는 고치고 회귀 테스트를 추가했으며, MEDIUM/LOW는 문서만 남겼습니다.

| 심각도 | 파일:줄 | 무엇이 고장 | 재현 경로 | 수정 |
|---|---|---|---|---|
| HIGH | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/FileLyricsOrigin.kt:35-61`, `app/src/main/java/com/music/bitchord/data/lyrics/EmbeddedLyrics.kt:98-118`, `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:349-365` | 저장된 가사 파일 헤더/라벨의 `@unknown` 표식을 실제 녹음 id로 읽습니다. `timingBelongsToAnotherRecording("unknown", 재생 id)`가 true가 되어, id가 없는 파일이 "다른 녹음"으로 분류됩니다. 곡 파일이면 foreign 측정 경로로 빠지고, `fileProviderKnown`도 true가 되어 `unknown-provider` 재검사 사유를 잃습니다. 새 헤더도 `@unknown`을 다시 쓸 수 있었습니다. | 다운로드 파일의 LRC/사이드카에 `[re:BitChord LRCLIB @unknown]`과 timed 가사를 넣고 곡으로 재생합니다. `lyrics-sync`에 `source=file:LRCLIB@unknown`, `base=measured`(foreign 경로)가 남고, id가 아예 없는 파일과 다르게 동작합니다. 기대: id 없음과 동일하게 foreign 아님 + 한 번 검증. | `FileLyricsOrigin.knownVideoId()`가 blank와 `unknown`(대소문자 무시) 표식을 모두 "id 없음"으로 정규화합니다. `EmbeddedLyrics`의 읽기와 `sourceHeader` 쓰기가 이 함수를 사용합니다. 회귀: `FileLyricsOriginTest`의 `at-unknown` 테스트, `EmbeddedLyricsTest`의 헤더 테스트. |
| HIGH | `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:961-986`, `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:971-988,1949-1956` | 다운로드 파일(정확히는 `fileTimingForeign`인 비디오 아님 행)의 자동 AI가 파일 소스 키 없이 `wantsAi`/`placeWithAi`를 호출했습니다. 답은 `textKey(lines)`(스트림 키)로 저장되지만 `refreshTiming`은 `audioSourceKey(videoId, file)` 키로 찾으므로 화면에 적용되지 않습니다. 다음 자동 패스는 답을 못 보고 다른 키로 다시 요청해 같은 곡에 엔진을 두 번 씁니다. | 헤더 id가 재생 id와 다른 다운로드 곡(또는 수정 전 `@unknown` 파일)을 재생하고 AI 싱크를 켭니다. 로그에 `AI timing ...` 답이 남아도 `base`가 `none`/`measured`에 머물고, 다음 재생·가사 전달에서 같은 요청이 다시 나갑니다. | `aiAhead`가 파일이면 `audioSourceKey(videoId, localUri != null)`을 계산해 `wantsAi`와 `placeWithAi`에 같이 넘깁니다. `hasAi`/`aiAlreadyFailed`도 같은 선택 인자를 받습니다. 회귀: `LyricsTimingAiKeyTest`(파일 키로 답을 보고, 스트림 키와 구분). |
| HIGH | `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:721-724,1055-1075`, `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:2029-2037` | AI 타이밍 버튼으로 답을 되돌릴 때 `startTiming(cancelAi = false)`가 호출되지만, 비디오/foreign 분기는 `cancelAi`를 보지 않았습니다. 곡 분기에는 "되돌리는 중에는 자동 검사가 다시 사지 않는다"는 가드가 이미 있었는데 비디오 분기만 빠져 있었습니다. 그래서 `clearAi` 직후 `aiAhead`/`autoAiIfNeeded`가 다시 요청해 답이 곧 돌아왔습니다. | AI 답이 적용된 비디오(`base=ai`)에서 AI 버튼을 다시 눌러 끕니다. `clearAi` 뒤 몇 초 안에 새 `AI timing` 요청이 나가고 `base=ai`가 다시 됩니다. 기대: `base=none`/`measured` 유지, 새 요청 없음. | 조건을 `videoTimingMayRun(adjustable, measure, measureNow, cancelAi, substituted)`로 뽑았습니다. 기존 항(adjustable·measure·measureNow)을 모두 유지하고 `cancelAi`와 `substituted`를 추가했습니다. 회귀: `LyricsTimingGuardTest`의 진리표. |
| HIGH | `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1544-1572`, `app/src/main/java/com/music/bitchord/data/lyrics/LyricsOverrideStore.kt:58-60`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsOverrideFile.kt:103-124` (호출부 `app/src/main/java/com/music/bitchord/download/Downloads.kt:978,988,1363`는 수정하지 않음) | 다운로드 파일이 교체/삭제될 때 `resetSongCheck`가 AI 답만 지우고 `syncCheckVersion`과 RECHECK override를 남겼습니다. `recheckDownloaded`는 현재 version을 보고 `NONE`을 반환하고, 화면은 override를 파일 태그보다 먼저 읽으므로 새 파일이 옛 가사/싱크를 계속 씁니다. 기존 `check-sync-regress.md` 5번과 같은 HIGH입니다. | 다운로드 곡을 재검사해 version 1과 RECHECK override가 생긴 뒤, 같은 id의 파일을 재다운로드/HQ 교체합니다. 재생하면 `lyrics-sync`에 `recheck=none`이 남고 옛 override 가사가 표시됩니다. 기대: 새 파일에서 `unverified`로 다시 검사. | `resetSongCheck`가 `syncCheckVersion=0`으로 되돌리고, `removeRecheck`로 자동 override만 제거합니다(USER override는 보존). `recheckTokens`도 지웁니다. 회귀: `LyricsTimingResetSongCheckTest`, `LyricsOverrideFileEdgeTest`의 kind별 삭제 테스트. |
| MEDIUM | `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:55,58,763-775` | 가사 화면 성능/배터리: 비디오·foreign 파일의 재측정 루프가 8초마다 최대 15분 동안 깨어납니다. `worthMeasuring`이 true인 회차마다 크로마 정렬을 IO 풀에서 돌리고, 새 오디오가 없어도 루프는 계속 돕니다. `loadLyrics`가 화면 표시와 무관하게 모든 곡에서 호출되므로 가사 화면을 닫아도 돕니다. "주기 폴링 금지" 원칙과 충돌합니다. | 비디오 가사를 연 채 15분 두고 CPU/로그를 봅니다. 8초 간격 `worthMeasuring`/`measure` 시도와 wake-up이 남습니다. | 미수정. 오디오 증가 이벤트(`AudioCache`의 바이트 진행)를 만들어 그 이벤트에서만 재측정해야 하며, 이 레인의 범위(`AudioCache`/`PlaybackService`)를 넘습니다. |
| MEDIUM | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:743-752` | 캐시 없는 곡마다 활성 제공처를 한꺼번에 시작합니다. 1순위가 먼저 답해도 이미 시작한 요청이 소켓/라디오를 깨워 배터리를 씁니다. | 제공처를 여러 개 켜고 새 곡을 재생합니다. 시작 로그에 여러 제공처가 동시에 `FETCHING`으로 찍힙니다. | 미수정. 상위 2~3개 sliding window가 필요하며, 가사 선택 순서/응답 동작을 바꾸므로 이번 "확실한 버그" 범위에서 제외했습니다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:194-213` | `lyricsFor` 키가 `videoId`, 오디오 소스(file/stream), 켜진 제공처 집합만 봅니다. 재생 중 제공처 **순서**나 "음절 우선"을 바꿔도 현재 곡은 다시 조회하지 않습니다. | 곡을 재생한 뒤 설정에서 제공처 순서를 바꿉니다. 현재 곡의 `lyrics-sync`/화면 제공처가 그대로입니다. | 미수정. 키에 `lyricsSourceOrder`와 `prioritizeSyllableSync`를 넣어야 하며, 기존 `audit-lyrics.md` 4번과 중복입니다. |
| MEDIUM | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsAiGuard.kt:65,76` | 한국어 곡(제목/가수/가사에 한글)은 Deepgram/Whisper/곡 검증에서 모두 제외됩니다. Apple 계열이 단어 타이밍을 주지 않으면 한국어 곡은 줄 단위에 머뭅니다. | 한국어 제목·가사 곡을 재생하고 AI 싱크를 켭니다. `Deepgram skipped (... Korean)` 로그만 남고 AI 답이 없습니다. | 미수정. Whisper만 허용하는 별도 정책이 필요하며, "한국어는 not enough 5/5"라는 기존 주석/실패 기록을 약화하지 않기 위해 이번 범위에서는 동작을 바꾸지 않았습니다. |
| MEDIUM | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt:900,918-924` | 번역문의 단어 타이밍이 `LyricWord` 하나로 붕괴합니다. 번역 보기에서는 원문의 단어별 하이라이트가 사라지고 줄 전체 스윕만 남습니다. | 번역을 켠 뒤 단어 싱크가 있는 곡을 봅니다. 원문은 단어별로 차오르지만 번역문은 한 덩어리로만 차오릅니다. | 미수정. 번역 토큰에 원문 word span을 비례 배분해야 하며, 정렬 수학을 건드리지 말라는 지시에 따라 문서만 남겼습니다. |
| LOW | `app/src/main/java/com/music/bitchord/playback/smart/SyncTestMode.kt:39-62` | `lastSignature`가 곡 전환 때 초기화되지 않습니다. 서명에 videoId가 들어 있어 다른 곡에는 영향이 없지만, 같은 곡을 같은 타이밍으로 다시 재생하면 sync-dump가 다시 기록되지 않습니다. | sync-test 플래그를 켜고 같은 곡을 멈췄다 다시 재생합니다. 두 번째 재생의 `sync-dump` 블록이 없습니다. | 미수정. 테스트 하네스가 한 곡당 한 블록을 기대한다면 문제이고, 재생 단위를 원하면 `videoId` 변경/재생 시작에서 `lastSignature`를 지워야 합니다. |

## 수정한 파일

- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/FileLyricsOrigin.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsOverrideFile.kt`
- `app/src/main/java/com/music/bitchord/data/lyrics/EmbeddedLyrics.kt`
- `app/src/main/java/com/music/bitchord/data/lyrics/LyricsOverrideStore.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt`
- `app/src/main/java/com/music/bitchord/ui/LyricsController.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/FileLyricsOriginTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsOverrideFileEdgeTest.kt`
- `app/src/test/java/com/music/bitchord/EmbeddedLyricsTest.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/LyricsTimingAiKeyTest.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/LyricsTimingGuardTest.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/LyricsTimingResetSongCheckTest.kt`
- `docs/audit/audit-lyrics-sync.md`

## 검증

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.*' --console=plain -q` — 통과
- `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.playback.smart.*' --tests 'com.music.bitchord.EmbeddedLyricsTest' --console=plain -q` — 통과
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과

## 조건 변경 진리표 (HIGH 3)

기존 비디오/foreign 분기 진입 조건:

`adjustable && measure && measureNow`

새 조건(`videoTimingMayRun`):

`adjustable && measure && measureNow && cancelAi && !substituted`

즉, 기존에 진입하던 모든 경우는 그대로 진입하고, 추가로 `cancelAi=false`(AI 되돌리기)와 `substituted=true`(대체 스트림)일 때만 막습니다. 기존 항은 하나도 삭제하지 않았습니다.
