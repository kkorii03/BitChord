# BitChord feature audit (read-only)

Date: 2026-10-18. Scope: `app`, `shared`, `sharedUi`, `desktopApp`.
Method: static reading only - no Gradle build, no kotlinc, no device or desktop
run. Every status below is inferred from an end-to-end code path (entry point ->
logic -> effect). "OK" means the path exists and is wired in the code, not that
it was observed working on a device. Line numbers are for this checkout.

Status legend:

- **OK** - entry point, logic and effect are all present and connected.
- **BROKEN** - the path is missing, contradicts its setting/label, or loses data.
- **SUSPICIOUS** - wired, but depends on an unchecked assumption, swallows a
  failure, or has a race the code cannot rule out.

---

## Priority list: BROKEN / high-risk SUSPICIOUS (max 25)

| # | Area | Finding | Evidence | Concrete fix |
|---|------|---------|----------|--------------|
| 1 | Playback errors | Android never shows a playback failure. `error` is written into the controller state but no shared/app surface reads it, so a failed track looks like dead buttons | `app/.../playback/PlayerConnection.kt:59`, `:355-360`; no consumer of `.error` in `sharedUi`/`MainActivity` (grep) | Render `player.error` as a snackbar/inline notice in `MainActivity` and clear it on retry/success |
| 2 | WebDAV | A failed WebDAV listing is converted to `emptyList()`, so bad credentials/network read as "no audio files" and `RemoteListing` never sees the error | `app/.../data/webdav/WebDavRepository.kt:24-29`; `data/remote/RemoteListing.kt:13-17` (SMB returns `Result`) | Return `Result<List<Song>>` and feed it to `RemoteListing`, as SMB does |
| 3 | Downloads | Deleting a download forgets the record even when the file delete failed; both callers ignore the boolean, so the UI says "gone" while the file remains | `app/.../download/Downloads.kt:345-351`; `ui/components/SongActionsSheet.kt:636`; `MainActivity.kt:2869` | Forget only on `deleted == true`; toast on failure |
| 4 | Downloads | Deleting a downloaded album/playlist drops the collection record even if every track delete failed | `download/Downloads.kt:456-462` | Keep the collection when `any == false`; report partial failures |
| 5 | Local music | "Show non-music audio" off still requires `IS_MUSIC != 0`; recordings/voice notes stay hidden either way, so the toggle is a no-op | `app/.../data/LocalMediaRepository.kt:233-245` | When the filter is off, do not add the `IS_MUSIC` selection (keep only the path/extension checks) |
| 6 | Desktop appearance | "Hide volume bar" is persisted but the shared player reads a different flow that is always false, and the sync effect never copies it | `desktopApp/.../DesktopPlayerHost.kt:188`; `DesktopApp.kt:2736-2748`; `sharedUi/.../NowPlayingScreen.kt:854` | Copy `DesktopAppearanceSettings.hideVolumeBar` into `DesktopPlayerSettings` in the side effect |
| 7 | Desktop appearance | "Reduce dynamic blur" affects only desktop chrome; player/lyrics/drawer read a `PlayerSettings` flow that is never set | `DesktopPlayerHost.kt:205`; `DesktopApp.kt:2736-2748`; `NowPlayingScreen.kt`, `PlayerLyrics.kt` consumers | Sync `reduceDynamicBlur` into `DesktopPlayerSettings` |
| 8 | Desktop lyrics | Translation language chosen on desktop is stored but never reaches the shared lyric host, which is pinned to `""` and follows the OS locale | `desktopApp/.../DesktopTranslationSetting.kt:19-33`; `DesktopPlayerHost.kt:214`; `PlayerLyrics.kt:2906` | Set `DesktopPlayerSettings.translationLanguage` from `DesktopTranslationSetting.language` (and update on change) |
| 9 | Desktop MPRIS | MPRIS `Seek` is a relative offset but is passed to an absolute seek, so "forward 5 s" jumps to 0:05 | `desktopApp/.../DesktopMpris.kt:308-310`; `DesktopApp.kt:3025,3097` | Add the current position to `offsetUs` before seeking |
| 10 | Desktop MPRIS | `Position` is only refreshed on song/play/volume/rate/repeat changes, so it freezes between events and after seeks | `DesktopMpris.kt:219`; `DesktopApp.kt:1784-1794` | Read the live engine position when the property is requested, or add the position tick to the effect keys |
| 11 | Desktop MPRIS | External `Shuffle` writes the flag/persistence directly, bypassing `setShuffle()`: the queue is not reordered and party lock is ignored | `DesktopApp.kt:1563-1566`; `:1342` | Route the MPRIS callback through `setShuffle` |
| 12 | Desktop sleep timer | With a crossfade/upcoming track prepared, `finishTrack` promotes it and returns before `onEnded`, so "pause after this song" is ignored | `desktopApp/.../DesktopAudioEngine.kt:1000-1002`; `DesktopApp.kt:1447-1451` | Check `DesktopSleepTimer.afterTrack` in the promotion path (and emit an ended event for the outgoing track) |
| 13 | Desktop scrobbling | ListenBrainz finished listens send `start_ms` = Unix epoch ms and `end_ms` = track duration instead of track offsets | `DesktopScrobbling.kt:177,246-276` | Send `start_ms = 0`, `end_ms = played range/duration` with `listened_at` as the epoch |
| 14 | Desktop downloads | The "Standard" download quality is not a `DesktopAudioQuality` name, so `ceiling()` falls back to the streaming default (usually Lossless) | `DesktopApp.kt:5947-5952`; `DesktopSourceRegistry.kt:565-570`; `DesktopSourceConfig.kt:18-24` | Map `STANDARD` to `MEDIUM`/`LOW` before resolving |
| 15 | Scrobbling | Duration parsing only accepts `M:SS`; `H:MM:SS` returns 0, so hour-plus tracks are never scrobbled/submitted | `ScrobbleManager.kt:178-184`; `ListenBrainzManager.kt:116-123` | Use `Song.durationMillis()`/`String.durationMillis()` from `Models.kt:251-259` |
| 16 | Spotify import | `resolveToSongs` takes the first search hit per track and ignores `matchTrack`/`TrackMatcher`, so imports drift to wrong recordings | `app/.../data/spotify/SpotifyImporter.kt:247-264`; matcher at `:164-178` | Use the existing matcher with title/artist/duration/album |
| 17 | Spotify import / local playlists | One malformed playlist or song aborts the whole `runCatching`; `_playlists` stays empty and the next save persists the loss | `data/spotify/LocalPlaylistStore.kt:36-63` | Parse per entry, skip/log bad rows, keep the rest |
| 18 | Queue | Remove/move from the queue panel pass UI indices to the controller with no bounds check or feedback; a stale index throws `IndexOutOfBoundsException` | `MainActivity.kt:2318-2319`; `PlayerQueue.kt:305,387`; `PlaybackService.kt:7613-7635` | Clamp against `mediaItemCount`, catch and show a notice |
| 19 | Notifications | `POST_NOTIFICATIONS` is requested only when a download starts; first playback (especially from the widget/car) never asks, so the media notification can stay hidden on Android 13+ | `MainActivity.kt:2022-2036` | Request on first playback/`startForeground`, or prompt from the widget-launched activity |
| 20 | AI lyrics/timing | `aiLyrics` defaults on and `autoLyricsTiming`/`aiTimingAhead` are tappable with blank Gemini/Deepgram keys; the work then returns null silently | `playback/smart/AiLyrics.kt:42`; `SettingsSheet.kt:1200-1271`; `res/values/strings.xml:1148-1149`; `MainViewModel.kt:854,875` | Gate the switches like `geminiTranslation` (`SettingsSheet.kt:1167-1170`) and show "needs key" |
| 21 | Lyrics providers | Every provider exception/timeout/403 is reported as "not found"; the user cannot distinguish offline, rejected key or genuinely missing lyrics | `shared/.../lyrics/LyricsRepository.kt:717-719` | Carry a reason through `onSourceResult` and render it in the provider sheet |
| 22 | Lyrics providers | The manual provider sheet lists every source in `lyricsSourceOrder`, including disabled ones, and requests the tapped source anyway | `sharedUi/.../LyricsProviderSheet.kt:63-65`; `app/.../ui/MainViewModel.kt:529-536` | Filter the sheet by enabled sources and reject disabled ones in `fetchLyricsProvider` |
| 23 | Shuffle | After a restart, shuffle is restored as `enabled = true` but the pre-shuffle order is not persisted; turning shuffle off only flips the icon | `QueueShuffle.kt:48,122-136`; `PlaybackService.kt:5886` | Persist the original order with the queue, or restore shuffle as "on but non-restorable" |
| 24 | Updates | The in-app update dialog is permanently unreachable: `check()` returns before setting `_available` | `data/AppUpdateChecker.kt:108,179`; `MainActivity.kt:4849`; `UpdateAvailableDialog.kt:83` | Point `LATEST_RELEASE_URL` at the fork's releases and enable, or delete the dead dialog |
| 25 | Desktop UI language | Changing the app language swaps only the desktop XML catalogue; Compose resources and `appLanguageTag()` still use the OS locale, producing a mixed-language UI | `desktopApp/.../DesktopStrings.kt:61-64`; `sharedUi/.../PlayerPlatformUi.jvm.kt:34` | Drive Compose resources and `appLanguageTag()` from `DesktopStrings.resolvedTag()` |

