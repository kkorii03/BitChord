# BitChord feature-gap audit (read-only)

Date: 2026-10-19. Branch: `task/char-sync`.
Scope: `app/`, `shared/`, `sharedUi/`, plus the state holders those trees read
(`AppSettings`, `PlayerState`, `MainViewModel`). No `state/` package exists in
this checkout.

Method: static reading and grep only. No Gradle, no kotlinc, no device run, no
internet. A status means the end-to-end path is present in code, not that it was
observed working on a handset. Line numbers are for this checkout.

Status legend:

- **EXISTS** - entry point, logic and effect are connected. Any caveat is stated.
- **PARTIAL** - the feature exists in some form, but a named piece is missing or
  silently does nothing.
- **MISSING** - no implementation found in the scanned trees.

The target listener is in Korea without YouTube Premium. `music.youtube.com`
feeds are gated, so the signed-in Home is built from `www.youtube.com`
(`shared/src/jvmSharedMain/.../data/YouTubeFallback.kt:32`,
`YouTubeFallback.kt:112`, `innertube/YouTubeWebParser.kt:22`,
`data/YtMusicRepository.kt:306`, `innertube/MusicGateFallback.kt:32`). A feature
is not downgraded merely because it uses the fallback path; the fallback is the
normal path for this account.

## Summary

| Status | Count | Features |
|---|---:|---|
| EXISTS | 18 | equalizer, loudness normalization, sleep timer, crossfade/gapless, Jam, widget, lyrics providers + word sync, AI translation, downloads/cache, local playlists, quick picks, Last.fm/ListenBrainz, SponsorBlock-style skip, audio quality, Android Auto, Chromecast, lyrics share card, stats/Replay |
| PARTIAL | 5 | tempo/pitch, skip silence, Spotify/M3U import, backup/restore library, search-history control |
| MISSING | 1 | incognito/private listening |

## Feature-by-feature

### Playback and audio

