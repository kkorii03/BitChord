# 좀비 코드 검사 (1/1)

작업 기준: `task/proxy-fallback` HEAD (`ko-101..HEAD` 전체, 이전 대기열 작업 결과 포함).
방법: `git log ko-101..HEAD`, `git diff ko-101..HEAD`로 새 코드를 먼저 읽고,
선언 이름을 대소문자 구분 정규식으로 `app`·`shared`·`sharedUi`의 Kotlin/Java/XML/C++
전체에서 참조 횟수로 셌다. 매니페스트·XML 레이아웃·`@Preview`·리플렉션/직렬화
가능성도 함께 확인했다. 문자열·드로어블은 리소스 이름과 `R.*`/`@*`/`Res.*` 참조를
대조했고, 설정은 `KEY_*` 상수와 `set*` 호출부를 확인했다. 로컬 Gradle 빌드는 하지
않았고 컴파일·테스트는 CI가 수행한다.

원칙: 사용자 기능·동작·설정·저장 데이터 형식은 바꾸지 않았다. 업스트림 파일
(`v1.8.1`에 존재하는 파일) 안의 미사용 코드는 합치기 충돌을 피하려고 **삭제하지
않고 목록만** 남겼다. 포크 전용 파일이라도 공개 API/테스트 계약/직렬화 호환이
걸린 것은 목록으로 남겼다.

## 1. 삭제한 것 (100% 미사용, 포크 전용 파일)

| 위치 | 항목 | 근거 |
|---|---|---|
| `shared/src/jvmSharedMain/.../data/sources/HqTier.kt` | `HqQuality.rungsGained(candidate, current)` | 선언 외 참조 0. 프로덕션·테스트·문서·XML 어디에도 없음. 같은 파일의 `isBetter`/`couldWin`이 실제 비교를 담당한다. |

`HqTier.kt`는 `v1.8.1`에 없는 포크 전용 파일이라 업스트림 합치기 충돌 위험이 없다.
순수 로직 추가가 아니라 미사용 함수 삭제이므로 새 테스트는 만들지 않았다.

## 2. 이번 범위에서 죽은 코드 (삭제 보류)

### 2.1 `ko-101..HEAD`에서 마지막 호출이 사라진 것

| 위치 | 항목 | 상태 |
|---|---|---|
| `shared/src/commonMain/.../playback/smart/TransitionPolicy.kt:85` | `speedUpBetween()` | `ko-101`에는 호출 1곳이 있었지만 `00d-automix-quality`에서 `assessTransitionTier`가 다른 식으로 바뀌며 호출이 사라졌다. 업스트림 파일이라 목록만. |
| `shared/src/jvmSharedMain/.../data/sources/TrackMatcher.kt:164` | `hasLeadingTrackNumber()` | 이번 범위에서 추가됐지만 호출 0. 파일은 업스트림이므로 목록만. `LEADING_TRACK_NUMBER` 정규식은 `parseTitle`이 계속 사용한다. |
| `app/src/main/res/values/strings.xml`·`values-ko/strings.xml` | `song_info_unavailable` | `SongInfoDialog`가 `song_info_unknown`으로 바뀌며 참조가 사라졌다. 문자열 파일은 업스트림이라 삭제하지 않았다. |

### 2.2 이번 범위에서 늘어난 미사용 import (업스트림 파일, 삭제 보류)

동작에는 영향이 없지만 리팩터링 후 남은 것이다.

- `MainActivity.kt`: `animateFloatAsState`, `statusBars`, `windowInsetsTopHeight`,
  `CircleShape`, `ArrowUpward`, `Person`, `RectangleShape`, `TextAlign`,
  `TrackMatcher`, `QueueBuilder`, `autoplaySectionStart`, `toDirectYouTubeMediaItem`,
  `MediaController`, `Rect`, `LayoutDirection`, `MiniPlayerPull`
- `PlaybackService.kt`: `DirectUsbProbeResult`, `toUri`, `MediaCodecUtil`, `MediaSessionService`
- `MainViewModel.kt`: `TrackDiagnostics`, `withTimeout`, `SourceKind`
- `SettingsSheet.kt`: `fillMaxSize`, `Cloud`, `Dns`
- `PlayerLyrics.kt`: `Timer`
- `PlayerState.kt`: `roundToInt`
- `MusicSource.kt`: `Locale`
- `LockScreenLyricsActivity.kt`: `aspectRatio`
- (`tv/.../TvSongActionsDialog.kt`에도 여러 개가 있지만 이 작업 범위(app·shared·sharedUi) 밖이라 기록만 한다.)