---

## 1. Playback, queue, shuffle, repeat, crossfade, AutoMix

| Feature | Entry point | Logic | Effect | Status | Evidence |
|---------|-------------|-------|--------|--------|----------|
| Play/pause/next/previous | Player controls, notification, widget, MPRIS/SMTC | `MediaController` -> `PlaybackService` -> ExoPlayer | Audio | OK | `PlayerConnection.kt:100-200`; `PlaybackService.kt:5560-5700` |
| Queue display/edit | Player queue drawer | `PlayerQueue` callbacks -> controller `remove/moveMediaItem` | Queue changes | SUSPICIOUS | No index guard: `MainActivity.kt:2318-2319` |
| Shuffle on/off | Player button, album/playlist shuffle | `QueueShuffle.toggle` / `startingOrder` | Queue reorder | BROKEN after restart | `QueueShuffle.kt:48,108-136`; `PlaybackService.kt:5886` |
| Repeat off/all/one | Player button, notification | Setting persisted by `MainActivity`; service reads on start | Repeat | SUSPICIOUS | External (car) repeat changes do not persist: `MainActivity.kt:2299` vs `PlaybackService.kt:5887` |
| Crossfade | Settings > Playback | `CrossfadeController` arms on track end, fades and swaps players | Audible blend | OK | `CrossfadeController.kt:766-1060` |
| AutoMix / smart fade | Settings > AutoMix; player blend badge | `TrackAnalyzer` -> `TransitionPlanner` -> `CrossfadeController` | Beat-matched transition | OK on Android; SUSPICIOUS on desktop | Android collectors in `PlaybackService.kt:6228-6280`; desktop host publishes blank mix state (`DesktopPlayerHost.kt:207-211`) |
| Sleep after this song | Player menu | `SleepTimer.afterTrack` checked on transition | Pause | SUSPICIOUS | Check runs after the next item is current: `PlaybackService.kt:3242-3245`, `CrossfadeController.kt:867-875` |
| Sleep timer | Player menu | `SleepTimer.deadline` -> pause | Pause | OK / SUSPICIOUS | `PlaybackService.kt:5668-5674`; deadline not cleared in `onDestroy` (`:6743-6760`) |
| Headphone resume | Settings > Playback | `HeadphoneResume` receiver -> play | Resume | SUSPICIOUS | A restored queue paused at 0:00 never resumes (`PlaybackService.kt:940,352`) |
| Stop when task removed | Settings > Playback | `onTaskRemoved` -> stop | Playback stops | OK | `PlaybackService.kt:6723-6730` |
| Autoplay (radio) | Player AutoPlay button | `Autoplay` + `QueueCoordinator` append | More tracks | OK | `Autoplay.kt`; `PlaybackService.kt:2620-2710` |
| Skip non-music | Settings > Playback | SponsorBlock segments filtered by category | Auto-seek | OK / SUSPICIOUS mid-track | `PlaybackService.kt:7090-7170`; `skipSponsorParts` is read when scheduling messages, so turning it on mid-song may not schedule later segments |
| Playback speed | Discord display / setting | Applied to every player | Rate | SUSPICIOUS (Android) | `setPlaybackSpeed` has no Android caller: `AppSettings.kt:1354`; only desktop has a control (`DesktopApp.kt:1577`) |

