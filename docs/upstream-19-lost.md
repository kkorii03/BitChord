# Lost Korean-fork behaviour after the v1.9 merge

Source of truth: `.dswork/ours-hunks.txt` (321 conflict hunks; all lines shown there are the
Korean-fork side that lost). Current tree = `0fce7db4` (merged, upstream side won).

Method: every hunk was read and assigned to a bucket. For buckets A-E, I and J the named
identifiers were grepped in the merged tree (source only, excluding `docs/` and tests) to see
whether the behaviour still exists. Only hunks whose behaviour is **missing or materially
degraded** are listed in A-E/I/J. F/G/H list all hunks because v1.9 replaces those areas.
Line numbers are the pre-merge fork line numbers from the hunk headers.

---

## A - lyrics-sync (lyric offset / sync correction / chroma alignment / LRC timing)

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1427` | Kept audio fetched only for lyric timing (`timingKeep`, 15 min / 16 entries) so the cache sweep did not delete the original before `LyricsTiming` measured it. Definition is gone; only the call `keptForTiming()` at AudioCache.kt:1395 survives, so the fork build would not compile. | `keepForTiming`, `keptForTiming`, `timingKeep`, `TIMING_KEEP_MS`, `LyricsTiming.keepForTiming` | Re-add the map + helpers to `AudioCache` and call `AudioCache.keepForTiming(videoId)` from `LyricsTiming` where it fetches the original/next video. Merge into v1.9 `AudioCache.dropForeignEntries` sweep. |

Everything else in A survives: `SongLyricsSync`/`onAiTiming` (LyricsOffsetSheet.kt:96,323),
`withHeardWords`/`withEstimatedWords`/`isWordSynced` (PlayerLyrics.kt:2601-2605),
`lyricsSourceOrder`/`prioritizeSyllableSync`/`artistAliases` (LyricsController, MainActivity),
`lyricsProviderStates` (MainViewModel.kt:442).

## B - song-version verdict / no-MV / audio-instead-of-video / cover-piano rejection

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `app/src/main/java/com/music/bitchord/MainActivity.kt:2053` (+ `:7464`) | Automatic *before-audio* video-to-catalogue-song swap effect, de-duplicated per process by `musicOnlyTried`; the effect is gone from the merged MainActivity (only the pure helper `autoMusicOnlyEffectKey` and its test remain). Manual `switchToMusicOnly` still exists. | `autoMusicOnlyEffectKey`, `musicOnlyTried`, `SongVersionPolicy.mayAutoSwapBeforeAudio`, `switchToMusicOnly`, `beforeAudioOnly` | Add a `LaunchedEffect(autoMusicOnlyEffectKey(player.song?.videoId, preferMusicOnly, videoRadio))` in the MainActivity player composition, calling `switchToMusicOnly(..., pauseWhileResolving=false, alignVersion=false, beforeAudioOnly=true)` and gating on `SongVersionPolicy.mayAutoSwapBeforeAudio`. |
| `app/src/main/java/com/music/bitchord/MainActivity.kt:1482` (+ `:2053`) | `alternateVersionCache` / `hasAlternateVersion`: looked up whether a video/audio twin exists **only when the player action sheet is open**, caching hits and misses per process. No equivalent remains (`missingVersions` only records failed manual switches). | `alternateVersionCache`, `hasAlternateVersion`, `YtMusicRepository.songVersion`, `YtMusicRepository.resolveVideo`, `SongVersionPolicy.keepsLocalFile` | Re-attach in MainActivity around the `SongActionsMenu`/player state; feed the version row from the cache rather than always offering it. |
| `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:2207` | Setters `setPreferMusicOnly`, `setSkipNonMusicParts`, `setSkipSponsorParts`; all three are absent from the merged AppSettings, so the stored switches cannot be changed (the flows themselves still read the values). | `setPreferMusicOnly`, `setSkipNonMusicParts`, `setSkipSponsorParts`, `KEY_PREFER_MUSIC_ONLY`, `KEY_SKIP_NON_MUSIC_PARTS`, `KEY_SKIP_SPONSOR_PARTS` | Re-add beside the other AppSettings setters and wire the playback/settings rows (see H). |

Survives: `audioSwitchTargetOf`/`audioSwitchQueries` (TrackMatcher.kt:1062-1082),
`counterpartAudio`/`CounterpartSwitch`/`parseCounterpartSong` (YtMusicRepository, InnertubeParser),
`videoChannelId` (VideoRadioSources.kt:216), `logVersionSwapRefused` (PlaybackService.kt:3290),
`HqAudioVerify`, `SongVersionPolicy`, `preferMusicOnlyDefault` (AppSettings.kt:4019).

## C - korea-blocked fallback / proxy playback / alternate ATV copy

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:2563` | One immediate gate re-check after proxy settings move: `recheckGateNow()` after `MusicGateFallback.resetAll`; `MainViewModel.kt:1582` still calls it but the repository method is gone. | `recheckGateNow`, `ensureGateKnown`, `MusicGateFallback.resetAll`, `ensureSessionForGate`, `isMusicGateSignal` | Add `suspend fun recheckGateNow() = ensureGateKnown()` beside the existing `ensureGateKnown()` in YtMusicRepository (it exists at YtMusicRepository.kt:272+). |