## 3. 이전부터 있던 미사용 선언 (목록만)

전부 `ko-101` 시점에도 참조 1(선언뿐)이었고, 파일이 업스트림이거나 공개 API라
삭제하지 않았다. 괄호 안은 현재 참조 수(1 = 선언 외 0).

### 3.1 함수·클래스

| 파일:줄 | 항목 | 비고 |
|---|---|---|
| `app/.../data/listentogether/ListenTogether.kt:636` | `probeHealth()` (private) | 파티 서버 상태 확인의 옛 경로. 현재 상태 확인은 다른 경로가 담당. |
| `app/.../data/listentogether/ListenTogether.kt:798` | `canJoin()` | 공개 API, UI 호출 없음. |
| `app/.../playback/QueueShuffle.kt:301` | `avoidIdentityShuffleMediaItems()` | 대기열 리팩터링 후 미사용. |
| `app/.../playback/QueueShuffle.kt:309` | `shuffledSection()` (private) | 위 함수에서만 쓰던 보조 함수. |
| `app/.../playback/smart/TrackAnalyzer.kt:244` | `isAnalysed()` | `ko-101` 이전부터 미사용. |
| `app/.../ui/components/backdrop/DrawBackdropModifier.kt:56` | `Modifier.drawPlainBackdrop()` | 새 backdrop 경로가 대체. |
| `app/.../ui/components/backdrop/effects/RenderEffect.kt:29` | `BackdropEffectScope.runtimeShaderEffect()` | 새 backdrop 경로가 대체. |
| `app/.../ui/components/backdrop/RuntimeShader.kt:25` | `RuntimeShader.asComposeShader()` | 새 backdrop 경로가 대체. |
| `app/.../ui/screens/ListenTogetherScreen.kt:979` | `PartyAvatar()` (private) | 파티 화면에서 호출 없음. |
| `shared/.../playback/smart/TransitionPolicy.kt:85` | `speedUpBetween()` | 2.1 참고. |
| `shared/.../data/cover/YouTubeCoverResolver.kt:127` | `coverFor()` | 포크 전용 파일이지만 `matchFor()`의 얇은 공개 래퍼라 보류. |
| `shared/.../data/LocaleTags.kt:47` | `systemTag()` | 공개 유틸, 호출 없음. |
| `shared/.../data/LocaleTags.kt:99` | `sameLanguageScriptAware()` | 공개 유틸, 호출 없음. |
| `shared/.../data/sources/TrackMatcher.kt:164` | `hasLeadingTrackNumber()` | 2.1 참고. |
| `shared/.../data/YouTubeFallback.kt:544` | `recentShelf()` | 포크 전용 파일. 현재 최근 재생은 `history()`가 담당. |
| `shared/.../playback/QueueTimeline.kt:260` | `upcomingUserQueueIndices()` | 이전 대기열 작업의 순수 함수. |
| `shared/.../playback/QueueTimeline.kt:267` | `playedUserQueueIndices()` | 위와 같음. |
| `shared/.../playback/QueueTimeline.kt:292` | `shuffledUpcomingOrder()` | 위와 같음. |
| `shared/.../playback/QueueTimeline.kt:304` | `sectionedOrder()` | 위와 같음. |
| `shared/.../playback/QueueWindow.kt:249` | `fromSnapshot()` | 이전 대기열 작업의 순수 함수. |
| `shared/.../playback/QueueWindow.kt:366` | `upcomingCount()` | 위와 같음. |
| `shared/.../playback/QueueWindow.kt:396` | `nextAutomatic()` | 위와 같음. |
| `shared/.../playback/TransitionFilter.kt:8` | `class TransitionFilter` | 실제 재생은 `TransitionFilterProcessor`를 쓴다. 업스트림 클래스라 목록만. |
| `shared/.../com/my/kizzy/rpc/KizzyRPC.kt:49` | `isRpcRunning()` | Discord RPC 잔여. |
| `shared/.../com/my/kizzy/utils/Ext.kt:17` | `String.toRpcImage()` | Discord RPC 잔여. |
| `sharedUi/.../player/PlayerLyrics.kt:177` | `textDirectionFor()` (private) | `voiceStyle`가 방향을 인라인 계산해 대체. |
| `sharedUi/.../player/PlayerSidePanels.kt:38` | `LyricsSidePanel()` | 현재 플레이어는 `NowPlayingScreen`의 패널을 쓴다. |
| `sharedUi/.../player/PlayerSidePanels.kt:158` | `QueueSidePanel()` | 위와 같음. |
| `sharedUi/.../screens/HomeScreen.kt:877` | `localizeCardSubtitle()` | 호출 없음. |