## 2. Audio quality, lossless, equalizer, loudness

| Feature | Entry point | Logic | Effect | Status | Evidence |
|---------|-------------|-------|--------|--------|----------|
| Wi-Fi/cellular quality | Settings > Audio quality | `StreamResolver`/`SourceResolver` ceiling | Stream selection | OK | `SettingsSheet.kt`; `StreamChoice.kt`; `SourceResolver.kt` |
| Lossless upgrade | Automatic during playback | `QualityUpgrade` re-resolves and swaps item | Better stream | OK | `PlaybackService.kt:1562-1650,3173-3287`; `QualityUpgrade.kt` |
| Output PCM / USB DAC | Settings > Audio pipeline | `OutputNegotiator` / `AudioOutputPolicy` | Out format | OK | `OutputNegotiator.kt`; `PlaybackService.kt:5968-5970` |
| Equalizer | Equalizer screen | `EqualizerProcessor` fed from settings | Audible EQ | OK | `PlaybackService.kt:6270-6285`; `EqualizerScreen.kt` |
| Loudness normalization | Settings > Audio | `LoudnessProcessor`, off on speaker option | Leveling | OK | `PlaybackService.kt:6258-6266` |
| Spatial audio / Dolby | Settings > Audio | `SpatialAudioProcessor`, Atmos passthrough | Widening | OK | `PlaybackService.kt:6248`; `SpatialAudioProcessor.kt` |
| Skip silence | Settings > Playback | ExoPlayer `skipSilenceEnabled` | Silence skipped | SUSPICIOUS | Admitted no-op on float output in `SettingsSheet.kt:809-820` |
| Audio cache | Settings > Storage | `AudioCache` LRU + startup sweep | Cached audio | OK / SUSPICIOUS | Startup sweep assumes nothing plays yet (`AudioCache.kt:240-258`), but widget bind can restore and play immediately (`PlaybackService.kt:1802-1814`) |

