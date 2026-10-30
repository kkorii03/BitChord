# 화면·설정 통합 검토 — integration-ui-settings

기준: `task/fix-1030` 계열 통합 트리(`89f9e3b8` 이후 오늘 병합분), 2026-10-31.
범위: `AppSettings`, `SettingsSheet`, `NowPlayingScreen`, `PlayerQueue`,
`LyricsController`, `MainActivity`, 앱/공유 문자열.
방법: `git diff 89f9e3b8..HEAD`로 오늘 변경 전체를 읽고, 여러 작업이 같은
상태·같은 함수를 다르게 건드린 지점을 코드로 추적했다. 배터리 규칙에 따라
새 폴링·타이머·delay 루프는 넣지 않았다. `.github/`, keys, 서명은 건드리지 않았다.

## 요약

| 심각도 | 파일:줄 | 무엇이 어긋났나 | 사용자 영향 | 수정 |
|---|---|---|---|---|
| HIGH | `MainActivity.kt:1269`, `:1831` | 비디오 라디오 작업이 before-audio 음원 전환 조건에 `videoRadioEnabledFor(song)`을 추가했지만, 그 효과의 key는 `(songId, preferMusicOnly)` 그대로였다. | 비디오 곡 재생 중 설정에서 비디오 라디오를 껐다 켜도 현재 곡의 전환 판단이 다음 곡까지 갱신되지 않는다. | **고침** |
| MEDIUM | `MainViewModel.kt:2314` | 검색 재시도가 `q.isEmpty()`이면 무조건 반환했다. 라이브러리 검색은 빈 검색어로 기기 폴더 전체를 보여 주므로, 그 오류/빈 상태의 다시 시도가 화면에 보이는데도 동작하지 않았다. | 로컬 음악 권한/스캔 오류에서 다시 시도 버튼이 먹통이 된다. | **고침** |
| LOW | `NowPlayingScreen.kt:177`, `PlayerQueue.kt:87`, `MainActivity.kt:56` | 서로 다른 브랜치가 같은 import를 각각 추가해 병합 트리에 중복 import가 남았다(`kotlin.math.abs`, `layout.width`). | 컴파일은 되지만 이후 병합 충돌과 unused-import 검사가 실제와 다르게 보인다. | **고침** |
| LOW | `AppSettings.kt:518`, `LyricsController.kt:270`, `SyncSafetyPolicy.kt:10` | 10-31에 `syncSafetyNextPrep` 기본값이 켜짐으로 바뀌었는데, 주변 KDoc은 여전히 "세 스위치 모두 기본 off", "first-frame 가사 경로는 기본 off"라고 말했다. | 코드와 설명이 반대라 다음 수정이 안전 장치를 잘못 가정하게 된다. | **고침(설명)** |

## 1. before-audio 음원 전환 효과의 key 누락 (HIGH)

- **증거**: `MainActivity.kt:1831`의 판정은 `preferMusicOnly && !party && !videoRadioEnabledFor(song)`이다.
  `videoRadioEnabledFor`는 `AppSettings.videoRadio`를 읽는다. 그런데
  `LaunchedEffect` key는 `player.song?.videoId, preferMusicOnly`뿐이었다.
- **상호작용**: 비디오 라디오 기능은 `SettingsSheet`와 `PlayerQueue` 두 화면에서
  같은 `AppSettings.videoRadio`를 토글한다. key에 그 값이 없으면 설정을 바꿔도
  현재 곡에 대한 효과가 다시 평가되지 않는다.
- **수정**: 셸에서 `AppSettings.videoRadio`를 수집하고, key를
  `autoMusicOnlyEffectKey(songId, preferMusicOnly, videoRadio)`로 바꿨다.
  helper는 `MainActivityHelpers.kt`에 두어 key 계약을 단위 테스트한다.
- **진리표(key)**

  | songId | preferMusicOnly | videoRadio | 수정 전 key | 수정 후 key |
  |---|---|---|---|---|
  | A | true | false | (A,true) | (A,true,false) |
  | A | true | true | (A,true) ← 변화 없음 | (A,true,true) |
  | A | false | * | (A,false) | (A,false,*) |
  | B | * | * | (B,*) | (B,*,*) |

- **회귀 테스트**: `AutoMusicOnlyEffectKeyTest`
  - `changingTheVideoRadioSwitchChangesTheEffectKey`
  - `theSongAndMusicOnlyPreferenceStillChangeTheEffectKey`
  - `anUnchangedDecisionKeepsTheSameKey`

## 2. 라이브러리 검색의 빈 검색어 재시도 (MEDIUM)