### 3.2 프로퍼티·상수

직렬화(`@Serializable`)·JSON 필드·인터페이스 구현은 이름이 코드에 안 보여도
사용 중이므로 제외했다. 아래는 그런 경로가 아닌 것만.

| 파일:줄 | 항목 | 비고 |
|---|---|---|
| `app/.../ui/components/FloatingBottomBar.kt:173` | `currentSelectedIndex` (로컬) | `rememberUpdatedState` 결과를 아무도 읽지 않는다. |
| `app/.../data/listentogether/ListenTogether.kt:421` | `serverStatus` | 공개 StateFlow, 구독자 없음. |
| `app/.../playback/audio/usb/DirectAudioOutput.kt:29` | `probeResult` | 읽는 곳 없음. |
| `shared/.../data/settings/AutomixPerformanceMode.kt:11` | `yieldsToPlayback` | 계산 프로퍼티, 읽는 곳 없음. |
| `shared/.../playback/EqualizerCurve.kt:80` | `TONE_TILT_HZ` | UI 설명용 상수, 읽는 곳 없음. |
| `shared/.../data/sources/StreamFormat.kt:54` | `isSuspectLossless` | 계산 프로퍼티, 읽는 곳 없음. |
| `shared/.../data/YouTubeCategoryRules.kt:21,24` | `FEATURED_PLAYLIST_PARAMS`, `COMMUNITY_PLAYLIST_PARAMS` | 포크 전용 파일의 별칭 상수, 읽는 곳 없음. |

### 3.3 직렬화 호환 때문에 남긴 것 (삭제 금지)

| 파일:줄 | 항목 | 이유 |
|---|---|---|
| `app/.../playback/smart/LyricsTiming.kt:1691-1692` | `Entry.autoChecked`, `Entry.autoShiftMs` | `@Serializable` 저장 데이터의 옛 필드. 주석에 "superseded"라고 적혀 있고, 지우면 기존 사용자의 저장된 타이밍 기록 형식이 바뀐다. |

## 4. 테스트만 호출하는 API (삭제 보류)

프로덕션 호출은 없지만 테스트가 계약으로 검증한다. 지우려면 테스트도 함께
고쳐야 하므로 이번 범위에서는 남긴다.

| 위치 | 항목 |
|---|---|
| `app/.../playback/DecoderMonitor.kt:36,39` | `activeNames()`, `activeCount()` |
| `app/.../playback/HqHostPolicy.kt:126` | `HqHostGate.isBusy()` |
| `app/.../playback/HqHostPolicy.kt:276` | `HqHostTracker.isBusy()` (테스트도 이 래퍼는 안 씀) |
| `shared/.../data/model/SongOrigins.kt:54` | `showsAccountActions()` |
| `shared/.../data/sources/HqMatchPolicy.kt:79` | `applyOffset()` |
| `shared/.../data/sources/HqTier.kt:108` | `HqQuality.isFakeHighQuality()` |

## 5. 쓰이지 않는 리소스

드로어블/밈맵: 앱과 `sharedUi` compose 리소스 모두 미사용 0건.

### 5.1 앱 문자열 (기준 `values/strings.xml` 1,298키 중 101키)

`strings.xml`은 업스트림 파일이라 **삭제하지 않았다.** `values-ko`는 포크 파일이지만
기준 키와의 차집합이 0이고, 아래 키들은 번역만 남아 있다.

