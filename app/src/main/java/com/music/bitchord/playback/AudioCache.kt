package com.music.bitchord.playback

import android.content.Context
import android.media.MediaDataSource
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import com.music.bitchord.playback.smart.HqAudioVerify
import com.music.bitchord.playback.smart.HqVerificationStore
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.Downloads
import com.music.bitchord.startup.StartupWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * On-disk cache of the audio itself, and the read-ahead that fills it.
 *
 * Two problems, one cache:
 *
 *  - **Seeking.** Everything played is written to disk on the way through, so
 *    seeking back is always a file read. Seeking *forward* past what the
 *    player has buffered is the gap, and it closes once a track is on disk in
 *    full — which is why read-ahead fetches whole tracks rather than openings.
 *  - **Track changes.** The next track needs a stream URL resolved (an
 *    Innertube round trip, plus running YouTube's player JavaScript to
 *    de-obfuscate the `n` parameter) before its first byte can even be asked
 *    for. Fetching its opening ahead of time moves all of that off the gap
 *    between songs.
 *
 * The cache is keyed by videoId, not by URL: googlevideo URLs are single-use,
 * expire within hours, and differ between resolves of the same track, so
 * keying on them would cache every track afresh on every play. Because
 * [CacheDataSource] sits *outside* the resolving data source it sees the
 * original `bitchord://watch?v=<id>` request, and a cache hit never resolves a
 * URL at all.
 */
@UnstableApi
object AudioCache {

    private const val TAG = "BitChord"

    /**
     * The disk budget, straight from [AppSettings] — 512MB by default, roughly
     * 150 tracks at the highest bitrate offered, adjustable up to 10GB — or
     * unlimited — from Settings. Least-recently-used entries are dropped past it, so it's a
     * ceiling rather than something the listener has to manage day to day.
     */
    private val evictor = DynamicLruCacheEvictor(AppSettings.DEFAULT_CACHE_LIMIT_BYTES)

    /**
     * How much of the next track to fetch. About 50 seconds at 160kbps — long
     * enough that playback starts instantly and keeps going while the rest
     * streams, without spending the listener's data on a track they may well
     * skip past.
     */
    private const val PRELOAD_BYTES = 1L * 1024 * 1024

    /**
     * Size of each range the whole-track fetch asks for.
     *
     * Ranges, not one long read, because googlevideo paces a continuous
     * response down to roughly playback speed after the first megabyte or so —
     * a track fetched that way finishes caching around the time it finishes
     * playing, which is far too late to be worth anything to a seek. Bounded
     * ranges are served at line rate: two megabytes lands in about a third of a
     * second on this connection, against seventy seconds streamed.
     */
    private const val CHUNK_BYTES = 2L * 1024 * 1024

    /**
     * What [cacheWholeOnce] risks before committing to a whole [CHUNK_BYTES].
     *
     * Sized to answer one question — did this write land at all — as cheaply
     * as that question can be asked, not to be worth anything on its own.
     */
    private const val LOCK_PROBE_BYTES = 64L * 1024

    /**
     * How far into a rendition [cachedPrefixBytes] looks when its real length
     * isn't known yet. Only an upper bound on the answer, so it costs nothing
     * to set well past the half-minute of audio any caller actually wants —
     * eight megabytes covers that even for lossless.
     */
    private const val HEAD_PROBE_BYTES = 8L * 1024 * 1024

    /**
     * What [requestAnalysisHead] asks for when the track's real size can't be
     * had.
     *
     * Twelve seconds of audio is what the head pass needs and four megabytes
     * clears that for anything short of lossless. Only reached when
     * [StreamResolver] cannot say how long the file is, which is rare — it has
     * just resolved the stream — and a blind request is the one case where
     * spending more would be the listener's data spent on a guess.
     */
    private const val MAX_ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024

    /**
     * The most [requestAnalysisHead] will pull for one track when its size *is*
     * known.
     *
     * A bound rather than "the whole file, always": a substituted lossless
     * rendition runs to thirty or forty megabytes, and the analyzer only ever
     * reads the head and the tail. Sixteen covers every YouTube Opus stream in
     * full — a ten-minute track at 160 kbps is twelve — which is the case this
     * exists for.
     *
     * Taking the whole file in one request is also what keeps the entry
     * single-sourced; see [analysisHeadSize].
     */
    private const val MAX_ANALYSIS_TRACK_BYTES = 16L * 1024 * 1024

    /**
     * How long [renditionKeysFor]'s answer is reused. Short enough that a
     * rendition which just started downloading is picked up well inside the
     * several seconds an analysis takes anyway.
     */
    private const val RENDITION_KEYS_TTL_MS = 5_000L

    /**
     * Grace period before reading ahead. The seconds just after a track starts
     * are when the player is filling its own buffer and the listener is waiting
     * on sound; read-ahead competing for bandwidth there would trade the gap
     * between songs for a gap at the start of one. It also collapses a burst of
     * skips into a single fetch of wherever the listener lands, and leaves the
     * player's opening burst holding the cache entry alone — see [fetchWhole].
     *
     * Ten seconds, not eight: by then the track has demonstrably started, and
     * the next item's stream URL has been resolved in the background well
     * before the queue reaches it — which is what makes a track change start
     * instantly instead of waiting out a client walk.
     */
    private const val PREFETCH_DELAY_MS = 10_000L

    /** How long to leave the player alone with an entry before trying again. */
    private const val RETRY_DELAY_MS = 5_000L

    /** Enough to cover a hand-over, not enough to keep chasing a lost race. */
    private const val MAX_ATTEMPTS = 4

    /**
     * How many tracks past the immediate next one get their stream URL warmed
     * ahead of time. Only the very next track is worth spending bytes on — see
     * [prefetchQueue] — but resolving a URL costs a handful of small round
     * trips, not a stream's worth of data, so paying that cost several tracks
     * early is worth it purely to keep a fast run of skips from ever landing
     * on a track that has to resolve cold.
     *
     * One, not three, and the difference is not the round trips. While every
     * player client is being refused, *every* warm-up falls through to NewPipe
     * extraction — the one step in this app that does not share out when it is
     * run concurrently, but collapses: 1.8s alone against 30.3s with three in
     * flight. Warming three tracks ahead therefore did not cost three cheap
     * resolves in the background, it cost the track the listener was waiting on
     * a thirty-second start. See
     * [StreamResolver][com.music.bitchord.data.innertube.StreamResolver]'s
     * extraction gate, which serialises what is left of that.
     */
    private const val QUEUE_LOOKAHEAD = 1

    /** Spacing between queued resolves, so warming the queue never competes with the track actually playing. */
    private const val QUEUE_RESOLVE_STAGGER_MS = 500L

    /** How many upcoming tracks are worth gathering for [prefetchQueue] — the caller doesn't need to know why. */
    const val QUEUE_DEPTH = QUEUE_LOOKAHEAD + 1

    private var appContext: Context? = null

    /** Set once the cache is open; null until someone needs it. See [cache]. */
    @Volatile
    private var openedCache: SimpleCache? = null

    private val openLock = Any()

    /**
     * The cache, opened on first use.
     *
     * Opening a [SimpleCache] is a database round trip and only one may be open
     * per process, so [init] records where it lives and has the startup thread
     * open it. A player built before that finished opens it here instead; both
     * paths meet under [openLock], so exactly one `SimpleCache` is ever built.
     */
    private val cache: SimpleCache
        get() = openedCache ?: synchronized(openLock) {
            openedCache ?: openCache().also { openedCache = it }
        }

    private fun openCache(): SimpleCache {
        val context = checkNotNull(appContext) { "AudioCache.init has not run" }
        val dir = File(context.noBackupFilesDir, CACHE_DIR_NAME)
        val databaseProvider = StandaloneDatabaseProvider(context)
        migrateFromCacheDir(File(context.cacheDir, LEGACY_CACHE_DIR_NAME), dir, databaseProvider)
        return SimpleCache(dir, evictor, databaseProvider)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Kept in the app's no-backup files directory, not its cache directory.
     *
     * It used to live in `cacheDir`, on the reasoning that it is disposable.
     * It is not disposable to the listener any more: the Cached songs folder
     * lists it, offline playback reads from it, and Settings lets it grow
     * without a ceiling. Android treats `cacheDir` as its own to reclaim — it
     * empties whole cache directories, largest-over-quota first, during idle
     * maintenance whenever storage runs low — and a multi-gigabyte song cache
     * is exactly the app it picks first. That is the "everything vanished
     * overnight, all at once" report: the evictor never touched it, the system
     * did. Here only [DynamicLruCacheEvictor], [clear] and the folder's own
     * remove actions take anything out. No-backup rather than plain
     * `filesDir` so gigabytes of audio are never offered to Auto Backup.
     *
     * [SimpleCache] still copes with files disappearing underneath it by
     * dropping the spans that named them.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        val app = context.applicationContext
        evictor.maxBytes = AppSettings.audioCacheLimitBytes.value
        // The open itself, the one-time migrations and schema cleanup, and the
        // foreign-entry sweep all touch every cached key; none of them is
        // needed for the first frame, so they run on the startup thread.
        StartupWork.background("audio-cache") {
            // Opens (and, for a build that cached under `cacheDir`, migrates)
            // the song cache where upstream keeps it: no-backup files, so the
            // system never reclaims a multi-gigabyte library during idle.
            cache
            val state = app.getSharedPreferences(CACHE_STATE_PREFS, Context.MODE_PRIVATE)
            // Replaces wiping the whole cache on every app update, which emptied
            // the Cached songs folder each time a new build was installed. Only a
            // build that actually changes what a key means, or how its bytes are
            // laid out, bumps [CACHE_FORMAT]. An install that has never recorded a
            // format is taken to be current rather than wiped.
            val format = state.getInt(KEY_CACHE_FORMAT, -1)
            if (format in 0 until CACHE_FORMAT) {
                cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
                TrackLog.d(TAG, "cleared the song cache for cache format $CACHE_FORMAT")
            }
            if (format != CACHE_FORMAT) state.edit().putInt(KEY_CACHE_FORMAT, CACHE_FORMAT).apply()
            // Builds before album/explicit/video-aware and credit-aware matching
            // may have cached a completely different recording under a YouTube
            // track's `#alt` key.
            // Those bytes otherwise outlive the matcher fix: playback serves them
            // without resolving, then refuses the correct 320kbps replacement as
            // no quality gain. Drop only the substitution-capable entries once;
            // ordinary YouTube cache entries remain warm.
            if (state.getInt(KEY_MATCHING_SCHEMA, 0) < MATCHING_SCHEMA) {
                val stale = cache.keys.filter { it.endsWith(ALT_SUFFIX) }
                stale.forEach { runCatching { cache.removeResource(it) } }
                state.edit().putInt(KEY_MATCHING_SCHEMA, MATCHING_SCHEMA).apply()
                TrackLog.d(TAG, "invalidated ${stale.size} source cache entries after matcher upgrade")
            }
            // Nothing is playing yet, so nothing an addon served is worth keeping —
            // see [dropForeignEntries].
            dropForeignEntries(keep = emptySet())
        }
        // A SimpleCache can only be opened once per process, so the ceiling
        // moves by mutating this evictor rather than reopening the cache —
        // see [DynamicLruCacheEvictor].
        scope.launch {
            AppSettings.audioCacheLimitBytes.collect { maxBytes ->
                evictor.maxBytes = maxBytes
                evictor.applyNow(cache)
            }
        }
    }

    /**
     * Moves a cache left in `cacheDir` by an older build to [dir], keeping
     * every track in it. A rename within app storage, so instant and free; the
     * index survives with it, because [SimpleCache] keys its database tables on
     * the uid file inside the directory. Should the rename fail, the old copy
     * is deleted rather than left for the system to find.
     */
    private fun migrateFromCacheDir(legacy: File, dir: File, databaseProvider: StandaloneDatabaseProvider) {
        if (!legacy.isDirectory) return
        if (!dir.exists()) {
            dir.parentFile?.mkdirs()
            if (legacy.renameTo(dir)) {
                TrackLog.d(TAG, "moved the song cache out of the system-clearable cache directory")
                return
            }
        }
        runCatching { SimpleCache.delete(legacy, databaseProvider) }
            .onFailure { legacy.deleteRecursively() }
    }