## 3. Downloads, offline, local music

| Feature | Entry point | Logic | Effect | Status | Evidence |
|---------|-------------|-------|--------|--------|----------|
| Download a track/album | Song menu, album menu, download manager | `Downloads.enqueue` -> `DownloadService` (dataSync FGS) | File saved | OK | `Downloads.kt`; `DownloadService.kt:169-176` |
| Offline playback | Queue/player when offline | `OfflineDash`/`ManifestDownload` -> local URI | Plays from disk | OK | `OfflineDash.kt`; `DownloadStore.kt` |
| Delete download | Song menu, download manager | Delete file, forget record | File gone | BROKEN | `Downloads.kt:345-351`; callers ignore result |
| Delete album/playlist download | Download manager card | Delete all tracks, forget collection | Files gone | BROKEN | `Downloads.kt:456-462` |
| Auto-download liked | Settings > Downloads | `LikedAutoDownload` on like/connectivity | Liked track downloaded | SUSPICIOUS | Failed enqueue is unparked and never retried (`LikedAutoDownload.kt:111-121`); no write permission gate on API<29 |
| Export to Music | Settings > Downloads | MediaStore copy | File in Music/BitChord | SUSPICIOUS | API 26-28 export toggle does not request `WRITE_EXTERNAL_STORAGE` (`MainActivity.kt:2021-2042`) |
| Local music scan | Library > Local | `LocalMediaRepository` MediaStore query | Local songs | BROKEN filter toggle | `LocalMediaRepository.kt:233-245` |
| Local folder picker | Settings > Local music | SAF tree URI persisted | Scan folder | SUSPICIOUS | `takePersistableUriPermission` failure is ignored (`SettingsSheet.kt:345-350`) |
| WebDAV library | Library > WebDAV | `WebDavRepository.getSongs` | Remote songs | BROKEN errors | `WebDavRepository.kt:24-29` |
| SMB library | Library > SMB | `SmbRepository.getSongs` (throws) | Remote songs | OK | `SmbRepository.kt:27`; `RemoteListing` shows failure |
| Downloads restore after backup | Settings > Backup import | Rewrites prefs | Download records restored | SUSPICIOUS | Only `SearchHistory` reloads; `Downloads` keeps stale in-memory maps (`AppSettings.kt:2190-2208`) |

## 4. Lyrics

