# BitChord Android — quality & performance audit (2026-11-02)

Read-only audit of `app/src/main/java/com/music/bitchord` (plus the two shared
files that the app calls). No source file was changed. Method: `grep` over all
`.kt` files for timers/loops, main-thread I/O, startup work, caches and
duplicate declarations, then a manual read of every hit. This is not a runtime
profile; the "impact" column is the likely user effect on an S24 Ultra.

Legend: **Upstream** = file outside `ko/` (merge risk with upstream v1.9);
**KO** = fork file; **Shared** = `shared/` module.

---

## HIGH

### H1. Song actions sheet exists twice; the upstream copy is unreachable dead code
**File:** `ui/components/SongActionsSheet.kt:271` (Upstream) and
`ko/KoSongActionsSheet.kt:146` (KO). `ui/components/SongActionsMenu.kt:11`
also repeats the full 100-line parameter list.

**Evidence**
```kotlin
    ) // [KO] song-actions
    return
    var showingSongInfo by remember(song.videoId) { mutableStateOf(false) }
```
`SongActionsSheet` forwards everything to `KoSongActionsSheet` and then returns;
everything from line 272 to the end (767 lines), including `DownloadRow`,
`WebDavUploadRow`, `SleepTimerPicker`, `ActionRow` and `SheetTrackHeader`, is
never executed. `KoSongActionsSheet.kt` (1,249 lines) contains its own copies of
the same private helpers.

**Problem:** one feature is implemented twice. A fix made in the upstream
helpers silently does nothing; a fix in `ko/` has to be repeated. This is also
the largest dead block in the app.

**Smallest fix (M):** delete `ui/components/SongActionsSheet.kt:272-1038` and
the now-unused private helpers/imports. Keep the forwarding signature so call
sites and upstream merges stay intact. Optionally make `SongActionsMenu` call
`KoSongActionsSheet` directly and drop its duplicated parameter list.

**Risk:** none at runtime. Merge conflicts in `SongActionsSheet.kt` become more
likely on the next upstream sync. Do not move upstream code into `ko/`.

---

### H2. PlaybackService restores the queue with a JSON/prefs read on the main thread
**File:** `playback/PlaybackService.kt:2452,2455` (Upstream) →
`playback/LastPlayed.kt:151` and
`playback/BoundedContextPersistence.kt:134` (Upstream).

**Evidence**
```kotlin
val restored = if (PartyPersonalQueueStash.hasStash()) {
    val stashed = PartyPersonalQueueStash.load()
...
restoreBoundedQueueOrLast(exoPlayer)
```
```kotlin
fun load(): Snapshot? {
    val raw = prefs.getString(KEY_QUEUE, null) ?: return null
    val stored = runCatching { json.decodeFromString<StoredQueue>(raw) }.getOrNull()
```
Both functions are called synchronously from `onCreate` (line 2438 is still in
`onCreate`), so the queue JSON is decoded on the service/main thread before the
player is prepared.

**Problem:** first sound and cold-start latency depend on the size of the saved
queue (and on the bounded-context blob, which is larger). On a big queue this is
main-thread disk + JSON work, exactly the shape of an ANR.

**Smallest fix (M):** load both snapshots in an `IO` coroutine (or on the
existing `StartupWork` thread) and `setMediaItems` when it completes; show the
last notification state meanwhile. Keep `PartyPersonalQueueStash` first, as now.

**Risk:** a restored queue still needs to exist before the user presses play;
guard against a second restore or a race with a user-chosen track.

---

### H3. Offline playlists are parsed on the main thread during Application.onCreate
**File:** `data/OfflinePlaylists.kt:77` and `:204` (Upstream), called from
`data/settings/AppSettings.kt:1003` inside `BitChordApplication.onCreate`
(`BitChordApplication.kt:263`).

**Evidence**
```kotlin
        publish(load())
```
```kotlin
    private fun load(): List<OfflinePlaylist> {
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
```
`load()` walks every offline playlist and every song in it with
`JSONObject`/`JSONArray` on the caller's thread. Unlike `LocalPlaylistStore`,
`SearchHistory`, `Downloads` and `SourceRegistry`, which were all moved to
`StartupWork.background`, this one stayed on the launch path.