    /** Drops everything on disk. The listener asked; no grace period. */
    fun clear(onComplete: () -> Unit = {}) {
        cancel()
        // The bookkeeping describes bytes that are about to stop existing: a
        // one-round-per-track claim over an entry that just got deleted starts
        // nothing on the next switch until the wait's settle pass evicts it,
        // a remembered `want` is a size for a file that is gone, and the key
        // memo lists keys with nothing behind them. Cleared with the disk so
        // the first switch after a wipe asks for its head from a clean slate —
        // [discardBadRendition] clears one track's claim for the same reason.
        analysisHeads.clear()
        analysisHeadsInFlight.clear()
        analysisHeadDeferredLogged.clear()
        analysisHeadsHeldElsewhere.clear()
        analysisHeadWant.clear()
        stalledAnalysisHeads.clear()
        renditionKeys.clear()
        purgeCandidates.clear()
        purgeIndexBuilt = true
        scope.launch {
            cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
            _contentsChanged.value++
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /**
     * Throws away everything held for the track [uri] plays, so the next open
     * fetches it again from the top.
     *
     * For when what is on disk is the problem rather than the network: a
     * half-written entry, or one filled from two different files and now
     * unreadable at the seam. Nothing here can tell which of those it is
     * looking at, so every rendition of the track goes — the `#alt` and
     * `#hifi` siblings as well as the entry named — and the cost is a
     * re-download rather than a track that cannot be played at all.
     *
     * A key still locked by a live reader can't be removed; that throw is
     * caught rather than prevented, because the alternative is holding a lock
     * of our own across the player's teardown.
     *
     * Runs on the caller's thread rather than off in [scope], so that a caller
     * about to re-open the track can be sure the old bytes are gone first.
     * Call it off the main thread.
     *
     * @param keepComplete spare every entry that is on disk in full. For a
     *   failure that was the network's rather than the file's — a dropped
     *   connection, an expired URL, no connection at all. A complete entry is
     *   never written to again, so it cannot be the half-filled seam this
     *   exists to clear, and throwing it away for a network error is how
     *   playing offline used to delete the very songs it was playing.
     */
    fun discard(uri: Uri, keepComplete: Boolean = false) {
        val exact = keyFactory.buildCacheKey(DataSpec(uri))
        val about = mediaIdIn(uri)
        val family = uri.getQueryParameter("v")?.let { videoId ->
            cache.keys.filter { it == videoId || it.startsWith("$videoId#") }
        } ?: emptyList()
        (family + exact).distinct().forEach { key ->
            if (keepComplete && isComplete(key)) {
                TrackLog.d(TAG, "kept complete cache entry $key through a network failure", about = about)
                return@forEach
            }
            runCatching { cache.removeResource(key) }
                .onSuccess {
                    forgetPurgeCandidate(key)
                    TrackLog.d(TAG, "discarded cache entry $key", about = about)
                }
                .onFailure { TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = about) }
        }
    }

    /**
     * Throws away only the rendition [uri] names, leaving the track's other
     * entries where they are.
     *
     * [discard]'s scorched-earth pass is right when what is on disk cannot be
     * trusted and there is no telling which entry is at fault. This is for the
     * case where there is: an upgrade that was fetched and then not used — an
     * audition that failed to prove itself, a swap the player put back — has
     * written a prefix of one file under the `#hifi` key and stopped. Left
     * there, the *next* upgrade of the same track keys to that same `#hifi`
     * entry, is served the abandoned prefix, and streams a different file into
     * the middle of it. Taking the whole family instead would throw away the
     * bytes of the stream still playing, which is the one thing that is
     * definitely fine.
     *
     * Runs on the caller's thread; call it off the main one.
     */
    fun discardRendition(uri: Uri) {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        runCatching { cache.removeResource(key) }
            .onSuccess {
                forgetPurgeCandidate(key)
                TrackLog.d(TAG, "discarded unused rendition $key", about = mediaIdIn(uri))
            }
            .onFailure { TrackLog.d(TAG, "rendition $key still in use: ${it.message}", about = mediaIdIn(uri)) }
    }

    /**
     * Throws away one named rendition of [uri] because its bytes cannot be
     * decoded, so the next attempt starts from a clean copy.
     *
     * The analyzer can tell a corrupt file from a merely incomplete one — a
     * container that reports two and a half minutes and decodes fourteen seconds
     * is not still downloading — but until this existed, knowing that led
     * nowhere. It recorded the rendition as bad and moved on, the bytes stayed on
     * disk looking complete, and every later attempt (including after a restart,
     * which clears that memory) re-read the same file and reached the same
     * answer. Two tracks were observed stuck that way permanently.
     *
     * Refuses to touch the rendition the player is currently reading from, whose
     * bytes are by definition fine — the decode that failed was of a *sibling*
     * copy. [Cache.removeResource] would also throw on the live entry's lock, but
     * relying on that would mean asking for playback's bytes to be deleted and
     * being saved by a race.
     *
     * Runs on the caller's thread; call it off the main one.
     *
     * @return true when the bytes are actually gone, which the caller must treat
     *   as "stop holding this key against the track" — a copy that no longer
     *   exists cannot be the reason to refuse the one replacing it.
     */
    fun discardBadRendition(uri: Uri, key: String): Boolean {
        if (openedCache == null) return false
        val about = mediaIdIn(uri)
        if (key == keyFactory.buildCacheKey(DataSpec(uri))) {
            TrackLog.d(TAG, "keeping undecodable rendition $key: it is the one playing", about = about)
            return false
        }
        return runCatching { cache.removeResource(key) }
            .onSuccess {
                forgetPurgeCandidate(key)
                TrackLog.d(TAG, "discarded undecodable rendition $key", about = about)
                // Deleting the bytes is only half of it. The head fetch runs once
                // per track per session, so without this the entry it just made
                // room for is never refilled and the discard buys nothing.
                uri.getQueryParameter("v")?.let {
                    analysisHeads.remove(it)
                    analysisHeadDeferredLogged.remove(it)
                    analysisHeadsHeldElsewhere.remove(it)
                    stalledAnalysisHeads.remove(it)
                }
            }
            .onFailure {
                TrackLog.d(TAG, "undecodable rendition $key still in use: ${it.message}", about = about)
            }
            .isSuccess
    }

    /**
     * The videoId behind a request. Playback asks through the custom scheme;
     * read-ahead builds the same URI, so both land on one cache entry.
     */
    /**
     * Marks a read made for the BitChord watch app. Its streams are capped below
     * lossless — see [AppSettings.streamCeilingOverride] — and so are kept apart
     * from this phone's own: a lossless file cached by the phone must never be
     * what the watch is handed, and the other way round.
     */
    const val WATCH_READ_PARAM = "watch_read"

    private val keyFactory = CacheKeyFactory { spec ->
        val key = phoneKey(spec)
        if (spec.uri.getQueryParameter(WATCH_READ_PARAM) != null) "$key#watch" else key
    }

    private fun phoneKey(spec: androidx.media3.datasource.DataSpec): String =
        spec.uri.getQueryParameter("v")
            // A YouTube id can name several different recordings on disk: the
            // Opus rendition YouTube serves, whatever a source ranked above it
            // hands over instead — see [SourceResolver.substituteForYouTube] —
            // and the better copy that replaces *that* mid-track when one
            // turns up, see [QualityUpgrade]. Sharing one entry between them
            // survives neither a reorder nor a half-cached track: the next
            // play would serve a FLAC prefix and then stream Opus into the
            // middle of it. Each gets its own entry, and the duplication costs
            // a re-download rather than a corrupt file.
            //
            // Written as a `when` rather than a chain of `?.let`: the previous
            // form ended `cacheTag(...)?.let { return@let "$videoId#$it" }`,
            // where `return@let` binds to the *inner* lambda, not the outer
            // one it was meant for. The upgraded key was built, discarded as
            // an unused expression, and every upgraded track fell through to
            // the `#alt` entry belonging to the stream it had just replaced —
            // so a 320kbps AAC was written into the middle of a half-cached
            // WebM, which is the exact corruption the paragraph above exists
            // to prevent. It cost `IllegalStateException: No valid varint
            // length mask found` at the seam, and eight-second stalls before
            // that, when the swap blocked on a cache lock the outgoing reader
            // still held.
            ?.let { videoId ->
                val rendition = QualityUpgrade.cacheTag(spec.uri)
                when {
                    rendition != null -> "$videoId#$rendition"
                    SourceResolver.canSubstituteForYouTube() ->
                        completeUpgradeRendition(videoId)
                            ?: "$videoId#alt${atmosKeySuffix()}".let { completeSibling(videoId, it) ?: it }
                    else -> completeUpgradeRendition(videoId)
                        ?: completeSibling(videoId, videoId)
                        ?: videoId
                }
            }
            // A source-backed track keys on the source and its track id alone.
            // The full URI would work but carries the title and artist used
            // for cross-source matching, and the same track queued from a row
            // that spelled either of them differently would then occupy a
            // second copy of itself on disk.
            ?: spec.uri.takeIf { it.authority == "source" }?.let { uri ->
                val source = uri.getQueryParameter("s")
                val track = uri.getQueryParameter("t")
                if (source != null && track != null) {
                    // A stereo row picked by hand gets an entry of its own: the
                    // shared one may hold the Atmos mix the addon used to send
                    // for it — see [SourceResolver.MIX_PARAM].
                    val stereo = uri.getQueryParameter(SourceResolver.MIX_PARAM) == SourceResolver.MIX_STEREO
                    val base = if (stereo) "$source|$track#stereo" else "$source|$track"
                    QualityUpgrade.cacheTag(uri)?.let { "$base#$it" } ?: base
                } else {
                    null
                }
            }
            ?: spec.key
            ?: spec.uri.toString()

    /**
     * The complete copy of [videoId] the cache holds under a sibling key, when
     * [preferred] — the key the current settings name — holds nothing at all.
     *
     * Which key a track lands under depends on settings that can change after
     * it was cached: ranking a source above YouTube, or turning Dolby Atmos
     * off, moves it from `videoId` to `videoId#alt` or `#alt-noatmos`, and
     * un-ranking it moves it back. Offline that made a track the Cached songs
     * folder lists — fully on disk — fail to play at all, because the player
     * asked for an empty entry and the network to fill it was not there.
     *
     * Only for a copy that is complete: a complete entry is never written to
     * again, so reading it cannot start the mixed-file seam [phoneKey] keeps
     * renditions apart to prevent.
     *
     * Online it depends on the direction. With a source ranked above YouTube
     * the preferred `#alt` key keeps its own rendition — falling back to the
     * plain entry would hand back a YouTube Opus copy in place of the better
     * one a source would resolve, and `#alt` against `#alt-noatmos` is the
     * Atmos setting itself. With nothing ranked above YouTube, the plain key
     * would only ever resolve the same YouTube stream, so a kept YouTube or
     * JioSaavn copy under `#alt` is taken online too. Measured: East Side Flow
     * cached under `#alt` while a source was ranked first, the source then
     * dropped — every online play re-resolved through InnerTubeX and wrote a
     * second, byte-identical 3.8 MB itag 251 file under the plain key, while
     * offline plays came straight off disk.
     */
    private fun completeSibling(videoId: String, preferred: String): String? {
        if (openedCache == null) return null
        // Ahead of the connectivity check, which is a binder call: nearly every
        // open is of an entry that already holds something.
        if (cache.getCachedSpans(preferred).isNotEmpty()) return null
        val siblings = listOf(videoId, "$videoId$ALT_SUFFIX", "$videoId$ALT_SUFFIX-noatmos")
            .filter { it != preferred }
        if (isOffline()) return siblings.firstOrNull(::isComplete)
        if (preferred != videoId) return null
        return siblings.firstOrNull { key -> isComplete(key) && isKeptOrigin(key) }
    }

    /**
     * A complete upgraded rendition of [videoId], if one is on disk.
     *
     * Checked before [completeSibling] because that function deliberately stops
     * as soon as the preferred base entry holds anything: the Opus copy from an
     * earlier play is complete and would otherwise keep winning for the rest of
     * the track's life, even though the verified lossless copy sits fully
     * cached under `videoId#hifi`. See [CacheRenditionChoice].
     */
    private fun completeUpgradeRendition(videoId: String): String? {
        if (openedCache == null) return null
        // A copy prefetched during this play must not take over its key until
        // the queue moves on: a seek reopens the plain URI, and handing it the
        // FLAC entry at the Opus entry's byte offset breaks the extractor.
        if (videoId in upgradeKeyDeferred) return null
        val candidates = renditionKeysFor(videoId).mapNotNull { key ->
            if (key.endsWith("#watch")) return@mapNotNull null
            val suffix = key.removePrefix("$videoId#")
            if (suffix == key) return@mapNotNull null
            val order = QualityUpgrade.upgradeGeneration(suffix)
            if (order <= 0) return@mapNotNull null
            CachedRendition(
                key = key,
                complete = isComplete(key),
                lossless = isLosslessRendition(key),
                upgradeOrder = order,
            )
        }
        if (candidates.isEmpty()) return null
        return CacheRenditionChoice.choose(videoId, candidates).takeIf { it != videoId }
    }

    /** Whether [key] was written from a stream the quality ladder calls lossless. */
    private fun isLosslessRendition(key: String): Boolean = runCatching {
        cache.getContentMetadata(key).get(META_HQ_LOSSLESS, null as String?) == "1"
    }.getOrDefault(false)

    /**
     * Whether [key]'s bytes came from YouTube or JioSaavn — the copies kept
     * past playback, see [Origin]. An addon's file is dropped once the queue
     * moves past it, but until then may sit under `#alt` as an Atmos or
     * lossless rendition nothing ranked above YouTube would serve.
     */
    private fun isKeptOrigin(key: String): Boolean = runCatching {
        val origin = cache.getContentMetadata(key).get(META_ORIGIN, null as String?)
        origin == Origin.YOUTUBE.name || origin == Origin.JIOSAAVN.name
    }.getOrDefault(false)

    /** Whether every byte of the entry [key] is on disk. */
    private fun isComplete(key: String): Boolean = runCatching {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        length > 0 && cache.isCached(key, 0, length)
    }.getOrDefault(false)

    /**
     * Whether the device has no usable connection right now. Asked by the
     * places that must behave differently offline — choosing a cache entry,
     * and deciding whether a failed read means the bytes on disk are bad.
     */
    fun isOffline(): Boolean {
        val context = appContext ?: return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }
            .getOrNull()
        // A captive portal advertises INTERNET but only serves its sign-in
        // page. Treating that as online sent every lookup into a timeout; the
        // portal's own capability is the event-free way to tell them apart.
        // (A proxy-only network can be unvalidated but is not a portal, so
        // VALIDATED is deliberately not required here.)
        //
        // No capabilities means there is no active network — airplane mode, or
        // a moment after Wi-Fi and cellular have both dropped. Reporting that
        // as online skipped the cached copy and sent every lookup into a
        // timeout instead.
        return offlineFromCapabilities(
            hasCapabilities = capabilities != null,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        )
    }

    /**
     * Splits the `#alt` bucket in two along whatever this process currently
     * believes about Dolby Atmos, so a listener who turns the setting off
     * cannot be handed an Atmos file back out of the cache.
     *
     * Without this, `#alt` names one entry regardless of which rendition of
     * "not YouTube" ended up in it, and [CacheDataSource] answers from that
     * entry without ever calling the resolver — see the class doc. A track
     * first played with Atmos on writes an Atmos file under `#alt`; turning
     * the setting off changes nothing about that file, so the very next play
     * of the same track reads it straight off disk, atmosAllowed check and
     * all, because the check lives in [ModuleSource.unplayable] and that code
     * never runs. The suffix means "atmos off" resolves and caches under a
     * key the "atmos on" file was never written to, so the setting actually
     * takes effect on replay; flipping back on reads the original file again
     * rather than re-fetching it.
     */
    private fun atmosKeySuffix(): String =
        if (DeviceCodecs.playsDolbyAtmos && AppSettings.dolbyAtmos.value) "" else "-noatmos"

    /**
     * Which kind of server a cache entry's bytes came from, as recorded by
     * [recordServed].
     *
     * Only [YOUTUBE] and [JIOSAAVN] are kept on disk once a track stops
     * playing — they are what the player reaches first, before any addon
     * has answered, so they are the copies worth having instantly next time.
     * Everything else — an addon's or a module's stream, a lossless upgrade
     * from one of them — is [OTHER]: cached while it plays, so seeking and
     * Automix's analysis work exactly as before, then dropped by
     * [dropForeignEntries].
     *
     * The origin alone is not what the Cached songs folder shows. An entry is
     * listed only once the player has actually played the track from it
     * ([META_PLAYED]) — not for Automix's own YouTube Opus copy
     * ([META_ANALYSIS]), which is a separate download made for analysis even
     * while the track plays from JioSaavn or an addon, and not for read-ahead
     * of a track the queue never reached.
     */
    enum class Origin { YOUTUBE, JIOSAAVN, OTHER }

    /** One track in the Cached songs folder. */
    data class CachedSong(
        val song: Song,
        val origin: Origin,
        /** Bytes on disk across every kept rendition of the track. */
        val bytes: Long,
        /** When any of those bytes were last read or written, epoch millis. */
        val lastUsedMs: Long,
        /**
         * Whether one of those renditions is on disk from first byte to last —
         * what playing it with no connection, and saving it to Downloads
         * straight from the cache, both need. See [completeCopyOf].
         */
        val complete: Boolean,
    )

    private val _contentsChanged = MutableStateFlow(0)

    /** Bumped whenever the cache is emptied, so an open Cached songs folder can re-read itself. */
    val contentsChanged: StateFlow<Int> = _contentsChanged.asStateFlow()

    private val _renditionCompleted = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /**
     * Video ids whose rendition just became complete on disk, one emission per
     * rendition key. Event-driven — nothing polls — and used by the lyrics
     * panel to offer AI timing once a stream that was still arriving when the
     * lyrics loaded has finished caching. See [watchRenditionCompletion].
     */
    val renditionCompleted: SharedFlow<String> = _renditionCompleted.asSharedFlow()

    /**
     * Video ids whose rendition is complete on disk, as state rather than only
     * an event.
     *
     * A SharedFlow with no replay loses a completion that happened before the
     * lyrics panel subscribed, and can drop one when its buffer is full during
     * a burst of cache writes. This holds the same answer for a late collector,
     * so the AI timing action still appears after the copy finished.
     */
    private val _completedRenditions = MutableStateFlow<Set<String>>(emptySet())
    val completedRenditions: StateFlow<Set<String>> = _completedRenditions.asStateFlow()

    /** Cache keys whose completion listener is installed, so each is watched once. */
    private val watchedCompletionKeys = ConcurrentHashMap.newKeySet<String>()

    /** Keys already reported complete, so adding more spans does not repeat the event. */
    private val completedKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * Watches [videoId]'s renditions for the moment one holds the whole file,
     * then emits its video id on [renditionCompleted]. Safe to call repeatedly;
     * a key is only watched once for the process.
     *
     * The plain and substitute keys are watched even before they exist, so a
     * track whose first bytes arrive after the lyrics are shown is still
     * caught. Callers that need an event must call this before collecting.
     */
    fun watchRenditionCompletion(videoId: String) {
        if (openedCache == null) return
        watchCompletionKey(videoId)
        watchCompletionKey("$videoId$ALT_SUFFIX")
        renditionKeysFor(videoId).forEach(::watchCompletionKey)
    }

    private fun watchCompletionKey(key: String) {
        if (openedCache == null) return
        if (!watchedCompletionKeys.add(key)) return
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                val rendition = renditionFor(key) ?: return
                if (!rendition.isComplete) return
                if (!completedKeys.add(key)) return
                val mediaId = key.substringBefore('#')
                _completedRenditions.update { it + mediaId }
                _renditionCompleted.tryEmit(mediaId)
            }

