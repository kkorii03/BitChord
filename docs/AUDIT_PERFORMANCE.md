# BitChord performance audit (read-only)

Date: 2026-10-18. Scope: `app`, `sharedUi`, `shared`. Method: static reading of
the code only; no Gradle build, no profiler, no device run. Frequencies are
derived from the constants in the code (frame loops, `delay`s, poll intervals)
and are marked as guesses where the code alone cannot prove them. Impact and
confidence are estimates, not measurements.

Terminology: **per frame** = ~60/s while the surface is drawing; **per second**
= wall-clock cadence; **per track** = once per song change; **once** = startup
or a user action.

> The app is already unusually well tuned in several places (position split out
> of `PlayerState`, lyric sweep reading state in draw lambdas, mesh decodes
> staggered, prefetch bounded to two tracks). This audit targets what is left,
> and explicitly lists the things that were checked and found *not* to be
> problems at the end.

---

## TOP 20 (priority = impact x confidence / effort)

| # | Finding | Evidence | Frequency | I/C/E |
|---|---------|----------|-----------|-------|
| 1 | Cold start blocks the main thread on ~20 synchronous store opens and then `join()`s a thread doing Keystore/encrypted-store work | `BitChordApplication.kt:44-185` (join at 185), `Downloads.kt:146-157`, `SearchHistory.kt:37-49`, `AppSettings.kt:877/909`, `AudioCache.kt:204`, `EncryptedPrefs.kt:33-68` | once per cold start | High/High/Med |
| 2 | `BitChordApp` is one 4,849-line restart scope fed by 76 `collectAsStateWithLifecycle` reads; any of them re-runs the whole root body | `MainActivity.kt:447-5296`; 76 collects, 87 `var … by remember`, 27 `LaunchedEffect` | every feed/search/player-state emission (search suggestions: per keystroke) | Med-High/High/Large |
| 3 | `canvasFrame` (motion-cover readback) is state in the 3,134-line `NowPlayingScreen`; it is replaced every 3 s and read twice in the body, so the whole screen recomposes each time | `NowPlayingScreen.kt:831, 1350, 2153`; `NowPlayingScreen.kt:286` (`MESH_REFRESH_MS=3000`); `CanvasArtworkPlayer.kt:350-360` | every 3 s while a canvas plays | Med/High/Small |
| 4 | Lyric clock updates drive `derivedStateOf` computations that rescan and allocate the entire line list, while the playing line already redraws at 60 fps | `PlayerLyrics.kt:1664-1688` (`activeRows`, `leadLine`), `2684-2686`; `LyricFocus.kt:6-14` (indexOfLast + filter → new list) | per frame while lyrics/strip visible | Med/High/Small |
| 5 | Playback service runs a Main-thread lyrics ticker every 500 ms for the whole song and rewrites MediaSession metadata on every line change, screen off included | `PlaybackService.kt:6857-6864`, `6991-7023` | 2/s; metadata ~1-2/s | Med/High/Small |
| 6 | Default cellular audio quality is LOSSLESS and the player buffers the entire track (8 MB ceiling) | `AppSettings.kt:164, 1145-1147`; `PlaybackService.kt:5621-5630, 8456-8459` | every track | Med-High/High/Trivial |
| 7 | `reportProgress` runs on `Dispatchers.Main` every 5 s and does prefs writes, a fresh `NerdStats.Snapshot`, and upgrade checks inside the loop | `PlaybackService.kt:735` (`scope` = Main), `5519-5557` | every 5 s while playing | Med/Med/Small |
| 8 | Taste model is re-serialised (up to 1,000 tracks/artists) only 1.5 s after a skip/like burst | `TasteSignals.kt:307-338, 348-355, 455` | per skip burst | Med/High/Small |
| 9 | Listening stats rewrite the whole month JSON (up to 600 tracks) every 6 samples (~30 s of playback) | `ListeningStats.kt:103-106, 214-259, 657-663` | ~2/min while playing | Med/Med/Small |
| 10 | MainViewModel eagerly loads Home, Explore, Library and checks for updates in `init`, before their screens exist | `MainViewModel.kt:1862-1931` | once per app start | Med/High/Small |
| 11 | Continuous mesh-gradient blur animations on Library shelf cards, at 60 fps, even while audio is paused | `HomeScreen.kt:949-954, 991-996` (`continuous = true`); `MeshGradient.kt:126-144, 169` | per frame while Library visible | Med/High/Trivial |
| 12 | Every line within four rows of the active lyric gets its own `Modifier.blur` (RenderEffect/offscreen layer), re-blurred during each hand-over and scroll | `PlayerLyrics.kt:1951-1958, 2158`; falloff at `314-315` | per frame during line transitions/scroll | Med/Med/Small |
| 13 | Translation ahead does a lyrics lookup, timing measurement and (optionally) a translation for the next track on every track | `PlaybackService.kt:6745-6761`; `TranslationAhead.kt:74-110` | per track | Med/Med/Med |
| 14 | Smart-fade analysis decodes a whole track and runs ONNX passes; also pulls head bytes through the cache | `TrackAnalyzer.kt:267-450`; `CrossfadeController.kt:824, 917` | per track when smart fade on | Med/High/Med |
| 15 | Marquee title/artist animate in an unconditional `while (true)`, so they keep running while paused and even while the user reads lyrics | `PlayerControls.kt:1259-1273`, `1372-1385` | per frame while player open + text overflows | Low-Med/High/Trivial |
| 16 | 44 dp queue thumbnails request the raw thumbnail URL instead of the 160 px ladder, so they decode a larger image and miss the row cache entry | `PlayerQueue.kt:742` vs `Common.kt:526` / `ROW_ART_PX` (`Models.kt:272`) | per queue row | Low-Med/High/Trivial |
| 17 | Lyric rows have no stable `key`; active-row membership is a linear `in` scan per row | `PlayerLyrics.kt:1904, 1945` | on each active-row change | Low-Med/High/Small |
| 18 | Long-press menu capture does a GPU→bitmap readback on the main thread for every hold | `LongPressOrigin.kt:107-124` | per long-press | Low/High/Small |
| 19 | `positionMs` is read in a settings `AnimatedContent` slot, so the open page recomposes twice a second — exactly what `PlaybackPosition`'s doc warns against | `MainActivity.kt:2723`; warning in `PlaybackPosition.kt:10-24` | 2/s while Discord settings open | Low/High/Trivial |
| 20 | Per-recomposition list work with no `remember`: Explore flattens/dedupes/chunks, Library re-pins shelves, lyric intro strings re-read | `ExploreScreen.kt:110-113`; `LibraryScreen.kt:206, 413`; `PlayerLyrics.kt:2695` | on recomposition of those subsections | Low/Med/Small |