| # | Feature | Status | Main entry point | Settings toggle / exposure | Notes |
|---:|---|---|---|---|---|
| 1 | Equalizer | **EXISTS** | `app/src/main/java/com/music/bitchord/playback/EqualizerProcessor.kt:51`; UI `ui/screens/EqualizerScreen.kt:91` | Settings row `ui/screens/SettingsSheet.kt:869` opens the screen; enable switch `EqualizerScreen.kt:124` (`AppSettings.equalizerEnabled`, `data/settings/AppSettings.kt:322`) | In-app 7-band/manual EQ, tone pad, presets and balance. Not a system `AudioEffect`; it runs inside the DSP chain. Bypassed bit-exactly when off, per `DspChain.kt:24`. |
| 2 | Tempo and pitch control | **PARTIAL** | Speed plumbing `data/settings/AppSettings.kt:351`; applied in `playback/PlaybackService.kt:6270` and `playback/CrossfadeController.kt:1816` | No Android UI. `setPlaybackSpeed` (`AppSettings.kt:1425`) has no caller in `app/` or `sharedUi/`. | Tempo is wired from settings to ExoPlayer, but no screen exposes the setter, so on Android it is always 1.0x. Pitch control is absent: all calls use `setPlaybackSpeed`, which never changes pitch. The desktop app has a speed row, but it is out of scope here. |
| 3 | Skip silence | **PARTIAL** | `playback/PlaybackService.kt:5921`, live toggle `PlaybackService.kt:6245` | Settings row `SettingsSheet.kt:804` (`AppSettings.skipSilence`, `AppSettings.kt:248`) | Implemented through Media3's `skipSilenceEnabled`. The app itself documents the gap: on a float PCM output the switch does nothing because Media3 only appends its silence processor on the 16-bit branch (`SettingsSheet.kt:809-819`). |
| 4 | Loudness normalization | **EXISTS** | `playback/PlaybackService.kt:6403` (`setupLoudness`), `playback/LoudnessProcessor.kt:56` | Settings row `SettingsSheet.kt:715` (`AppSettings.loudnessNormalization`, `AppSettings.kt:276`); speaker bypass `SettingsSheet.kt:734` (`loudnessOffOnSpeaker`, `AppSettings.kt:279`) | Per-player gain, read lazily by media id, plus a peak limiter and crossfade blend trim. Uses YouTube's own normalization figure. On by default. No user-selectable target despite leftover `loudness_target` strings; normalization itself is complete. |
| 5 | Sleep timer (incl. end of track) | **EXISTS** | `playback/SleepTimer.kt:15`; deadline watcher `PlaybackService.kt:5710`; end-of-track `PlaybackService.kt:3252` | Player extras only: `ui/components/SongActionsSheet.kt:706` (row `:379`), presets + "after this song" at `SongActionsSheet.kt:746-770` | Deadline-based, `elapsedRealtime`, no ticking service. After-track is an event on track end, not a countdown. Also handled on the crossfade path (`CrossfadeController.kt:867`, `:1013`). |
| 6 | Crossfade / gapless | **EXISTS** | `playback/CrossfadeController.kt:107`; gapless boundary `playback/audio/PrecisionAudioSink.kt:127` | Crossfade slider `SettingsSheet.kt:746` (`AppSettings.crossfadeSeconds`, `AppSettings.kt:229`); Automix switch `SettingsSheet.kt:760` (`smartFadeEnabled`, `AppSettings.kt:241`) | True two-player overlap, not a volume dip. Gapless is Media3's own queue advance plus sink offset handling; there is no separate gapless toggle because crossfade 0 leaves it on. Automix planning is `shared/.../playback/smart/TransitionPlanner.kt:1173`. |
| 7 | Audio quality selection | **EXISTS** | `data/settings/AppSettings.kt:169-170`; ceiling applied `data/sources/SourceResolver.kt:54` | Separate Wi-Fi and cellular rows `SettingsSheet.kt:489-501`; download quality `SettingsSheet.kt:554` (`AppSettings.kt:192`) | Independent streaming ceilings and a separate download ceiling, as documented at `AppSettings.kt:175-192`. Lossless, high, medium and low are all exposed. |
| 8 | SponsorBlock-style non-music skip | **EXISTS** | `playback/SkipSegments.kt:237` (`segmentsFor`); category filter `SkipSegments.kt:72`; service fetch `PlaybackService.kt:7125`, check `:7197` | Primary switch `SettingsSheet.kt:654` (`skipNonMusicParts`, `AppSettings.kt:449`); sponsor/self-promo/intro/outro switch `SettingsSheet.kt:674` (`skipSponsorParts`, `AppSettings.kt:456`) | Fetches `music_offtopic` by default, optionally sponsor/selfpromo/intro/outro. Respects manual seeks, avoids end-of-video jumps, and re-checks on an ExoPlayer message rather than a timer. |

### Lyrics

| # | Feature | Status | Main entry point | Settings toggle / exposure | Notes |
|---:|---|---|---|---|---|
| 9 | Lyrics providers and word-by-word highlighting | **EXISTS** | `shared/src/jvmSharedMain/.../data/lyrics/LyricsRepository.kt:102`; source list `LyricsSource.kt:12` | Synced lyrics `SettingsSheet.kt:1079`; provider set/order `SettingsSheet.kt:1123` (`lyricsSources`, `lyricsSourceOrder`); syllable priority in `ui/components/LyricsSourcesDialog.kt:202` | All requested providers exist: LRCLib (`lyrics/LrcLib.kt:25`), KuGou (`KuGou.kt:22`), BetterLyrics / Apple TTML (`BetterLyrics.kt:19`, `TtmlLyrics.kt:30`, `LrcRed.kt`), Musixmatch (`Musixmatch.kt:25`), and YouTube captions/music lyrics (`YouTubeLyrics.kt:14` and `:38`). Word timing is carried by `LyricLine.words` and `revealedChars` (`LyricLine.kt:148`, `:290`), rendered by `sharedUi/.../player/PlayerLyrics.kt:822`. Sources may return whole lines; the player also estimates a sweep for line-only lyrics. Hidden providers (SimpMusic, Megalobiz) are deliberate. |
| 10 | AI lyrics translation | **EXISTS** | `shared/.../data/lyrics/LyricsTranslation.kt:55`; AI transcription `app/.../playback/smart/AiLyrics.kt:23` | Gemini toggle `SettingsSheet.kt:1153` (`AppSettings.geminiTranslation`, `AppSettings.kt:591`); auto-translate `SettingsSheet.kt:1181` (`AppSettings.kt:603`); language `SettingsSheet.kt:1136` | Google Translate is the keyless default; Gemini is the optional AI engine and falls back to Google on failure (`LyricsTranslation.kt:143-179`). Translation is per-line and cached; prefetch/offline keep are separate toggles (`AppSettings.kt:597-600`). AI transcription is a separate last-resort path for videos no provider has. |