| Feature | Entry point | Logic | Effect | Status | Evidence |
|---------|-------------|-------|--------|--------|----------|
| Synced lyrics | Player lyrics panel | `LyricsRepository` queries sources in order | Timed lines | OK | `LyricsRepository.kt:662-760` |
| Provider toggles/order | Settings > Lyrics sources | `lyricsSources`, `lyricsSourceOrder` | Which sources asked | OK | `LyricsRepository.kt:668-670` |
| Manual provider picker | Player > choose provider | `fetchLyricsProvider` | Request one source | BROKEN/suspicious | Disabled sources still listed and fetchable (`LyricsProviderSheet.kt:63-65`; `MainViewModel.kt:529-536`) |
| Provider error reporting | Provider sheet | `onSourceResult(null)` | Row state | BROKEN | Errors become NOT_FOUND (`LyricsRepository.kt:717-719`) |
| Word/syllable sync | Automatic | `LyricsWordAlign`, `SyllableRuns`, `prioritizeSyllableSync` | Word-highlight | OK | `LyricsRepository.kt:733`; `LyricsWordAlign.kt` |
| Guessed sweep | Automatic for line-only sources | `withEstimatedWords` (display only) | Letter sweep | OK | `EstimatedSweep.kt:28`; `PlayerLyrics.kt:2295-2300` |
| Heard words | AI timing available | `withHeardWords` | Real word times | OK | `HeardWords.kt:35`; `PlayerLyrics.kt:2295-2300` |
| Translation | Player translate button / auto | `LyricsTranslation`, Gemini optional | Translated lines | OK; key gating SUSPICIOUS | `LyricsTranslation.kt`; `TranslationAhead.kt`; key blank by default (`strings.xml:1148`) |
| Gemini translation toggle | Settings > Lyrics | `LyricsTranslation.geminiEnabled` | Gemini used | OK | `AppSettings.kt:1592-1595`; `SettingsSheet.kt:1167-1170` |
| AI transcription | Settings > Lyrics (on by default) | `AiLyrics` -> Gemini audio upload | Lines when no source has them | BROKEN when no key | `AiLyrics.kt:42`; `SettingsSheet.kt:1255-1271` |
| AI timing / Deepgram | Settings > Lyrics | `LyricsTiming`, `Deepgram` | Word timing | SUSPICIOUS key gating | `SettingsSheet.kt:1200-1235`; `MainViewModel.kt:852-900` |
| Lyrics offset sheet | Player menu | `lyricsOffsetMs` (per song + global) | Shifted lyrics | OK Android; BROKEN desktop | `LyricsOffsetSheet.kt`; desktop never opens it (`DesktopApp.kt:3157-3158`) |
| Lock-screen lyrics | Settings > Lock-screen lyrics | Overlay permission + `LockScreenLyricsActivity` | Lyrics over lock screen | OK / SUSPICIOUS | `LockScreenLyrics.kt:50,83`; start failure logged only (`:94-95`) |
| Lyrics share card | Player share | `LyricsShareCard`/`AndroidLyricsShareSheet` | Shared image | OK Android; intentionally absent desktop | `LyricsShare.kt:110-111`; `PlayerHost.kt:117,124` |
| Embedded/downloaded lyrics | Offline track | `LyricsTag`, embedded tags | Lyrics offline | OK | `LyricsTag.kt`; `EmbeddedLyrics` tests |
| Megalobiz / SimpMusic | Source list | Hidden sources | Never asked | SUSPICIOUS/dead | `LyricsSource.kt:97-101,118-123`; `LyricsRepository.kt:670` |
| Desktop lyrics cache | Desktop playback | `LyricsDiskCache` needs `keepDir`; desktop sets only `cacheDir` | Cache reuse | BROKEN | `LyricsDiskCache.kt:50`; `Main.kt:38` |
| Desktop keyed PaxSenix | Desktop lyrics sources | `PaxSenix.apiKey` only set from Android settings | Keyed sources | BROKEN | `DesktopLyricsClient.kt:46,70-72`; `PaxSenix.kt:333-335` |

## 5. Covers, original versions, chroma, AI timing

| Feature | Entry point | Logic | Effect | Status | Evidence |
|---------|-------------|-------|--------|--------|----------|
| Cover detection | Automatic for titles/channels | `CoverTitles` with designated channels/singers | Cover labelling/credit | OK | `BitChordApplication.kt:68-76`; `CanvasRepository.kt:84`; `ScrobbleManager.kt:120` |
| Remember cover channel | Song menu | `setCoverChannel` | Future covers recognized | OK | `AppSettings.kt:1652-1666`; `SongActionsSheet.kt` |
| Original version pin | Song menu > use YouTube upload | `OriginalVersion.pin/unpin` | Direct video stream | OK | `PlaybackService.kt:2528-2533,4080`; `PlayerConnection.kt:143,613-618` |
| Cover original lookup | Automatic during timing | `CoverOriginals` + search | Original recording | OK | `CoverOriginals.kt`; `LyricsTiming.kt:388-463` |
| Chroma alignment | Automatic during lyric timing | `ChromaAlignment` + `VersionAudioAligner` | Offset between cover and original | OK | `LyricsTiming.kt:483-570`; `ChromaAlignment.kt` |
| Smart version alignment toggle | Settings > Playback | `smartVersionAlignment` read before swap | Hold until aligned | OK | `PlaybackService.kt:2144`; `MainActivity.kt:1173,1241` |

## 6. Listen Together, Spotify, Discord, scrobbling

