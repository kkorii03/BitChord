# review-1031 — 가사 번역·로마자 엄격 검토

범위: Gemini/Google 번역 전환, 키 없음 처리, 줄 맞춤, 캐시, 언어 감지(한국어 곡 번역 안 함), 실패 안내.
기준: `dsq/deep-translation-1031` (867ca07). 아래 줄 번호는 수정 후 파일 기준이다.

## 요약

| 심각도 | 건수 | 상태 |
|---|---|---|
| HIGH | 0 | — |
| MEDIUM | 5 | 3건 수정, 2건 문서화 |
| LOW | 4 | 1건 수정, 3건 문서화 |

수정한 것

- M1 中: 중국어 간체↔번체를 같은 언어로 판정해 번역을 건너뛰던 문제 (`LyricsTranslation.sameLanguage`).
- M2 中: 플레이어와 백그라운드/잠금화면이 서로 다른 `tl`을 써서 캐시가 갈라지던 문제 (`LocaleTags.translationTarget`, `TranslationAhead.targetLanguage`).
- M3 中: 줄 시작만 비교해 단어 싱크가 나중에 와도 번역문이 옛 타이밍으로 남던 문제 (`sameTiming`).
- L1: 자동 번역 실패가 로그 없이 사라지던 경로 (`TranslationAhead.translate`).

---

## M1. 중국어 간체/번체가 "이미 같은 언어"로 판정된다 — 수정

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt:942`
- 증거(수정 전): `sameLanguage`가 `canonicalLanguage`(기본 언어)만 비교해 `zh-CN`과 `zh-TW`가 같다고 답했다. `translate`는 이 답을 받으면 엔진 결과를 버리고 `Result.SameLanguage`를 돌려준다(`:264` 이후).
- 사용자 영향: 앱 언어가 번체 중국어인데 가사가 간체인 곡, 또는 그 반대에서 번역 버튼이 "이미 %s 가사입니다"만 띄우고 문자 변환을 하지 않는다. `LocaleTags.sameLanguageScriptAware`가 이미 있었지만 번역 경로에서 쓰이지 않았다.
- 흔한 정도: 중국어 사용자에게는 항상. 전체 곡 중에서는 낮음(중국어권 한정).
- 수정: 중국어 태그가 하나라도 있으면 `LocaleTags.sameLanguageScriptAware`로 스크립트까지 비교하고, 나머지는 기존 기본 언어 비교를 유지.
- 테스트: `shared/src/jvmTest/.../LyricsTranslationLanguageTest.kt`.

## M2. 플레이어/프리페치/잠금화면의 번역 목적지가 갈라진다 — 수정

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/LocaleTags.kt:90`, `app/src/main/java/com/music/bitchord/playback/TranslationAhead.kt:81`, `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt`(목적지 계산부)
- 증거(수정 전): 플레이어는 앱 로캘의 스크립트를 살려 `zh-TW`/`zh-CN`을 요청했지만(`PlayerLyrics`의 `translationWireTarget` 경로), `TranslationAhead.targetLanguage`는 `Locale.language`만 써서 `zh`를 돌려줬다. `zh`는 Google에서 간체로 해석된다.
- 사용자 영향: 번체 중국어 앱에서 다음 곡 프리페치와 잠금화면이 `zh`(간체)로 번역/캐시하고, 플레이어는 `zh-TW`를 찾으므로 캐시 미스. 다운로드 곡은 오프라인에서 `zh-TW` 번역이 없어 실패한다. 같은 곡을 화면과 잠금화면에서 다른 문자로 볼 수 있다.
- 흔한 정도: 중국어 앱 + 프리페치/오프라인 조합에서 항상.
- 수정: `LocaleTags.translationTarget(preferred, appTag)` 하나로 통일하고 세 진입점이 모두 호출. 명시 설정은 그대로, 빈 설정은 앱 언어(중국어는 스크립트 유지).
- 테스트: `shared/src/jvmTest/.../LocaleTagsTranslationTargetTest.kt`.

## M3. 단어 싱크가 나중에 와도 번역문이 다시 맞춰지지 않는다 — 수정