---

## A. Compose / rendering

### A1. Root recomposition scope is the whole app (High, code-evidenced)
`MainActivity.kt:447` `BitChordApp(...)` runs to `5296` — 4,849 lines, 87
`remember`-backed `var`s, 76 `collectAsStateWithLifecycle` reads, 27
`LaunchedEffect`s, ~200 `if`/`when` branches. Every one of those reads happens
in the same restart scope. A search-suggestion emission (per keystroke), a
"liked" map update, a download-list change, a home shelf arriving, or a player
queue change invalidates that scope and re-executes the whole body. Compose
still skips children whose parameters are equal, but the call graph, argument
evaluation and lambda identity work all repeat, and the player sheet and tab
bar live in this tree.

Fix (incremental, no rewrite): extract each tab/pane into its own composable
that takes only its own stable parameters (`HomeTab`, `ExploreTab`,
`LibraryTab`, `SearchTab`, `PlayerSheetHost`, `DialogsHost`); move each
`collectAsStateWithLifecycle` down to the composable that draws it (home →
`HomeTab`, like statuses → rows, detail stack → a small navigator). Keep the
root to theme/backdrop/window size + selected tab.

### A2. `NowPlayingScreen` is also a god composable (High confidence)
`NowPlayingScreen.kt:636-3769` — one function of ~3,130 lines (there are no
other top-level functions after 636; helpers are local functions such as
`canvasBottom` at 1377 and `landscapeDockPose` at 1794). It holds the sheet
motion, artwork, canvas, backdrop, two layouts (portrait/landscape),
lyrics/queue panels, transport, sheets and dialogs.

`canvasFrame` at `831` is replaced every 3 s (`MESH_REFRESH_MS = 3000`,
`286`; capture loop `CanvasArtworkPlayer.kt:350-360`) and read at `1350` and
`2153`. So while a motion cover plays, the entire 3,130-line function
recomposes 20×/min. This is the most likely source of "the player hitches every
few seconds" on canvas tracks. (Guess: how visible the hitch is depends on how
many children strong-skipping can skip; the recomposition itself is certain.)