| Feature | Entry point | Logic | Effect | Status | Evidence |
|---------|-------------|-------|--------|--------|----------|
| Create/join party | Listen Together screen | `ListenTogether` REST + socket | Synced playback | OK | `ListenTogetherScreen.kt:226-453`; `PartySync.kt` |
| Invite links | Share sheet / deep link | `JamInviteLink` + manifest filters | Join by link | SUSPICIOUS | Default server emits upstream static origin (`ListenTogetherScreen.kt:193-199`; `JamInviteLink.kt:19,80-87`); custom-host parse rejects non-upstream hosts (`:70-77`) |
| Party failure feedback | Listen Together sheets | Error state | User message | OK | `ListenTogetherScreen.kt:540-544` |
| Party join timeout | Join screen | REST calls | Spinner | SUSPICIOUS | Socket has no read timeout; REST joins have no per-request timeout (`ListenTogether.kt:223-247,1565-1572`) |
| Spotify playlist import | Spotify import dialog | `SpotifyImporter` -> search -> playlist | Imported playlist | BROKEN matching | `SpotifyImporter.kt:247-264` |
| Spotify import >50 tracks | Import dialog | First 50 direct, rest `addToPlaylist` | Full playlist | SUSPICIOUS | Results ignored (`MainViewModel.kt:1530-1533`) |
| Spotify library | Library > Spotify | `SpotifyLibrary` Pathfinder | Spotify playlists | SUSPICIOUS | Hardcoded query hashes (`SpotifyLibrary.kt:46-53`); no reconnect on 401/403 (`SpotifyLibraryScreen.kt:100-111`) |
| Spotify Canvas | Player canvas | `SpotifyToken` + `CanvasRepository` | Animated cover | SUSPICIOUS | Token mint failure only logged (`SpotifyToken.kt:89-90,215`) |
| Discord RPC | Settings > Discord | Token -> `DiscordRPC` -> presence | Discord presence | OK / SUSPICIOUS | `PlaybackService.kt:6516-6540`; `discordPresenceUp` set before push, no retry (`:6654-6673`) |
| Last.fm scrobbling | Settings > Account | `ScrobbleManager` | Scrobbles | OK / SUSPICIOUS | `PlaybackService.kt:6415-6441`; duration parsing bug (`ScrobbleManager.kt:178-184`); `scrobblingAvailable` hardcoded true (`AppSettings.kt:738`) |
| ListenBrainz scrobbling | Settings > Account | `ListenBrainzManager` | Listens submitted | OK / SUSPICIOUS | `PlaybackService.kt:6415-6441`; token saved unvalidated (`SettingsSheet.kt:1954-1981`); duration parse bug (`ListenBrainzManager.kt:116-123`) |

## 7. Stats, Replay, smart playlists, history, search, autoplay

| Feature | Entry point | Logic | Effect | Status | Evidence |
|---------|-------------|-------|--------|--------|----------|
| Listening stats | Automatic | `ListeningRecorder` -> `ListeningStats` | Local stats | OK | `PlaybackService.kt:986,1173`; `ListeningRecorder.kt` |
| Replay | Replay screen | `ReplayModel`/stories from stats | Poster/stories | OK | `ReplayModel.kt`; `ReplayScreen.kt` |
| Replay genres | Settings > Replay | `replayGenres` + `ArtistFacts` (Last.fm key) | Genre facts | SUSPICIOUS | Toggle live even with no Last.fm key (`ArtistFacts.kt:126`; `SettingsSheet.kt:1535-1555`) |
| Smart playlists | Library links | `SmartPlaylists.songs(kind, liked)` | Playlists | OK | `MainViewModel.kt:3347`; `LibraryPhoneParts.kt:103-126` (signed-out error `:3341`) |
| History | Library > History | `YtMusicRepository.history` | Recent tracks | OK | `MainViewModel.kt:2348-2361` (sign-in prompt) |
| Search (anonymous) | Search bar | Credential-free `Innertube.search` | Results | OK | `shared/.../Innertube.kt:1291-1310`; `InnertubeSearchPrivacyTest.kt` |
| Search history | Search bar | `SearchHistory` | Suggestions | OK | `SearchHistory.kt:58`; `MainViewModel.kt:2368-2463` |
| Personalised autoplay | AutoPlay | `Autoplay` + `TastePool`/`Recommender` | Taste-ranked radio | OK | `Autoplay.kt`; `TastePool.kt` |
| ListenBrainz similar | AutoPlay top-up | `SimilarCandidates` 7-day cache | Similar tracks | OK | `SimilarCandidates.kt`; `BitChordApplication.kt` init |
| Low power deferral | Settings > Performance | `heavyWorkAllowed` | Heavy work deferred | OK | `AppSettings.kt:394`; `TrackAnalyzer.kt:280`; `TranslationAhead.kt:130` |

## 8. Android platform surfaces

