# UI tour coverage

Last full run: 65 numbered screens (`shots/index.txt`, `screen-texts.md` 1311 lines).
The full tour (`TOUR_QUICK=0`) now adds the mood grid, library shelf/release and
long-press menu, search source pills, the player's extra sheets, the Settings
categories and choice pages, the source editors, the language dialogs and
onboarding. Quick mode keeps its old screen set. Every new step is fail-soft:
a missing label is logged and the section moves on.

How the app is wired: tabs are `AppDestination.Tab(0..3)`, pushed pages are
`AppRoute` (`app/src/main/java/com/music/bitchord/ui/navigation/AppNavigator.kt`),
album/artist/playlist pages are the view model's `detailStack`, and sheets/dialogs
are state flags in `MainActivity.kt` / `SettingsSheet.kt` / `PlayerHost.kt`.
There is no androidx `NavHost`.

## Coverage table

| Surface | Code entry | Before | Now | How the tour reaches it |
|---|---|---:|---:|---|
| Home | `TAB_HOME` 0 | yes | yes | Home tab; scrolled captures |
| Home mood grid | `AppRoute.Moods` via `onMoodsShowAll` | no | yes | Home mood row `모두 보기` |
| Explore | `TAB_EXPLORE` 1 | yes | yes | Explore tab; scrolled |
| Explore mood/genre page | `selectedMoodGenre` / `MoodGenrePlaylistsScreen` | yes | yes | Explore > `운동` |
| Library | `TAB_LIBRARY` 2 | yes | yes | Library tab; scrolled |
| Library shelf "Show all" | `AppRoute.LibraryShowAll` | no | yes | Library > `모두 보기` |
| Library release detail | `detailStack` | no | yes | First Library card |
| Library card context menu | `BrowseActionsMenu` | no | yes | Long press a Library card |
| Downloads manager | `AppRoute.Downloads` | yes | yes | Library > `다운로드` |
| Local music (Songs/Artists/Albums) | release page / `LocalGroups` | yes | yes | Library > `이 기기의 음악` |
| Smart playlists (4) | Library shelves | yes | yes | `자주 들은 노래`, `반복 재생 중`, `다시 듣기`, `좋아요…덜 들은 노래` |
| Listening history | `AppRoute.History` | yes | yes | Library top bar `청취 기록` |
| Search empty/typing/results | `TAB_SEARCH` 3 | yes | yes | Search tab; Latin input `Dance Monkey` |
| Search result tabs | `filter` | yes | yes | `노래`, `앨범`, `아티스트`, `재생목록`, `비디오` |
| Search source tabs | `searchSource` | no | yes | `보관함` / `애드온` source pills |
| Album detail | `detailStack` | yes | yes | Search results > Albums > first item |
| Artist detail | `detailStack` | yes | yes | Search results > Artists > first item |
| Playlist detail | `detailStack` | yes | yes | Search results > Playlists > first item |
| Mini player | `MiniPlayer` | yes | yes | Shared YouTube Music link |
| Now playing | `NowPlayingScreen` | yes | yes | Tap mini player |
| Lyrics panel | `lastPlayerScreenOf` LYRICS | yes | yes | `가사` |
| Queue panel | `lastPlayerScreenOf` QUEUE | yes | yes | `다음 재생` |
| Lyrics provider sheet | `LyricsProviderSheet` | yes | yes | `가사 제공` |
| Lyrics offset sheet | `LyricsOffsetSheet` | yes | yes | Song actions > `가사 싱크 조절` |
| Song actions sheet | `SongActionsSheet` / `KoSongActionsSheet` | yes | yes | Player `더 보기` |
| Sleep timer page | `CustomSleepTimerPage` | no | yes | Song actions > `취침 타이머` |
| Playback speed page | `PlaybackSpeedPage` | no | yes | Song actions > `재생 속도` |
| Song info | `SongInfoDialog` | no | yes | Song actions > `노래 정보` |
| Lyrics share | `LyricsShareSheet` | no | no | Needs a lyric picker long-press; not deterministic |
| Playlist picker | `PlaylistPickerSheet` | no | yes | Song actions > `플레이리스트에 추가` |
| Audio output sheet | `AudioOutputSheet` | yes | yes | Player `오디오 출력` |
| Audio pipeline dialog | `AudioPipelineDialog` | no | yes | Audio output > `오디오 파이프라인` |
| Cast dialog | `CastDialog` | no | no | Output sheet cast row; no receiver on the emulator |
| Song meaning sheet | `SongMeaningSheet` | no | no | Needs a lyrics/Gemini response; not deterministic |
| Settings main | `AppRoute.Settings` | yes | yes | Library > `계정 전환` > `설정` |
| Account menu | account button | yes | yes | Library > `계정 전환` |
| Downloads category | `SettingsCategory.DOWNLOADS` | no | yes | Settings > `다운로드` |
| Playback category | `SettingsCategory.PLAYBACK` | no | yes | Settings > `재생` |
| Appearance category | `SettingsCategory.APPEARANCE` | no | yes | Settings > `화면 표시` |
| Lyrics category/page | `SettingsCategory.LYRICS` / `AppRoute.Lyrics` | yes | yes | Settings > `가사` |
| Performance category | `SettingsCategory.PERFORMANCE` | no | yes | Settings > `성능` |
| Local music category | `SettingsCategory.LOCAL_MUSIC` | no | yes | Settings > `이 기기의 음악` |
| Storage category | `SettingsCategory.STORAGE` | no | yes | Settings > `저장 공간` |
| Your data category | `SettingsCategory.YOUR_DATA` | no | yes | Settings > `나의 데이터` |
| Miscellaneous category | `SettingsCategory.MISCELLANEOUS` | no | yes | Settings > `기타` |
| Language category | `SettingsCategory.LANGUAGE` | no | yes | Settings > `언어` |
| Advanced category | `SettingsCategory.ADVANCED` | no | yes | Settings > `고급 및 실험적 기능` |
| Audio quality (Wi-Fi/cellular) | `SettingsChoiceKind.AUDIO_QUALITY_*` | yes | yes | Settings rows `Wi-Fi 스트리밍` / `셀룰러 스트리밍` |
| Download quality | `SettingsChoiceKind.DOWNLOAD_QUALITY` | yes | yes | Settings > 다운로드 > `다운로드 음질` |
| Automix performance | `SettingsChoiceKind.AUTOMIX_PERFORMANCE` | yes | yes | Settings > 재생 > `자동 믹스 성능` |
| Bit-perfect output | `SettingsChoiceKind.BIT_PERFECT` | no | yes | Settings > 재생 > `비트 퍼펙트 출력` |
| Font | `SettingsChoiceKind.FONT` | no | yes | Settings > 화면 표시 > `글꼴` |
| Account and scrobbling | `AppRoute.AccountScrobbling` | yes | yes | Settings > `계정 및 연동` |
| Discord page | `AppRoute.Discord` | yes | yes | Account > `Discord` (login dialog also opened) |
| Spotify library | `AppRoute.Spotify` | yes | yes | Account > `Spotify` |
| Telegram settings | `AppRoute.Telegram` | no | yes | Account > `텔레그램` |
| Sources page | `AppRoute.Sources` | yes | yes | Settings > `소스` |
| WebDAV editor | `showWebDavEditor` | no | yes | Sources > `WebDAV` |
| SMB editor | `showSmbEditor` | no | yes | Sources > `SMB` |
| Listen together | `AppRoute.ListenTogether` | yes | yes | Settings > `함께 듣기` |
| Create/join party sheets | `CreatePartySheet` / `JoinPartySheet` | no | yes | Listen together > `새 파티` / `파티 참여` |
| Wear OS page | `AppRoute.WearOs` | no | no | Only when the watch APK is bundled; not on the phone emulator |
| Equalizer | `AppRoute.Equalizer` | yes | yes | Settings > 재생 > `이퀄라이저` |
| Equalizer presets | `AppRoute.EqualizerPresets` | no | yes | Equalizer > `프리셋` |
| Replay | `AppRoute.Replay` | yes | yes | Settings > 나의 데이터 > `Replay` |
| Replay share/story | `ReplayShareSheet` / `ReplayStories` | no | no | Needs a generated Replay history and a share tap |
| App language dialog | `AppLanguageDialog` | no | yes | Settings > 언어 > `앱 언어` |
| Content country dialog | `ContentCountryDialog` | no | yes | Settings > 언어 > `콘텐츠 국가` |
| Translation language dialog | `TranslationLanguageDialog` | no | yes | Settings > 가사 > `번역 언어` |
| Lyrics sources dialog | `LyricsSourcesDialog` | yes | yes | Settings > 가사 > `가사 제공처` |
| Account switcher | `AccountChannelDialog` | no | no | Only for a signed-in multi-channel account |
| Proxy dialog | `ProxyConfigDialog` | no | no | Account page row; save path is exercised by `section_proxy` |
| Signature API editor | `SignatureApiEditor` | no | no | Appearance > `아티스트 서명` (switch off by default) |
| Gemini/Deepgram key dialogs | settings flag | no | no | Lyrics page rows; need an API key to be meaningful |
| Spotify import | `KoPlaylistImportDialog` | no | no | Library import row; would alter local playlists |
| Update dialog | `UpdateAvailableDialog` | no | no | Only when a newer release is published |
| Onboarding name/connect | `OnboardingScreen` | no | yes | Last full-tour step: `pm clear` then first launch |
| YT Music / Spotify / Discord login WebViews | auth screens | no | no | Login is deliberately not attempted by the tour |