**Problem:** a listener with a large on-device library pays a full JSON parse
before the first frame; the file grows with the library. This is both a startup
cost and an ANR risk.

**Smallest fix (S):** make `OfflinePlaylists.init` use the existing `LoadOnce`
pattern (`data/spotify/LocalPlaylistStore` is the model): `StartupWork.background
("offline-playlists") { loadGate.ensureLoaded() }`, with the current sync load
kept as the fallback for a caller that arrives first.

**Risk:** an early reader could briefly see an empty list; the `LoadOnce`
fallback covers that, same as the other stores.

---

## MEDIUM

### M1. Crossfade ticker wakes 4×/s for the whole time music plays, even when nothing will cross-fade
**File:** `ko/KoCrossfadeTicker.kt:35-40` (KO) and
`playback/CrossfadeController.kt:4369` (Upstream).

**Evidence**
```kotlin
CrossfadeController.Phase.IDLE -> if (active().isPlaying) {
    if (screenOn()) CrossfadeController.IDLE_STEP_MS else CrossfadeController.SCREEN_OFF_IDLE_STEP_MS
} else {
    0L
}
```
```kotlin
const val IDLE_STEP_MS = 250L
const val SCREEN_OFF_IDLE_STEP_MS = 1_000L
```
Each tick also runs `publishAnalysisState()` and `publishAutomixDetail()`
(`CrossfadeController.kt:957-960`).

**Problem:** battery-first policy says no periodic polling. The loop is
event-driven only when the player is idle and silent; while playing it still
wakes 4×/s on screen and 1×/s with the screen off, usually to do nothing when
crossfade/Automix is off.

**Smallest fix (M):** park in `Phase.IDLE` on `ko.tickerWake.receive()` and let
the existing player events (end-of-track, position discontinuity, settings
change) wake it; keep the timed step only for armed/transition phases. If a
timed idle step is still needed, gate it on crossfade/automix being enabled.

**Risk:** a late cue would miss the planned start. Keep one timed wake before
the expected cue point (computed from track duration) rather than a constant
250 ms poll.

---

### M2. Analysis-head wait polls at 40 ms (and 100 ms) instead of using the cache event
**File:** `ko/KoAnalysisHeadEngine.kt:124` (KO), `ko/KoAnalysisWaits.kt:35-37`
(KO).

**Evidence**
```kotlin
            delay(40)
        }
        return alignmentHeadComplete(videoId)
```
```kotlin
        while (videoId in analysisHeadsInFlight && SystemClock.elapsedRealtime() < deadline) {
            delay(100)
        }
```
`awaitAnalysisHead` may run for `20_000L` ms and calls
`alignmentPrefixBytes()` in the loop; `awaitAnalysisTrack` waits up to 60 s.

**Problem:** 25–10 wakeups per second for the length of an analysis window on a
feature that is explicitly not on the audible path. The code already has the
event-driven pattern (`KoAnalysisWaits` `Cache.Listener`).

**Smallest fix (S):** wait on the cache's `onSpanAdded`/`onSpanRemoved` event
(or a `CompletableDeferred` completed by it) with the existing deadline as the
timeout. Keep a slow (500 ms+) fallback only if the cache listener cannot be
registered.

**Risk:** analysis starts slightly later in rare races; the deadline already
bounds it.

---

### M3. Root composable subscribes to many app-wide flows, so any change recomposes the whole tree
**File:** `MainActivity.kt:882` and `MainActivity.kt:1101-1121` (Upstream).

**Evidence**
```kotlin
val partyState by ListenTogether.state.collectAsStateWithLifecycle()
```
```kotlin
val likeStatuses by viewModel.likeStatuses.collectAsStateWithLifecycle()
...
val qualityUpgradesInFlight by NerdStats.racingLossless.collectAsStateWithLifecycle()
val playlists by viewModel.playlists.collectAsStateWithLifecycle()
```
All of these are read in `BitChordApp`, the root of the UI, and every read is
invalidated at that scope. `partyState` updates while a party is playing,
`likeStatuses` changes on every like, `savedDownloads` on every download.