| Feature | Entry point | Logic | Effect | Status | Evidence |
|---------|-------------|-------|--------|--------|----------|
| Media notification | Playback | Media3 `MediaSessionService` | Notification/card | OK, permission risk | `AndroidManifest.xml` FGS/mediaPlayback; `MainActivity.kt:2022-2036` |
| Android Auto / media browse | Car | `MediaLibraryService` browse tree | Car UI | SUSPICIOUS signed out | Signed-out liked/playlists return empty (`PlaybackService.kt:8295-8320`) |
| Widgets (2x2, 4x2, pill) | Launcher | `MediaWidget*` + `MediaWidgetActions` | Controls/artwork | OK / minor no-op | `MediaWidgetActions.kt:43-77` (empty queue tap does nothing) |
| Cast | Player cast row | `CastController`/`CastPlayback` -> receiver | Remote playback | OK Android, hidden desktop | `AndroidPlayerHost.kt:181-190`; `PlayerHost.kt:134-135` |
| Lock-screen overlay permission | Settings | `Settings.canDrawOverlays` | Overlay activity | OK | `LockScreenLyrics.kt:50,83`; `SettingsSheet.kt:1318-1343` |
| Foreground service types | Manifest | mediaPlayback / dataSync declared | Android 14 startForeground | OK | `AndroidManifest.xml` service entries |
| Bluetooth names | Output picker | `BLUETOOTH_CONNECT` check | Device names | OK | `AudioOutput.kt:182-188`; `BluetoothAudioTracker.kt:90-95` |
| Local audio permission | Local music | `READ_MEDIA_AUDIO`/legacy | Scan | OK | `LocalMediaRepository.kt:50-61` |
| Notification permission | Downloads/playback | `POST_NOTIFICATIONS` | Media notification | SUSPICIOUS | Only requested on download (`MainActivity.kt:2022-2036`) |

## 9. Desktop app and sharedUi parity

| Feature | Entry point | Logic | Effect | Status | Evidence |
|---------|-------------|-------|--------|--------|----------|
| Desktop playback/queue | Desktop window | `DesktopAudioEngine`, `DesktopQueue` | Audio | OK | `DesktopAudioEngine.kt`; `DesktopQueue.kt` |
| Desktop shuffle | Player button | `setShuffle` | Queue reorder | OK internally; MPRIS bypass | `DesktopApp.kt:1342,1563-1566` |
| Desktop crossfade/AutoMix | Settings | `DesktopAudioEngine` transition | Audible blend | OK audio, UI state missing | `DesktopAudioEngine.kt`; blank host flows (`DesktopPlayerHost.kt:207-211`) |
| Desktop EQ | Equalizer window | `DesktopEqualizer` | Audible EQ | OK | `DesktopEqualizer.kt`; `DesktopAudioEngine.kt:985` |
| Desktop downloads | Download manager | `DesktopDownloadManager`/`ManifestDownload` | Files saved | OK / quality bug | `DesktopApp.kt:5947-5952` |
| Desktop local music | Library | `DesktopLocalMusic` watcher | Local songs | OK / SUSPICIOUS | Missing folder silently falls back (`DesktopLocalMusic.kt:33-44`) |
| Desktop lyrics | Player | `DesktopLyricsClient` | Lyrics | OK but cache/translation gaps | `DesktopLyricsClient.kt:70-100`; `Main.kt:38` |
| Desktop MPRIS | OS media controls | `DesktopMprisController` | Controls/metadata | BROKEN seek/position/shuffle | `DesktopMpris.kt:219,308-310` |
| Windows SMTC / Linux status notifier | OS | `DesktopWindowsMedia`, `DesktopStatusNotifier` | OS media UI | OK | `DesktopWindowsMedia.kt`; `DesktopStatusNotifier.kt` |
| Desktop tray / close-to-tray | Window close | `DesktopWindowVisibility` | Hide to tray | SUSPICIOUS | `keepRunningWhenClosed` not gated on tray start (`DesktopApp.kt:2319-2323`) |
| Desktop Discord RPC | Settings | `DesktopDiscordRpc` | Presence | OK / SUSPICIOUS | `DesktopDiscordRpc.kt`; seek not republished (`:126-128`) |
| Desktop scrobbling | Settings | `DesktopScrobbling` | Last.fm/ListenBrainz | BROKEN LB timestamps | `DesktopScrobbling.kt:177,274-275` |
| Desktop Listen Together | Screen | `DesktopListenTogether` | Party sync | OK | `DesktopListenTogether.kt` |
| Desktop Replay/stats | Replay | `DesktopListeningStats` | Stats UI | OK | `DesktopListeningStats.kt` |
| Desktop sign-in | Account | Browser cookies/YouTube auth | Session | OK | `DesktopBrowserSignIn.kt`; `DesktopYouTubeAuth.kt` |
| sharedUi platform split | Both | `jvmSharedMain` has no `android.*` imports; every `expect` has Android+JVM actuals | Compiles on both | OK | grep of `sharedUi/src/jvmSharedMain`; `sharedUi/src/jvmMain` |
| Desktop foreground gating | Animations | `rememberIsForeground` uses Compose lifecycle; desktop window visibility is not mapped to lifecycle | Animations while hidden | SUSPICIOUS | `ForegroundState.kt:38`; `DesktopWindowVisibility.kt`; `Main.kt:29` |