## Not captured automatically

These need an account, a network service, a real receiver, or a destructive
action: login WebViews, account switcher, cast, Replay share/story, lyrics share,
song meaning, update prompt, Spotify import, Gemini/Deepgram keys, proxy/signature
editors.
The tour still fails soft on their rows, so their absence is visible in
`index.txt` rather than aborting the run.

## Text checker

`scripts/ui/ui_textcheck.py` runs at the end of every tour (quick and full) and
writes `shots/ui-text-issues.md`. It reads each `shots/*.xml` and reports:

- `a-cut` — text that needs more width than its node and is ellipsized or
  clipped; an ellipsis that genuinely fits is not reported.
- `b-split-word` — a break between two Hangul characters with no space, from an
  explicit newline or from the layout wrapping a single long word.
- `c-overlap` — two text nodes whose bounds overlap.
- `d-english` — an English UI string whose resource has a Hangul `values-ko`
  translation (whole-token brand, song and artist allow-list included).
- `e-size-jump` — sibling rows in one list whose per-line text heights jump
  (a two-line row is not a jump by itself).
- `f-short-line` — a line holding only one or two characters, from an explicit
  newline or from a wrapped line.

Its fixture check is `python3 scripts/ui/ui_textcheck.py --self-test`; the unit
tests are `python3 -m unittest scripts/ui/test_ui_textcheck.py`.