            override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
                // The entry (or one of its spans) is gone; a later rebuild of
                // the same key must be allowed to report completion again.
                completedKeys.remove(key)
                // Other renditions of the same media (the plain key, the
                // alternate, an older quality) may still hold the whole file.
                // Removing the media id just because this one key lost a span
                // hid AI timing on later plays, and nothing re-added it.
                val mediaId = key.substringBefore('#')
                val stillComplete = completionSurvivesSpanRemoval(
                    removedKey = key,
                    remainingKeys = renditionKeysFor(mediaId),
                ) { otherKey -> renditionFor(otherKey)?.isComplete == true }
                _completedRenditions.update { current ->
                    if (stillComplete) current + mediaId else current - mediaId
                }
            }

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        }
        runCatching { cache.addListener(key, listener) }
            .onSuccess {
                // The copy may already be complete from before this listener
                // existed (lyrics arriving late). State must hold that answer:
                // no span-added event will ever be delivered for it.
                val rendition = renditionFor(key) ?: return@onSuccess
                if (!rendition.isComplete || !completedKeys.add(key)) return@onSuccess
                val mediaId = key.substringBefore('#')
                _completedRenditions.update { it + mediaId }
                _renditionCompleted.tryEmit(mediaId)
            }
            .onFailure {
                watchedCompletionKeys.remove(key)
                TrackLog.w(TAG, "could not watch cache completion for $key: ${it.message}")
            }
    }

    /**
     * Whether losing [removedKey] still leaves another complete rendition of
     * the same media.
     *
     * A media id has several cache keys (plain, subtitle, alternate qualities).
     * Completion is a property of the media, not of whichever key happened to
     * fire the removal, so the id may only be forgotten when no other key
     * remains whole.
     */
    internal fun completionSurvivesSpanRemoval(
        removedKey: String,
        remainingKeys: List<String>,
        isComplete: (String) -> Boolean,
    ): Boolean = remainingKeys.any { it != removedKey && isComplete(it) }

    /**
     * Fires the media id each time the player (or a read-ahead) opens a stream
     * for it — the point at which more bytes are about to be written. A timing
     * pass that is waiting for a video's opening can suspend on this instead
     * of polling the cache on a timer.
     */
    private val _served = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val served: SharedFlow<String> = _served.asSharedFlow()

    /** How many bytes playback has read per video, so a wake-up is not sent for every buffer. */
    private val playedProgress = ConcurrentHashMap<String, Long>()

    private fun notePlayedBytes(videoId: String, bytes: Int) {
        if (bytes <= 0) return
        val after = playedProgress.merge(videoId, bytes.toLong(), Long::plus) ?: return
        val before = after - bytes
        if (before / PROGRESS_STEP_BYTES != after / PROGRESS_STEP_BYTES) _served.tryEmit(videoId)
    }

    private const val PROGRESS_STEP_BYTES = 256L * 1024

    /**
     * What the queue said about the tracks the cache is about to hold.
     *
     * The resolver seam [recordServed] runs at only has a DataSpec in hand —
     * a read-ahead URI is nothing but a video id — so the title and artwork
     * the folder shows are noted here by the player as tracks are queued
     * up and become current. Bounded: only tracks near the playhead are ever
     * being written.
     */
    private val notedSongs = ConcurrentHashMap<String, Song>()

    /**
     * Tracks that have become current this session — the ones playback has
     * really played. Read by [recordServed] for entries whose first bytes
     * arrive after the track became current.
     */
    private val playedIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Remembers [songs]' metadata for the cache entries they are about to
     * fill, and backfills it into any entry already tagged without it.
     */
    fun noteSongs(songs: List<Song>) {
        val playable = songs.filter { it.localUri == null && it.videoId.isNotBlank() }
        if (playable.isEmpty()) return
        if (notedSongs.size > MAX_NOTED_SONGS) notedSongs.clear()
        playable.forEach { notedSongs[it.videoId] = it }
        if (openedCache == null) return
        scope.launch {
            val keys = cache.keys
            playable.forEach { song ->
                val base = cacheKeyBaseOf(song.videoId)
                keys.filter { it == base || it.startsWith("$base#") }.forEach { key ->
                    runCatching {
                        val meta = cache.getContentMetadata(key)
                        if (meta.get(META_ORIGIN, null as String?) == null) return@runCatching
                        if (meta.get(META_TITLE, null as String?) != null &&
                            meta.get(META_ART, null as String?) != null
                        ) {
                            return@runCatching
                        }
                        cache.applyContentMetadataMutations(
                            key,
                            ContentMetadataMutations().also { describe(it, song, null) },
                        )
                    }
                }
            }
        }
    }

    /**
     * The track [song] just became current, playing from [uri]: marks the
     * entry the player reads it from as played — see [Origin].
     *
     * The key comes from the same [keyFactory] the player's [CacheDataSource]
     * uses, so it names exactly the rendition playback is reading, never a
     * sibling copy something else wrote. A track replayed entirely from disk
     * never reaches [recordServed], so this is also what keeps such a replay
     * counted. If the entry doesn't exist yet, [recordServed] marks it as its
     * first bytes arrive.
     */
    fun notePlayed(song: Song, uri: Uri?) {
        if (song.localUri != null || song.videoId.isBlank()) return
        if (playedIds.size > MAX_NOTED_SONGS) playedIds.clear()
        playedIds += song.videoId
        noteSongs(listOf(song))
        if (openedCache == null || uri?.scheme != "bitchord") return
        scope.launch {
            val key = keyFactory.buildCacheKey(DataSpec(uri))
            runCatching {
                val meta = cache.getContentMetadata(key)
                if (meta.get(META_ORIGIN, null as String?) == null) return@runCatching
                if (meta.get(META_PLAYED, null as String?) != null) {
                    updatePurgeCandidate(key, purgeCandidate(meta))
                    return@runCatching
                }
                cache.applyContentMetadataMutations(
                    key,
                    ContentMetadataMutations().set(META_PLAYED, "1"),
                )
                updatePurgeCandidate(
                    key,
                    BatteryPolicy.cacheEntryNeedsPurge(
                        origin = meta.get(META_ORIGIN, null as String?),
                        foreignOrigin = Origin.OTHER.name,
                        hasAnalysis = meta.get(META_ANALYSIS, null as String?) != null,
                        hasPlayback = meta.get(META_PLAYBACK, null as String?) != null,
                        hasPlayed = true,
                    ),
                )
            }
        }
    }

    /**
     * Tags the cache entry [spec] is filling with who is serving it.
     *
     * Called from the player's resolving data source, the one place where the
     * `bitchord://` request and the real stream URL it resolved to are both in
     * hand — see PlaybackService. [CacheDataSource] has already put the cache
     * key it is writing under into [DataSpec.key], so the tag lands on exactly
     * the entry being filled, whichever rendition that is.
     *
     * Stored in the entry's own content metadata rather than a side table:
     * it is evicted, discarded and cleared together with the bytes it
     * describes, and there is nothing to keep in step.
     */
    fun recordServed(spec: DataSpec, servedUrl: String) {
        if (openedCache == null) return
        if (spec.uri.scheme != "bitchord") return
        val mediaId = mediaIdIn(spec.uri) ?: return
        _served.tryEmit(mediaId)
        val key = spec.key ?: keyFactory.buildCacheKey(spec)
        val origin = originOf(spec.uri, mediaId, servedUrl)
        val song = notedSongs[mediaId]
        // Automix's analysis-only read: its own YouTube Opus download, made
        // whatever the track is actually playing from. Never playback.
        val analysis = AutomixAnalysisSource.requestsYouTubeOpus(
            spec.uri.getQueryParameter(AutomixAnalysisSource.OPUS_QUERY_PARAMETER),
        )
        runCatching {
            val meta = cache.getContentMetadata(key)
            fun has(name: String) = meta.get(name, null as String?) != null
            val usedByPlayback = has(META_PLAYBACK) || !analysis
            val played = has(META_PLAYED) || (!analysis && mediaId in playedIds)
            val tagged = meta.get(META_ORIGIN, null as String?) == origin.name &&
                meta.get(META_MEDIA_ID, null as String?) == mediaId &&
                has(META_PLAYBACK) == usedByPlayback &&
                (usedByPlayback || has(META_ANALYSIS)) &&
                has(META_PLAYED) == played
            // Every re-open — a seek, a continuation fetch — passes through
            // here. Only write when something would actually change.
            if (tagged && (song == null || has(META_TITLE))) {
                updatePurgeCandidate(key, purgeCandidate(meta))
                return
            }
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().also { mutations ->
                    mutations.set(META_ORIGIN, origin.name)
                    mutations.set(META_MEDIA_ID, mediaId)
                    if (usedByPlayback) mutations.set(META_PLAYBACK, "1") else mutations.set(META_ANALYSIS, "1")
                    if (played) mutations.set(META_PLAYED, "1")
                    describe(mutations, song, spec.uri)
                },
            )
            updatePurgeCandidate(
                key,
                BatteryPolicy.cacheEntryNeedsPurge(
                    origin = origin.name,
                    foreignOrigin = Origin.OTHER.name,
                    hasAnalysis = !usedByPlayback,
                    hasPlayback = usedByPlayback,
                    hasPlayed = played,
                ),
            )
        }.onFailure {
            TrackLog.d(TAG, "could not tag cache entry $key: ${it.message}", about = mediaId)
        }
    }

    /**
     * Whose file [servedUrl] is.
     *
     * The host settles it for the two kinds that are kept: YouTube only ever
     * hands out googlevideo URLs and JioSaavn its own CDN. The source that was
     * chosen is the fallback, for a JioSaavn URL on a host this doesn't know
     * — read in the same order the resolver answers in: an upgrade's stream,
     * then a source-backed track's own source, then the choice already
     * serving the track. Anything not positively one of the two is [Origin.OTHER].
     */
    private fun originOf(uri: Uri, mediaId: String, servedUrl: String): Origin {
        val host = runCatching { Uri.parse(servedUrl).host }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()
        if (host.endsWith("googlevideo.com") || host.endsWith("youtube.com")) return Origin.YOUTUBE
        if (host.endsWith("saavncdn.com") || host.endsWith("jiosaavn.com")) return Origin.JIOSAAVN
        val upgrade = QualityUpgrade.forcedStream(uri)
        val configId = when {
            upgrade != null -> upgrade.sourceConfigId
            uri.getQueryParameter("s") != null -> uri.getQueryParameter("s")
            else -> StreamChoice.of(mediaId)?.sourceConfigId
        }
        val kind = configId?.let { SourceRegistry.config(it)?.kind }
        return if (kind == SourceKind.JIOSAAVN) Origin.JIOSAAVN else Origin.OTHER
    }

    /** Writes what the folder shows about a track into [mutations]. */
    private fun describe(mutations: ContentMetadataMutations, song: Song?, uri: Uri?) {
        val title = song?.title?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("n")
        val artist = song?.artist?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("a")
        val album = song?.albumName?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("l")
        val duration = song?.durationText
            ?: uri?.getQueryParameter("d")?.toIntOrNull()?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) }
        title?.takeIf { it.isNotBlank() }?.let { mutations.set(META_TITLE, it) }
        artist?.takeIf { it.isNotBlank() }?.let { mutations.set(META_ARTIST, it) }
        album?.takeIf { it.isNotBlank() }?.let { mutations.set(META_ALBUM, it) }
        duration?.takeIf { it.isNotBlank() }?.let { mutations.set(META_DURATION, it) }
        song?.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { mutations.set(META_ART, it) }
    }

    /**
     * Every track the player has played from YouTube or JioSaavn audio that is
     * still on disk, most recently used first — what the Cached songs folder
     * lists. Only the entries playback read from count; see [Origin].
     *
     * A track owns up to several entries (see [keyFactory]); they are folded
     * into one row. Spans are checked against the disk rather than trusted:
     * the system's "Clear cache" deletes the files underneath a running
     * [SimpleCache], which goes on believing in them until it next reads one.
     */
    suspend fun cachedSongs(): List<CachedSong> = withContext(Dispatchers.IO) {
        // A page read, not a hot path: opening the cache here is exactly what
        // the caller is asking for, and it is off the main thread.
        class Entry(
            val origin: Origin,
            val meta: ContentMetadata,
            val bytes: Long,
            val touched: Long,
            val complete: Boolean,
        )
        val byTrack = LinkedHashMap<String, MutableList<Entry>>()
        for (key in cache.keys) {
            val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: continue
            val origin = meta.get(META_ORIGIN, null as String?)
                ?.let { runCatching { Origin.valueOf(it) }.getOrNull() }
                ?: continue
            if (origin == Origin.OTHER) continue
            if (meta.get(META_PLAYED, null as String?) == null) continue
            val mediaId = meta.get(META_MEDIA_ID, null as String?) ?: continue
            val spans = cache.getCachedSpans(key).filter { it.isCached && it.file?.exists() == true }
            val bytes = spans.sumOf { it.length }
            if (bytes <= 0L) continue
            val touched = spans.maxOf { it.lastTouchTimestamp }
            val length = ContentMetadata.getContentLength(meta)
            val complete = length > 0 && bytes >= length && cache.isCached(key, 0, length)
            byTrack.getOrPut(mediaId) { mutableListOf() } += Entry(origin, meta, bytes, touched, complete)
        }
        byTrack.map { (mediaId, entries) ->
            fun field(name: String): String? =
                entries.firstNotNullOfOrNull { it.meta.get(name, null as String?)?.takeIf(String::isNotBlank) }
            val bytes = entries.sumOf { it.bytes }
            val lastUsed = entries.maxOf { it.touched }
            val origin = entries.maxBy { it.bytes }.origin
            val complete = entries.any { it.complete }
            val noted = notedSongs[mediaId]
            val art = field(META_ART) ?: noted?.thumbnailUrl
                ?: mediaId.takeIf { SourceRegistry.parseTrackKey(it) == null }
                    ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            CachedSong(
                song = Song(
                    videoId = mediaId,
                    title = field(META_TITLE) ?: noted?.title?.takeIf(String::isNotBlank) ?: mediaId,
                    artist = field(META_ARTIST) ?: noted?.artist.orEmpty(),
                    thumbnailUrl = art,
                    durationText = field(META_DURATION) ?: noted?.durationText,
                    albumName = field(META_ALBUM) ?: noted?.albumName,
                    // The row's second line: where the copy came from, what
                    // it costs on disk, and — when only part of it is there —
                    // that it will need a connection to play to the end.
                    downloadFormat = listOfNotNull(
                        origin.label,
                        formatBytes(bytes),
                        appContext?.getString(R.string.cached_song_partial)?.takeUnless { complete },
                    ).joinToString(" · "),
                    localDateAddedSeconds = lastUsed / 1000,
                ),
                origin = origin,
                bytes = bytes,
                lastUsedMs = lastUsed,
                complete = complete,
            )
        }.sortedByDescending { it.lastUsedMs }
    }

    /** Every cache key holding a rendition of [mediaId]; see [keyFactory]. */
    private fun keysOf(mediaId: String): List<String> {
        val base = cacheKeyBaseOf(mediaId)
        return cache.keys.filter { key ->
            key == base || key.startsWith("$base#") ||
                runCatching { cache.getContentMetadata(key).get(META_MEDIA_ID, null as String?) }
                    .getOrNull() == mediaId
        }
    }

    /**
     * Whether the cache holds any bytes of [mediaId] — what decides whether a
     * song's menu offers to remove it from the cache. Reads the index, so call
     * it off the main thread.
     */
    fun holds(mediaId: String): Boolean {
        if (openedCache == null || mediaId.isBlank()) return false
        return keysOf(mediaId).any { key -> cache.getCachedSpans(key).any { it.isCached } }
    }

    /**
     * Takes every rendition of [mediaId] off disk, and with it the track's row
     * in the Cached songs folder. The listener asked, from a song's menu or
     * the folder itself.
     *
     * A track that is playing right now keeps playing: the reader already has
     * its file open, and whatever it fetches after this is written back. It is
     * forgotten as played, though, so those bytes come back as ordinary cache
     * the folder does not list rather than undoing the removal in front of the
     * listener.
     *
     * @return how many entries went.
     */
    suspend fun remove(mediaId: String): Int = withContext(Dispatchers.IO) {
        if (openedCache == null || mediaId.isBlank()) return@withContext 0
        playedIds.remove(mediaId)
        analysisHeads.remove(mediaId)
        analysisHeadsInFlight.remove(mediaId)
        analysisHeadDeferredLogged.remove(mediaId)
        analysisHeadsHeldElsewhere.remove(mediaId)
        analysisHeadWant.remove(mediaId)
        renditionKeys.remove(mediaId)
        var removed = 0
        keysOf(mediaId).forEach { key ->
            runCatching { cache.removeResource(key) }
                .onSuccess {
                    forgetPurgeCandidate(key)
                    removed++
                }
                .onFailure { TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = mediaId) }
        }
        TrackLog.d(TAG, "removed $removed cache entries at the listener's request", about = mediaId)
        _contentsChanged.value++
        removed
    }

    /**
     * One rendition of a track that is on disk in full, ready to be copied out
     * by [copyOut] — see [completeCopyOf].
     */
    class CompleteCopy(
        internal val key: String,
        val length: Long,
        /** The file extension its container is saved under: `webm`, `m4a` or `flac`. */
        val extension: String,
        val mimeType: String,
    )

    /**
     * The best complete copy of [mediaId] the cache holds, or null when none
     * is whole or its container is not one a download can be filed as.
     *
     * This is what lets the Cached songs folder save a track to Downloads
     * without the network — the bytes are already here, and fetching them
     * again would spend the listener's data on a file they have. The largest
     * complete rendition is taken, which across one track's renditions is the
     * highest quality. A copy made for the watch is never offered: it is
     * capped below what the phone would keep.
     *
     * The container is read off the file's own first bytes rather than
     * inferred from where it came from: a substituted rendition can be any of
     * several, and a manifest cached by mistake must not become a `.flac`.
     */
    fun completeCopyOf(mediaId: String): CompleteCopy? {
        if (openedCache == null || mediaId.isBlank()) return null
        return keysOf(mediaId)
            .filterNot { it.endsWith("#watch") }
            .mapNotNull { key ->
                val length = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }
                    .getOrDefault(C.LENGTH_UNSET.toLong())
                if (length <= 0 || !cache.isCached(key, 0, length)) return@mapNotNull null
                val (extension, mime) = containerOf(key) ?: return@mapNotNull null
                CompleteCopy(key, length, extension, mime)
            }
            .maxByOrNull { it.length }
    }

    /**
     * Whether [mediaId] already has a whole lossless rendition on disk.
     *
     * The automatic high-quality pass leaves such a song alone: the copy
     * answers the request, and fetching the verified pair again would only
     * spend network and battery on bytes that are already here. A copy made
     * for the watch is excluded, exactly as in [completeCopyOf].
     */
    fun hasCompleteLosslessCopy(mediaId: String): Boolean {
        if (openedCache == null || mediaId.isBlank()) return false
        return keysOf(mediaId)
            .filterNot { it.endsWith("#watch") }
            .any { key ->
                val lossless = isLosslessRendition(key) ||
                    containerOf(key)?.first?.let { it in LOSSLESS_CONTAINERS } == true
                if (!lossless) return@any false
                runCatching {
                    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                    length > 0 && cache.isCached(key, 0, length)
                }.getOrDefault(false)
            }
    }

    /** The container of the entry [key], from the magic at its very start. */
    private fun containerOf(key: String): Pair<String, String>? {
        val head = cache.getCachedSpans(key)
            .firstOrNull { it.position == 0L && it.isCached }
            ?.file
            ?.takeIf { it.exists() }
            ?: return null
        val magic = ByteArray(CONTAINER_MAGIC_BYTES)
        val read = runCatching { head.inputStream().use { it.read(magic) } }.getOrDefault(-1)
        if (read < magic.size) return null
        return cachedContainerOf(magic)
    }

    /**
     * Writes [copy] to [sink] from disk alone, never the network.
     *
     * Read through a [CacheDataSource] with no upstream at all, so a span that
     * has gone missing since [completeCopyOf] looked fails the copy instead of
     * being quietly filled from a stream that may be a different file.
     */
    suspend fun copyOut(
        copy: CompleteCopy,
        sink: java.io.OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // No upstream factory, so anything not on disk fails rather than streams.
        val source = CacheDataSource.Factory().setCache(cache).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("bitchord://cache"))
            .setKey(copy.key)
            .setPosition(0)
            .setLength(copy.length)
            .build()
        try {
            source.open(spec)
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var written = 0L
            while (written < copy.length) {
                ensureActive()
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, copy.length)
            }
            if (written < copy.length) {
                throw IOException("cached copy ended at $written of ${copy.length} bytes")
            }
        } finally {
            source.close()
        }
    }

    /**
     * Drops every entry an addon or module filled ([Origin.OTHER]), and every
     * copy Automix downloaded for analysis alone that playback never read,
     * except the ones belonging to [keep] — the tracks around the playhead,
     * whose bytes are still being read, seeked through and analysed.
     *
     * This is what "only the YouTube and JioSaavn audio that played is cached"
     * means in practice: the bytes an addon serves pass through the cache so
     * playback behaves the same as ever, and leave it once the track does.
     * Analysis results are kept in their own store, so the analysis-only
     * audio has done its job by then.
     */
    /**
     * Recordings fetched for lyric timing — a cover's original, the next video
     * — that no queue holds. They are "analysis only" to the sweep below, which
     * deleted them at the next track change, usually before the measurement had
     * read them: the original was fetched and gone, every time ("원곡을 받지 못함"),
     * which is why video lyrics were so rarely timed. Held [TIMING_KEEP_MS].
     */
    private val timingKeep = LinkedHashMap<String, Long>()

    fun keepForTiming(videoId: String) = synchronized(timingKeep) {
        timingKeep.remove(videoId)
        timingKeep[videoId] = SystemClock.elapsedRealtime()
        while (timingKeep.size > TIMING_KEEP_MAX) timingKeep.remove(timingKeep.keys.first())
    }

    private fun keptForTiming(videoId: String): Boolean = synchronized(timingKeep) {
        val at = timingKeep[videoId] ?: return false
        SystemClock.elapsedRealtime() - at < TIMING_KEEP_MS
    }

    private const val TIMING_KEEP_MS = 15 * 60_000L
    private const val TIMING_KEEP_MAX = 16

    /**
     * Cache keys that may need dropping, maintained as entries are written and
     * removed rather than rediscovered by walking the whole cache on every
     * track change.
     *
     * A multi-gigabyte cache holds tens of thousands of keys, and the old
     * [dropForeignEntries] read every one's metadata after each transition.
     * The index is seeded once from disk (see [rebuildPurgeIndex]) and then
     * follows [recordServed], [notePlayed] and the removal paths.
     */
    private val purgeCandidates = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var purgeIndexBuilt = false

    private fun purgeCandidate(meta: ContentMetadata): Boolean = BatteryPolicy.cacheEntryNeedsPurge(
        origin = meta.get(META_ORIGIN, null as String?),
        foreignOrigin = Origin.OTHER.name,
        hasAnalysis = meta.get(META_ANALYSIS, null as String?) != null,
        hasPlayback = meta.get(META_PLAYBACK, null as String?) != null,
        hasPlayed = meta.get(META_PLAYED, null as String?) != null,
    )

    private fun updatePurgeCandidate(key: String, candidate: Boolean) {
        if (candidate) purgeCandidates.add(key) else purgeCandidates.remove(key)
    }

    private fun forgetPurgeCandidate(key: String) {
        purgeCandidates.remove(key)
    }

    private fun rebuildPurgeIndex() {
        if (openedCache == null) {
            purgeIndexBuilt = true
            return
        }
        // Only adds: a concurrent [recordServed] may have put a newer key in
        // the set while this scan was running, and it must not be lost.
        cache.keys.forEach { key ->
            val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: return@forEach
            if (purgeCandidate(meta)) purgeCandidates.add(key)
        }
        purgeIndexBuilt = true
    }

    fun dropForeignEntries(keep: Set<String>) {
        if (openedCache == null) return
        scope.launch {
            if (!purgeIndexBuilt) rebuildPurgeIndex()
            var dropped = 0
            purgeCandidates.toList().forEach { key ->
                val meta = runCatching { cache.getContentMetadata(key) }.getOrNull()
                if (meta == null || !purgeCandidate(meta)) {
                    forgetPurgeCandidate(key)
                    return@forEach
                }
                val mediaId = meta.get(META_MEDIA_ID, null as String?)
                if (mediaId != null && (mediaId in keep || keptForTiming(mediaId))) return@forEach
                runCatching { cache.removeResource(key) }
                    .onSuccess {
                        dropped++
                        forgetPurgeCandidate(key)
                        mediaId?.let { id ->
                            analysisHeads.remove(id)
                            analysisHeadDeferredLogged.remove(id)
                            analysisHeadsHeldElsewhere.remove(id)
                            analysisHeadWant.remove(id)
                            stalledAnalysisHeads.remove(id)
                            renditionKeys.remove(id)
                        }
                    }
                    .onFailure { TrackLog.d(TAG, "unkept cache entry $key still in use: ${it.message}") }
            }
            if (dropped > 0) TrackLog.d(TAG, "dropped $dropped addon / analysis-only cache entries")
        }
    }

    /** The part of a cache key that names the track, before any `#rendition`; see [keyFactory]. */
    private fun cacheKeyBaseOf(mediaId: String): String =
        SourceRegistry.parseTrackKey(mediaId)?.let { (source, track) -> "$source|$track" } ?: mediaId

    private val Origin.label: String
        get() = when (this) {
            Origin.YOUTUBE -> SourceKind.YOUTUBE.label
            Origin.JIOSAAVN -> SourceKind.JIOSAAVN.label
            Origin.OTHER -> "Addon"
        }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(Locale.ROOT, mb / 1024) else "%.1f MB".format(Locale.ROOT, mb)
    }

    private const val META_ORIGIN = "bc_origin"
    private const val META_MEDIA_ID = "bc_media_id"
    private const val META_TITLE = "bc_title"
    private const val META_ARTIST = "bc_artist"
    private const val META_ALBUM = "bc_album"
    private const val META_DURATION = "bc_duration"
    private const val META_ART = "bc_art"

    /** Set once a non-analysis request — the player or its read-ahead — has read this entry. */
    private const val META_PLAYBACK = "bc_playback"

    /** Set on an entry Automix's analysis-only YouTube Opus fetch filled. */
    private const val META_ANALYSIS = "bc_analysis"

    /** Set once the player has played the track from this entry. */
    private const val META_PLAYED = "bc_played"

    /**
     * Set on a rendition filled from a stream the quality ladder calls lossless.
     * Read when a plain play request picks between several complete renditions
     * — see [CacheRenditionChoice] — and when the lower-quality copy is dropped.
     */
    private const val META_HQ_LOSSLESS = "bc_hq_lossless"

    /** Containers that are lossless whatever their metadata says. */
    private val LOSSLESS_CONTAINERS = setOf("flac", "wav")

    private const val MAX_NOTED_SONGS = 256
    private const val COPY_BUFFER_BYTES = 64 * 1024

    private const val CACHE_DIR_NAME = "audio_cache"
    private const val LEGACY_CACHE_DIR_NAME = "audio"
    private const val CACHE_STATE_PREFS = "audio_cache_state"
    private const val KEY_CACHE_FORMAT = "cache_format"

    /**
     * Bumped only by a build whose keys or bytes the previous one's cache
     * cannot be read under. Bumping it empties the Cached songs folder for
     * everyone who updates, so it is not for ordinary releases.
     */
    private const val CACHE_FORMAT = 1
    private const val KEY_MATCHING_SCHEMA = "matching_schema"
    private const val MATCHING_SCHEMA = 2
    private const val ALT_SUFFIX = "#alt"

    /**
     * Wraps [upstream] so everything played is written to disk on the way
     * through, and anything already there is served without a request.
     * Local file and content URIs bypass disk caching to prevent redundant writes.
     */
    fun playbackFactory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val cacheDs = cacheFactory(upstream).createDataSource()
        val upstreamDs = upstream.createDataSource()
        object : DataSource {
            private var activeDs: DataSource = cacheDs
            private var heldKey: String? = null
            private var heldVideoId: String? = null

            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                cacheDs.addTransferListener(transferListener)
                upstreamDs.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                // A DataSource instance is normally closed before it is opened
                // again, but the hold must not be counted twice if it is not.
                // This is not a terminal release: the same source is about to
                // re-retain the key, so it must not wake a head retry that would
                // race the reopened playback reader.
                releaseHeld(retryAnalysisHead = false)
                val scheme = dataSpec.uri.scheme
                activeDs = if (scheme == "file" || scheme == "content") {
                    upstreamDs
                } else {
                    cacheDs
                }
                if (scheme != "file" && scheme != "content") {
                    // Remember that the player — including the item it buffers
                    // ahead in its playlist — owns this cache entry. Read-ahead
                    // and the analysis head ask before touching it; Media3
                    // gives an entry to one writer, so anything else spends the
                    // listener's data writing nowhere.
                    val key = cacheKeyOf(dataSpec.uri)
                    val videoId = dataSpec.uri.getQueryParameter("v")
                    heldKey = key
                    heldVideoId = videoId
                    retain(playbackKeysInUse, key)
                    videoId?.let { retain(playbackVideoIdsInUse, it) }
                }
                return try {
                    activeDs.open(dataSpec)
                } catch (failure: Throwable) {
                    releaseHeld()
                    throw failure
                }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val read = activeDs.read(buffer, offset, length)
                heldVideoId?.let { if (read > 0) notePlayedBytes(it, read) }
                return read
            }

            override fun getUri(): Uri? = activeDs.uri

            override fun close() {
                try {
                    activeDs.close()
                } finally {
                    releaseHeld()
                }
            }

            private fun releaseHeld(retryAnalysisHead: Boolean = true) {
                heldKey?.let { key ->
                    release(playbackKeysInUse, key)
                    if (!playerHoldsKey(key)) keyIdleSignals.remove(key)?.complete(Unit)
                }
                heldVideoId?.let { videoId ->
                    release(playbackVideoIdsInUse, videoId)
                    // The held analysis claim waits for this event rather than
                    // for the next idle tick to discover the lock is gone. Only
                    // a terminal release counts: open() releases before it
                    // re-retains the same key, and retrying there would race
                    // the reader it just reopened.
                    if (retryAnalysisHead) retryAnalysisHeadIfReaderReleased(videoId)
                }
                heldKey = null
                heldVideoId = null
            }
        }
    }

    /**
     * Cache entries the player (or its read-ahead inside ExoPlayer's playlist)
     * is currently reading, as open counts. Tracking rather than asking
     * `Cache.isCached` is what makes "the player already has it" visible before
     * a competing writer has to discover it by failing to write.
     */
    private val playbackKeysInUse = ConcurrentHashMap<String, Int>()
    private val playbackVideoIdsInUse = ConcurrentHashMap<String, Int>()

    /**
     * One deferred per cache key, completed when the player lets go of that
     * entry. A whole-track fetch waits on this instead of polling: Media3 gives
     * an entry to one writer, and the player holds it for as long as it is
     * streaming the rendition.
     */
    private val keyIdleSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Whole-rendition downloads in flight, so a queue move can stop the old one. */
    private val wholeRenditionJobs = ConcurrentHashMap<String, Job>()

    /**
     * Tracks whose verified lossless copy has already been asked for in the
     * background. One attempt per track, whatever it returns: a second search
     * for the same bytes while the first is still running would only compete
     * with the playback it is meant to improve.
     */
    private val losslessPrefetchAttempted = ConcurrentHashMap.newKeySet<String>()

    /** The single background lossless fetch, so two tracks never download at once. */
    private var losslessPrefetchJob: Job? = null

    /** The track [losslessPrefetchJob] belongs to, so a queue change can stop only that one. */
    @Volatile
    private var losslessPrefetchFor: String? = null

    /**
     * Tracks whose lossless copy finished while they were still playing, with
     * the rendition key that must be kept.
     *
     * The low-quality entry cannot be removed then: the player still owns the
     * plain URI and may reopen its cache key for a seek, which would now resolve
     * to the lossless sibling mid-song. The drop waits for the queue to move off
     * the track instead, where the next play already prefers the lossless copy.
     */
    private val pendingLowQualityDrops = ConcurrentHashMap<String, String>()

    /**
     * Tracks with a background lossless fetch started during their current
     * play. [completeUpgradeRendition] ignores them until another track becomes
     * current, so one play never sees its cache key change under it.
     */
    private val upgradeKeyDeferred = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var currentMediaId: String? = null

    /**
     * Tracks whose bytes read-ahead is writing right now. The analysis head is
     * another writer of the same entry, and Media3 allows one: without this it
     * requested the range read-ahead already held, wrote nothing, and logged
     * `held=0` over a track that was being cached at that moment.
     */
    private val readAheadWrites = ConcurrentHashMap<String, Int>()

    private fun retain(counts: ConcurrentHashMap<String, Int>, key: String) {
        counts.merge(key, 1) { open, added -> open + added }
    }

    private fun release(counts: ConcurrentHashMap<String, Int>, key: String) {
        counts.compute(key) { _, open -> if (open == null || open <= 1) null else open - 1 }
    }

    private fun playerHoldsKey(cacheKey: String): Boolean =
        (playbackKeysInUse[cacheKey] ?: 0) > 0

    private fun playerHoldsVideo(videoId: String): Boolean =
        (playbackVideoIdsInUse[videoId] ?: 0) > 0

    /** Another writer — the player or read-ahead — already owns [videoId]'s entry. */
    private fun anotherWriterHas(videoId: String): Boolean =
        playerHoldsVideo(videoId) || (readAheadWrites[videoId] ?: 0) > 0

    /**
     * A reader just let go of [videoId]; if a head round was waiting on that
     * lock, hand the claim back and ask again now.
     *
     * Called from the player's and read-ahead's own close paths, so the retry
     * is driven by the release event rather than by the idle tick. If another
     * holder remains, the claim and the marker stay where they are and the next
     * release event tries again.
     */
    private fun retryAnalysisHeadIfReaderReleased(videoId: String) {
        if (videoId !in analysisHeadsHeldElsewhere) return
        if (anotherWriterHas(videoId)) return
        analysisHeadsHeldElsewhere.remove(videoId)
        analysisHeads.remove(videoId)
        analysisHeadDeferredLogged.remove(videoId)
        requestAnalysisHead(Uri.parse(AutomixAnalysisSource.opusUri(videoId)))
    }

    private fun cacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)
        // A cache write that fails (full disk, evicted mid-write) should drop
        // to streaming, not surface as a playback error.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * As [cacheFactory], minus [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] —
     * for [fetch] alone, never for playback.
     *
     * That flag exists so a playback read whose *write* fails still serves
     * the listener their audio; a read-ahead fetch has no listener to serve,
     * so hiding the same failure just spends their data reading bytes onto
     * the floor. Measured: read-ahead for a track the player had already
     * reached — its cache entry locked by the real reader, exactly the "lost
     * race" [fetchWhole] is meant to give up on cheaply — instead read a
     * full [CHUNK_BYTES] from the network on every one of [MAX_ATTEMPTS]
     * retries, because the flag turned the lock exception into a silent,
     * uncached pass-through rather than the failure [fetch]'s own
     * `runCatching` is written to catch. Nine megabytes on one ordinary,
     * unskipped track change, for a fetch that cached nothing and was always
     * going to. Without the flag, losing the race throws before a byte is
     * read, and every attempt past the first costs nothing.
     */
    private fun readAheadCacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)

    /** Set once the player exists; read-ahead resolves streams the same way. */
    private var upstreamFactory: DataSource.Factory? = null

    fun setUpstream(factory: DataSource.Factory) {
        upstreamFactory = factory
    }

    private var job: Job? = null
    private var pendingQueue: List<String> = emptyList()

    /**
     * The next row's high-quality answer, completed by read-ahead once it has
     * been searched and (when one exists) pinned. The next-song bundle waits
     * on this instead of running the same search a second time.
     */
    private val hqHeadReady = ConcurrentHashMap<String, CompletableDeferred<SourceStream?>>()

    /** Waits for read-ahead's high-quality answer for [videoId], or null when it is not running. */
    suspend fun awaitHqHead(videoId: String, timeoutMs: Long): SourceStream? {
        StreamChoice.of(videoId)?.let { stream ->
            val sourceId = stream.sourceConfigId
            if (sourceId != null && SourceRegistry.config(sourceId)?.kind != SourceKind.YOUTUBE) {
                return stream
            }
        }
        val flight = hqHeadReady[videoId] ?: return null
        return withTimeoutOrNull(timeoutMs) { flight.await() }
    }

    /**
     * Gets the queue ahead of the one playing warmed up, in play order.
     *
     * The first id gets the full treatment: its opening onto disk first, so
     * it can start the moment it's reached, then the rest of it, so that
     * seeking around it is a disk read from the first second it plays. Only
     * that one track — never the one playing, and never bytes for anything
     * further out. Media3 locks a cache entry to a single writer and the
     * player holds that lock for as long as it is streaming the track — a
     * fetch aimed at the same entry is quietly served from the network and
     * written nowhere, spending the listener's data to cache precisely
     * nothing. Caching a track before it is reached gets the same result
     * without the contention. And full-track bytes for tracks that may never
     * be reached would spend real mobile data on nothing.
     *
     * The next [QUEUE_LOOKAHEAD] ids past that one get a lighter treatment:
     * just their stream URL resolved and held in [StreamResolver]'s own
     * cache, not their bytes. That's the gap a fast run of skips actually
     * falls into — the queue moving faster than a single-track read-ahead can
     * follow it — and a resolve is cheap enough that warming several at once
     * costs nothing worth guarding.
     *
     * Called freely; a call naming the same queue as the one already running
     * is left alone, and a different one replaces it outright, since on a run
     * of skips only wherever the listener actually lands is worth chasing.
     */
    fun prefetchQueue(upcoming: List<Upcoming>) {
        val context = appContext ?: return
        val mediaIds = upcoming.map { it.mediaId }
        if (mediaIds == pendingQueue) return
        android.util.Log.d("BCFetchDebug", "prefetchQueue: head ${pendingQueue.firstOrNull()} -> ${mediaIds.firstOrNull()}")
        pendingQueue = mediaIds
        val headId = mediaIds.firstOrNull()
        val headFlight = if (headId != null) CompletableDeferred<SourceStream?>() else null
        if (headId != null && headFlight != null) {
            hqHeadReady[headId] = headFlight
        }
        job?.cancel()
        // Both halves of the read-ahead below go through [StreamResolver],
        // which speaks YouTube ids and nothing else. A source-backed track
        // handed to it resolves to a failure, so filtering here saves a dead
        // round trip per queued track rather than changing any outcome —
        // read-ahead for those is a separate job, and their servers are
        // typically a good deal closer than googlevideo anyway.
        //
        Downloads.ensureOpen()
        val videoIds = mediaIds.filter { SourceRegistry.parseTrackKey(it) == null }
            // A track already on disk needs no reading ahead, and read-ahead
            // speaks only to googlevideo: warming one would spend mobile data
            // fetching a second copy of a file the listener deliberately saved,
            // then cache it under a key playback is never going to ask for —
            // it plays the download instead. See [Song.toMediaItem].
            .filter { it !in Downloads.saved.value }
            // The player may already be buffering this item (it buffers the
            // next playlist entry ahead of reach); there is no entry left for
            // read-ahead to own. Re-checked when the fetch actually runs.
            .filterNot(::playerHoldsVideo)
        // A head that read-ahead will not resolve (a download, a source-backed
        // row) has no high-quality answer coming; release anyone waiting on it.
        if (headId != null && headId !in videoIds) {
            headFlight?.complete(null)
        }
        // With substitution possible, only the *bytes* half drops out. Read-
        // ahead builds its own spec below from an id alone and carries none of
        // the title and artist a substitution is matched on — so it resolves
        // to YouTube and would write Opus bytes into the very entry playback
        // is about to fill from a higher-ranked source, under the same key, at
        // whatever offset each of them happened to reach. Reading ahead for a
        // track and then corrupting it is worse than not reading ahead at all.
        //
        // The URL half is a different matter and was thrown out with it, at
        // real cost. Warming [StreamResolver]'s own cache writes nothing to
        // disk and cannot corrupt anything, and it is the difference between
        // the fallback starting instantly and starting with a full client walk
        // — measured at 7.9s. Since the fallback now races the module lookup
        // rather than waiting behind it, that walk is what a track waits on
        // whenever the modules are slow, and warming it here is what makes the
        // race worth running at all.
        val substitutable = SourceResolver.canSubstituteForYouTube()
        job = videoIds.firstOrNull()?.let { next ->
            val target = upcoming.firstOrNull { it.mediaId == next }?.target
            // A track the listener reverted plays from its own `#original`
            // rendition — see [OriginalVersion] — while read-ahead builds its
            // spec from an id alone and so writes under the plain key. Every
            // byte of that would land in an entry playback is never going to
            // read, and pinning a substitute for it would pin a source it is
            // never going to use. The URL half below is still worth having: a
            // reverted track resolves through YouTube, which is what it warms.
            val pinnedToYouTube = OriginalVersion.isPinned(next)
            val nextFlight = if (next == headId) headFlight else null
            scope.launch {
                // With substitution on, the *bytes* half above used to be
                // switched off outright, and the paragraph explaining why is
                // still correct as far as it goes: read-ahead resolving to
                // YouTube on its own would write Opus into the entry a
                // higher-ranked source is about to fill.
                //
                // What it treated as impossible was knowing the answer in
                // advance. A quick source can be asked *here* — see
                // [SourceResolver.prefetchSubstitute] — and once its stream is
                // recorded in [StreamChoice], the question stops being open:
                // every later resolve for this track, read-ahead's own included,
                // is held to that one stream. Both writers then agree on the
                // file and on the `#alt` key it lands under, which is exactly
                // the condition the byte half was missing.
                //
                // A source that is disabled, doesn't have the track, or fails
                // leaves nothing pinned, and this falls through to the same
                // URL-only warm-up it did before — YouTube resolves the track at
                // playback time as usual.
                val warmed = if (substitutable && target != null && !pinnedToYouTube) {
                    // The sound comparison is the gate: a candidate that is a
                    // cover, a live take or a different edit is refused here,
                    // before it can be pinned for the next play. Because it is
                    // applied inside the race, a refused fast answer does not
                    // stop a slower source's real recording from winning. The
                    // first sound then stays YouTube if nothing passes.
                    val currentTier = StreamChoice.of(next)?.format?.let { HqQuality.tierOf(it) }
                        ?: HqVerificationStore.bestTier(context, next)
                        ?: HqTier.NORMAL
                    val found = try {
                        SourceResolver.prefetchSubstitute(target, remember = false) { _, candidate ->
                            HqAudioVerify.verify(context, next, target, candidate) != null
                        }
                    } catch (cancelled: CancellationException) {
                        // A cancelled read-ahead is not a failed lookup: let
                        // the job unwind instead of carrying on to the URL
                        // warm-up after the queue has already moved on.
                        throw cancelled
                    } catch (failed: Exception) {
                        TrackLog.d(TAG, "warm-up substitute failed for $next: ${failed.message}", about = next)
                        null
                    }
                    found?.also { candidate ->
                        SourceResolver.rememberOriginal(target, candidate)
                        StreamChoice.remember(next, candidate, substituted = true)
                        logHqTier(next, target.title, candidate, currentTier)
                    }
                } else {
                    null
                }
                // The next-song bundle may be suspended on this; a null is a
                // real answer too (nothing on a higher source passed).
                nextFlight?.complete(warmed)
                // Safe to fill for the same reason in both cases: either nothing
                // outranks YouTube and read-ahead is the only writer, or a
                // source has been pinned and every writer now resolves to it.
                //
                // Bytes are on-disk data the listener may never reach, so on a
                // metered connection only the URL half runs: resolving the next
                // item's stream costs a few small round trips and is what keeps
                // the track change instant; prefetching its audio would spend
                // mobile data on a track a skip may skip over. Low power is the
                // same answer for the bytes half — the URL is still warmed so a
                // transition does not wait on a client walk.
                val metered = AppSettings.meteredConnection.value == true
                val cacheBytes = BatteryPolicy.cachePrefetchBytesAllowed(
                    metered = metered,
                    lowPower = AppSettings.lowPower.value,
                ) && (!substitutable || warmed != null) && !pinnedToYouTube
                if (cacheBytes) {
                    launch(TrackLog.about(next)) {
                        delay(PREFETCH_DELAY_MS)
                        // Low power can begin during the grace delay. Re-read
                        // it here, where the bytes would actually be spent.
                        if (!BatteryPolicy.cachePrefetchBytesAllowed(
                                metered = AppSettings.meteredConnection.value == true,
                                lowPower = AppSettings.lowPower.value,
                            )
                        ) {
                            return@launch
                        }
                        // Visible to [requestAnalysisHead] while these bytes are
                        // being written: an analysis round for the same track
                        // would meet this writer's lock and come up empty. Not
                        // set during the grace delay above, when no write exists
                        // to collide with yet.
                        retain(readAheadWrites, next)
                        try {
                            val nextHost =
                                (warmed?.url ?: StreamChoice.of(next)?.url)?.let(HqHostTracker::hostOf)
                            if (nextHost != null && HqHostTracker.isResting(nextHost)) {
                                TrackLog.d(
                                    TAG,
                                    "read-ahead $next: $nextHost is resting; not fetching it",
                                    about = next,
                                )
                                return@launch
                            }
                            // The current track may still be downloading from
                            // this same host. Its bytes win: wait for that
                            // download to finish rather than opening a second
                            // request and tripping the server's rate limit.
                            // Another host is unaffected and proceeds at once.
                            HqHostTracker.awaitIdle(nextHost, exceptTrack = next)
                            fetch(next, 0, PRELOAD_BYTES)
                            fetchWhole(next)
                        } finally {
                            release(readAheadWrites, next)
                            retryAnalysisHeadIfReaderReleased(next)
                        }
                    }
                }
                // Metered, only the immediate next track's URL is worth
                // minting: it is the one a skip most likely lands on, and every
                // resolve past it is a request the listener pays for.
                val urlLookahead = if (metered) 1 else QUEUE_LOOKAHEAD + 1
                launch {
                    delay(PREFETCH_DELAY_MS)
                    for (id in videoIds.take(urlLookahead).let { if (cacheBytes) it.drop(1) else it }) {
                        // A track already pinned to another source has no use
                        // for a YouTube URL: nothing will ask for one, and
                        // minting it spends a client walk to fill a cache entry
                        // that is never read.
                        if (id == next && warmed != null) continue
                        runCatching { StreamResolver.resolve(id) }
                            .onFailure { TrackLog.d(TAG, "queue warm-up skipped $id: ${it.message}", about = id) }
                        delay(QUEUE_RESOLVE_STAGGER_MS)
                    }
                }
            }.also { warm ->
                // Cancellation before the answer lands still releases waiters.
                warm.invokeOnCompletion { nextFlight?.complete(null) }
            }
        }
    }

    /**
     * One line when the copy prepared for the next play is on a different rung
     * from the one already remembered — the user's ladder, not a raw bitrate.
     *
     * The current rung is the stream serving this id right now, or the best
     * verified copy from an earlier play, or YouTube's own normal rung when
     * neither is known. A higher rung is kept through [StreamChoice] and is
     * what the next play opens; nothing is swapped mid-song.
     */
    private fun logHqTier(
        youtubeId: String,
        title: String,
        candidate: SourceStream,
        current: HqTier,
    ) {
        val next = HqQuality.tierOf(candidate.format)
        if (current == next) return
        val source = candidate.sourceConfigId
            ?.let { SourceRegistry.config(it)?.displayName }
            ?: "source"
        TrackLog.i(TAG, "hq-tier: $title ${current.label} -> ${next.label} from $source", youtubeId)
    }

    /**
     * A queued track as read-ahead needs it.
     *
     * [target] is what a cross-source match is made on, and read-ahead cannot
     * reach it any other way: it runs for tracks that are not the current item,
     * so the session's metadata is the wrong track's, and the plain
     * `bitchord://watch?v=…` URI it builds for itself carries an id and nothing
     * else. It rides along from the queue instead — see
     * [PlaybackService.prefetchAround][com.music.bitchord.playback.PlaybackService].
     */
    data class Upcoming(val mediaId: String, val target: TrackMatcher.Target)

    /**
     * Nothing to read ahead for once playback stops. The queue is cleared with
     * the job, so resuming starts the fetch again rather than being mistaken
     * for one already in hand.
     */
    fun cancel() {
        pendingQueue = emptyList()
        job?.cancel()
        job = null
    }

    /**
     * Gets the whole of [videoId] onto disk, a range at a time.
     *
     * Progress is measured rather than assumed: a pass that caches nothing
     * means the entry is held by another writer — the listener has skipped
     * ahead and the player now owns this track — so there is no point hammering
     * it. A few spaced retries cover the hand-over, and then it is left alone.
     */
    private suspend fun fetchWhole(videoId: String) {
        repeat(MAX_ATTEMPTS) {
            // The player may have reached this item while the read-ahead was
            // walking it — ExoPlayer buffers the next playlist entry as soon as
            // its buffer allows. The player's copy is the one that will play,
            // so the rest of this track is its work, not read-ahead's.
            if (playerHoldsVideo(videoId)) {
                TrackLog.d(TAG, "$videoId is being read by the player; read-ahead stops", about = videoId)
                return
            }
            // The race this retry loop exists to cover is the *queue's own*:
            // cancelling [job] tells a blocking network read to stop, but that
            // takes until its next checkpoint, not instantly — so the walk
            // that lost the entry to the player can still be a retry or two
            // into asking for it again by the time [prefetchQueue] has moved
            // this track's job on to a different one. Re-checking here is
            // what makes that overlap cost one interrupted read instead of
            // up to four full ones: once this videoId is no longer the track
            // [pendingQueue] actually wants read ahead, every further attempt
            // is spent on a track something else now owns, and asking again
            // in five seconds would only be wrong for longer.
            if (pendingQueue.firstOrNull() != videoId) {
                TrackLog.d(TAG, "$videoId is no longer the read-ahead target; stopping", about = videoId)
                return
            }
            if (cacheWholeOnce(videoId)) return
            delay(RETRY_DELAY_MS)
        }
        TrackLog.d(TAG, "stopped short of caching $videoId in full", about = videoId)
    }

    /** @return true once every range of [videoId] is on disk. */
    private suspend fun cacheWholeOnce(videoId: String): Boolean {
        val total = runCatching { StreamResolver.contentLength(videoId) }.getOrNull()
            ?: return false

        var position = 0L
        while (position < total) {
            // Checked per chunk, not just once per pass: a track long enough
            // to need several chunks can lose the race partway through one,
            // and a queue change mid-pass is exactly the "the player has it
            // now" case the guard in [fetchWhole] exists for.
            if (pendingQueue.firstOrNull() != videoId) return false
            if (playerHoldsVideo(videoId)) return true
            val length = minOf(CHUNK_BYTES, total - position)
            if (cache.getCachedBytes(videoId, position, length) < length) {
                fetch(videoId, position, length)
                // Written nowhere means the entry is held elsewhere; the rest
                // of this pass would be just as wasted. See [fetch] for why
                // this can be true even though the fetch just above returned
                // without error. When the holder is the player, the bytes will
                // arrive through its own read and there is nothing left for
                // read-ahead to do.
                if (cache.getCachedBytes(videoId, position, length) < length) {
                    return playerHoldsVideo(videoId)
                }
            }
            position += length
        }
        return true
    }

    /**
     * What is actually on disk for [uri]'s rendition, as a log line.
     *
     * Here because "the audition cached it" is an assumption that has already
     * been wrong once, and the only place it can be checked is against the
     * cache itself: a swap that lands on bytes the audition was supposed to
     * have fetched looks, from the player's side, exactly like one that lands
     * on bytes it never reached.
     */
    fun cachedSummary(uri: Uri): String {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val spans = cache.getCachedSpans(key).filter { it.isCached }
        if (spans.isEmpty()) return "$key holds nothing"
        val total = spans.sumOf { it.length }
        val ranges = spans.sortedBy { it.position }
            .joinToString(" ") { "[${it.position},${it.position + it.length})" }
        return "$key holds ${total / 1024}kB in ${spans.size} spans: $ranges"
    }

    /**
     * Pulls [length] bytes at [position] into [uri]'s cache entry, under
     * [uri]'s own key rather than the plain videoId.
     *
     * For the opening of a rendition that is about to be swapped in — see
     * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s audition.
     * A player preparing a progressive source has to parse the container from
     * byte zero before it can seek anywhere, and for a FLAC that is not a few
     * bytes: STREAMINFO, the seek table, the tags and an embedded cover can run
     * to hundreds of kilobytes. Measured here, the audition itself cached only
     * `[0, 8192)` before seeking away to the playing position, so the real
     * player's very first read after the swap — the one nothing can start
     * without — was a cache miss and a round trip to the CDN, in silence.
     *
     * Call it *before* the audition rather than alongside: Media3 locks a cache
     * entry to one writer, and two writers on the same rendition means one of
     * them spends the listener's data caching nothing.
     *
     * [probeLock] false skips the small "is another writer holding this entry"
     * probe [fetch] otherwise makes first. The audition is the one caller that
     * can say so for free: it has just discarded the entry (or knows the bytes
     * under it came from this very URL) and no player is reading the marked
     * rendition yet, so the probe would only add a range request and a round
     * trip ahead of the header the audition cannot start without. See
     * `auditionUpgrade`.
     */
    suspend fun warmRange(uri: Uri, position: Long, length: Long, probeLock: Boolean = true) {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        fetch(key, uri, position, length, probeLock = probeLock)
    }

    /** Waits, without polling, until the player no longer holds [key]'s cache entry. */
    private suspend fun awaitPlaybackRelease(key: String) {
        while (true) {
            val signal = synchronized(keyIdleSignals) {
                if (playerHoldsKey(key)) {
                    keyIdleSignals.getOrPut(key) { CompletableDeferred() }
                } else {
                    null
                }
            } ?: return
            signal.await()
        }
    }

    /**
     * Pulls the whole of an upgraded rendition onto disk, sequentially.
     *
     * The listener's own playback has priority on the entry, so this waits for
     * the player to let go of it rather than fighting for Media3's single
     * writer slot. When the player has closed the entry — after a buffer fill,
     * a pause, or the track ending — the rest is fetched in one pass and stays
     * cached, so a host that starts refusing bytes later cannot take back what
     * has already arrived.
     *
     * Called after a high-quality swap. The host stays marked as downloading
     * until this finishes, which is what keeps the next track's read-ahead off
     * the same server until the current track is complete.
     */
    suspend fun cacheWholeRendition(uri: Uri, title: String?) {
        cacheWholeRenditionDetailed(uri, title)
    }

    private class WholeRenditionResult(
        val complete: Boolean,
        val bytes: Long,
        val failure: String?,
    )

    private suspend fun cacheWholeRenditionDetailed(uri: Uri, title: String?): WholeRenditionResult {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val mediaId = mediaIdIn(uri)
        val job = coroutineContext.job
        mediaId?.let { wholeRenditionJobs[it] = job }
        // The cache index and its metadata are not main-thread data: callers
        // reach this from playback on Main, and `getCachedSpans`, `isComplete`
        // and the metadata write all take the cache's lock. The whole fetch
        // and check therefore run on IO, whatever dispatcher asked.
        return withContext(Dispatchers.IO) {
            // [uri] is the virtual `bitchord://watch?…&q=hifi` item, so its own
            // authority is not the server. The parked stream names the real host.
            val stream = QualityUpgrade.forcedStream(uri)
            val host = stream?.url?.let(HqHostTracker::hostOf)
                ?: HqHostTracker.hostOf(uri.toString())
            try {
                var failure: String? = null
                for (attempt in 0 until 2) {
                    awaitPlaybackRelease(key)
                    val upstream = upstreamFactory
                        ?: return@withContext WholeRenditionResult(false, 0L, "no upstream")
                    val source = readAheadCacheFactory(upstream).createDataSource()
                    val spec = DataSpec.Builder().setUri(uri).build()
                    val writer = CacheWriter(source, spec, /* temporaryBuffer = */ null, /* listener = */ null)
                    failure = runCatching {
                        val handle = coroutineContext.job.invokeOnCompletion { writer.cancel() }
                        try {
                            writer.cache()
                        } finally {
                            handle.dispose()
                        }
                    }.exceptionOrNull()?.let { error ->
                        if (error is CancellationException) throw error
                        error.message
                    }
                    if (failure == null) break
                }
                val bytes = cache.getCachedSpans(key).filter { it.isCached }.sumOf { it.length }
                val complete = failure == null && isComplete(key)
                if (complete && stream != null && HqQuality.isLosslessTier(stream.format)) {
                    runCatching {
                        cache.applyContentMetadataMutations(
                            key,
                            ContentMetadataMutations().set(META_HQ_LOSSLESS, "1"),
                        )
                    }
                }
                val label = title?.takeIf { it.isNotBlank() } ?: mediaId ?: key
                if (complete) {
                    mediaId?.let(renditionKeys::remove)
                    TrackLog.d(TAG, "hq-cache: $label fully cached ${formatBytes(bytes)}", about = mediaId)
                    return@withContext WholeRenditionResult(true, bytes, null)
                }
                val reason = failure ?: "incomplete"
                TrackLog.d(
                    TAG,
                    "hq-cache: $label stopped short at ${formatBytes(bytes)}: $reason",
                    about = mediaId,
                )
                WholeRenditionResult(false, bytes, reason)
            } finally {
                mediaId?.let {
                    wholeRenditionJobs.remove(it, job)
                    HqHostTracker.endDownload(it, host)
                }
            }
        }
    }

    /**
     * Fetches a verified lossless rendition to disk in the background, so the
     * next play of the track opens it instead of the low-quality copy that is
     * playing now.
     *
     * Runs at most once per track and only one at a time. It waits on the
     * rendition's own cache entry, never the entry playback is reading, so it
     * cannot take Media3's single writer slot from the current track. A failure
     * leaves every existing entry alone; only a complete copy is worth acting
     * on, and only then is the lower-quality copy removed.
     */
    fun prefetchVerifiedRendition(uri: Uri, title: String?, stream: SourceStream): Boolean {
        val mediaId = mediaIdIn(uri) ?: return false
        if (!HqQuality.isLosslessTier(stream.format)) return false
        if (!losslessPrefetchAttempted.add(mediaId)) return false
        upgradeKeyDeferred += mediaId
        losslessPrefetchJob?.cancel()
        // Same park as a manual swap: the resolving data source reads the
        // stream from here, so its origin and host are recorded on the entry
        // exactly as they are for the copy a swap fills.
        QualityUpgrade.force(mediaId, stream)
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val host = HqHostTracker.hostOf(stream.url)
        losslessPrefetchFor = mediaId
        losslessPrefetchJob = scope.launch(TrackLog.about(mediaId)) {
            val complete = fetchLosslessRendition(uri, title, stream, mediaId, host)
            if (!complete) return@launch
            pendingLowQualityDrops[mediaId] = key
            if (currentMediaId != null && currentMediaId != mediaId &&
                pendingLowQualityDrops.remove(mediaId, key)
            ) {
                dropLowerQualityCopies(mediaId, keep = key)
            }
        }
        return true
    }

    /**
     * Stops the background lossless fetch if it belongs to [mediaId] — the row
     * that was just replaced as the next track. A fetch for any other track
     * (the one currently playing, most importantly) is left alone.
     */
    fun cancelLosslessPrefetchFor(mediaId: String?) {
        if (mediaId == null || losslessPrefetchFor != mediaId) return
        // The row this fetch belongs to is now playing. A timed-out fetch was
        // deliberately left running for it; cancelling here would abort the
        // playing track's lossless copy when the next row is scheduled.
        if (mediaId == currentMediaId) return
        val active = losslessPrefetchJob?.isActive == true
        if (active) losslessPrefetchJob?.cancel()
        losslessPrefetchJob = null
        losslessPrefetchFor = null
        if (active) losslessPrefetchAttempted.remove(mediaId)
    }

    /**
     * The same hq-prefetch path, awaited, for a swap that is about to happen
     * rather than one being prepared for the next play.
     *
     * The current track keeps playing from its own cache entry while this
     * fills the marked rendition, so the listener hears nothing until the
     * replacement is complete. A background prefetch already running for the
     * track is joined instead of opening a second writer on the same entry.
     *
     * @return true only when every byte of the rendition is on disk.
     */
    suspend fun downloadVerifiedRendition(uri: Uri, title: String?, stream: SourceStream): Boolean {
        val mediaId = mediaIdIn(uri) ?: return false
        if (!HqQuality.isLosslessTier(stream.format)) return false
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        losslessPrefetchJob?.takeIf { it.isActive }?.join()
        // Whether the `#hifi` entry already holds *this* stream's bytes, asked
        // before [force] overwrites the record of what filled it. The key names
        // a slot, not a file: a partial left by another candidate must be
        // thrown away rather than resumed, or the two files are spliced into
        // one entry. The same guard [PlaybackService.swapIn] uses.
        val alreadyFilled = QualityUpgrade.forcedStream(uri)?.url == stream.url
        // Park the stream even when the bytes are already complete: the player
        // is about to open the marked URI and the resolving data source must
        // hand it this exact URL rather than searching for another one.
        upgradeKeyDeferred += mediaId
        QualityUpgrade.force(mediaId, stream)
        return withContext(Dispatchers.IO) {
            if (!alreadyFilled) discardRendition(uri)
            if (isComplete(key)) {
                losslessPrefetchAttempted.add(mediaId)
                true
            } else {
                losslessPrefetchAttempted.add(mediaId)
                val host = HqHostTracker.hostOf(stream.url)
                fetchLosslessRendition(uri, title, stream, mediaId, host)
            }
        }
    }

    /**
     * One fetch of a verified lossless rendition, shared by the background
     * prefetch and the awaited mid-song swap. Logs under the `hq-prefetch`
     * name both callers use, so a failed download is visible either way.
     */
    private suspend fun fetchLosslessRendition(
        uri: Uri,
        title: String?,
        stream: SourceStream,
        mediaId: String,
        host: String?,
    ): Boolean {
        HqHostTracker.beginDownload(mediaId, host)
        var complete = false
        try {
            TrackLog.d(TAG, "hq-prefetch: $mediaId start ${stream.format.summary}", about = mediaId)
            val result = try {
                cacheWholeRenditionDetailed(uri, title)
            } catch (cancelled: CancellationException) {
                TrackLog.d(TAG, "hq-prefetch: $mediaId failed cancelled", about = mediaId)
                throw cancelled
            } catch (failed: Exception) {
                WholeRenditionResult(false, 0L, failed.message ?: failed.javaClass.simpleName)
            }
            if (!result.complete) {
                TrackLog.d(
                    TAG,
                    "hq-prefetch: $mediaId failed ${result.failure ?: "incomplete"}",
                    about = mediaId,
                )
                return false
            }
            complete = true
            TrackLog.d(TAG, "hq-prefetch: $mediaId done ${formatBytes(result.bytes)}", about = mediaId)
            renditionKeys.remove(mediaId)
            return true
        } finally {
            // A fetch cut short — most often by the queue moving on before
            // it finished — leaves the next play of the track free to try
            // again; only a complete copy closes the question.
            if (!complete) losslessPrefetchAttempted.remove(mediaId)
            HqHostTracker.endDownload(mediaId, host)
        }
    }

    /**
     * Drops the copies a completed mid-song swap has left behind.
     *
     * Called after the session player has been handed the marked rendition,
     * so the base entry is no longer being read. [dropLowerQualityCopies]
     * still waits for any straggling reader to let go before removing it.
     */
    fun dropLowerQualityAfterLosslessSwap(mediaId: String, keepKey: String) {
        pendingLowQualityDrops.remove(mediaId)
        scope.launch(TrackLog.about(mediaId)) {
            dropLowerQualityCopies(mediaId, keepKey)
        }
    }

    /**
     * Notes which track the player is on, and lets go of lower-quality copies
     * whose lossless replacement finished while they were still current.
     *
     * Called on every track change rather than from a timer: nothing has to run
     * while a track sits still, and a track that is played again immediately
     * keeps its old entry until the queue actually moves away from it.
     */
    fun onTrackBecameCurrent(mediaId: String?) {
        currentMediaId = mediaId
        upgradeKeyDeferred.removeAll { it != mediaId }
        val ready = pendingLowQualityDrops.entries
            .filter { it.key != mediaId }
            .map { it.key to it.value }
        ready.forEach { (id, keep) ->
            if (pendingLowQualityDrops.remove(id, keep)) {
                scope.launch(TrackLog.about(id)) { dropLowerQualityCopies(id, keep) }
            }
        }
    }

    /**
     * Removes the complete base/`#alt` copies of [mediaId] once [keep] holds
     * the whole lossless recording.
     *
     * Waits for the player to let go of each entry rather than pulling bytes
     * out from under the track that is still sounding. A copy that cannot be
     * removed stays where it is; the next play already prefers [keep].
     */
    private suspend fun dropLowerQualityCopies(mediaId: String, keep: String) {
        renditionKeys.remove(mediaId)
        val keys = renditionKeysFor(mediaId).filter { key ->
            if (key == keep || key.endsWith("#watch")) return@filter false
            val suffix = key.removePrefix("$mediaId#")
            suffix == key || suffix == "alt" || suffix == "alt-noatmos"
        }
        for (key in keys) {
            if (!isComplete(key)) continue
            awaitPlaybackRelease(key)
            runCatching { cache.removeResource(key) }
                .onSuccess {
                    forgetPurgeCandidate(key)
                    TrackLog.d(
                        TAG,
                        "hq-prefetch: $mediaId removed lower-quality copy $key",
                        about = mediaId,
                    )
                }
                .onFailure {
                    TrackLog.d(
                        TAG,
                        "hq-prefetch: $mediaId kept lower-quality copy $key: ${it.message}",
                        about = mediaId,
                    )
                }
        }
        renditionKeys.remove(mediaId)
    }

    /** Stops whole-rendition downloads for tracks other than [mediaId]. */
    fun cancelWholeRenditionsExcept(mediaId: String?) {
        wholeRenditionJobs.entries
            .filter { it.key != mediaId }
            .forEach { entry ->
                entry.value.cancel()
                wholeRenditionJobs.remove(entry.key, entry.value)
            }
    }

    /**
     * Video ids whose analysis copy has already been asked for this session.
     *
     * A set, not a size: the fetch is sized once from the track's real length
     * rather than grown into over several rounds — see [analysisHeadSize].
     * Cleared for a track whose copy turns out to be undecodable, so discarding
     * it leads to a fresh pull rather than to nothing.
     */
    private val analysisHeads = ConcurrentHashMap<String, Boolean>()

    /**
     * Tracks whose head round was deferred because another writer held the
     * entry, so the per-tick retry does not repeat the same log line. Cleared
     * when the round finally starts or the entry is forgotten.
     */
    private val analysisHeadDeferredLogged = ConcurrentHashMap.newKeySet<String>()

    /**
     * Tracks whose head claim is kept while a live reader holds a partial
     * entry. The claim is not dropped (dropping it made the idle tick retry
     * every 250 ms, re-resolving the length and hitting the same cache lock for
     * the minutes the player or read-ahead held the entry). It is released by
     * the reader's own release event, or by the next request once
     * [anotherWriterHas] is false — never by a timer.
     */
    private val analysisHeadsHeldElsewhere = ConcurrentHashMap.newKeySet<String>()

    /** Video ids with a head fetch in the air, so a tick cannot stack another on top. */
    private val analysisHeadsInFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Video ids whose last round ended without adding a byte. The round is
     * sized from the real length and fetches one range of one resolve, so the
     * next attempt would be the same request against the same refusal — see
     * [analysisHeadShouldRetry]. Kept for the session; a discarded rendition
     * or a wipe clears it, and playback growing the copy makes
     * [alignmentHeadComplete] true regardless.
     */
    private val stalledAnalysisHeads = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many bytes each track's last round asked for — the only way for
     * [awaitAnalysisHead] to tell a finished head from one the round left
     * part-written. See [alignmentHeadComplete].
     */
    private val analysisHeadWant = ConcurrentHashMap<String, Long>()

    /**
     * Pulls [uri]'s recording onto disk under the plain YouTube key, so Smart
     * Fade has something to measure; sized by [analysisHeadSize].
     *
     * ## Why the analyzer has to ask for this
     *
     * Nothing else fetches a track's opening early enough. The analyzer can only
     * read bytes some other part of the app happened to write, at the offsets it
     * happened to need, and neither of the two writers produces a head in time:
     *
     *  - Read-ahead's *byte* half is switched off outright whenever source
     *    substitution is on — see [prefetchQueue] — so a track that has never
     *    been played holds nothing at all until it starts playing. Measured, the
     *    next track's analysis then lands eleven to forty seconds late, which is
     *    after the transition it was meant to inform and sometimes after the
     *    track has started.
     *  - A quality upgrade fetches its rendition from the swap point onward, so
     *    the upgraded copy's first seconds are a region nothing downloads on its
     *    own, and the copy it replaced is discarded. A track that plays lossless
     *    from the start is therefore permanently unmeasurable however long it
     *    sits in the cache.
     *
     * ## Why this one is safe when [prefetchQueue]'s bytes are not
     *
     * The hazard read-ahead was disabled over is a key that disagrees with its
     * contents. [keyFactory] decides between `videoId` and `videoId#alt` from
     * the *global* substitution setting rather than from what a request actually
     * resolved to, so a fetch built from an id alone — which always resolves to
     * YouTube, carrying none of the title and artist a substitution is matched
     * on — wrote Opus bytes into the entry playback was filling from another
     * source. That is what produced `No valid varint length mask found` at the
     * seam.
     *
     * Here the key is pinned to the plain videoId rather than derived, so the
     * bytes and the entry they land in are both unambiguously YouTube's, and the
     * `#alt` and `#<rendition>` entries are untouched. The analyzer reaches this
     * copy through [renditionsOf] and cross-checks its container duration before
     * borrowing a beat grid across renditions, so a substituted source that
     * turns out to be a different cut is caught there.
     *
     * ## Why it fetches once, at full size
     *
     * An earlier version started at a megabyte and grew the request on each
     * later call, so that a track whose first attempt did not decode was not
     * stuck forever. That cured the dead end and caused a worse fault: every
     * round resolves the stream again, and the second round skips what is
     * already cached and writes the *remainder of a different rendition* into the
     * same entry. See [analysisHeadSize].
     *
     * The dead end it was solving is now answered from the other side. A copy
     * that cannot be decoded is deleted rather than remembered — see
     * [discardBadRendition] — which both frees the entry and re-arms this, so the
     * retry is a clean pull instead of a larger read of the same bad bytes.
     *
     * A no-op for anything that isn't a YouTube-backed track.
     */
    /**
     * Enough of a YouTube Opus head to decode the opening window
     * [com.music.bitchord.playback.smart.VersionAudioAligner] measures.
     */
    const val MIN_ALIGNMENT_PREFIX_BYTES = 800L * 1024

    /**
     * How many passes [awaitAnalysisHead] may start at a head. One is the
     * normal case; the rest exist for a round that ends part-written, which is
     * the failure worth spending a fetch on again.
     */
    private const val MAX_ALIGNMENT_HEAD_ROUNDS = 3

    /**
     * Gap kept before a retry claims the track again. Claiming and registering
     * the claim are two statements in [requestAnalysisHead]; without a gap, a
     * wait that looked between them could take the claim away and set off a
     * second fetch alongside the one it was trying to join.
     */
    private const val HEAD_ROUND_SETTLE_MS = 500L

    /**
     * Pulls the analysis head for [uri] if needed and waits until enough of
     * the opening is on disk to align two versions of a track.
     *
     * "Enough" is the whole opening a round asked for, or
     * [MIN_ALIGNMENT_PREFIX_BYTES] for a head that was grown elsewhere. What it
     * deliberately does *not* accept is part of a round that gave up halfway —
     * a head that decodes for a handful of seconds caps how far the two cuts
     * may be slid against each other, so a thirty-second difference comes back
     * as an eight-second search reporting whatever noise it found. Handing
     * that over as a measurement is how a swap ends up landing near the wrong
     * second while looking like it worked, so the wait pulls again instead.
     *
     * @return whether the opening is fully on disk, `false` once no further
     *   round can improve on it — the deadline passed,
     *   [MAX_ALIGNMENT_HEAD_ROUNDS] rounds all came up short, or a round added
     *   no bytes (the next identical request would fail the same way).
     */
    suspend fun awaitAnalysisHead(uri: Uri, timeoutMs: Long = 20_000L): Boolean {
        if (openedCache == null) return false
        val videoId = uri.getQueryParameter("v") ?: return false
        if (alignmentHeadComplete(videoId)) return true
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        // A previous round that wrote nothing would otherwise block retries
        // for the rest of the session — but one that ended with no progress is
        // not worth a second identical fetch either; see [stalledAnalysisHeads].
        if (videoId !in analysisHeadsInFlight && videoId !in stalledAnalysisHeads) {
            analysisHeads.remove(videoId)
        }
        // What a further round has to beat to be worth starting: taken before
        // the round is started so its own bytes cannot count as progress.
        var lastHeld = alignmentPrefixBytes(videoId)
        requestAnalysisHead(opus)
        var rounds = 1
        var askedAt = SystemClock.elapsedRealtime()
        val deadline = askedAt + timeoutMs
        // The player is the likeliest writer of this track's entry — it is
        // either playing it or buffering it ahead in its playlist — and
        // read-ahead is the other. While either holds the entry, racing them
        // for the same key can only fail, so wait for the bytes already being
        // brought in and let [alignmentPrefixBytes] decide when there are
        // enough. One round is still allowed after they let go: the entry is
        // free then even if the other writer wrote nothing.
        var waitedForWriter = anotherWriterHas(videoId)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (alignmentHeadComplete(videoId)) return true
            if (anotherWriterHas(videoId)) {
                waitedForWriter = true
                // Bounded by [deadline]; a quarter second is fine-grained enough
                // for an analysis that is not on the audible path, and keeps a
                // whole track's wait from waking the CPU 25 times a second.
                delay(250)
                continue
            }
            if (videoId !in analysisHeadsInFlight) {
                if (rounds >= MAX_ALIGNMENT_HEAD_ROUNDS) return alignmentHeadComplete(videoId)
                val now = SystemClock.elapsedRealtime()
                if (now - askedAt >= HEAD_ROUND_SETTLE_MS) {
                    // A round that got as far as its lock probe — the few
                    // dozen kilobytes written before the stream underneath
                    // them was refused — used to count as bytes on disk and
                    // block every later attempt, so the wait sat out the
                    // whole deadline against a head that was never going to
                    // grow and the switch went ahead unaligned. Below
                    // [MIN_ALIGNMENT_PREFIX_BYTES] there is nothing here a
                    // measurement can act on, so the round may try again, and
                    // each retry runs [clearPartialHead] first — which is what
                    // keeps a part-written entry from splicing two encodings.
                    // [MAX_ALIGNMENT_HEAD_ROUNDS] still bounds how often, and
                    // a round that added nothing is not repeated at all: the
                    // same resolve and range would fail the same way, which is
                    // how one track logged three "came up short: held=0" lines
                    // in two seconds.
                    val held = alignmentPrefixBytes(videoId)
                    if (held < MIN_ALIGNMENT_PREFIX_BYTES) {
                        val progressed = analysisHeadShouldRetry(
                            heldBefore = lastHeld,
                            heldAfter = held,
                            roundsStarted = rounds,
                            maxRounds = MAX_ALIGNMENT_HEAD_ROUNDS,
                            minBytes = MIN_ALIGNMENT_PREFIX_BYTES,
                        )
                        if (!progressed && !waitedForWriter) return false
                        if (rounds >= MAX_ALIGNMENT_HEAD_ROUNDS) return false
                        lastHeld = held
                        analysisHeads.remove(videoId)
                        requestAnalysisHead(opus)
                        waitedForWriter = false
                        rounds++
                    }
                    askedAt = now
                }
            }
            delay(40)
        }
        return alignmentHeadComplete(videoId)
    }

    /**
     * As [awaitAnalysisHead], then on until the round that pulls the rest of
     * the track — [requestAnalysisHead] asks for the whole file when it knows
     * its size — has finished writing, or [timeoutMs] has passed since the
     * call. For a pass that reads a whole song rather than its opening.
     *
     * A track some rendition of which is already complete on disk returns at
     * once and fetches nothing.
     *
     * @return whether at least an opening worth measuring is on disk.
     */
    suspend fun awaitAnalysisTrack(uri: Uri, timeoutMs: Long = 60_000L): Boolean {
        if (openedCache == null) return false
        val videoId = uri.getQueryParameter("v") ?: return false
        val watch = Uri.parse("bitchord://watch?v=$videoId")
        if (renditionsOf(watch).any { it.isComplete && !it.key.endsWith(ALT_SUFFIX) }) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        if (!awaitAnalysisHead(uri, timeoutMs)) return false
        while (videoId in analysisHeadsInFlight && SystemClock.elapsedRealtime() < deadline) {
            delay(100)
        }
        return true
    }

    /**
     * Waits until [videoId]'s opening is at least [minBytes] on disk, waking on
     * the cache's own span-added event for the keys that rendition may use.
     *
     * This is for a row that is not playing yet: nothing is guaranteed to be
     * writing it on a clock, so a poll would wake the CPU for a track nobody is
     * listening to. The listener is registered for the key family read-ahead
     * writes under (the plain id, its `#alt` siblings, and any rendition keys
     * already on disk) and removed when the wait ends. Returns false on the
     * timeout, exactly like the poll it replaces.
     */
    suspend fun awaitRenditionPrefix(videoId: String, minBytes: Long, timeoutMs: Long): Boolean {
        if (openedCache == null) return false
        if (alignmentPrefixBytes(videoId) >= minBytes) {
            refreshRenditionKeys(videoId)
            return true
        }
        val keys = LinkedHashSet<String>().apply {
            add(videoId)
            add("$videoId#alt")
            add("$videoId#alt-noatmos")
            addAll(renditionKeysFor(videoId))
        }
        val reached = CompletableDeferred<Unit>()
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                if (alignmentPrefixBytes(videoId) >= minBytes) reached.complete(Unit)
            }

            override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        }
        keys.forEach { key -> runCatching { cache.addListener(key, listener) } }
        return try {
            val ready = if (alignmentPrefixBytes(videoId) >= minBytes) {
                true
            } else {
                withTimeoutOrNull(timeoutMs) { reached.await() } != null
            }
            // The wait can see the bytes through the direct cache-key reads
            // while [renditionKeysFor]'s five-second memo still lacks the key
            // they landed under. The caller re-checks through that memo, so
            // without dropping it here the loop spins until the memo expires.
            if (ready) refreshRenditionKeys(videoId)
            ready
        } finally {
            keys.forEach { key -> runCatching { cache.removeListener(key, listener) } }
        }
    }

    /** Drops the rendition-key memo so the next read sees a newly written key. */
    private fun refreshRenditionKeys(videoId: String) {
        renditionKeys.remove(videoId)
    }

    /**
     * Whether [videoId]'s opening is as complete as it is ever going to be:
     * past [MIN_ALIGNMENT_PREFIX_BYTES], or as much as the round asked for once
     * that round has stopped writing.
     *
     * [analysisHeadWant] is what was actually asked for, recorded when it was
     * asked, because the wait cannot otherwise tell "finished" from "stopped
     * early" — both look like bytes on disk.
     */
    private fun alignmentHeadComplete(videoId: String): Boolean {
        val held = alignmentPrefixBytes(videoId)
        if (held >= MIN_ALIGNMENT_PREFIX_BYTES) return true
        val want = analysisHeadWant[videoId] ?: return false
        if (videoId in analysisHeadsInFlight) return false
        return held >= want
    }

    /**
     * The figure [VersionAudioAligner] opens by: the largest same-source
     * (non-`#alt`) copy when it is big enough to measure, and the largest copy
     * of any kind otherwise. A substitute can be a different cut of the same
     * song, so it is only worth waiting on when nothing else is on disk — and
     * asking the same question here as there is what makes "ready" mean
     * "openable with exactly these bytes".
     */
    private fun alignmentPrefixBytes(videoId: String): Long {
        val watch = Uri.parse("bitchord://watch?v=$videoId")
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        val renditions = renditionsOf(watch)
        val sameSource = renditions
            .filter { !it.key.endsWith("#alt") }
            .maxOfOrNull { it.cachedPrefix } ?: 0L
        if (sameSource >= MIN_ALIGNMENT_PREFIX_BYTES) return sameSource
        return maxOf(
            cachedPrefixBytes(opus),
            cachedPrefixBytes(watch),
            renditions.maxOfOrNull { it.cachedPrefix } ?: 0L,
        )
    }

    fun requestAnalysisHead(uri: Uri) {
        // Each of these gates used to stop a head dead with nothing in the
        // log to show for it: an unwired cache or upstream looked from the
        // outside exactly like a network that refuses everything.
        if (openedCache == null) {
            TrackLog.w(TAG, "analysis head for $uri not started: cache is not initialised")
            return
        }
        if (upstreamFactory == null) {
            TrackLog.w(TAG, "analysis head for $uri not started: no upstream yet")
            return
        }
        val videoId = uri.getQueryParameter("v") ?: return
        // A player read, or read-ahead's own write, holds the entry and is
        // already bringing the bytes in. Fetching the same range here would
        // fail the write and leave this round "short" over data that went on
        // to be cached. Deferral must not consume the one-round claim: doing
        // that used to leave the track marked "already asked" forever, so once
        // the other writer let go no later tick could start the fetch. A claim
        // kept from a previous HeldElsewhere round is retried only after that
        // writer releases, not on every tick: see
        // [analysisHeadsHeldElsewhere].
        val anotherWriter = anotherWriterHas(videoId)
        val heldForReader = videoId in analysisHeadsHeldElsewhere
        if (!analysisHeadMayClaimOrRetry(
                alreadyRequested = analysisHeads.containsKey(videoId),
                heldForReader = heldForReader,
                anotherWriterHas = anotherWriter,
                inFlight = videoId in analysisHeadsInFlight,
            )
        ) {
            if (anotherWriter && analysisHeadDeferredLogged.add(videoId)) {
                TrackLog.d(TAG, "analysis head for $videoId deferred: another writer has it", videoId)
            }
            return
        }
        // One round per track per session, and it is sized correctly up front
        // rather than grown into. See [analysisHeadSize] for why growing it was
        // the wrong shape. A held claim is already in the map, so reuse it
        // instead of treating its presence as "already done".
        if (heldForReader) {
            analysisHeadsHeldElsewhere.remove(videoId)
            // A concurrent release hook may have taken the claim just before
            // this request; put it back defensively so the round still has its
            // one claim behind it.
            analysisHeads.putIfAbsent(videoId, true)
        } else if (analysisHeads.putIfAbsent(videoId, true) != null) {
            return
        }
        analysisHeadDeferredLogged.remove(videoId)
        // Checked before the round is claimed, so the playback thread pays a set
        // lookup per tick rather than queueing coroutines four times a second on
        // top of a fetch that is still running.
        if (!analysisHeadsInFlight.add(videoId)) {
            // A round slipped in between the claim check and here. Never leave
            // the claim without a round behind it: that is a permanent "asked"
            // mark that no later tick can retry.
            analysisHeads.remove(videoId)
            return
        }
        scope.launch {
            try {
                val total = runCatching { StreamResolver.contentLength(videoId) }
                    .onFailure {
                        TrackLog.w(TAG, "contentLength failed for $videoId: ${it.message}", videoId)
                    }
                    .getOrNull() ?: 0L
                val want = analysisHeadSize(total)
                analysisHeadWant[videoId] = want
                TrackLog.w(
                    TAG,
                    "analysis head round for $videoId: total=$total want=$want " +
                        "held=${alignmentPrefixBytes(videoId)}",
                    videoId,
                )
                // What this round has to beat; a round that ends no higher is
                // the same request against the same refusal and is not worth
                // starting again in this session.
                val before = alignmentPrefixBytes(videoId)
                when (clearPartialHead(videoId, want)) {
                    AnalysisHeadClear.Proceed -> Unit
                    AnalysisHeadClear.AlreadyComplete -> return@launch
                    AnalysisHeadClear.HeldElsewhere -> {
                        if (anotherWriterHas(videoId)) {
                            // Keep the one-round claim and wait for the
                            // reader's release event. Releasing it here made
                            // every 250 ms idle tick launch a coroutine,
                            // re-resolve contentLength and retry the same
                            // locked removeResource for the minutes the
                            // player/read-ahead held the entry.
                            analysisHeadsHeldElsewhere.add(videoId)
                            TrackLog.w(
                                TAG,
                                "analysis head round for $videoId deferred: partial is held by a reader",
                                videoId,
                            )
                        } else {
                            // The lock belongs to a writer this cache does not
                            // track (an in-flight fetch). Release the claim so
                            // the next request can retry; it is short-lived.
                            analysisHeads.remove(videoId)
                            TrackLog.w(
                                TAG,
                                "analysis head round for $videoId skipped: partial is held elsewhere",
                                videoId,
                            )
                        }
                        return@launch
                    }
                }
                fetch(
                    cacheKey = videoId,
                    // Do not inherit a JioSaavn/lossless StreamChoice from
                    // playback. The base key is reserved for the lightweight
                    // YouTube Opus copy used by Automix analysis.
                    uri = Uri.parse(AutomixAnalysisSource.opusUri(videoId)),
                    position = 0,
                    length = want,
                    pinKey = true,
                )
                if (alignmentPrefixBytes(videoId) < MIN_ALIGNMENT_PREFIX_BYTES) {
                    // The plain Opus copy would not come: on the networks where
                    // its googlevideo resolve fails it never does, while
                    // playback of the very same track is unbothered because it
                    // reads whichever source the substitution chose — which is
                    // also exactly what the direction that *works* measures
                    // against. So when the pinned copy comes up short, pull
                    // that copy instead: alignment opens the fullest copy on
                    // disk anyway, and a failed head here otherwise costs the
                    // whole switch its smart align while the swap goes ahead
                    // regardless.
                    // Through [fetch], so an entry the player is streaming into
                    // costs a probe rather than the whole track read onto the floor.
                    val watch = Uri.parse("bitchord://watch?v=$videoId")
                    fetch(keyFactory.buildCacheKey(DataSpec(watch)), watch, 0, want)
                }
                if (total > 0) recordContentLength(videoId, total)
                // The keys this video has on disk were memoized before the round
                // wrote its own (the plain id, pinned): with substitution on the
                // playing copy is "#alt", so the stale list held only that one and
                // the head read as 0 bytes — "came up short" over a complete download,
                // and no measurement ever started. Asked afresh now.
                renditionKeys.remove(videoId)
                val held = alignmentPrefixBytes(videoId)
                if (held < MIN_ALIGNMENT_PREFIX_BYTES) {
                    if (held <= before) stalledAnalysisHeads.add(videoId) else stalledAnalysisHeads.remove(videoId)
                    // The one line that says *this* fetch failed, as opposed to
                    // the read-ahead chatter around it: every cause below
                    // already logs its own message at debug, but nothing said
                    // where a round ended or what it had to work with.
                    TrackLog.w(
                        TAG,
                        "analysis head for $videoId came up short: " +
                            "held=$held want=$want total=$total " +
                            "(cause in the read-ahead lines around this one)",
                        videoId,
                    )
                } else {
                    stalledAnalysisHeads.remove(videoId)
                }
            } finally {
                analysisHeadsInFlight.remove(videoId)
            }
        }
    }

    /**
     * How much of a track to pull for the analyzer, decided once from its real
     * size.
     *
     * This used to start at a megabyte and grow by [HEAD_ESCALATION] on each
     * later call, which was the wrong shape for a reason this file documents
     * elsewhere — see [discardRendition]. Each round resolves the stream again,
     * and YouTube does not promise the same rendition twice. Round one wrote a
     * megabyte of one encoding; round two skipped what was already cached and
     * wrote the *remainder of a different one* into the same entry. The result is
     * a contiguous, correctly sized, complete-looking file that decodes for a few
     * seconds and then stops at the seam — which is exactly what two tracks were
     * observed doing, one decoding 14.4s of a 153s container and another 35.5s of
     * 211.5s, both while the cache called them complete.
     *
     * One resolve, one range, one encoding. [StreamResolver] has the length
     * already, from resolving the stream, so asking first costs nothing.
     */
    private fun analysisHeadSize(total: Long): Long =
        if (total > 0) minOf(total, MAX_ANALYSIS_TRACK_BYTES) else MAX_ANALYSIS_HEAD_BYTES

    /**
     * Makes sure [videoId]'s entry is either empty or already covers [want]
     * before anything writes to it.
     *
     * A part-filled entry is the seam hazard in [analysisHeadSize] waiting to
     * happen: whatever is there came from an earlier resolve — a fetch this
     * session cancelled, or a round from a build that still escalated — and
     * filling the gap would splice a second encoding onto it. Cheaper to throw
     * the prefix away and pull one clean copy.
     *
     * @return [AnalysisHeadClear.Proceed] when the caller may fetch, or why it
     *   must not: the entry is already complete enough, or it is held by a live
     *   reader and a partial write would create the very seam this avoids.
     */
    private fun clearPartialHead(videoId: String, want: Long): AnalysisHeadClear {
        val held = cache.getCachedBytes(videoId, 0, want)
        if (held <= 0L) return AnalysisHeadClear.Proceed
        if (held >= want) return AnalysisHeadClear.AlreadyComplete
        val removed = runCatching { cache.removeResource(videoId) }
            .onSuccess {
                forgetPurgeCandidate(videoId)
                TrackLog.d(TAG, "cleared partial analysis head for $videoId ($held of $want)", about = videoId)
            }
            .onFailure {
                TrackLog.d(TAG, "partial head for $videoId is in use: ${it.message}", about = videoId)
            }
            .isSuccess
        return analysisHeadClearOutcome(held = held, want = want, removalSucceeded = removed)
    }

    /**
     * Writes [videoId]'s real size into its cache metadata, which a bounded
     * fetch does not.
     *
     * Media3 records a resource's length from a response that describes the
     * whole resource. [requestAnalysisHead] asks for a megabyte, so the response
     * describes a megabyte, and the entry is left with no length at all — which
     * is not a cosmetic gap. Everything downstream divides by it: the analyzer
     * ranks renditions by how much *audio* each holds, so a length of zero makes
     * a freshly fetched head score zero seconds and lose to any sibling holding
     * a sliver of an unusable one. That is how a track whose opening had just
     * been downloaded went on reading as having nothing.
     *
     * Cheap because [StreamResolver] has the number already: it was read to
     * resolve the stream this fetch just pulled from.
     */
    private fun recordContentLength(videoId: String, total: Long) {
        if (ContentMetadata.getContentLength(cache.getContentMetadata(videoId)) > 0) return
        if (total <= 0) return
        runCatching {
            cache.applyContentMetadataMutations(
                videoId,
                ContentMetadataMutations().apply { ContentMetadataMutations.setContentLength(this, total) },
            )
        }.onFailure { TrackLog.d(TAG, "could not record length for $videoId: ${it.message}", about = videoId) }
    }

    /**
     * True once every byte of [uri]'s rendition is on disk.
     *
     * Automix's analyzer needs this before it can safely decode a track: a
     * partially fetched file may not even have a parsable container, and
     * analysing the head of a track whose tail hasn't arrived would produce a
     * grid for audio the listener will never reach through that transition.
     *
     * Reads the content length Media3 already recorded against this cache key
     * (from the upstream response, the first time anything read this rendition)
     * rather than re-deriving it per source type — unlike [cacheWholeOnce],
     * which only knows how to ask [StreamResolver] for a YouTube videoId's
     * length, this works for anything that has ever been opened through
     * [cacheFactory], YouTube or not.
     */
    fun isFullyCached(uri: Uri): Boolean {
        val contentLength = contentLengthOf(uri)
        if (contentLength <= 0) return false
        // [Cache.getCachedLength], not [Cache.getCachedBytes]. The latter counts
        // every cached byte in the span *however it is scattered*, so a
        // rendition with holes in it — which is the normal result of seeking
        // around a track while read-ahead fills the rest in behind you — reports
        // the same total as a complete one. The decoder does not skip holes: it
        // stops at the first, and the analyzer then reads the entire missing
        // remainder as trailing silence and places the mix-out anchor there.
        // That is how a four-minute track came to be analysed as ending at
        // 61 seconds and transitioned out of at 56. Contiguous-from-zero is what
        // "fully cached" was always meant to mean.
        return cachedPrefixBytes(uri) >= contentLength
    }

    /**
     * The full size of [uri]'s rendition in bytes, or 0 when it isn't known yet.
     *
     * Read from the content metadata Media3 recorded from the upstream response
     * the first time anything opened this rendition, so it is available well
     * before the bytes are.
     */
    fun contentLengthOf(uri: Uri): Long {
        if (openedCache == null) return 0L
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        return ContentMetadata.getContentLength(cache.getContentMetadata(key)).coerceAtLeast(0L)
    }

    /**
     * How many bytes of [uri]'s rendition are on disk *contiguously from the
     * start*, which is the only measure a head-only decode can act on: a
     * rendition holding its last megabyte and nothing else has plenty of cached
     * bytes and no parsable beginning.
     *
     * Zero when the rendition's length isn't known yet, when nothing is cached,
     * or when the cached region doesn't start at byte 0.
     */
    fun cachedPrefixBytes(uri: Uri): Long {
        if (openedCache == null) return 0L
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        // Probed over a fixed span rather than the content length. The length is
        // only recorded once something has *opened* the rendition, and the whole
        // point of this measurement is a track nothing has opened yet — read-
        // ahead has written its opening bytes and nothing else has touched it.
        // Requiring the length here made this return zero for exactly the
        // tracks it exists to describe.
        val probe = contentLengthOf(uri).takeIf { it > 0 } ?: HEAD_PROBE_BYTES
        // Negative means "this many bytes of hole", i.e. position 0 isn't cached at all.
        return cache.getCachedLength(key, 0, probe).coerceAtLeast(0L)
    }

    /**
     * One rendition of a recording, as Automix's analyzer sees it.
     *
     * [key] is a cache key, not a URI: the point of this type is to name a
     * rendition the *player* is not using, which no URI in hand refers to.
     */
    data class Rendition(val key: String, val contentLength: Long, val cachedPrefix: Long) {
        val isComplete: Boolean get() = contentLength > 0 && cachedPrefix >= contentLength
    }

    /**
     * Every rendition of [uri]'s recording that is on disk, cheapest first.
     *
     * One videoId owns up to three cache entries — the Opus stream YouTube
     * serves, a substituted source's copy (`#alt`), and a quality upgrade
     * (`#<rendition>`); see [keyFactory]. They hold the same *music*, so an
     * analysis of any of them describes all of them, and analysing the smallest
     * one costs a fraction of what the largest does. This is what lets a track
     * be analysed off a 3 MB Opus stream that finished downloading a minute ago
     * instead of waiting on the 40 MB FLAC that replaced it.
     *
     * Ordered by size because decode time tracks bytes, and because the
     * lightest complete rendition is the one that was available earliest.
     *
     * Callers must still check the durations agree before treating two
     * renditions as interchangeable — see [TrackAnalyzer]. A `#alt` entry comes
     * from a different source and can be a different cut of the same song,
     * where a shared beat grid would put every anchor seconds off.
     */
    fun renditionsOf(uri: Uri): List<Rendition> {
        if (openedCache == null) return emptyList()
        val videoId = uri.getQueryParameter("v")
            ?: return listOfNotNull(renditionFor(keyFactory.buildCacheKey(DataSpec(uri))))
        return renditionKeysFor(videoId)
            .mapNotNull(::renditionFor)
            .sortedBy { it.contentLength }
    }

    /**
     * Which cache keys belong to [videoId], memoized.
     *
     * [SimpleCache.getKeys] copies the entire key set on every call, and this
     * runs on the main thread twice per crossfade tick — four times a second,
     * against a cache holding every track ever played. Which *renditions* exist
     * changes only when a new one starts downloading, so it is safe to hold for
     * a few seconds; how much of each is on disk is not memoized and is still
     * read fresh every time.
     */
    private fun renditionKeysFor(videoId: String): List<String> {
        val now = SystemClock.elapsedRealtime()
        renditionKeys[videoId]?.let { (at, keys) ->
            if (now - at < RENDITION_KEYS_TTL_MS) return keys
        }
        val keys = cache.keys.filter { it == videoId || it.startsWith("$videoId#") }
        // An empty answer is never remembered. The head round asks this
        // question a moment before it starts writing — to log `held=` — and
        // a five-second "no keys" memo taken then hides the very bytes it is
        // about to write from [renditionsOf], while [alignmentPrefixBytes]'s
        // direct reads see them the moment they land and open the gate. The
        // aligner, which goes only through this list, would then measure an
        // empty disk for the rest of the memo's life: the switch fails, the
        // retap five seconds later succeeds, and the wait in between is this
        // entry. Cold is the one state where the list is guaranteed to change
        // next, so cold is the one state that cannot be cached.
        if (keys.isNotEmpty()) {
            renditionKeys[videoId] = now to keys
            // A rendition key can appear after the watch started (a quality
            // upgrade is the common one); watch it as soon as the refreshed
            // list names it, so its completion is not missed.
            keys.forEach(::watchCompletionKey)
        } else {
            renditionKeys.remove(videoId)
        }
        return keys
    }

    private val renditionKeys = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    /** The cache key [uri] itself resolves to right now — the rendition the player is using. */
    fun cacheKeyOf(uri: Uri): String = keyFactory.buildCacheKey(DataSpec(uri))

    private fun renditionFor(key: String): Rendition? {
        val contentLength =
            ContentMetadata.getContentLength(cache.getContentMetadata(key)).coerceAtLeast(0L)
        val probe = contentLength.takeIf { it > 0 } ?: HEAD_PROBE_BYTES
        val prefix = cache.getCachedLength(key, 0, probe).coerceAtLeast(0L)
        return if (prefix <= 0L) null else Rendition(key, contentLength, prefix)
    }

    /**
     * A reader over one named [Rendition], whatever the player is currently
     * using.
     *
     * The key is pinned rather than derived, because [keyFactory] resolves a
     * YouTube URI to whichever rendition is live *now* — which is precisely the
     * heavy one this exists to avoid reading. The URI is still passed along for
     * [CacheDataSource] to open against; only the key decides which bytes come
     * back.
     */
    fun renditionDataSource(uri: Uri, rendition: Rendition): MediaDataSource =
        CacheMediaDataSource(
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(NoUpstream)
                .setCacheKeyFactory { rendition.key }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource(),
            uri,
        )

    /**
     * Never fetches. For a fully cached rendition nothing should reach this; for
     * a partially cached one, throwing is how a read past the cached prefix
     * becomes an end-of-stream instead of a download.
     */
    private object NoUpstream : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long =
                throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
            override fun getUri(): Uri? = null
            override fun close() {}
        }
    }

    /**
     * Adapts a Media3 [DataSource] (reading only from [cache]) to the
     * [MediaDataSource] interface [android.media.MediaExtractor] wants.
     *
     * Keeps the underlying source open across consecutive sequential reads —
     * the pattern MediaExtractor actually uses — and only reopens at a new
     * position when the read pattern jumps, e.g. a seek.
     */
    private class CacheMediaDataSource(
        private val dataSource: DataSource,
        private val uri: Uri,
    ) : MediaDataSource() {
        private var isOpen = false
        private var openPosition = -1L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (!isOpen || position != openPosition) {
                closeUpstream()
                val available = try {
                    dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                } catch (error: IOException) {
                    return -1
                }
                isOpen = true
                openPosition = position
                if (available == 0L) return -1
            }
            val count = try {
                dataSource.read(buffer, offset, size)
            } catch (error: IOException) {
                closeUpstream()
                return -1
            }
            if (count == C.RESULT_END_OF_INPUT) {
                closeUpstream()
                return -1
            }
            openPosition += count
            return count
        }

        override fun getSize(): Long {
            closeUpstream()
            return try {
                dataSource.open(DataSpec(uri))
            } catch (error: IOException) {
                -1L
            } finally {
                closeUpstream()
            }
        }

        override fun close() {
            closeUpstream()
        }

        private fun closeUpstream() {
            if (isOpen) {
                runCatching { dataSource.close() }
                isOpen = false
            }
        }
    }

    /**
     * Pulls [length] bytes of [videoId] from [position] into the cache.
     * [CacheWriter] fetches only the gaps, so a range already partly on disk —
     * from a track played earlier, or skipped back to — costs only the rest.
     */
    private suspend fun fetch(videoId: String, position: Long, length: Long) =
        fetch(videoId, Uri.parse("bitchord://watch?v=$videoId"), position, length)

    /**
     * [pinKey] forces the write to land under [cacheKey] instead of wherever
     * [keyFactory] would put it.
     *
     * Worth spelling out because the two are not otherwise the same thing:
     * [cacheKey] is only consulted for the "is this already here" check below,
     * while the entry actually written is chosen by [keyFactory] from the spec —
     * and [keyFactory] answers partly from the global substitution setting, not
     * from what this request resolves to. A caller that knows exactly what it is
     * fetching, as [requestAnalysisHead] does, is better off saying so than
     * letting a global decide on its behalf.
     */
    private suspend fun fetch(
        cacheKey: String,
        uri: Uri,
        position: Long,
        length: Long,
        pinKey: Boolean = false,
        probeLock: Boolean = true,
    ) {
        if (upstreamFactory == null) return

        val about = mediaIdIn(uri)
        val label = about ?: cacheKey
        // The entry this fetch would actually write, which is not always the
        // [cacheKey] used for the "already here" check: without [pinKey],
        // [keyFactory] chooses it from the URI and settings. If the player is
        // reading that entry, there is nothing to win — Media3 grants one
        // writer, and the read-ahead that tried anyway was observed fetching
        // 0.1 MB at a time onto the floor 226 times in one run.
        val writeKey = if (pinKey) cacheKey else keyFactory.buildCacheKey(DataSpec(uri))
        if (playerHoldsKey(writeKey)) {
            TrackLog.d(TAG, "read-ahead $label: skipped (player is reading it)", about = about)
            return
        }
        if (cache.getCachedBytes(cacheKey, position, length) >= length) return

        val fetchStart = SystemClock.elapsedRealtime()
        var probes = 0
        var bytes = 0L
        var failure: String? = null

        suspend fun pullTracked(at: Long, size: Long): Pair<Long, String?> {
            probes++
            val outcome = pull(cacheKey, uri, at, size, pinKey)
            bytes += outcome.first
            failure = outcome.second
            return outcome
        }

        // A cheap first knock rather than the whole range on the door.
        // Losing this entry to another writer isn't something
        // [CacheDataSource] surfaces as a failure — it quietly falls through
        // to the network and hands the bytes to nobody, which looks exactly
        // like a real fetch until the write is checked afterwards, because
        // that check has always been the only way to tell "nobody's home"
        // from "got it". Measured without this: a read-ahead fetch that had
        // lost that race read a full [CHUNK_BYTES] from the network, found
        // nothing had landed, and paid that again on every one of
        // [MAX_ATTEMPTS] retries — nine megabytes for a track that was never
        // going to cache, because whoever held the entry held it the whole
        // time. A small probe reaches the same verdict for a fraction of
        // the cost, and only a probe that actually lands is worth following
        // with the rest of the range.
        //
        // The probe goes where the first missing byte is, not at [position]: a
        // range whose start is already on disk — written by the player, which
        // holds the entry while it streams — passed a probe at [position]
        // without writing anything, and the whole track was then read onto the
        // floor. Watched on a playing video: three head rounds, 5.3 MB each,
        // nothing kept.
        if (probeLock && length > LOCK_PROBE_BYTES) {
            val ready = cache.getCachedLength(cacheKey, position, length).coerceAtLeast(0L)
            val hole = position + ready
            val probe = minOf(LOCK_PROBE_BYTES, position + length - hole)
            if (probe > 0) {
                pullTracked(hole, probe)
                if (cache.getCachedBytes(cacheKey, hole, probe) < probe) {
                    TrackLog.d(
                        TAG,
                        "read-ahead $label: $probes probes, ${formatBytes(bytes)} not kept (entry in use)",
                        about = about,
                    )
                    return
                }
            }
        }
        pullTracked(position, length)
        // One summary for the whole operation, where there used to be a line
        // before and after every probe. The duration is what makes a
        // read-ahead overlapping a resolve visible; the byte count is what
        // makes it expensive.
        val elapsed = SystemClock.elapsedRealtime() - fetchStart
        TrackLog.d(
            TAG,
            "read-ahead $label: $probes probes, ${formatBytes(bytes)} in ${elapsed}ms" +
                (failure?.let { " (failed: ${it.substringBefore("?").take(60)})" } ?: ""),
            about = about,
        )
        val transferHost = mediaIdIn(uri)
            ?.let(StreamChoice::of)
            ?.url
            ?.let(HqHostTracker::hostOf)
            ?: HqHostTracker.hostOf(uri.toString())
        HqHostTracker.noteTransfer(
            transferHost,
            bytes,
            elapsed,
            ok = failure == null,
        )
    }

    /** The actual network pull behind [fetch], unconditional and unchecked. */
    private suspend fun pull(
        cacheKey: String,
        uri: Uri,
        position: Long,
        length: Long,
        pinKey: Boolean,
    ): Pair<Long, String?> {
        val upstream = upstreamFactory ?: return 0L to null

        val source = readAheadCacheFactory(upstream)
            .apply { if (pinKey) setCacheKeyFactory { cacheKey } }
            .createDataSource()
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(position)
            .setLength(length)
            .build()
        val writer = CacheWriter(source, spec, /* temporaryBuffer = */ null, /* listener = */ null)

        // Expected failures here are a skip or losing the entry to the player;
        // neither is worth failing playback over, and both are reported by the
        // caller's one summary line — see [fetch].
        val failure = runCatching {
            withContext(Dispatchers.IO) {
                // CacheWriter blocks in a read loop and checks this flag between
                // reads; cancelling the coroutine alone would leave it running.
                val handle = coroutineContext.job.invokeOnCompletion { writer.cancel() }
                try {
                    writer.cache()
                } finally {
                    handle.dispose()
                }
            }
        }.exceptionOrNull()
        return length to failure?.message
    }
}