- 이번 범위에서 새로 죽은 키: `song_info_unavailable`
- 포크에서 추가했지만 지금은 죽은 키(7):
  `account_channels_failed`, `account_channels_hint`, `account_channels_loading`,
  `brand_channel`, `channel_default_suffix`, `choose_in_youtube_music`, `your_channel`
  (삭제된 `AccountChannelDialog`의 잔여. 포크 전용 키라 업스트림 충돌은 없지만,
  `values/strings.xml` 자체가 업스트림 파일이라 목록만 남긴다.)
- 이전부터 업스트림에 있던 미사용 키(94):
  `add_module_source`, `advanced_automix`, `advanced_automix_subtitle`, `album_count`,
  `artist_count`, `beta`, `channel_session_unavailable`, `channel_switch_instructions`,
  `chinese`, `choose_a_channel`, `converting_to_audio`, `current_account`,
  `current_profile`, `dolby_atmos_open_failed`, `grid_view`, `hour_count`, `list_view`,
  `listen_together_activity_added`, `listen_together_activity_changed_size`,
  `listen_together_activity_cleared`, `listen_together_activity_paused`,
  `listen_together_activity_played`, `listen_together_activity_removed_member`,
  `listen_together_activity_removed_track`, `listen_together_activity_reordered`,
  `listen_together_activity_seeked`, `listen_together_activity_skipped`,
  `listen_together_activity_updated`, `listen_together_activity_went_back`,
  `listen_together_create_footer`, `listen_together_create_subtitle`,
  `listen_together_custom_server_saved`, `listen_together_invite`,
  `listen_together_profile`, `listen_together_server`,
  `listen_together_server_online_fallback`, `listen_together_server_unknown`,
  `listen_together_switch_failed`, `listen_together_switch_failed_stayed_in_party`,
  `log_copied`, `lossless_audio`, `lossless_hq_audio`, `lossless_source_subtitle`,
  `loudness_measuring`, `loudness_target`, `loudness_target_subtitle`,
  `loudness_target_value`, `lyrics_debug_logs`, `lyrics_debug_logs_subtitle`,
  `miscellaneous_footer`, `no_album_metadata`, `no_dolby_atmos`,
  `no_youtube_music_results`, `overridden`, `paxsenix_api_key_configured`,
  `paxsenix_api_key_missing`, `personalized_recommendations`, `pipeline_loudness`,
  `prefer_lossless`, `profiles`, `replay_minutes_value`, `replay_mostly_around`,
  `replay_summary_end_week`, `search_this_folder`, `sharing_coming_soon`,
  `shelf_100_on_repeat`, `shelf_commute`, `shelf_energy_boosters`, `shelf_feel_good`,
  `shelf_focus`, `shelf_new_albums_singles`, `shelf_party`, `shelf_relax`,
  `shelf_romance`, `shelf_sad`, `shelf_sleep`, `shelf_workout`, `sign_in_discord`,
  `skip_silence_float_subtitle`, `song_count`, `song_count_with_duration`,
  `song_count_with_hours_minutes`, `song_count_with_minutes`, `song_info_unavailable`,
  `spotify_empty_playlists`, `spotify_track_unavailable`, `use_this_channel`,
  `version_alignment_status`, `version_cut_aligned`, `version_cut_fetching`,
  `version_cut_measuring`, `version_cut_not_fetched`, `webdav_server_url`,
  `widget_open_bitchord`

### 5.2 sharedUi compose 문자열 (233키 중 14키)

- 포크에서 추가했지만 지금은 죽은 키(5): `ai_lyrics_timing`, `lyrics_timing_ai`,
  `lyrics_timing_ai_detail`, `lyrics_timing_auto`, `lyrics_timing_manual`
  (옛 AI 타이밍 UI의 잔여. 현재는 `lyrics_timing_ai_button*`을 쓴다.)
- 업스트림에 있던 미사용 키(9): `data_saver`, `downgraded_from`,
  `high_quality_upgrading`, `listen_together_open`, `listen_together_open_count`,
  `medium_quality`, `personalized_recommendations`, `replay_subtitle`, `your_replay`

`sharedUi/src/commonMain/composeResources/values/strings.xml`도 업스트림 파일이라
삭제하지 않았다. `values-ko` 차집합은 0이다.

## 6. 항상 같은 값인 플래그·도달 불가 분기