- **증거(수정 전)**: `retrySearch()`가 `val q = _query.value.trim(); if (q.isEmpty()) return`로
  시작했다. 그런데 `loadLibrarySongs()`는 검색어와 무관하게 기기 폴더를 다시 읽고,
  `SearchScreen`의 라이브러리 오류/빈 상태는 빈 검색어에서도 `retry` 버튼을 그린다.
- **수정**: `retrySearchAllowed(source, query)`를 추가해 라이브러리 소스만 빈 검색어
  재시도를 허용한다. `SearchSource.LIBRARY -> loadLibrarySongs()` 경로는 그대로다.
- **진리표**

  | source | query | 수정 전 재시도 | 수정 후 재시도 |
  |---|---|---|---|
  | LIBRARY | 빈 문자열/공백 | 안 함 | **함** |
  | YOUTUBE | 빈 문자열/공백 | 안 함 | 안 함 |
  | ADDONS | 빈 문자열/공백 | 안 함 | 안 함 |
  | * | 비어 있지 않음 | 함 | 함 |

- **회귀 테스트**: `SearchRetryBlankQueryTest` 3건.

## 3. 병합이 남긴 중복 import (LOW)

- `sharedUi/.../NowPlayingScreen.kt`의 `kotlin.math.abs` 2회
- `sharedUi/.../PlayerQueue.kt`의 `kotlin.math.abs` 2회
- `app/.../MainActivity.kt`의 `androidx.compose.foundation.layout.width` 2회
- 두 번째 줄만 제거했다. 동작 변경은 없다.
- **회귀 테스트**: `IntegrationImportHygieneTest`가 대상 파일에서 같은 import 줄이
  두 번 나오면 실패한다.

## 4. 안전 스위치 설명과 실제 기본값 불일치 (LOW)

- `syncSafetyNextPrep`은 10-31 사용자 요청(FIXLIST 99)으로 기본 on이 됐고,
  `AppSettingsImportValidationTest`도 on을 고정한다. 그러나 KDoc/주석은 기본 off를
  말하고 있었다.
- 기본값 자체는 바꾸지 않았다(사용자가 명시한 변경). 설명만 실제 상태에 맞췄다.
- 대상: `AppSettings.kt`, `LyricsController.kt`, `SyncSafetyPolicy.kt`.

## 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.AutoMusicOnlyEffectKeyTest' --tests 'com.music.bitchord.ui.SearchRetryBlankQueryTest' --tests 'com.music.bitchord.IntegrationImportHygieneTest' --tests 'com.music.bitchord.ui.SearchRetryCacheTest' --tests 'com.music.bitchord.ui.SearchEmptyPipelineTest' --tests 'com.music.bitchord.data.settings.AppSettingsImportValidationTest'` → 통과.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.SyncSafetyPolicyTest'` → 통과.

## 바꾼 파일

- `app/src/main/java/com/music/bitchord/MainActivity.kt`
- `app/src/main/java/com/music/bitchord/MainActivityHelpers.kt`
- `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt`
- `app/src/main/java/com/music/bitchord/ui/LyricsController.kt`
- `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/SyncSafetyPolicy.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt`
- `app/src/test/java/com/music/bitchord/AutoMusicOnlyEffectKeyTest.kt` (신규)
- `app/src/test/java/com/music/bitchord/ui/SearchRetryBlankQueryTest.kt` (신규)
- `app/src/test/java/com/music/bitchord/IntegrationImportHygieneTest.kt` (신규)
- `docs/review-1031/integration-ui-settings.md` (이 문서)

## 남은 문제

- `PlayerSidePanels.kt`의 `LyricsPanel` 호출부에는 새 `onChooseProvider`가 연결되지
  않아, 사이드 패널 레이아웃에서는 "가사 없음" 상태가 여전히 제공처 선택으로 바로
  이어지지 않는다. 이번 수정 범위(지정 파일) 밖이라 남긴다.
- `PlayerQueue`의 자동 재생 빈 상태는 여전히 로딩/실패/진짜 없음의 구분 없이
  "추천 곡을 찾지 못했습니다"를 보여 준다. 재생 레인의 `autoplayLoadJob` 상태
  노출이 먼저 필요하다.
- 전체 `:app:testProdReleaseUnitTest`의 기존 실패 22건
  (`ArtistNameMatchTest`, `LocaleStringsTest`, `ProviderLyricsTest`, `SourceFormatsTest`,
  `BluetoothAudioTrackerTest`)은 이번 변경 전에도 같은 클래스에서 실패하던 항목이다.