/** How many leading bytes [cachedContainerOf] needs to tell the containers apart. */
internal const val CONTAINER_MAGIC_BYTES = 12

/**
 * The file extension and MIME type a download of a cached file is filed under,
 * from the file's first [CONTAINER_MAGIC_BYTES] — or null for anything that is
 * not WebM, MP4 or FLAC, which a download made from the cache refuses rather
 * than misnames. See [AudioCache.completeCopyOf].
 */
internal fun cachedContainerOf(magic: ByteArray): Pair<String, String>? {
    if (magic.size < CONTAINER_MAGIC_BYTES) return null
    fun ascii(from: Int, to: Int) = String(magic, from, to - from, Charsets.US_ASCII)
    return when {
        magic[0] == 0x1A.toByte() && magic[1] == 0x45.toByte() &&
            magic[2] == 0xDF.toByte() && magic[3] == 0xA3.toByte() -> "webm" to "audio/webm"
        ascii(4, 8) == "ftyp" -> "m4a" to "audio/mp4"
        ascii(0, 4) == "fLaC" -> "flac" to "audio/flac"
        else -> null
    }
}

/**
 * Offline decision from the capabilities of the active network.
 *
 * [hasCapabilities] is false when the platform has no active network at all.
 * That is offline, not unknown: reporting it as online skipped cached playback
 * and sent lookups into timeouts.
 */
internal fun offlineFromCapabilities(
    hasCapabilities: Boolean,
    hasInternet: Boolean,
    captivePortal: Boolean,
): Boolean = !hasCapabilities || !hasInternet || captivePortal