| 위치 | 상태 | 판단 |
|---|---|---|
| `app/.../data/settings/AppSettings.kt:914` | `scrobblingAvailable = true` | 6곳에서 읽지만 항상 true라 `&&`·`if`의 반대 분기가 도달 불가. 릴리스 게이트로 보이며 업스트림 파일이라 유지. |
| `app/.../data/AppUpdateChecker.kt:108` | `CHECK_UPSTREAM_RELEASES = false` | `check()`가 즉시 반환해 인앱 업데이트 다이얼로그 전체가 도달 불가. 포크 정책상 의도된 값으로 보이며 유지. |
| `shared/.../data/Http.kt:61` | `USAGE_LOGGING_ENABLED = false` | 사용량 인터셉터 등록이 항상 꺼져 있다. 의도된 진단 스위치로 보이며 유지. |
| `app/.../playback/CrossfadeController.kt:1098` | `val advanced = true` | "DJ 플래너는 이제 Automix"라 고정값. 다른 호출자/테스트가 `false`를 넘길 수 있어 분기 자체는 남긴다. |

충전 조건 제거(`heavyWorkAllowed = !lowPower`)로 의미가 달라진 주석 4곳은
동작을 바꾸지 않고 문구만 고쳤다: `AppSettings.charging`,
`TranslationAheadPolicy`(2곳), `LyricsController.autoAiIfNeeded`,
`LyricsTiming`의 후보 프리페치. `charging` 값 자체는
`LowPowerMode.AUTO`의 "저배터리여도 충전 중이면 저전력 아님" 판정에 계속 쓰인다.

## 7. 설정 키

- `ko-101..HEAD`에서 새로 추가된 설정 키는 없다(`AppSettings` diff는
  `heavyWorkAllowed`와 주석뿐).
- `AppSettings`의 `KEY_*` 상수 152개와 `set*` 함수 전부에 최소 1개 참조가 있다.
- 이전부터 알려진 미사용/죽은 설정은 `docs/AUDIT_FEATURES.md` 10장에 기록돼 있다
  (Android `setPlaybackSpeed`, Last.fm 키 setter, `CHECK_UPSTREAM_RELEASES` 등).
  이 작업에서는 중복 기록만 하고 코드는 건드리지 않았다.

## 8. 검증

- `git diff --check` 통과.
- 삭제한 `rungsGained`는 선언 외 참조 0을 다시 확인했다(대소문자 구분).
- 문자열은 `R.string.*`·`@string/*`·`Res.string.*` 참조를, 드로어블은
  `R.drawable.*`·`@drawable/*`·`Res.drawable.*` 참조를 대조했다.
- 설정은 `KEY_*`/`set*` 참조를 대조했다.
- 로컬 Gradle 빌드 금지 원칙에 따라 컴파일·테스트는 CI(`ko-wip-check`)에 맡긴다.
  순수 로직 추가가 없어 새 `shared` 테스트는 만들지 않았다.

## 9. 다음 단계에 남긴 것

1. 업스트림 파일 안의 미사용 함수·프로퍼티(3장)는 다음 업스트림 merge 후,
   해당 파일이 충돌 없이 정리된 시점에 한 번에 삭제한다. 특히
   `QueueTimeline`/`QueueWindow`의 순수 함수 7개와 `TransitionFilter` 클래스는
   이전 대기열 작업의 잔여라 우선순위가 높다.
2. 포크 전용 미사용 문자열 7개(앱)·5개(compose)는 `values/strings.xml`이 아닌
   포크 전용 리소스 파일로 옮기거나, 다음 merge에서 삭제한다.
3. 테스트 전용 API 6개는 테스트를 유지할지, 프로덕션 호출을 연결할지 결정한다.
4. `analysis_jni.cpp`는 chroma·mid/high energy·peak·dynamic range를 계산하지만
   하류에서 읽지 않는다(이번 범위에서 loudness만 방출하도록 바뀜). 배터리 우선
   원칙상 다음 성능 작업에서 계산 자체를 줄일 후보.
5. `CrossfadeController`의 `advanced = true` 고정은 Automix 정책이 정리되면
   파라미터를 없애고 플래너 쪽 `advanced=false` 경로도 함께 정리한다.