Fix: move the mesh/palette/canvas-frame consumers into a `PlayerBackground`
composable that takes `State<ImageBitmap?>` and reads it inside its own scope,
and keep the captured frame out of `NowPlayingScreen`'s body. Then split
portrait/landscape and the panel hosts.

### A3. Continuous mesh blur on shelf cards (High confidence, cheap fix)
`MeshGradient.kt:126-144` restarts a `while (isActive)` animation when
`continuous = true`; `169` applies `.blur(blurRadius)` (RenderEffect on API
31+) to the whole layer, and the phase is read in the draw lambda at `178`, so
the layer redraws and re-blurs every frame. The class doc itself (`60-71`)
calls a full-screen re-blur at refresh rate "the most expensive thing this app
does", and the `continuous` doc (`78-87`) says it is meant for a card the user
deliberately opened (Replay), not a feed surface. `HomeScreen.kt:949-954` and
`991-996` pass `continuous = true` for the Library device-folder cards
(150 dp, `Common.kt:146`), so up to ~5 cards animate and blur permanently
while Library is on screen, including while paused.

Fix: pass `continuous = false` on shelf/service cards (the one-shot
`trackKey` drift is the intended treatment), and gate on
`rememberIsForeground()`. Restore `continuous` only on Replay cards.

### A4. Lyric clock: per-frame O(n) work that does not need to be per-frame
The clock itself is a good design: `rememberLyricClock` (`PlayerLyrics.kt:629`)
writes state inside `withFrameNanos` and the sweep is read in draw lambdas, so
lines do not recompose 60×/s. The remaining cost is the *derived* state around
it:

- `activeRows` (`1664-1668`) runs `activeLyricRows(lines, clock)` — an
  `indexOfLast` plus `(0..latest).filter { … }` that allocates a new list
  (`LyricFocus.kt:6-14`) — and the value is read every time the clock changes.
- `leadLine` (`1679-1688`) runs the same function again plus `scrollLead`
  (another `indexOfLast`, `413-427`).
- `CurrentLyricLine.index` (`2684-2686`) runs another `indexOfLast` per frame.

That is ~4 full scans and one list allocation per frame (~240 scans/s and
~60 list allocations/s for a 300-line song) for values that change only when
the active line changes. Guess: ~0.2-0.6 ms/frame on a mid-range device; the
allocation churn matters more than the scans.

Fix: derive a single `activeIndex` with a monotonic cursor (previous index →
advance while `next.timeMs <= now`), then compute the small active window only
when the cursor changes. Or convert `activeRows` to a `derivedStateOf` that
first computes the cursor, and make `leadLine` depend on `activeRows` instead
of recomputing it. No visual change.

### A5. Per-line RenderEffect blur in the lyrics panel
Each row computes `animateDpAsState` (`PlayerLyrics.kt:1951-1958`) and applies
`.blur(blur, BlurredEdgeTreatment.Unbounded)` (`2158`) when it is 1-4 rows from
the active line (`314-315`). On a 10-line viewport this means ~8 offscreen
layers with a RenderEffect; they are re-blurred on every hand-over
(`LYRIC_SETTLE_MS`) and on every auto-scroll frame, and the panel scrolls on
*every* line (`1805-1839`). Blur is on by default (`AppSettings.kt:453`).

Fix: keep the alpha falloff but drop the blur below the two middle steps (or
make the blur alpha-only), and force `blur = 0.dp` while
`listState.isScrollInProgress`. On API < 31 it is already a no-op.

### A6. Lyric list keys and membership
`itemsIndexed(lines)` at `1904` supplies no `key`, so any edit to the line
list (translation toggle, re-timing) reuses slots by index and invalidates
whatever state is remembered per slot; `isActive = index in activeRows`
(`1945`) is a linear scan per visible row. Fix: `key = { _, line -> line.id }`
(if `LyricLine` has a stable id; otherwise a stable hash of text+time), and
pass `isActive` from a set/cursor rather than a list membership test.
(Guess: correctness is unaffected today; this is jank/latent-state only.)