**Problem:** a change that only affects one row recomposes the tabs, both
floating bars and the live-blur backdrop. This is the same class of cost the
`PlaybackPosition` split was written to avoid.

**Smallest fix (M):** move each subscription into the child that reads it (or
pass the relevant `StateFlow`/lambda down), starting with the fastest-moving
ones: `partyState`, `likeStatuses`, `savedDownloads`,
`qualityUpgradesInFlight`.

**Risk:** pure plumbing change; watch for a child that needs the same value and
ends up collecting it again.

---

### M4. Music-video picture polls the controller every 250 ms while it is mounted
**File:** `ui/player/MusicVideoPlayer.kt:82` and `:472-478` (Upstream).

**Evidence**
```kotlin
private const val SYNC_EVERY_MS = 250L
```
```kotlin
syncJob = scope.launch {
    while (isActive) {
        sync()
        delay(SYNC_EVERY_MS)
    }
}
```
`sync()` calls `controller.currentPosition` (a `MediaController` binder call from
the Activity) and compares play state/rate.

**Problem:** 4 binder calls/s for as long as the video surface is mounted. It is
correctly stopped when the app leaves the foreground (surfaces are released),
but while the player is open it is avoidable: the engine already listens for
`onIsPlayingChanged`, `onPositionDiscontinuity` and
`onPlaybackParametersChanged`, which are the events that actually break A/V
sync.

**Smallest fix (S):** on `onIsPlayingChanged`/discontinuity, re-arm a single
delayed check instead of a free-running loop; keep the 250 ms loop only for the
first ~1 s after a seek/load.

**Risk:** small drift accumulation between events; the existing seek-drift
tolerance (`SEEK_DRIFT_MS`) still corrects it.

---

## LOW

### L1. Presence telemetry POSTs every ~5 minutes while the app is open
**File:** `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/presence/Presence.kt:55-58`
(Shared).

**Evidence**
```kotlin
                    while (true) {
                        send(open = true)
                        delay(nextDelayMs())
                    }
```
`DEFAULT_INTERVAL_SEC = 300`; server may lower it to 60 s.

**Problem:** a periodic network request that does not affect playback. It is
small and only while foreground, but it is exactly the kind of keep-alive the
battery policy asks to avoid.

**Smallest fix (S):** make it opt-in, or stop pinging while the device is in
power-save/do-not-disturb; the close POST already keeps the server correct.

**Risk:** the public "apps open" count under-reports.

---

### L2. SongInfoDialog collects a fast flow without lifecycle awareness
**File:** `ui/components/SongInfoDialog.kt:67` and `:196` (Upstream).

**Evidence**
```kotlin
val snapshot by NerdStats.current.collectAsState()
```
Twice (two dialog variants), while the rest of the app uses
`collectAsStateWithLifecycle`.

**Problem:** the dialog keeps recomposing from playback telemetry even when the
app is backgrounded; both copies subscribe.

**Smallest fix (S):** use `collectAsStateWithLifecycle()`.

**Risk:** none.

---

### L3. Crossfade lyric caches grow for the whole session
**File:** `playback/CrossfadeController.kt:2457-2458` and `:2475` (Upstream).

**Evidence**
```kotlin
private val lyricSpans = java.util.concurrent.ConcurrentHashMap<String, LyricSpans>()
private val lyricsAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
```
`askLyrics` adds every media id and stores a `LyricSpans` (including `null`
spans); nothing removes entries.

**Problem:** memory grows with every track heard in a long session, including
tracks that never loop-mix. Small per entry, but unbounded.

**Smallest fix (S):** use a bounded `LinkedHashMap` (8–16 entries, access-order)
for both, or clear `lyricSpans` when its media id leaves the queue.

**Risk:** an evicted track re-reads lyrics when it comes back; acceptable.

---

