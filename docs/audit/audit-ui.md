# UI·사용성 감사 — audit-ui

기준: 2026-10-26, 현재 체크아웃. 코드는 읽기만 했고 한 줄도 수정하지 않았다.
빌드·기기 실행 없이 `app/src/main/res/values*`, `sharedUi/src/commonMain/composeResources/values*`,
`app/src/main/java/com/music/bitchord/ui`, `sharedUi/src/jvmSharedMain/.../ui`를 좁게 읽었다.
사용자 우선순위(올바른 곡 → 가사 싱크 → 안정성 → 배터리 → 속도 → 고음질 → UI)에 맞춰
UI가 직접 막거나 흐리는 것부터 점수를 매겼다.

표시: `[버그]` 실제로 틀린 동작, `[위험]` 접근성·널·경쟁 상태, `[낭비]` 배터리·네트워크,
`[빠진 기능]` 사용자가 원할 만한 것. `추측`은 코드로 확정할 수 없는 부분, `알려짐`은
이미 docs/에 적힌 문제다.

---

## 1. [위험] 재생·볼륨·가사 오프셋 슬라이더가 TalkBack에 아예 보이지 않는다 — 8

**근거**
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ThinSlider.kt:141-149`
  ```
      .height(activeHeight + 22.dp)
      .pointerInput(Unit) {
  ```
  `ThinSlider`에는 `semantics`, `progressBarRangeInfo`, `setProgress`가 하나도 없다.
- 호출부: `PlayerControls.kt:222`(재생 위치), `PlayerControls.kt:544`(볼륨),
  `LyricsOffsetSheet.kt:284`(가사 오프셋).

**증상**
TalkBack 사용자는 재생 바를 "진행률"로 읽지 못하고, 두 손가락 스와이프로도 값을 바꿀 수
없다. 가사 싱크를 0.2초 단위로 맞추려는 사용자가 가장 많이 만지는 컨트롤이 접근성
트리에 존재하지 않는다.

**고치는 방법**
`ThinSlider`에 `Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f); setProgress { ... } }`를
추가하고, 최소한 `stateDescription`(예: "1분 23초 / 3분 45초")을 넘겨받게 한다. 재생 바와
볼륨은 `PlayerControls`에서, 가사 오프셋은 `LyricsOffsetSheet`에서 각각 값/설명을 주입한다.

**위험도** 중간 · **크기** M · **우선순위** 8

---

## 2. [버그] 오류 화면에 영어 예외 원문이 그대로 노출된다 — 8

**근거**
- `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:3836-3841`
  ```
  private fun Throwable.friendly(): String = when {
      ...
      else -> message ?: text(R.string.something_went_wrong)
  ```
  `resolve host`·`401`·`403`만 한국어로 바꾸고, 타임아웃·429·5xx·SSL·프록시 거부 등은
  `message`를 그대로 돌려준다.
- `app/src/main/java/com/music/bitchord/ui/screens/SpotifyLibraryScreen.kt:75-77`
  ```
  runCatching { SpotifyLibrary.playlists() }
      .onSuccess { playlists = it }
      .onFailure { error = it.message }
  ```
- `app/src/main/java/com/music/bitchord/download/Downloads.kt:1787-1790`
  ```
  private fun Exception.friendly(): String = when {
      ...
      else -> "다운로드 실패 — 연결 상태를 확인하십시오"
  ```

**증상**
홈·검색·상세·Spotify 라이브러리·다운로드 관리자에 `java.net.SocketTimeoutException`,
`HTTP 429`, `Unsupported MIME type audio/webm` 같은 영어 기술 문구가 한국어 UI 한가운데
표시된다. 사용자는 "내가 뭘 해야 하는지"를 알 수 없고, 앱이 고장 난 것으로 읽는다.

**고치는 방법**
`MainViewModel.friendly()`를 예외 종류별로 확장한다(Timeout/UnknownHost/SSL/429/5xx/
프록시 연결 실패 → 각각 `R.string.no_internet_connection`, `youtube_request_rejected`,
새 `server_busy`, `proxy_unreachable` 등). `SpotifyLibraryScreen`은 `it.message` 대신
같은 매퍼를 쓰고, `Downloads.friendly()`는 사용자용 요약만 반환하며 원문은
`DebugLog`/`TrackLog`로만 남긴다. `DownloadManagerSheet.kt:389`의 `progress.reason`도
같은 요약을 쓰게 한다.

**위험도** 낮음 · **크기** M · **우선순위** 8

---

## 3. [버그] 앨범·검색·Spotify 오류 화면에 "다시 시도"가 없다 — 7

**근거**
- `app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt:788`
  ```
  } else {
      item { MessageState(state.message) }
  }
  ```
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt:373`
  ```
  results is UiState.Error -> item { MessageState(results.message) }
  ```
  `:532`의 라이브러리 검색 오류도 같다.
- `app/src/main/java/com/music/bitchord/ui/screens/SpotifyLibraryScreen.kt:130-136`
  ```
  error?.let { message ->
      item {
          Text(
  ```

**증상**
홈·탐색·보관함·기록은 오류에 `retry` 버튼이 있는데, 앨범/재생목록 상세와 검색 결과,
Spotify 라이브러리는 메시지만 남는다. 일시적인 네트워크 오류에서 사용자는 뒤로 나갔다
다시 들어오는 것 말고 할 수 있는 게 없다.

**고치는 방법**
`DetailScreen`에 `onRetry: () -> Unit`을 추가해 `MainActivity`의 상세 재로드 경로를 넘기고,
`MessageState(state.message, actionLabel = stringResource(R.string.retry), onAction = onRetry)`로
바꾼다. `SearchScreen`은 `onRetry = { submitSearch(query) }`(또는 현재 필터 재요청)를,
`SpotifyLibraryScreen`은 `reload` 람다를 만들어 `LaunchedEffect` 밖에서도 다시 부를 수 있게 한다.

**위험도** 낮음 · **크기** S · **우선순위** 7

---

## 4. [위험] 주요 아이콘 버튼의 터치 영역이 48dp보다 작다 — 7

**근거**
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt:728-733`
  ```
  Box(
      modifier = Modifier
          .size(34.dp)
  ```
  좋아요·가사·메뉴 등 `CircleGlyph` 공통.
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt:1499-1504`
  ```
  Box(
      modifier = Modifier
          .size(34.dp)
  ```
  번역·로마자 토글도 34dp.
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt:808-812`
  대기열 삭제 32dp, `:754-759` 드래그 핸들 20dp.
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SearchField.kt:122-126`
  검색 지우기 28dp.

**증상**
S24 Ultra처럼 넓은 화면에서 한 손으로 번역·좋아요·대기열 삭제·검색 지우기를 누르면
자주 빗나간다. 특히 드래그 핸들은 20dp라 대기열 순서 변경이 사실상 두 손을 요구한다.

**고치는 방법**
시각 크기는 그대로 두고 바깥 Box를 48dp로 키우거나
`Modifier.minimumInteractiveComponentSize()`를 붙인다. 대상은 `CircleGlyph`,
`TranslationToggleButton`, `RomanizationToggleButton`, `InlineQueueRow`의 삭제/핸들,
`SearchField`의 clear 버튼. 드래그 핸들은 아이콘 크기가 아니라 행 전체 높이의
터치 영역을 갖게 한다.

**위험도** 낮음 · **크기** S · **우선순위** 7

---

## 5. [빠진 기능] 첫 실행 온보딩에 프록시 설정 진입점이 없다 — 7

**근거**
- `app/src/main/java/com/music/bitchord/ui/onboarding/OnboardingScreen.kt:281-298`
  ```
  PillButton(
      label = stringResource(R.string.onboarding_sign_in_google),
      onClick = onSignIn,
  ```
  온보딩은 이름 → Google 로그인/건너뛰기 두 페이지뿐이다.
- `app/src/main/java/com/music/bitchord/ui/screens/ProxySettingsSection.kt:68`
  프록시 UI는 `ProxySettingsSection()`으로, 실제로는 계정/연동 설정 안에만 있다.

**증상**
한국에서 비프리미엄 계정으로 `music.youtube.com`이 막히는 사용자는 첫 화면부터
YouTube 폴백/오류를 본다. 도쿄 프록시를 쓰는 사용자도 온보딩에서는 그 존재를 알 수
없어 설정 → 계정 및 연동 → 네트워크 프록시까지 직접 찾아가야 한다. **추측**: 신규
설치 사용자는 이 경로를 못 찾아 "앱이 안 된다"고 판단할 가능성이 높다.

**고치는 방법**
온보딩에 "네트워크(한국)" 페이지를 추가하거나, 로그인 페이지 하단에
"프록시 설정" 텍스트 버튼을 넣어 `ProxySettingsSection`을 담은 다이얼로그를 연다.
최소한 `onboarding_proxy_hint` 문구와 "나중에 설정에서" 안내를 추가한다.

**위험도** 중간 · **크기** M · **우선순위** 7

---

## 6. [버그] 검색 결과의 종류 라벨이 영어 enum 이름으로 표시된다 — 6

**근거**
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt:1119-1123`
  ```
      text = appleCardKind(item.type)?.let { appleSubtitleText(item.subtitle, it) }
          ?: item.subtitle.ifBlank {
              item.type.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }
  ```

**증상**
검색 결과의 아티스트/앨범/재생목록 행에 부제가 비어 있으면 한국어 UI에서도
`Album`, `Playlist`, `Artist`가 그대로 나온다. Apple Music 한국어의 "앨범/재생목록/
아티스트"와 어긋난다.

**고치는 방법**
`BrowseRow`에서 `item.type.name` 대신 `when (item.type) { ALBUM -> stringResource(R.string.album); PLAYLIST -> ...; ARTIST -> ...; OTHER -> "" }`를
쓴다. 이미 `DetailScreen.localizedLabel()`과 `BrowseActionsSheet.localizedNoun()`에
같은 매핑이 있으니 공통 함수로 올려 재사용한다.

**위험도** 낮음 · **크기** S · **우선순위** 6

---

## 7. [빠진 기능] 가사가 없을 때 가사 화면이 막다른 길이다 — 6

**근거**
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt:2140-2146`
  ```
  Box(empty, contentAlignment = Alignment.Center) {
      Text(
          text = stringResource(Res.string.no_lyrics_for_track),
  ```
- `PlayerLyrics.kt:514-522`의 `LyricsStatusWithChange`도 "가사를 찾을 수 없음"일 때는
  `onStatusClick`을 null로 두어 제공처 선택을 열지 않는다.

**증상**
자동 조회가 실패하면 전체 가사 패널에는 "이 노래에는 가사가 없습니다." 한 줄만 남는다.
재시도·제공처 직접 선택·가사 오프셋으로 갈 버튼이 없어, 사용자는 플레이어 메뉴를
뒤져야 한다. 가사 싱크가 2순위인 사용자에게 가장 답답한 빈 상태다.

**고치는 방법**
`LyricsPanel`의 빈 상태에 `onChooseProvider`와 `onRetry` 콜백을 추가하고
`NowPlayingScreen`에서 `LyricsProviderSheet`/`loadLyrics`를 연결한다. 버튼 라벨은
`choose_lyrics_provider`와 `retry`를 재사용한다. `LyricsStatusWithChange`도
"없음" 상태에서 제공처 선택을 열 수 있게 `onStatusClick`을 넘긴다.

**위험도** 낮음 · **크기** S · **우선순위** 6

---

## 8. [버그] 오디오 파이프라인 화면이 한국어·영어·enum 이름을 섞어 보여 준다 — 5

**근거**
- `app/src/main/java/com/music/bitchord/ui/components/AudioPipelineDialog.kt:369-373`
  ```
  val eqPresetText = if (eqEnabled) {
      eqPreset.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
  } else {
  ```
- `AudioPipelineDialog.kt:118`
  ```
  outputExact -> "Yes${outputExactDetail?.let { " ($it)" }.orEmpty()}"
  ```
- `AudioPipelineDialog.kt:471`
  ```
  PipelineRow("경로", outputStatus.routeKind.name)
  ```
  `pipeline_pcm_data_rate`도 `translatable="false"`다(`app/src/main/res/values/strings.xml:512`).

**증상**
한국어 UI에서 EQ 프리셋이 `bass boost`, 비트 퍼펙트가 `Yes`, 경로가 `BLUETOOTH`,
행 이름이 `PCM Data Rate`로 보인다. 반대로 코드에 박힌 "패스스루", "리샘플링됨",
"코덱 샘플레이트"는 다른 언어 사용자에게 한국어로 보인다.

**고치는 방법**
`AudioPipelineDialog`의 하드코딩 문자열을 전부 `strings.xml`로 옮기고,
`EqualizerPreset.localizedLabel()`을 `EqualizerScreen` 밖에서도 쓸 수 있게 공개한다.
`Flat`은 `R.string.off` 또는 새 `eq_preset_flat`, `Yes`는 `R.string.yes`,
`routeKind`/`transportType`은 `when`으로 한국어 라벨을 준다. `pipeline_pcm_data_rate`의
`translatable="false"`를 제거하고 `values-ko`를 추가한다.

**위험도** 낮음 · **크기** M · **우선순위** 5

---

## 9. [버그] Spotify 가져오기 언어 라벨이 한국어로 번역되지 않는다 — 5

**근거**
- `app/src/main/res/values/strings.xml:1222-1223`
  ```
  <string name="spotify_import_language_preference" translatable="false">Language preference</string>
  <string name="spotify_import_language_hint" translatable="false">e.g. Punjabi, Marathi, Korean…</string>
  ```
- 사용처: `app/src/main/java/com/music/bitchord/ui/components/SpotifyImportAlert.kt:218`, `:259`.

**증상**
한국어 앱에서 Spotify 재생목록 가져오기 화면에 "Language preference"와
"e.g. Punjabi, Marathi, Korean…"이 영어로 나온다. 한국 사용자에게는 예시 언어도
Punjabi/Marathi라 맥락이 맞지 않는다.

**고치는 방법**
두 문자열의 `translatable="false"`를 제거하고 `values-ko/strings.xml`에
"언어 우선순위", "예: 한국어, 영어, 일본어…"를 추가한다. 언어 칩 자체는 이미
`R.string.spotify_language_*`로 번역되어 있으므로 라벨만 고치면 된다.

**위험도** 낮음 · **크기** S · **우선순위** 5

---

## 10. [버그] 가사 오프셋 값이 한국어 UI에서 `+0.5 s`로 표시된다 — 5

**근거**
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsOffsetSheet.kt:625-629`
  ```
  private fun formatOffset(offsetMs: Long): String = String.format(
      Locale.getDefault(),
      "%+.1f s",
  ```

**증상**
가사 싱크 조절 드로어의 큰 숫자가 `+0.5 s`, `-1.2 s`처럼 영어 단위로 나온다.
같은 화면의 설명과 상태 줄은 "초"를 쓰므로(`lyrics_timing_seconds` = `%1$s초`)
단위가 섞인다.

**고치는 방법**
`formatOffset`이 `stringResource(Res.string.lyrics_timing_seconds, formatted)`를 쓰게
하거나, 새 `lyrics_offset_value` 문자열(`%+.1f초`)을 추가한다. `formatOffset`은
`@Composable`이 아니므로 문자열을 인자로 받거나 호출부에서 포맷하게 바꾼다.

**위험도** 낮음 · **크기** S · **우선순위** 5

---

## 11. [빠진 기능] 앱 언어를 "시스템 언어"로 되돌릴 수 없다 — 5

**근거**
- `app/src/main/java/com/music/bitchord/ui/components/AppLanguageDialog.kt:56-75`
  ```
  val SUPPORTED_LANGUAGES = listOf(
      AppLanguage("en", R.string.english),
      ...
  ```
- `AppLanguageDialog.kt:179-181`
  ```
  AppCompatDelegate.setApplicationLocales(
      LocaleListCompat.forLanguageTags(language.tag),
  )
  ```

**증상**
언어를 한 번 고르면 `AppCompatDelegate`에 항상 명시 태그가 들어간다. 목록에는
"시스템 언어" 행이 없어, 기기 언어를 따라가게 되돌리려면 앱 데이터를 지우거나
OS 설정의 앱별 언어를 직접 만져야 한다.

**고치는 방법**
`SUPPORTED_LANGUAGES` 맨 앞에 빈 태그(`""`) 행을 추가하고, 선택 시
`AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())`를
호출한다. 현재 언어 판정도 `getApplicationLocales().isEmpty()`일 때 시스템 로케일로
표시하게 한다. `app_language_system` 문자열을 `values`/`values-ko`에 추가한다.

**위험도** 낮음 · **크기** S · **우선순위** 5

---

## 12. [버그] 한국어 용어와 어투가 화면마다 섞인다 — 4

**근거**
- `app/src/main/res/values-ko/strings.xml:821`
  ```
  <string name="playlist_action_failed">플레이리스트를 변경하지 못했습니다</string>
  ```
  같은 파일 `:813`은 `playlist` = `재생목록`, `:604`는 `playlist_name` = `재생목록 이름`.
- `app/src/main/res/values-ko/strings.xml:641`
  ```
  <string name="sleep_timer">잠자기 타이머</string>
  ```
  Apple Music 한국어는 "취침 타이머"를 쓴다.
- `:197`은 "다시 시도해 주세요", `:222`는 "선택하십시오", `:1272`는 "다시 시도하세요"로
  합니다체·해요체·하세요체가 한 설정 화면 안에서 섞인다.

**증상**
재생목록 작업이 실패했을 때만 "플레이리스트"라는 다른 단어가 뜨고, 취침 타이머는
"잠자기 타이머"로 어색하게 읽힌다. 문장 끝이 화면마다 달라 기계 번역처럼 느껴진다.

**고치는 방법**
`playlist_action_failed`를 "재생목록을 변경하지 못했습니다"로, `sleep_timer`를
"취침 타이머"로 바꾼다. `values-ko` 전체를 한 어투(예: 합니다체)로 통일하고,
`플레이리스트`·`잠자기`가 남아 있는지 grep으로 확인한다.

**위험도** 낮음 · **크기** S · **우선순위** 4

---

## 13. [위험] 하단 탭에서 TalkBack이 같은 이름을 두 번 읽는다 — 4

**근거**
- `app/src/main/java/com/music/bitchord/ui/components/GlassNavBar.kt:239-248`
  ```
  Icon(
      imageVector = tab.icon,
      contentDescription = tab.label,
  ```
- `app/src/main/java/com/music/bitchord/ui/components/FloatingBottomBar.kt:366-379`
  같은 구조(아이콘 `contentDescription = tab.label` + 아래 `Text(tab.label)`).

**증상**
레이블이 보이는 홈/탐색/보관함 탭에서 토크백이 "홈, 홈"처럼 이름을 두 번 읽는다.
아이콘과 텍스트가 각각 접근성 노드가 되기 때문이다.

**고치는 방법**
레이블이 함께 그려지는 탭은 `Icon(contentDescription = null)`로 두고, 레이블 없는
단독 검색 원형 탭만 `contentDescription`을 유지한다. `GlassNavBar`와
`FloatingBottomBar` 양쪽에 같은 규칙을 적용한다.

**위험도** 낮음 · **크기** S · **우선순위** 4

---

## 14. [빠진 기능] 빈 상태가 텍스트 한 줄뿐이고 다음 행동을 주지 않는다 — 4

**근거**
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt:924-946`
  ```
  fun MessageState(
      message: String,
      modifier: Modifier = Modifier,
  ```
  `actionLabel`/`onAction`은 있지만 대부분 호출부가 넘기지 않는다.
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt:526-529`
  ```
  } else item(key = "library:empty") {
      MessageState(
          stringResource(if (addons) Res.string.search_addons_empty else Res.string.search_library_empty),
  ```

**증상**
로컬 음악이 비었을 때 "Local Music 폴더의 노래를 검색하십시오", 애드온이 없을 때
"설정 → 소스에서 추가하세요"라고만 한다. 폴더 선택/소스 화면으로 가는 버튼이 없어
Apple Music의 빈 상태보다 막막하다.

**고치는 방법**
`MessageState`에 선택적 아이콘을 추가하고, `SearchScreen`의 로컬/애드온 빈 상태에
`actionLabel`과 `onAction`(설정의 로컬 폴더 선택, 소스 화면 열기)을 넘긴다.
다운로드 관리자·대기열의 빈 상태도 같은 컴포넌트로 통일한다.

**위험도** 낮음 · **크기** M · **우선순위** 4

---

## 15. [낭비] 로딩 스켈레톤 shimmer가 포그라운드 게이트 없이 무한 애니메이션된다 — 3 (알려짐)

**근거**
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Skeletons.kt:52-60`
  ```
  val sweep = rememberInfiniteTransition(label = "skeleton").animateFloat(
      initialValue = 0f,
      targetValue = 1f,
  ```
- `docs/cleanup/battery.md:51`에 "로딩/셔머/블링크 애니메이션 … 공통 포그라운드
  게이트(현재는 화면 꺼짐 시 Compose 프레임 클럭에 의존)"로 이미 제안되어 있다.

**증상**
로딩 중 화면이 켜져 있는 동안 매 프레임 그라데이션을 다시 그린다. 화면이 꺼지면
Compose가 프레임을 멈추므로 큰 낭비는 아니지만, 앱이 백그라운드에 남아 있는 동안
합성이 살아 있는 경로에서는 불필요한 프레임을 예약할 수 있다.

**고치는 방법**
`Skeletons.kt`의 `ShimmerBox`에서 `rememberIsForeground()`를 읽어
`rememberInfiniteTransition`을 foreground일 때만 만들거나, `animateFloat` 대신
정지된 그라데이션을 그린다. 홈·검색·상세 스켈레톤이 같은 컴포넌트를 쓰므로
한 곳만 고치면 된다.

**위험도** 낮음 · **크기** S · **우선순위** 3

---

## 요약 (상위 5개)

1. 재생·볼륨·가사 오프셋 슬라이더가 TalkBack에 보이지 않음 — 8
2. 오류 화면에 영어 예외 원문이 그대로 노출됨 — 8
3. 앨범·검색·Spotify 오류 화면에 "다시 시도"가 없음 — 7
4. 주요 아이콘 버튼의 터치 영역이 48dp 미만 — 7
5. 첫 실행 온보딩에 프록시 설정 진입점이 없음 — 7

보조 상위: 검색 종류 라벨 영어 노출(6), 가사 없음 막다른 길(6), 오디오 파이프라인
언어 혼용(5), Spotify 가져오기 라벨 미번역(5), 가사 오프셋 `s` 단위(5).