### A7. Marquee animations never stop
`PlayerControls.kt:1259-1273` and `1372-1385`: once text overflows, the offset
runs forever with a 1.2 s rest. Nothing keys it on `isPlaying`, on the player
being on-screen, or on the controls being hidden. On the S24 this is cheap
offscreen compositing, but it keeps the frame clock awake and forces a layout
(`offset`) pass for two texts the whole time the player is open — including
while the user is reading lyrics. Fix: add `isPlaying`/foreground to the key
and `snapTo(0f)` when not playing.

### A8. Infinite shimmer transitions are multiplied
- `SearchPlayingBars` (`Common.kt:653-697`) creates **three**
  `rememberInfiniteTransition`s per marker and reads their heights in
  composition, so the row recomposes every frame while the marker exists.
- `ShimmerBox` (`Skeletons.kt:61-89`) creates one transition per block; a feed
  skeleton is dozens of blocks `(Skeletons.kt:140-292)`.
All draw-only, transient, and read in the draw phase in the skeleton case,
so this is Low impact; but on a slow device a skeleton-heavy Home is ~50
animations. Fix: one transition per skeleton screen/marker, shared downward.

### A9. Composition-time list work without `remember`
- `ExploreScreen.kt:110-113`: `flatMap` + `distinctBy` + `chunked` runs in the
  `LazyColumn` content lambda on every recomposition of that scope.
- `LibraryScreen.kt:206, 413`: `pinnedFirst(...)` returns a copied shelf with a
  filtered list per call; `sortedForLibrary` sorts the whole shelf. They run
  when the item/Library content recomposes.
- `PlayerLyrics.kt:2695`: `stringArrayResource` is re-read every recomposition;
  the code already guards the *random pick* with `remember(trackKey)`.
Fix: wrap in `remember(sourceList, sort, pinned)`; these are milliseconds, but
they are on the scroll path of large lists.

### A10. Root-level value read that ticks twice a second
`MainActivity.kt:2723` passes `player.position.positionMs` into
`DiscordScreen` from inside the settings `AnimatedContent` content lambda. It
is not the global root (the lambda is its own slot), but it is exactly the
pattern `PlaybackPosition.kt:10-24` says not to use: the open settings page
recomposes at the 500 ms poll rate. Fix: pass `position: PlaybackPosition` and
read it where the elapsed value is drawn.

### A11. Checked and found fine
- `PlaybackPosition` is split out of `PlayerState` and read in draw/lambdas
  (`PlaybackPosition.kt`, `NowPlayingScreen.kt:866`, `PlayerLyrics.kt:594`);
  the two-second tick does **not** invalidate the app root.
- `rememberPlayerState` polls at 500 ms only while `isPlaying && foreground`
  (`PlayerConnection.kt:375-381`) and skips queue rebuilds for
  `SOURCE_UPDATE` (`PlayerConnection.kt:296-310`).
- The sweep, gap fill and glow read the clock in draw (`PlayerLyrics.kt:757-763,
  811-825, 2032-2053`), and `phase` avoids redraws for lines that are not
  animating (`747-756`).
- The mesh/backdrop decodes are staggered after track hand-off
  (`NowPlayingScreen.kt:890-898`) and cached by URL
  (`ArtworkMeshBackdrop.kt:101-118, 217-248`).
- Full-screen background blurs are skipped under `reduceAnimation`/`lowPower`/
  `reduceDynamicBlur` (`ArtworkMeshBackdrop.kt:320-328`,
  `NowPlayingScreen.kt:2134-2164`).

---

## B. Threads, disk and JSON

### B1. Cold start is serialised on the main thread (High)
`BitChordApplication.onCreate` does, before the first frame:
`AndroidStreamHooks.installEarly`, `PlayerPlatform.install`, `AuthStore(this)`
(`82`, EncryptedPrefs → `MasterKey` Keystore round trip),
`Innertube`/`CoverTitles` lambdas, `AppSettings.init` (`121`, reads every
preference, `AppSettings.kt:909`), `Downloads.init` (`146`, parses **three**
JSON maps from SharedPreferences), `SearchHistory.init → reload`
(`SearchHistory.kt:37-49`, reads and parses JSON), `AudioCache.init` (`204`,
opens a Media3 `SimpleCache` + database), plus ~12 more `init`s, then
`backgroundInit.join()` (`185`) — the main thread waits for
`SourceRegistry`'s encrypted store (the comment at `83-89` calls it the
slowest), `InnerTubeX`, and `CanvasCache`. The comment says "joined before
onCreate returns so nothing sees them half open" — but the cost is paid on
the launch path.