### L4. Tagging a download holds the whole track in memory (~3 copies)
**File:** `download/MediaTagger.kt:128-131` and `:468-473` (Upstream).

**Evidence**
```kotlin
     * It is about the heap. [embed] reads the whole
     * finished track into a `ByteArray`, hands it to a tagger that builds a
     * second copy of it in a [ByteArrayOutputStream], and takes a third on
     * `toByteArray()`; a 40MB FLAC is therefore ~120MB in flight
```
```kotlin
    private fun readAll(context: Context, uri: Uri): ByteArray? = runCatching {
        if (uri.scheme == "file") File(requireNotNull(uri.path)).readBytes()
```
The existing `taggingLock` corrects the worst case (four concurrent rewrites)
and the code comment already documents the limit.

**Problem:** a single hi-res FLAC/DSD download can still peak around 120 MB.
The app already runs near the heap ceiling.

**Smallest fix (M):** stream tag the container (the file already has a
`tagFlacStreaming` path for FLAC) or reject tagging when the file exceeds a
size cap.

**Risk:** streaming taggers are per-container and easy to get wrong; keep the
current path as fallback. No in-place rewrite should be added beyond the
existing finished-download tagging.

---

### L5. Startup thread serialises all stores behind the encrypted ones; main can still open the Keystore
**File:** `startup/StartupWork.kt:27-30` (Upstream) and
`BitChordApplication.kt:115` vs `:194` (Upstream).

**Evidence**
```kotlin
private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "startup-init").apply { isDaemon = true }
}
```
```kotlin
StartupWork.background("auth-store") { AuthStore.prewarm(this) }
...
authStore = AuthStore(this)
```
`EncryptedPrefs.open` is `@Synchronized`, so if the main thread reaches line 194
before the prewarm finishes, it waits on the Keystore open.

**Problem:** the single thread is required for the two encrypted stores, but it
also delays the plain stores (local playlists, downloads, search history) that
have no such constraint; and the main thread still has a blocking path into the
Keystore.

**Smallest fix (M):** keep one serial executor for the encrypted opens and a
second one for plain stores; or resolve the session on the startup thread and
publish it, so `Application.onCreate` never opens the Keystore itself.

**Risk:** early network calls need the cookie; publish it via a `StateFlow` and
gate the first resolve, as `SourceRegistry` already does.

---

## Summary

- **HIGH: 3, MEDIUM: 4, LOW: 5** (12 findings).
- Top 5: offline-playlist JSON on `Application.onCreate` (H3); queue restore on
  the service main thread (H2); duplicated/unreachable song-actions sheet (H1);
  crossfade idle ticker (M1); music-video 250 ms sync (M4).
- Networking, download queueing, lyrics lookups and most stores are already
  event-driven and off-main; the remaining battery cost is mostly the two
  periodic UI helpers plus the analysis-head poll.
- The fork/upstream split is clean in most `ko/` files (no unused top-level
  functions, no unused settings found); `SongActionsSheet` is the one large
  exception.
- Nothing in this audit recommends moving upstream code into `ko/`.

---

## Top-10 fix list (ordered by user impact ÷ risk)

| # | Finding | Fix | Size |
|---|---------|-----|------|
| 1 | H3 offline playlists JSON on main | `LoadOnce` on `StartupWork` | S |
| 2 | H2 queue restore off main | load snapshot on IO, then `setMediaItems` | M |
| 3 | H1 duplicate/dead song-actions sheet | delete unreachable block after `return` | S |
| 4 | M1 crossfade 250 ms idle ticker | park on event, timed only near cue | M |
| 5 | M4 music-video 250 ms sync | event-driven recheck | S |
| 6 | M2 analysis 40/100 ms polls | wake on cache span event | S |
| 7 | M3 root flow subscriptions | move reads into children | M |
| 8 | L4 whole-file tagging memory | stream FLAC / size cap | M |
| 9 | L1 presence 5-min ping | opt-in or power-save skip | S |
| 10 | L2/L3 dialog flow + lyric maps | lifecycle collect, bound maps | S |