## 10. Settings, dead controls, strings, silent failures, startup

| Item | Finding | Evidence | Status |
|------|---------|----------|--------|
| Playback speed (Android) | Stored and applied on every player, but no Android UI calls `setPlaybackSpeed`; desktop has a rate button | `AppSettings.kt:345,1354`; `DesktopApp.kt:1577` | SUSPICIOUS (dead setting) |
| Last.fm developer keys/endpoint | `setLastfmApiKey/Secret/Endpoint` have no callers; values only come from prefs/build config | `AppSettings.kt:1789-1804` | SUSPICIOUS (config-only) |
| `videoCanvas` | Read by canvas repository, exposed in Settings | `CanvasRepository.kt:145`; `SettingsSheet.kt:1054` | OK |
| `chartsCountry` | Setter `setChartsCountry` is called from `MainActivity.kt:3107` via `MainViewModel.selectChartsCountry` | `MainViewModel.kt:2133-2137` | OK |
| `exportDownloads` | Exposed and read; legacy write permission path incomplete on API 26-28 (see downloads) | `SettingsSheet.kt:576-582`; `MainActivity.kt:2021-2042` | SUSPICIOUS |
| Removed-feature strings | `prefer_lossless`, `lossless_source_subtitle`, `lossless_hq_audio`, `advanced_automix*`, `loudness_target*`, `lyrics_debug_logs*`, `sharing_coming_soon`, `import_spotify` have no code references | `app/src/main/res/values/strings.xml:102,110-111,242,828-829,991-995,1017-1019,1122-1123` | SUSPICIOUS (dead labels) |
| About links | GitHub/Discord/site links still point at upstream `kushagrasinghx`, not this fork | `SettingsSheet.kt:1765-1777` | SUSPICIOUS |
| Playlist actions | Create/remove/add/rename/delete playlist failures are empty `onFailure` handlers | `MainViewModel.kt:1507,1598,1639,1696,1767` | SUSPICIOUS |
| Rating/like | Optimistic UI rolls back on failure with no message | `MainViewModel.kt:1119-1121,1234-1236` | SUSPICIOUS |
| Download channel strings | Localized channel name/description ignored; channel hardcodes "Downloads" | `DownloadService.kt:239-244`; `strings.xml:4-5` | SUSPICIOUS |
| Update flag | `CHECK_UPSTREAM_RELEASES = false` gates the entire in-app update path | `AppUpdateChecker.kt:108,179` | BROKEN (dead UI) |
| Presence | `Presence.ENDPOINT` points at `api.bitchord.kushagrasingh.in` while the party default is the Render host; failures are swallowed | `shared/.../Presence.kt:111-121`; `app/build.gradle.kts:50-52` | SUSPICIOUS |
| Startup stores | `StartupWork` opens SearchHistory/SourceRegistry/Downloads/AudioCache/CanvasCache on one serial thread; each has a self-open fallback, so no main-thread join remains. `Downloads.open()` can still decode on the caller (main) if it wins the race | `StartupWork.kt:27-40`; `Downloads.kt:161-198`; `SearchHistory.kt:58`; `SourceRegistry.kt:122-159` | OK / minor jank |
| Backup import | Rewrites the shared prefs file but only reloads `SearchHistory`; other in-memory stores can overwrite imported data | `AppSettings.kt:2190-2208`; `Backup.kt:102-107` | SUSPICIOUS |

---

## Notes on what was verified as working

These paths were traced and found connected, with the evidence above:

- Anonymous search/suggestions (no cookie, visitor id, dataSync id or page id).
- Signed-out handling for history, library and smart playlists (explicit
  sign-in states, not failed requests).
- Lyrics source ordering, hidden-source filtering, disk cache and provider
  parsing; guessed sweep and heard-word overlays are display-only and never
  written back.
- Equalizer, loudness, spatial audio, output negotiation and lossless upgrade
  collectors in `PlaybackService.observeSettings`.
- SponsorBlock lookup/skip at track start and manual seek; low-power deferral;
  cast, widgets and Android 14 foreground-service declarations.
- `sharedUi/src/jvmSharedMain` contains no `android.*` imports, and every
  `expect` in it has both Android and JVM actuals.

## Caveats

- Static audit only. No build, unit test, emulator or S24 run was used, so a
  path marked OK can still fail at runtime for reasons not visible in code
  (server behaviour, MediaStore contents, OEM restrictions).
- Desktop-specific line numbers refer to `desktopApp/src/main/kotlin`; app paths
  refer to `app/src/main/java`.
- Existing test names and CI lists were not modified; this file is the only
  change.