Fix: make each store open on first use (or asynchronously with a ready gate)
and **drop the join**; only `AuthStore`/`AppSettings` must be ready before the
first composition. Decode `Downloads` and `SearchHistory` lazily when their
screens/actions first ask. This is the single best cold-start win.

### B2. Service timers on the main dispatcher
`PlaybackService.scope = CoroutineScope(Dispatchers.Main + SupervisorJob())`
(`735`). Consequently `reportProgress` (`5519-5557`) runs its work on Main
every 5 s:
`savePlaybackState` → SharedPreferences `apply()` (`LastPlayed.kt:80-86`),
`publishNerdStats` allocates a new `NerdStats.Snapshot` (`5234-5283`),
`lookForBetterCopy` walks upgrade maps (`3859-3884`), `enrich`/`record`
synchronized blocks (`ListeningRecorder.kt:62-107`,
`ListeningStats.kt:131-205`). Small individually, permanent while playing.
Fix: run the sampler on `Dispatchers.Default`, keep only the `player` reads on
Main, and publish nerd stats only when the audio format changes.

### B3. Listening stats and taste store write volume
- `ListeningRecorder` flushes every 6 samples (`103-106`, `216`) = every ~30 s;
  `ListeningStats.write` serialises the open month (up to 600 tracks,
  `657-663`) and writes a temp file + rename on IO (`246-258`). That is ~120
  full-file writes/hour of playback. On flash this is unnecessary wear and a
  small battery cost.
  Fix: flush on pause/track end/app background, plus a 5-minute ceiling.
- `TasteSignals` marks dirty on every play/skip/rating and debounces only
  1.5 s (`348-355`, `455`), then encodes the denormalised store under the lock
  (`307-338`, up to `MAX_TRACKS=1000`). A skip burst causes repeated encodes
  and writes.
  Fix: raise the debounce to 20-60 s, and encode from a copy outside the lock
  (the snapshot work is already done under the lock; the `Json` pass can move
  out).

### B4. `LastPlayed` JSON on the session thread
`saveQueue` (`LastPlayed.kt:35-52`) converts and JSON-encodes the bounded queue
(up to 76 items) and is called from timeline changes/queue edits;
`saveQueueImmediately` (`59-77`) uses `commit()` (synchronous disk). The
encoding is on whatever thread calls it (service main thread for timeline
callbacks). Guess: a few ms per queue edit; acceptable, but
`saveQueueImmediately`'s `commit()` must stay off Main. Fix: move the encode to
a single-thread IO executor.

### B5. `synchronized` on the main path
`ListeningRecorder.onSample` is `@Synchronized` (`61`) and
`ListeningStats.record` takes `lock` (`137`), shared with the IO writer and
with `summary()`; `TasteSignals.flush` holds its lock while JSON-encoding
(`313-325`). Today the heavy parts are on IO, so this is not a deadlock or a
long block, but it is a lock shared by UI and IO threads. Fix: keep the
encode outside the lock (B3) and make the sampler's critical section
copy-only.

### B6. Network/JSON looked at and found off-main
`LyricsDiskCache` reads/writes are wrapped in `withContext(Dispatchers.IO)`
(`LyricsRepository.kt:157, 256, 298`); `TranslationAhead` runs on IO;
`AudioCache.cachedSongs` is `withContext(IO)`; `PlaybackService`'s
quality-upgrade and source work use `withContext(IO)`. No main-thread JSON
found outside startup (B1).

---

## C. Background / service

### C1. Lyrics ticker (Medium)
`PlaybackService.kt:6857-6864`: `scope.launch(Dispatchers.Main)` while
`isPlaying`, calling `updateLyricSubtitle()` then `delay(500)`.
`updateLyricSubtitle` (`6991-7023`) reads `currentPosition`, scans the lines
(`indexOfLast`), and when the text changes sets `playlistMetadata` through
`buildUpon().setSubtitle(...)`. That fires `onMediaMetadataChanged` for the
MediaSession → notification/system-UI work every time a lyric line advances
(~every 1.5-3 s), including with the screen off and even when no lockscreen
lyrics consumer is attached. The per-tick work is tiny; the metadata churn is
the cost. Guess: measurable battery/notification churn, not a hot-spot.