### Library, offline and discovery

| # | Feature | Status | Main entry point | Settings toggle / exposure | Notes |
|---:|---|---|---|---|---|
| 11 | Offline download and cache size control | **EXISTS** | `app/.../download/Downloads.kt:71`, enqueue `:220`; cache `playback/AudioCache.kt:71` | Download quality `SettingsSheet.kt:554`; Wi-Fi-only `:567`; auto-download liked `:585`; cache limit `:1479` (`AppSettings.audioCacheLimitBytes`, `AppSettings.kt:659`); clear cache `:1500` | Per-track/album/playlist downloads through a dataSync foreground service; HLS/DASH packages are handled by `OfflineHls.kt`/`OfflineDash.kt`. Streaming cache is LRU with a 512 MB default, 10 GB hard ceiling and an unlimited option (`AppSettings.kt:2353-2357`). |
| 12 | Playlist import (Spotify, local M3U) | **PARTIAL** | Spotify: `app/.../data/spotify/SpotifyImporter.kt:50`; dialog `MainActivity.kt:4518` | Launched from the library's Spotify-import action `MainActivity.kt:3325` | Spotify playlist import exists (link -> public page -> search -> playlist). M3U/local-file import is absent: every `.m3u` hit in the repo is an HLS `.m3u8` manifest writer/reader, not a playlist importer. The Spotify matcher also still has the known first-search-hit weakness noted in `docs/AUDIT_FEATURES.md:37`. |
| 13 | Local playlists | **EXISTS** | `app/.../data/spotify/LocalPlaylistStore.kt:26`; read `MainActivity.kt:836`; create `ui/MainViewModel.kt:1577` | Library create/add/rename/delete actions; no Settings row | Stored in their own `bitchord_local_playlists` preference file (`LocalPlaylistStore.kt:27`), with per-row recovery on malformed data. This is also the library that backup currently misses (see #22). |
| 14 | Personalized quick picks | **EXISTS** | `shared/.../data/YtMusicRepository.kt:306`; fallback builder `YouTubeFallback.kt:160`; rules `YouTubeFallbackFeed.kt:17` | No dedicated toggle; follows the signed-in account and local plays. `personalisedAutoplay` (`SettingsSheet.kt:616`) governs Autoplay, not this shelf. | When the music gate is active, Quick picks is an `RD<videoId>` radio seeded by the most-played recent local song, minus anything played in the last hour (`YouTubeFallbackFeed.kt:63-90`). On an open gate it uses YouTube Music's own shelf. Shelf title localization is in `sharedUi/.../screens/HomeScreen.kt:604`. |

### Social, platform and privacy

| # | Feature | Status | Main entry point | Settings toggle / exposure | Notes |
|---:|---|---|---|---|---|
| 15 | Listen together (Jam) | **EXISTS** | `app/.../data/listentogether/ListenTogether.kt:164`; create `:725`, join `:801` | Settings row `SettingsSheet.kt:458`; screen `ui/screens/ListenTogetherScreen.kt:133` | WebSocket party with queue/track sync, host-only control and invite links. Server validation and fallback are covered by `shared/src/commonTest/.../JamInviteTest.kt` and `app/src/test/.../JamInviteLinkTest.kt`. |
| 16 | Home-screen widget | **EXISTS** | `app/.../widget/MediaWidget.kt:43` | No in-app toggle; launcher widget picker | Three providers: square 2x2, wide 4x2, and 4x1 pill (`app/src/main/AndroidManifest.xml:291`, `:302`, `:315`). Transport actions are in `MediaWidgetActions.kt`. |
| 17 | Scrobbling (Last.fm, ListenBrainz) | **EXISTS** | `app/.../data/scrobbling/ScrobbleManager.kt:14`; `LastFM.kt:21`; `ListenBrainzManager.kt:13` | Account & scrobbling screen `ui/screens/AccountAndScrobblingScreen.kt:202`; flags `AppSettings.kt:771-785`; Settings row `SettingsSheet.kt:446` | Last.fm now-playing + scrobble, ListenBrainz playing-now + finished. The screen supports timing thresholds and primary-artist-only. `scrobblingAvailable` is true (`AppSettings.kt:771`), so the "paused for this release" footer is not active. Known non-blocking defects are tracked separately in `docs/AUDIT_FEATURES.md` (duration parsing, validation). |
| 18 | Android Auto | **EXISTS** | `PlaybackService.kt:323` is a `MediaLibraryService`; browse root `:7874`, children `:7912` | No toggle; declared by `app/src/main/AndroidManifest.xml:91` + `res/xml/automotive_app_desc.xml` | Browse tree has Quick picks, recents, playlists, liked and offline (`PlaybackService.kt:7923`). Instrumented coverage exists in `app/src/androidTest/.../AndroidAutoMediaLibraryTest.kt`. Signed-out account shelves can be empty, as noted in the older audit. |
| 19 | Chromecast | **EXISTS** | `app/.../playback/cast/CastController.kt:29`; receiver playback `CastPlayback.kt` | Player cast button `sharedUi/.../player/NowPlayingScreen.kt:1589`; dialog `ui/components/CastDialog.kt:56`; manifest options provider `AndroidManifest.xml:359` | Google Cast sender + MediaRouter (`app/build.gradle.kts:410-411`). Casting needs a stream the receiver can fetch; unsupported tracks show a message rather than failing silently. |
| 20 | Lyrics share card | **EXISTS** | `sharedUi/.../player/LyricsShare.kt:71`; renderer `app/.../ui/player/LyricsShareCard.kt:52` | Player lyrics share action `NowPlayingScreen.kt:1669`, sheet `ui/player/AndroidLyricsShareSheet.kt:64` | Selected lines are laid out on a bitmap card with artwork, plus a story-size variant. Android-only by design; the shared host hook is a no-op on desktop. |
| 21 | Stats / Replay | **EXISTS** | `app/.../data/stats/ListeningStats.kt:56`; recorder `ListeningRecorder.kt:45`; UI `ui/replay/ReplayScreen.kt:89` | Settings row `SettingsSheet.kt:1526`; Replay genres `:1535` | Month-bucketed local aggregates, no upload and no account. Minutes and plays are recorded from the player's existing progress sampler (`ListeningRecorder.kt:62`), not a second timer. Replay stories/share live under `ui/replay/`. |
| 22 | Backup/restore of settings and library | **PARTIAL** | `app/.../data/stats/Backup.kt:41`; export `:63`; import `:90` | Settings rows `SettingsSheet.kt:1558` and `:1567` | Settings and listening history round-trip. "Library" does not: local playlists live in a different preference file (`LocalPlaylistStore.kt:27`) and are not in `BackupFile` (`Backup.kt:134`); downloads are deliberately device-local (`AppSettings.kt:2345`). Restoring on a new phone loses local playlists. |
| 23 | Search history control | **PARTIAL** | `app/.../data/settings/SearchHistory.kt:25`; record `:107`, remove `:113`, clear `:118` | Search screen `sharedUi/.../screens/SearchScreen.kt:123-124` exposes remove and clear | The listener can delete one query or all of them, but there is no "don't record" switch and no retention/incognito setting. Every completed search calls `SearchHistory.record` (`MainViewModel.kt:2471`). |
| 24 | Incognito / private listening | **MISSING** | None | None | No `incognito`, `private mode`, `privateListening`, `historyEnabled` or equivalent exists in `app/`, `shared/` or `sharedUi/`. The write paths that would need gating all exist: local Replay (`ListeningRecorder.kt:62`), YouTube account history (`data/innertube/PlaybackTracker.kt:42`), scrobbling (`ScrobbleManager.kt:36`), Discord presence, and search history (`SearchHistory.kt:107`). |

## What the gated-feed path changes

Nothing in the table is blocked by the `music.youtube.com` gate. Quick picks,
recents, mixes and the YouTube Home are rebuilt by `YouTubeFallback.buildHome`
(`YouTubeFallback.kt:112`) and selected at `YtMusicRepository.ensureGateKnown`
(`YtMusicRepository.kt:1337`). Scrobbling, SponsorBlock, downloads, lyrics and
the widget do not depend on the music gate at all.

Two consequences matter for gap prioritisation:

1. `PlaybackTracker` is the component that writes account-visible watch history
   and therefore feeds the signed-in `www.youtube.com` Home. A private mode that
   only silences local stats would still pollute recommendations.
2. Cover uploads are ordinary YouTube videos, so SponsorBlock's
   `music_offtopic` category, the video caption lyric provider, and the
   cover/original alignment path are all more relevant here than on a pure
   YouTube Music catalogue.

## Open problems / caveats

- The UI has no tempo control at all; `AppSettings.setPlaybackSpeed` is
  currently dead on Android. Do not mistake the desktop speed row for parity.
- Skip silence is advertised in Settings but is a no-op on float output. The
  subtitle says so, but the feature still reads as enabled.
- Spotify import matching remains weaker than the rest of the library path
  (`docs/AUDIT_FEATURES.md:37`); it is not a missing feature, but it is a
  quality gap for cover-heavy libraries where many uploads share a title.
- Backup is a settings/history backup, not a device migration for local
  playlists. Downloads are intentionally excluded.
- Incognito cannot erase server-side YouTube history by itself; suppressing
  `PlaybackTracker` prevents new account-visible plays but cannot undo past ones.
  The UI should say that plainly.

## Proposed unit-test classes

No Kotlin was changed in this read-only audit, so these are the test classes the
plans below should add, not tests that exist today:

- `PlaybackParametersMathTest` - speed/pitch/transition-rate composition.
- `PrivateSessionPolicyTest` - which sinks record in private mode.
- `SearchHistoryPauseTest` - search recording respects the policy.
- `SilenceSkipperTest` - threshold, minimum run and block boundaries.
- `SilenceSkipOffsetTest` - skipped frames map to the correct media position.
- `M3uPlaylistParserTest` - `#EXTINF`, URLs, relative paths, BOM/CRLF, dedupe.
- `M3uPlaylistImportTest` - YouTube-id direct match vs search fallback.
- `BackupLibraryRoundTripTest` - schema 2 settings + local playlists.
- `LocalPlaylistBackupTest` - import merge/replace and malformed input.

Existing coverage that already exercises nearby pure logic:
`EqualizerProcessorTest`, `LoudnessProcessorTest`, `SkipSegmentsTest`,
`WordSyncTest`, `LyricsWordAlignTest`, `LyricsShareCardTest`,
`YouTubeFallbackFeedTest`, `YouTubeFallbackFeedRulesTest`,
`LocalPlaylistStoreTest`, `SpotifyImportMatchTest`, `ScrobbleDurationTest`.

## Top 5 gaps for a heavy listener of VTuber cover songs

1. **Tempo and pitch control (PARTIAL).** Add a persisted `pitchSemitones`
   beside `AppSettings.playbackSpeed` (`data/settings/AppSettings.kt:351`), then
   replace every `setPlaybackSpeed` call on the playback path with one
   `PlaybackParameters(speed, pitch)` helper: `PlaybackService.kt:5922`,
   `:6270-6272`, `:2215`, and the crossfade call sites in
   `playback/CrossfadeController.kt:1258`, `:1411`, `:1816`, `:1866`, `:2398`,
   `:2431`, `:2518-2621`. Surface a speed/pitch sheet from the player's extras
   (the same host that already opens the sleep timer,
   `ui/components/SongActionsSheet.kt:706`) and an optional Settings row; add
   strings in `res/values/strings.xml`. Risks: ExoPlayer's `setPlaybackSpeed`
   preserves the current pitch, so the danger is the opposite one - a new
   `PlaybackParameters(speed)` built with the default pitch would reset it, and
   with call sites in two players the base speed, Automix transition rate and
   listener pitch must be composed once and applied to both decks; large pitch
   shifts can sound artefacted, so clamp to +/-12 semitones and default to 0.
   Battery cost: none when both values are default; Sonic runs per audio buffer
   only while non-default, with no timers or polling. Proposed pure test:
   `PlaybackParametersMathTest` for combining base speed, transition rate and
   semitones (identity, clamping, crossfade cases).

2. **Incognito/private listening (MISSING).** Add an in-memory
   `AppSettings.privateSession` flag (not persisted, cleared when the session
   service dies), a visible player/settings toggle, and one pure policy object
   (`PrivateSessionPolicy.shouldRecord(telemetry, private)`) that every sink
   consults before writing. Gate `ListeningRecorder.onSample/onEnded`
   (`data/stats/ListeningRecorder.kt:62`, `:136`), `PlaybackTracker`
   (`data/innertube/PlaybackTracker.kt:42`; this is the one that protects the
   signed-in Home), `ScrobbleManager.onSongStart`
   (`data/scrobbling/ScrobbleManager.kt:36`), Discord presence updates, and
   `SearchHistory.record` (`data/settings/SearchHistory.kt:107`). Risks: missing
   one sink leaks history; YouTube may still record server-side history unless
   `PlaybackTracker` is suppressed; private mode must not look like a data-loss
   bug, so show an explicit session indicator and do not persist it. Battery
   cost: none; these are boolean guards on events that already happen. Proposed
   tests: `PrivateSessionPolicyTest` (every sink matrix) and
   `SearchHistoryPauseTest`.

3. **Skip silence on float output (PARTIAL).** The current path relies on
   Media3's 16-bit-only processor (`SettingsSheet.kt:809-819`). Add a
   float-domain `SilenceSkipper` in `PrecisionAudioSink.handleBuffer`
   (`playback/audio/PrecisionAudioSink.kt:265`), before `dspChain.process`
   (`:345`), because `DspChain` is in-place and cannot change the frame count.
   It should detect sub-threshold runs for a bounded minimum (the same ~300 ms
   idea as Media3), compact frames, return the consumed input count to the
   renderer, and fold the skipped frames into the position accounting
   `setOutputStreamOffsetUs` (`:402`) already uses for gapless boundaries, so
   lyrics and seek stay aligned. Drive its enabled flag from the same setting as
   Media3's flag (`PlaybackService.kt:5921`, `:6245`). Risks: clicks at cut
   points, position drift against gapless/crossfade, and interaction with
   loudness/equalizer ordering; keep the skipper bypassed when the toggle is off
   so the bit-exact path is unchanged. Battery cost: one linear scan per buffer
   only while enabled, no timer or wake-up. Proposed tests:
   `SilenceSkipperTest` (threshold, minimum run, partial block) and
   `SilenceSkipOffsetTest` (skipped frames map to the right media time).

4. **M3U/local playlist import (PARTIAL).** Add a pure parser
   `shared/.../data/playlists/M3uPlaylist.kt` handling BOM/CRLF, `#EXTM3U`,
   `#EXTINF:duration,Artist - Title`, comments, relative paths, absolute paths
   and YouTube URLs; then add `LocalPlaylistStore.importPlaylist` beside
   `savePlaylist` (`data/spotify/LocalPlaylistStore.kt:73`). A
   `MainViewModel.importM3u(uri)` should open with
   `ActivityResultContracts.OpenDocument`, resolve YouTube ids directly, resolve
   local entries against the selected music folder, and fall back to
   `YtMusicRepository.search` + `data/sources/TrackMatcher.kt` with bounded
   concurrency. Exposure: an "Import playlist" action next to the existing
   Spotify import (`MainActivity.kt:3325`/`:4518`). Risks: SAF permissions and
   relative-path traversal, huge files, encoding differences, and one network
   search per unmatched row; cap concurrency, cancel when the screen leaves, and
   let the user review unmatched rows. Battery cost: user-triggered, bounded,
   no polling. Proposed tests: `M3uPlaylistParserTest` (formats, relative paths,
   dedupe, malformed lines) and `M3uPlaylistImportTest` (YouTube-id vs search
   fallback).

5. **Backup/restore of the local library (PARTIAL).** Extend
   `Backup.BackupFile` (`data/stats/Backup.kt:134`) with a serializable
   `localPlaylists` list, bump `SCHEMA_VERSION`, and have `Backup.exportTo`
   (`:63`) read `LocalPlaylistStore.playlists` while `importFrom` (`:90`)
   validates the whole file before replacing. Add `export`/`import` helpers to
   `data/spotify/LocalPlaylistStore.kt:26` and extend the Settings import
   summary (`ui/screens/SettingsSheet.kt:1558-1578`). Keep downloads and audio
   cache out on purpose (`AppSettings.kt:2345`); they are device-local files,
   not library. Risks: schema compatibility (older builds must refuse, not
   partially read), duplicate playlists on repeated import (choose replace or a
   deduped merge explicitly), and song metadata drift; validate before writing.
   Battery cost: only when the listener taps export/import. Proposed tests:
   `BackupLibraryRoundTripTest` and `LocalPlaylistBackupTest`.