- 파일: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt:2878`, 사용처 `:3371`
- 증거(수정 전): `sameTiming`이 `timeMs`만 비교했다.
  ```
  made.size == source.size && made.indices.all { made[it].timeMs == source[it].timeMs }
  ```
  단어 타이밍(`words`)·`sungUntilMs`·배경 보컬 타이밍이 바뀌어도 줄 시작이 같으면 `true`였다.
- 사용자 영향: 줄 단위 가사가 먼저 표시된 뒤 단어 싱크/AI 타이밍이 같은 시작 시각으로 도착하면, 번역문은 옛 원문 엔벨로프(`timingSource`)를 계속 투영해 단어 하이라이트가 어긋난다. "같은 단어, 새 타이밍"이라는 재번역 효과가 절반만 동작했다.
- 흔한 정도: 단어 싱크가 늦게 합류하는 곡(자동 보정·AI 보정·제공처 교체)에서 흔함.
- 수정: `timeMs`, `sungUntilMs`, 원문 단어(`timingSource.words`), 배경 보컬의 같은 필드를 함께 비교. 번역문 자신의 단어(번역 텍스트)와 원문 단어를 혼동하지 않도록 `timingSource`를 사용.
- 테스트: `sharedUi/src/jvmTest/.../LyricsTranslationRetimeTest.kt`.

## M4. 느리거나 끊긴 망에서 수동 번역이 최대 수 분간 로딩에 머문다 — 미수정

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt:172-182`, `:564-625`, UI `PlayerLyrics.kt:3314-3347`
- 증거: Gemini 모델 5개를 순서대로 시도하고 각 호출 `callTimeout(40s)`; 모두 시간 초과/실패하면 Google `callTimeout(12s)`로 폴백한다. 전체 요청 예산이나 사용자 취소 경로가 없다.
- 사용자 영향: 망이 끊기지 않고 매우 느리기만 하면(프록시·혼잡) 버튼이 `Loading`으로 길게 남는다. 실패 안내는 그 뒤에야 온다.
- 흔한 정도: 느린 망/프록시 사용 시 간헐적. 완전 오프라인은 connect timeout으로 더 빨리 끝난다.
- 남긴 이유: 긴 곡의 Gemini 응답을 자르지 않으면서 전체 예산을 정하려면 provider별 타임아웃 정책과 mock-server 테스트가 필요하다. 지금 임의로 줄이면 정상적으로 느린 긴 곡이 실패로 바뀔 수 있다.
- 필요한 작업: `translate`/`romanize`에 전체 deadline(예: 45초)과 취소 UI를 넣고, deadline 초과 시 kept 캐시로 폴백. MockWebServer로 지연 응답 테스트.

## M5. 다운로드 곡의 수동 번역은 오프라인용으로 저장되지 않는다 — 미수정

- 파일: `app/src/main/java/com/music/bitchord/ui/player/AndroidPlayerHost.kt:156-168`, `LyricsTranslation.kt:241-260`(`keep` 인자)
- 증거: 플레이어의 `translateLyrics`가 `LyricsTranslation.translate(trackId, lines, targetLanguageTag)`만 호출하고 `keep`을 넘기지 않는다. `keep=true`는 프리페치/다운로드 완료 경로에서만 쓰인다.
- 사용자 영향: "다운로드 번역 보관"과 "다음 곡 번역 미리 받기"를 모두 끈 사용자가 다운로드 곡을 온라인에서 수동 번역해도 `keepDir`에 남지 않아, 다음 오프라인 재생에서 다시 실패한다.
- 흔한 정도: 다운로드 곡 + 두 설정 끔 + 수동 번역 조합. 좁지만 재현 가능.
- 남긴 이유: `PlayerHost.translateLyrics` 시그니처를 바꾸면 Android/Desktop 두 구현과 공유 UI 호출부를 함께 고쳐야 해서 이번 변경 범위를 넘는다.
- 필요한 작업: 호스트에 `keep`(또는 곡의 `localUri`/다운로드 여부)를 넘기고, UI가 `song.localUri != null && keepDownloadTranslation`을 전달.

---

## LOW

1. **캐시된 Gemini 번역은 Gemini를 꺼도 계속 쓰인다** — `LyricsTranslation.kt:267-276`. 다운로드 곡의 kept 캐시에 Gemini 결과가 있으면 엔진을 Google로 바꿔도 kept를 그대로 쓴다. 오프라인 동작을 위한 의도적 폴백이지만 상태 줄에는 `Gemini`로 표시된다. 엔진 전환을 엄격히 지키려면 kept를 엔진별로 나눠야 한다.
2. **자동 번역 실패의 흔적 없음** — 수정: `TranslationAhead.kt`의 `translate`가 `Unavailable`/타임아웃을 로그로 남긴다. 이전에는 예외만 기록되어 실패가 조용히 사라졌다.
3. **긴 곡 Gemini 요청은 배치 상한이 없다** — `LyricsTranslation.kt:505-560`. Google 경로만 `MAX_BATCH_CHARS`로 나누고 Gemini는 전곡을 한 번에 보낸다. `maxOutputTokens`도 지정하지 않아 아주 긴 곡은 잘린 응답→Google 폴백이 될 수 있다(사용자에게는 정상 번역으로 보임).
4. **다운로드 번역 보관 설정과 프리페치 보관이 별개** — `TranslationAhead.kt:185-212`. 다운로드 파일 분기에서 `keep = true`가 설정과 무관하게 쓰인다. 사용자가 보관을 꺼도 프리페치가 파일에 남길 수 있다.