Fix: start the ticker only when `AppSettings.syncedLyrics` is on **and** a
consumer exists (lockscreen lyrics attached or subtitle enabled), tick at
1,000 ms, and skip the whole ticker when the service is not the active
playback (e.g. cast). `NowPlayingLyrics.publish` already exists as the
consumer signal.

### C2. No wakelocks / receivers of our own (checked)
There are no `PARTIAL_WAKE_LOCK`, `AlarmManager`, `WorkManager`, or
`JobScheduler` uses in `app/` (grep). Playback foreground is owned by Media3's
`MediaSessionService`; `DownloadService` calls `startForeground` only during a
download. `onTaskRemoved` (`6628-6640`) honours the stop-on-swipe setting.

### C3. Coroutines that never stop
- `ArtistFacts.init` (`104-120`) launches `while (true) { delay(5_000);
  save() }` for the life of the process; `save()` returns unless dirty
  (`156-165`), so it is a 5 s wake-up, not a write. Low impact; consider
  replacing with a debounced write triggered by `dirty`.
- `MeshGradient` `continuous` (A3) and `PlayerLyrics` frame loop (`664-684`,
  gated on `isPlaying && foreground` — good) are the only Compose loops.
- Party ping/report loops (`ListenTogether.kt:1219-1248`) exist only inside a
  party.
- `TrackAnalyzer` and `TranslationAhead` scopes are application-lifetime but
  queue one job at a time; they are not leaked per track.

### C4. Prefetch/download volume
- `prefetchAround` (`5447-5503`) warms the next track and `QUEUE_LOOKAHEAD=1`
  (`AudioCache.kt:186-192`), so the data volume is bounded — good.
- But the player's `DefaultLoadControl` target is 8 MB (`8459`) with a 15 min
  duration ceiling (`8456`), i.e. **the whole track is read into the cache**
  for the track being played (`5621-5630`). Combined with the default
  `AudioQuality.LOSSLESS` on cellular (`AppSettings.kt:164`), a cellular user
  streams lossless and downloads each track in full. Fix: default cellular to
  `HIGH` (or make "cache whole track" conditional on unmetered/charging).
- `TranslationAhead` (`74-110`) additionally looks up and times the next
  track's lyrics, and translates it when prefetch/auto-translate is on — a
  per-track network/CPU/Gemini cost. Gate the *lyrics/timing* prefetch behind
  the same `prefetchTranslation`/auto-translate switch that gates the
  translation, and skip it on metered connections.
- `CanvasRepository` guards cellular (default `canvasOverCellular=false`),
  so the looping video is not fetched on mobile data by default — good.

---

## D. Startup

1. **Main-thread serialisation** — see B1 (High). This is the dominant cost.
2. **Eager feeds in `MainViewModel.init`** (`1862-1931`): `loadHome()`,
   `loadExplore()`, `loadLibrary()`, `loadAccount()`, `loadPlaylists()`,
   `AppUpdateChecker.check()/noticeUpstream()`. Explore is loaded even if the
   user never leaves Home, and the update check runs on every start. Fix:
   load Home only, trigger Explore/Library on first tab selection, and move
   the update check behind a short delay / network-idle.
3. **`MainViewModel` constructs its own `AuthStore`** (`MainViewModel.kt:9`) in
   addition to the application's; `EncryptedPrefs` caches by name
   (`EncryptedPrefs.kt:35-37`) so the second open is cheap, but it still runs
   the first time from the composition/main thread.
4. **`BitChordApplication` 60+ static installs** — most are cheap (function
   references), but `LastPlayed.init`, `Downloads.init`, `SearchHistory.init`,
   `ListeningStats.init`, `TasteSignals.init`, `ArtistFacts.init` each start a
   coroutine or parse on the main thread. A "ready" boot gate would let the
   first frame be drawn before these finish.
5. **`windowBackground`/`BoxWithConstraints` first composition** — the huge
   `BitChordApp` body (87 remembered states, 76 flows, many `rememberLazy*`
   states) is measured/laid out on the first frame; splitting it (A1) also
   improves first-frame time.

---

## E. Spaghetti: the five worst objects, with an incremental split plan