Survives: `MusicGateFallback`, `ProxyPlayback`/`isRemembered` (ProxyPlayback.kt:246),
`YouTubeProxy`, `ResolutionOrder`, `resolveViaProxy`, `resolveWithModulePriority`,
`SourceCooldown`, `SourceRegistry.instance`.

## D - video-radio

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:1860` | Memoised `radio(videoId)` answers in a bounded access-order LRU (`radioCache`, `RADIO_CACHE_SIZE`) so autoplay top-ups across track changes did not re-run `next` for the same seed. Merged `radio()` (YtMusicRepository.kt:1773) calls `Innertube.next` every time. | `radioCache`, `RADIO_CACHE_SIZE`, `fun radio(`, `parseWatchQueue` | Wrap `radio(videoId, preferVideo)` in YtMusicRepository with the LRU keyed by `videoId + preferVideo`. |

Survives: `videoRadioModeFor` (VideoRadioAutoplay.kt:48), `Song.videoRadio`, `parseWatchQueue`,
`videoChannelId`, `VideoRadioSources`, `videoRadioFallbackStage`.

## E - diagnostic log lines / featurehealth / DiagLog / BitChordLog

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `app/src/main/java/com/music/bitchord/BitChordApplication.kt:124` | Routed the shared data layer's `DebugLog` into `TrackLog.write` on **every** build so the exportable diagnostic log showed lyrics/YouTube-client activity. Merged code only sends DebugLog to logcat in debug builds. | `DebugLog.sink`, `TrackLog.write`, `DiagnosticsExport` | In `BitChordApplication.onCreate` set `DebugLog.sink` to `TrackLog.write` unconditionally (or before the debug branch). |
| `app/src/main/java/com/music/bitchord/BitChordApplication.kt:498` | Crash trail + lifecycle diagnostics: custom `installCrashHandler` calling `TrackLog.crash`, `logProcessStart` warm/cold marker, and `onTrimMemory`/`onLowMemory` flushing `TrackLog` + `FeatureHealthCounters`. Definitions are absent; call sites remain (BitChordApplication.kt:104,108). | `installCrashHandler`, `TrackLog.crash`, `logProcessStart`, `processMarkerTimestamp`, `onTrimMemory`, `onLowMemory`, `FeatureHealthCounters.flush` | Re-add the missing functions to `BitChordApplication` (or the TrackLog persistence layer) and keep the existing lifecycle callbacks. |
| `app/src/main/java/com/music/bitchord/data/innertube/AndroidStreamHooks.kt:27` | Release-build `TrackLog.echo`: warnings/errors always, selected debug tags via `RELEASE_DEBUG_TAGS`, line truncation at `RELEASE_LINE_CHARS`. Merged echo is debug-only; the tags constant remains unused. | `TrackLog.echo`, `RELEASE_DEBUG_TAGS`, `RELEASE_LINE_CHARS`, `Log.isLoggable` | Replace the `if (BuildConfig.DEBUG)` echo in `AndroidStreamHooks.installEarly` with the fork's level/tag/truncation rule. |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:1164` | `logSkipped` / `rememberSkipped`: one `TrackLog` line for every transition abandoned before it became audible (`crossfade: skipped A->B reason=`). | `logSkipped`, `rememberSkipped`, `CrossfadeSkipReason.wire`, `skippedPair` | If v1.9 Automix keeps skip reasons, add the log line in its skip/refuse path; otherwise drop with G. |
| `app/src/main/java/com/music/bitchord/playback/QueueCoordinator.kt:129` | Logged user-queue consumption: `queue: remove n=... at=... size=... by=consume-user-queue`. | `by=consume-user-queue`, `QueueCoordinator`, `TrackLog.d` | Add the line where v1.9 consumes the user queue. |
| `app/src/main/java/com/music/bitchord/playback/StreamChoice.kt:182` | `forgetUrl` warned when a rate-limited URL had no pinned choice (`no stream choice to forget for rate-limited URL`), preserving the distinction between "pin survives" and "pin gone". | `forgetUrl`, `canonicalUrl`, `no stream choice to forget` | Re-add the warning branch in v1.9 `StreamChoice.forgetUrl`. |
| `app/src/main/java/com/music/bitchord/MainActivity.kt:1780` | Richer audio-switch refusal log: `no answer` vs `same id`, plus `row video=`/`typeUnknown=`. Merged line is the generic `audio switch found no distinct official song`. | `audio switch found no distinct`, `typeUnknown`, `isVideo` | Extend the existing log at MainActivity.kt:1671. |

Survives: `FeatureHealthCounters`, `StartupTrace.firstHomeContent`, `PlayerExpandGuard.logExpand/logRefused`,
`hqSearchLine`, `transitionReason`, `missingMeta`, `songCacheEvictionCount`.

## F - quality-upgrade (hq-swap, prefetch, Qobuz/FLAC replacement) - to be replaced by v1.9 Direct Source Stream

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt:390` | Bounded retry window (`retryAfter`) for an inconclusive second look instead of treating it as a verdict. | `QualityUpgrade`, `retryAfter`, `secondLook` | Only if v1.9 Direct Source still needs a second-look retry; otherwise discard. |
| `app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt:604` | Late-answer handling: exclude late source/host, require `worthSwapping` and `sameRecordingAs` before accepting. | `late`, `excludedSources`, `excludedHosts`, `SourceResolver.worthSwapping`, `sameRecordingAs` | Fold into Direct Source `DirectSourceGate.lookup`/`startedOnYouTube`. |
| `app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt:813` | Decoder's actual source identity (`NerdStats.current`, `SourceRegistry.config`) wins over a stale `StreamChoice`/stored verdict for the quality badge. | `NerdStats.current`, `SourceRegistry.config`, `SourceKind.YOUTUBE`, `StreamChoice.isSubstitute` | Reuse in v1.9's source badge/quality row. |
| `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:627` | `completeUpgradeRendition` + `CacheRenditionChoice`: prefer a complete verified `#hifi` rendition over the base Opus copy, deferring a mid-play prefetch key change. | `completeUpgradeRendition`, `CacheRenditionChoice`, `upgradeGeneration`, `upgradeKeyDeferred`, `META_HQ_LOSSLESS` | Re-attach to v1.9 cache rendition selection used by Direct Source. |
| `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:2078` | Read-ahead warm-up substitute gated by `HqAudioVerify.verify` (cover/live/edit refused before pinning) and `SourceResolver.rememberOriginal`. | `prefetchSubstitute`, `HqAudioVerify.verify`, `rememberOriginal`, `StreamChoice.remember`, `logHqTier` | Direct Source should keep the same verification gate before pinning an addon stream. |
| `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1713` | Whole-rendition bookkeeping: `playbackKeysInUse`, `keyIdleSignals`, `wholeRenditionJobs`, `losslessPrefetchAttempted`, `pendingLowQualityDrops`, `readAheadWrites`. | the six identifiers above | Review for the v1.9 Direct Source prefetch/cache writer; keep what still applies. |
| `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:1504` | Purge index handling (`purgeCandidates`, `purgeCandidate`, `forgetPurgeCandidate`) so the sweep does not re-walk every cache key. | `purgeCandidates`, `purgeCandidate`, `forgetPurgeCandidate`, `rebuildPurgeIndex` | Merge into v1.9 `AudioCache.dropForeignEntries`. |
| `app/src/main/java/com/music/bitchord/download/Downloads.kt:2290,2644,2696,2907,2934` | HQ download routing: reuse existing lossless file, source route, `routeForVerified` (exact verified source/track/tier), DASH/HLS manifest assembly (`OfflineDash`, `ManifestFile`), timeout-vs-empty diagnosis in `fromSources`, `excludeSources`. | `routeForVerified`, `HqDownloadSource`, `routeFromSource`, `OfflineDash`, `ManifestFile`, `fromSources`, `excludeSources` | Keep only if Direct Source downloads addon streams; otherwise the v1.9 download path replaces it. |
| `app/src/main/java/com/music/bitchord/download/Downloads.kt:1465` | `deleteCollection` keeps the collection record when any file could not be deleted and returns false so the UI can say so. | `deleteCollection`, `forgetCollection` | Re-attach in v1.9 Downloads collection deletion. |
| `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:778,966,1009,1317,1479,1534,1651,1819,1927` | HQ resolver machinery: `strictOriginal`, Dolby Atmos as first-class lossless-ish download, `SearchOutcome`/`SearchReport` conclusive verdicts, `SourceCooldown`, `hq-search` lines, tier ordering and `isBetter`. | `SearchOutcome`, `SearchReport`, `SourceCooldown`, `hqSearchLine`, `HqQuality`, `isDolbyAtmos`, `isBetter` | Most is still present but the `strictOriginal` parameter was dropped from `bestAcross`/`matchAndStream` while call sites still pass it (SourceResolver.kt:223,259,333,402,463) - reconcile before reuse. |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:2269,7347,8052` | Upgrade decision helpers: served-format check, manual-vs-automatic answer deferral (`defersAutomaticAnswerToManual`), screen-off shelve of an upgrade. | `defersAutomaticAnswerToManual`, `swapIn`, `QualityUpgrade.shelve`, `hqSwapOwnsStandby` | Re-express in Direct Source's start/upgrade path. |
| `app/src/main/java/com/music/bitchord/BitChordApplication.kt:286` | Background scheduling: `AppForeground.onForeground/onBackground` for `MvSoundRedownload`, `HqLibraryUpgrade`, `Downloads.onForeground`, `mvRepairShouldReschedule` collector, `HqVerificationStore.init`, `LikedAutoDownload.init`. All call sites are gone from merged onCreate. | `AppForeground.onForeground`, `MvSoundRedownload.scheduleIfEnabled`, `HqLibraryUpgrade.scheduleIfEnabled`, `HqVerificationStore.init`, `LikedAutoDownload.init`, `mvRepairShouldReschedule` | Re-attach these init/schedule calls in v1.9 `BitChordApplication.onCreate`/foreground callbacks. |
| `app/src/main/java/com/music/bitchord/download/Downloader.kt:7,320` | Downloads opened through `ProxyPlayback.mediaClientFor(url)` so a proxy-only addon host is reachable. | `ProxyPlayback.mediaClientFor`, `Downloader.open` | Ensure v1.9 Downloader uses the proxy-aware client. |

## G - automix/crossfade fixes - to be replaced by v1.9 Automix

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:991,1164,1813,1855,3355,3972` (+ `:23,50,1061`) | Event-driven ticker (idle wait instead of 4 Hz polling), `rememberSkipped`/one-shot skip logging, `automixFallback` parameter, arm deadline including `AutomixWorkPolicy.STANDBY_LEAD_MS`, `CrossfadeGain.mixRise/mixFall`. | `tickerWake`, `rememberSkipped`, `armDeadline`, `STANDBY_LEAD_MS`, `CrossfadeGain` | Compare against v1.9 Automix; keep any of these the new engine did not adopt. |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt:92,111,695,1279,1333,1472,1750,1847,1866,1906,1915,2082` | Spoken-word block, gapless/calm/rhythmic character, beat alignment/stretch windows, filter variants, echo disabled (`ECHO_RIDE` -> `BASS_HANDOFF`). | `TransitionCharacter`, `GAPLESS_TEXT`, `BLOCKED_TEXT`, `beatAlignmentRate`, `FilterTransitionVariant`, `ECHO_RIDE`, `BASS_HANDOFF` | Replace with v1.9 Automix planner; verify gapless/calm handling is not lost. |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt:82,684` | `MAX_ALIGN_STRETCH`, `speedUpBetween`, tempo-distance rejection. | `MAX_ALIGN_STRETCH`, `speedUpBetween`, `beatMatchable`, `tempo-distance` | Replace with v1.9 Automix policy. |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/AdvancedAutomixPlannerTest.kt:98,156` | Tests for stable fallback gesture per pair and no advertised `ECHO_RIDE`/echo tail. | `fallbackGestureIsStablePerPair`, `ECHO_RIDE`, `echoSeconds` | Port to v1.9 Automix tests or drop with G. |
| `app/src/main/java/com/music/bitchord/playback/smart/AudioDecoder.kt:132,208,258,423` | `decodeRawWith` refactor so both file and `MediaDataSource` sources share one decode loop, with channel/rate on `onBuffer`. | `decodeRawWith`, `decodeRaw`, `onBuffer`, `MediaDataSource` | Check v1.9 analysis decoder; the shared loop is still useful. |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt:377,1611` | Single-threaded request dedupe (`requestPending`) + `awaitWindow` non-polling wait for the next-song bundle; `vocalMask` in features. | `requestPending`, `awaitWindow`, `windowWaiters`, `hasWindow`, `vocalMask` | Keep the executor/waiter model when wiring v1.9 Automix analysis. |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/AnalysisStore.kt:339` | Analysis store `SCHEMA_VERSION = 2`. | `SCHEMA_VERSION` | Set v1.9 analysis schema explicitly if it differs. |

## H - downloads / settings / menus / video playback / tab bar / mini player UI - to be replaced by v1.9

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `app/src/main/AndroidManifest.xml:273` | `ACTION_SEND` intent filter for `.json`/`.octet-stream` settings import. | `SettingsTransferInbox`, `android.intent.action.SEND` | Re-add to v1.9 manifest if the settings-share feature is kept. |
| `app/src/main/java/com/music/bitchord/MainActivity.kt:11,185,347,601,782,1088,1453,1994,2705,4132,4160,4288,4497,4688,5257,5373,5583,5800,6140,6301` | Shell/player UI: launch-intent fingerprints, `setPlayerShown`+`PlayerExpandGuard`, startup trace, video-radio pref, player sheet motion, browse/artist cards, private-listening notice, search-history actions, root-tab chrome, playlist batch notice, song/browse menu hosts. | `setPlayerShown`, `handledLaunchFingerprints`, `rootTabChrome`, `songMenuBody`, `browseMenuBody`, `PlaylistPickerSheet`, `PlayerSheetMotion` | v1.9 MainActivity is the base; re-attach only fork-only behaviours (E/J rows above). |
| `app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt:13,299` | Local media rows with cleaned title/artist/album/artwork/track number/dates and string fallbacks. | `isEligibleLocalMusic`, `trackNumberFromMediaStore`, `localDateAddedSeconds`, `local_track_fallback` | Merge field population into v1.9 LocalMediaRepository. |
| `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:678,1415,3905,3960` | `preferMusicOnly`/`skipNonMusicParts`/`skipSponsorParts` declarations, fresh-install default write, key constants. | `preferMusicOnly`, `skipNonMusicParts`, `skipSponsorParts`, `preferMusicOnlyDefault` | Values survive; re-add setters (B) and settings rows (below). |
| `app/src/main/java/com/music/bitchord/playback/AudioOutputPolicy.kt:42,69` | Float policy: phone speaker always PCM16; every external mix path may take float; route encodings do not gate it. | `shouldUseFloatOutput`, `OutputPcmMode.FLOAT_32`, `AudioRouting.Kind.PHONE` | Keep in v1.9 output negotiation. |
| `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:212,456,496,1597,1728,1880,2061,2305,3175` | Home load-dedupe job, lyrics provider flow + load params (`alternatives`, `artistKnown`, `artistAliases`), deferred startup work, explore/library lazily shown, merge-on-refresh, `RECENTS_TITLE`/`STARTUP_DEFER_MS`. | `homeLoadJob`, `lyricsProviderStates`, `loadLyrics`, `onExploreShown`, `refreshHome`, `STARTUP_DEFER_MS`, `RECENTS_TITLE` | Most symbols survive; verify the refresh-joins-load race fix is retained. |
| `app/src/main/java/com/music/bitchord/ui/components/BrowseActionsMenu.kt:8,36,199,322`; `DownloadManagerSheet.kt:21,219`; `FrostedTopBar.kt:205`; `GlassNavBar.kt:387`; `LiquidGlass.kt:122`; `MiniPlayer.kt:430,472`; `PlaylistPickerSheet.kt:18,139,374` | Browse header/actions, downloads sheet (HQ progress/cancel-all), reduce-motion top bar, glass nav tint, private-listening icon in mini player, playlist picker insets. | `BrowseSheetHeader`, `glassBarTint`, `hq_library_progress`, `private_listening_indicator`, `PlaylistPickerSheet` | Port any visual/behaviour not present in v1.9 equivalents. |
| `app/src/main/java/com/music/bitchord/ui/components/SongActionsMenu.kt:18,29,44,89,120,138,275,309,503,526,670,757,815,1476,1503,1519`; `SongInfoDialog.kt:182` | Full song action sheet: library/playlist/queue/share/album/artist/like rows, cover original title + cover-channel toggle, sleep/speed/lyrics-offset/private rows, `GroupSeparator`, `TintedSheet`, `SongInfoPage`. | `SongMenuBody`, `GroupSeparator`, `TintedSheet`, `SongInfoPage`, `cover_original_title`, `lyrics_offset` | v1.9 menu is the base; migrate fork-only rows (cover channel, lyrics offset, sleep timer, copy log). |
| `app/src/main/java/com/music/bitchord/ui/player/CanvasArtworkPlayer.kt:241,340,466` | Canvas clip window (`clipStartMs`/`clipEndMs`) looping and frame refresh. | `clipStartMs`, `clipEndMs`, `frameCapturePx`, `REPEAT_MODE_ONE` | Check v1.9 canvas player. |
| `app/src/main/java/com/music/bitchord/ui/screens/AccountAndScrobblingScreen.kt:298`; `DetailScreen.kt:3,2283,2353,2460,2589`; `DiscordScreen.kt:3`; `EqualizerScreen.kt:806`; `HistoryScreen.kt:27`; `ListenTogetherScreen.kt:3,605` | Last.fm now-playing switch, detail card shapes (artist circle, artwork corner), equalizer preset scrolling sheet, history loading-timeout imports. | `LastfmNowPlaying`, `SMALL_ART_CORNER`, `PresetSheet`, `rememberLoadingTimedOut` | Port only fork-specific rows; otherwise v1.9 screens win. |
| `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:3,162,184,370,687,863,960,1199,1262,1321,1408,1511-2565,3113,3263,3432,3945` | Settings: audio-quality group (sources, Wi-Fi/cellular quality, Dolby), downloads/liked auto-download, playback (video radio, **prefer music only**, **skip non-music/sponsor**), loudness, automix, skip silence, spatial, storage, advanced, `QualitySheet`/`AutomixPerformanceSheet`/`DownloadQualitySheet`, switch colours. | `SearchableSettingsGroup`, `QualitySheet`, `AutomixPerformanceSheet`, `DownloadQualitySheet`, `setSkipNonMusicParts`, `setPreferMusicOnly` | Re-add the prefer-music-only and SponsorBlock rows (they are absent in merged SettingsSheet) and their setters (B). |
| `tv/src/main/java/com/music/bitchord/ui/tv/screens/TvSettingsScreen.kt:291` | TV settings interface-size row. | `tv_interface_size` | Check v1.9 TV settings. |
| `app/src/main/java/com/music/bitchord/ui/theme/Theme.kt:112,205` | Apple-like typography/shapes and `withPhraseLineBreak()` applied to the whole type scale (Korean word wrapping). Shape/typography survive; `withPhraseLineBreak` is gone. | `BitChordTypography`, `BitChordShapes`, `withPhraseLineBreak`, `PhraseLineBreak` | Re-apply `PhraseLineBreak` to v1.9 `Typography` (see I). |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/model/Models.kt:162,476` | Cover fields (`originalTitle`, `originalArtist`, `coverSinger`, `coverOriginalArtist`), `Song.videoRadio`, card `isArtist`/`isVideo` flags. | `originalTitle`, `coverSinger`, `coverOriginalArtist`, `videoRadio`, `isArtist` | Some survive (`videoRadio`, `isArtist`); re-add missing cover fields. |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt:852,899`; `LongPressOrigin.kt:19,29,106,176,218`; `Skeletons.kt:181`; `ui/player/LandscapePlayer.kt:472`; `LyricsOffsetSheet.kt:90`; `MeshGradient.kt:144`; `NowPlayingScreen.kt:977,1905,2548,3855,3896`; `PlayerControls.kt:504,689,848,1042,1262,1542`; `PlayerLyrics.kt:2282,3166,3188`; `ui/screens/HomeScreen.kt:80,205,302,338,394,584,699,740`; `SearchScreen.kt:107,520,712,966,1286` | Shared UI: search playing-bars animation, long-press capture scaling, skeletons, landscape star, `SongLyricsSync`, mesh/foreground gating, low-power artwork, automix status line, private/mini player text, lyric blur/slide/word sweep, Home recents/private notice, search pills/rows. | `LongPressOrigin`, `MAX_CAPTURE_PX`, `CanvasAwareArtworkMesh`, `automix_analysis_status`, `SongLyricsSync`, `localizeShelfTitle`, `browseFallbackLabel` | v1.9 sharedUi is the base; port fork-only behaviours (A/E/I rows above). |

## I - Korean-only UI text / locale handling

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `app/src/main/java/com/music/bitchord/playback/AudioOutputStatus.kt:1` | Korean output labels: default device `"시스템 기본값"`, hearing aid `"보청기"`, Korean fallback details for phone/USB/Float rejection. Merged `AudioOutputStatus` is English except callers that still pass the default. | `AudioOutputStatus.Snapshot`, `deviceName`, `bluetoothProfile`, `"시스템 기본값"`, `"보청기"` | Re-add localized strings (or move them to the string resources) in `AudioOutputStatus`/AudioPipelineDialog. |
| `app/src/main/java/com/music/bitchord/playback/audio/OutputNegotiator.kt:34` | Korean `FallbackReason` labels: `"경로 제한 (기기 프로필)"`, `"OS 제한 (시스템 오디오 정책)"`, `"Direct USB 사용 불가"`, `"디코더 제한"`. Only the USB 16-bit detail survives in the merged tree. | `FallbackReason`, `ROUTE_LIMITATION`, `OS_LIMITATION`, `DIRECT_USB_UNAVAILABLE`, `DECODER_LIMITATION` | Re-localize the enum labels in v1.9 OutputNegotiator. |
| `app/src/main/java/com/music/bitchord/ui/components/AudioPipelineDialog.kt:577,618` | Korean pipeline labels: `"전송 방식"`, `"활성 (Direct 유저스페이스 USB)"`, `"Direct (시스템 믹서 우회)"`. Only some `시스템 기본값` text survives. | `PipelineRow`, `DIRECT_USB`, `transportType.label`, `route_phone` | Restore in v1.9 AudioPipelineDialog. |
| `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:960` | Korean search aliases on settings rows (`"이어폰"`, `"추천"`, `"비디오"`, `"건너뛰기"`) so the Korean settings search finds the English-labelled rows. All four are absent from the merged SettingsSheet. | `row(` search terms, `personalised_autoplay`, `video_radio_continue`, `skip_non_music_parts` | Add the Korean terms to v1.9 settings search rows. |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt:1286` | `SearchFilter.labelResource()`: filter pill names follow the app language instead of hardcoded Korean; the function is gone from source (tests still call it). `browseFallbackLabel` survives. | `SearchFilter.labelResource`, `search_filter_all`, `SearchFilter.entries.localizedLabel` | Re-add `labelResource()`/`localizedLabel()` in v1.9 SearchScreen and use it for the filter pills. |
| `app/src/main/java/com/music/bitchord/ui/theme/Theme.kt:112` | `withPhraseLineBreak()` applies `PhraseLineBreak` to every typography style (Korean wraps between words, not inside them). Merged Theme does not apply it; only PlayerLyrics uses `PhraseLineBreak`. | `withPhraseLineBreak`, `PhraseLineBreak`, `BitChordTypography` | Wrap the v1.9 `Typography` with `withPhraseLineBreak()` in Theme.kt. |

Survives: `localizeShelfTitle` (HomeScreen.kt:716), `browseFallbackLabel` (SearchScreen.kt:1251),
`PhraseLineBreak` definitions, Korean lyrics-timing strings.

## J - general crash/race fixes

| file:line | what our code did (one line) | identifiers to grep | where to re-attach in v1.9 code |
|---|---|---|---|
| `app/src/main/java/com/music/bitchord/MainActivity.kt:1370` | Dismissed the player when the queue emptied (`player.song == null && showNowPlaying`) so a later track cannot open the sheet over the list by itself. No equivalent in merged MainActivity. | `showNowPlaying`, `dismissPlayer`, `player.song?.videoId` LaunchedEffect | Add the `LaunchedEffect(player.song?.videoId)` in v1.9 MainActivity. |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:11140` | Event-driven collectors for `skipNonMusicParts`, `skipSponsorParts`, `privateListening`, `preferMusicOnly`; toggles applied to the current track/queue immediately instead of waiting for the next play. All `.drop(1).collect` branches are absent. | `skipNonMusicParts.drop`, `skipSponsorParts.drop`, `privateListening.drop`, `preferMusicOnly.drop`, `refreshSkipSegmentsForSettings` | Re-add the four `scope.launch { ... drop(1).collect { ... } }` blocks in v1.9 PlaybackService init. |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt:377` | `awaitWindow`/`windowWaiters`: next-song preparation suspended on the analysis publication instead of polling; `hasWindow`/timeout logic. `windowWaiters` exists but no `awaitWindow` remains. | `awaitWindow`, `windowWaiters`, `hasWindow`, `completeWindow`, `NEXT_PREP_MIX_WAIT_MS` | Re-add the suspend waiter API and use it from v1.9 `NextTrackPreparation`/Automix prep. |

Survives: `handledLaunchFingerprints`, `homeLoadJob`/`refreshHome`, `onExploreShown`,
`resampleTargetRate`, `lastManualSeekAtMs`, `emptyRefreshesRemaining`, `AudioCache` playback-key maps,
`PlayerExpandGuard`, `StartupTrace.firstHomeContent`, `deleteCollection`, silence-skip offset.

---

## Direct Source Stream artist matching (v1.9)

`DirectSourceGate.lookup` (app/.../playback/DirectSourceGate.kt:200) calls
`SourceResolver.directStream` (app/.../data/sources/SourceResolver.kt:369), which calls
`bestAcross(addons, target, requestForNow())` (SourceResolver.kt:373) with **`strictLength = false`,
`requireSharedArtist = false` and no `strictOriginal`**. The actual artist comparison therefore happens
inside `TrackMatcher.score`:

- `TrackMatcher.score` - `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:819`
  (`val creditedArtist = artistScore(target.artist, candidate.artist) ?: ARTIST_SHARED.takeIf { namesTheSameArtist(...) ... }`)
- `TrackMatcher.artistScore` - `TrackMatcher.kt:1847` -> `sharesArtist` (`TrackMatcher.kt:205`) ->
  `namesTheSameArtist` (`TrackMatcher.kt:258`) -> `artistNames`/`artistVariants`
  (`TrackMatcher.kt:1868`, `TrackMatcher.kt:5831`).

Those compare whole-word **artist name sets only**. Unlike title matching (`romajiOf` at
`TrackMatcher.kt:794`), there is no cross-script transliteration or alias table for artists. A Qobuz
row crediting only `マイリー・サイラス` therefore fails `artistScore`/`namesTheSameArtist` for a
`Miley Cyrus` target.

**Answer: no, it is not unconditionally rejected.** The artist-name check fails, but `score()` then
falls through to the `CREDITS_DISAGREE` override at `TrackMatcher.kt:861`:
`CREDITS_DISAGREE.takeIf { !target.isVideo && withinSeconds(candidate, target, CREDIT_OVERRIDE_SEC) }`,
where `CREDIT_OVERRIDE_SEC = 2` (`TrackMatcher.kt:2043`). A Qobuz copy whose runtime is within 2 s of
the YouTube target is therefore accepted despite the Japanese-script artist, with the artist penalty.
Only a row more than 2 s away is rejected as `artist`/`no match`. If the Japanese credit must match
first time regardless of duration, v1.9 needs an artist alias/transliteration step; the fork's
`artistVariants`/`ArtistNameIndex` is only same-script.

---

## Files written

- `.dswork/lost-inventory.md` (this file)

## Bucket counts (rows)

A=1, B=3, C=1, D=1, E=7, F=13, G=7, H=15, I=6, J=3. Total = 57 (the F/G/H rows are grouped by
file/area, not one row per hunk; ~321 source hunks collapsed).

## Unresolved

- The merged tree contains several **undefined references** left by taking the upstream side:
  `installCrashHandler`/`logProcessStart`/`onTrimMemory`/`onLowMemory`, `keptForTiming`'s definition,
  `YtMusicRepository.recheckGateNow`, `SearchFilter.labelResource`, and `strictOriginal` passed to
  `bestAcross`/`matchAndStream` whose merged signatures dropped that parameter. These are compile
  blockers, not just lost behaviour, so this inventory cannot be verified by building.
- Whether v1.9 Automix already covers the fork's gapless/calm character rules was not verified beyond
  symbol presence (the fork symbols are absent).
- The Korean string loss was checked in `.kt` sources only; `res/values-ko` resources may still carry
  some of the same wording.