---

## 흔한 상황별 실제 경로

| 상황 | 진입점 → 처리 | 결과 | 비고 |
|---|---|---|---|
| 커버 영상 | 플레이어 버튼/자동 → `AndroidPlayerHost.translateLyrics` → `LyricsTranslation.translate` | 가사 텍스트 기준 슬롯 번역, 타이밍은 원문 투영 | 커버 여부와 무관하게 동작 |
| 비디오 노래 | AI 받아쓰기(`AiLyrics`)가 만든 줄 → 같은 번역 경로 | 동작 | 받아쓰기 실패 시 번역할 가사 없음 |
| 셔플 | 곡 전환 이벤트 → `TranslationAhead.nextTrack/trackStarted` | 곡별 캐시 키라 이전 곡 번역을 재사용하지 않음 | 정상 |
| 앱 재시작 | 메모리 캐시 소실 → `readCache`/kept 디스크 | 비다운로드 곡도 `keepDir`에 남아 재사용 | M2로 중국어 키 일치 |
| 화면 꺼짐 | 잠금화면 `LockScreenLyricsActivity` → `TranslationAhead.targetLanguage` → `translate` | 캐시 우선, 실패 시 번역 없이 원문 | M2 수정으로 플레이어와 같은 목적지 |
| 백그라운드 | 서비스가 `trackStarted` → `TranslationAhead` | 이벤트 기반, 폴링 없음 | 12초 1회 지연 후 시작 |
| 프록시 켬 | OkHttp 기본 프록시 → Gemini 실패 시 Google 폴백 | 느리면 M4 로딩 지속 | 캐시가 있으면 즉시 |
| 다운로드 곡 | 다운로드 완료 `TranslationAhead.downloaded` + 재생 시 프리페치(`keep=true`) | 온라인에서 만든 번역을 오프라인 재사용 | 수동만 한 경우 M5 |
| 느린/끊긴 망 | `askGemini` 모델 루프 → Google 폴백 | 실패 안내는 최종 실패 후 | M4 |
| Android 14+ | 포그라운드 서비스에서 시작, 별도 권한 없음 | 동작 | 번역 자체에 새 제약 없음 |

## 성적표 숫자와 이 영역의 관계

- **다음 곡 싱크 준비 29%**: 번역 프리페치는 `TranslationAheadPolicy.Plan.FULL`일 때만(비과금망 + `heavyWorkAllowed`) 12초 뒤 시작한다(`PlaybackService.kt:10051-10070`). 번역은 싱크 준비의 하위 단계라 이 숫자를 직접 만들지 않는다. 다만 FULL이 아닐 때 번역도 함께 미뤄지므로 체감상 "다음 곡 준비 안 됨"에 기여한다.
- **무손실 확인 후 실제 무손실 재생 4%**: 번역/로마자와 무관.
- **자동 재생 보충 성공 24%**: 번역과 무관. 번역은 곡이 이미 정해진 뒤에만 돈다.
- **사용자 조작 없는 백그라운드 시작 11회**: 번역은 시작을 만들지 않는다. 서비스가 시작한 뒤의 후속 작업일 뿐이다.
- **다운로드·고음질 보관함 실패 140회**: 번역 보관은 다운로드 commit 이후 별도 코루틴에서 `filesDir`에 최대 32MiB로만 쓰고(`TranslationAhead.downloaded`, `MAX_KEPT_BYTES`), 다운로드 성공 여부를 막지 않는다. 140회의 직접 원인은 다운로드/고음질 파이프라인이며, 번역이 기여할 수 있는 것은 저장 공간 고갈 정도다. 이번 수정은 그 경로를 더 쓰지 않는다.

## 테스트

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.LyricsTranslationLanguageTest' --tests 'com.music.bitchord.data.LocaleTagsTranslationTargetTest'`
- `./gradlew :sharedUi:jvmTest --tests 'com.music.bitchord.ui.player.LyricsTranslationRetimeTest'`
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q`