| Object | Size | Responsibilities |
|--------|------|------------------|
| `PlaybackService.kt` | 8,694 lines, 97 overrides, 134 private funs, 86 consts | Media3 service/session, player lifecycle, queue, crossfade, quality upgrade, prefetch, lyrics, translation-ahead, listening stats, widgets, notification, Discord, party, cast, Android Auto library |
| `MainActivity.kt` (`BitChordApp`) | 5,522 lines total; `BitChordApp` 447-5296 (~4,849), 87 `var … remember`, 76 flows | App state owner, navigation, every settings sheet, every dialog, all playback intents, search/library/home wiring, player sheet motion |
| `NowPlayingScreen.kt` (`NowPlayingScreen`) | 3,769 lines total; the function ~636-3769 (~3,130) | Sheet/dock motion, portrait+landscape layouts, artwork/canvas/backdrop, panels, transport, sheets |
| `MainViewModel.kt` | 3,806 lines, 41 `MutableStateFlow`, 59 `viewModelScope.launch` | Auth, home, explore, library, search, lyrics, timing, account, playlists, downloads, link handling, update check |
| `PlayerLyrics.kt` (`LyricsPanel`) | 3,138 lines; `LyricsPanel` 1608-2402 (~794) + many line renderers | Clock, scroll choreography, per-line effects, translation/romanization, pick/share, offset/timing UI |
| (`CrossfadeController.kt`) | 3,064 lines | honorary sixth: crossfade/Automix state machine |

Safe split order (each step is a pure extract, CI-testable, no behaviour
change; do them one per PR):

1. **`PlaybackService`: extract collaborators, keep the class as a façade.**
   Move (a) lyrics ticker + subtitle (`6857-7023`) into `LyricSubtitlePublisher`,
   (b) `reportProgress`/`publishNerdStats` into `ProgressSampler`,
   (c) `prefetchAround`/`cancelPrefetch` into `QueuePrefetcher`,
   (d) widget publication (`5384-5446`) into `WidgetPublisher`,
   (e) the Android Auto library callbacks (`7665-8050`) into
   `AutoLibraryCallbacks`. The service keeps `onCreate`/`onDestroy` and wiring.
2. **`BitChordApp`: extract tab/pane composables** (`HomeTab`, `ExploreTab`,
   `LibraryTab`, `SearchTab`) and `PlayerSheetHost`/`DialogHost`; move the
   corresponding `collectAsStateWithLifecycle` into them. The root keeps only
   theme, backdrop, window size, selected tab and show-player flags.
3. **`NowPlayingScreen`: extract `PlayerBackground`** (artwork, canvas,
   mesh/palette, blur, haze source) and pass `PlaybackPosition`/canvas frame
   as state objects rather than values; then extract `PortraitPlayerContent`
   and `LandscapePlayerContent` around the two layout branches.
4. **`MainViewModel`: split by feature into delegates** (`FeedViewModel`-like
   plain classes or `HomeController`, `SearchController`, `LyricsController`,
   `AccountController`) that own their `MutableStateFlow`s; the ViewModel
   exposes and delegates. Start with lyrics/timing (the largest contiguous
   block, `376-950`), then search (`2340-2800`).
5. **`PlayerLyrics`: extract `LyricsPanelState`** (active-row cursor, scroll
   run, browse timers) into a plain class, and `LineRenderer` (sweep/gap/glow)
   into its own file. `LyricsPanel` then becomes layout + state wiring.

Guardrails: no behaviour change in the same PR as a move; keep public
signatures identical; run the existing `Lyrics` and compile jobs after each
step (one PR at a time).

---

## Guesses / not verified by running

- All "ms per frame" numbers are estimates. The audit proves *what repeats*
  and *how often*, not the exact cost on the S24.
- Whether a recomposition of `BitChordApp`/`NowPlayingScreen` actually reaches
  the screen depends on Compose strong skipping (Kotlin 2.3.20 + Compose
  plugin) and on which lambdas capture unstable values; the extra work and
  invalidation are certain, the visible jank is not.
- Battery percentages cannot be derived from code; the ranking above is by
  frequency × work, not by measured mAh.
- `PlayerQueue.kt:742`'s model is a URL from the host (`rememberRemoteArtworkUrl`);
  whether the backend URL is already a small rendition depends on the source,
  but it is definitively not the `ROW_ART_PX` ladder used by every other row.
