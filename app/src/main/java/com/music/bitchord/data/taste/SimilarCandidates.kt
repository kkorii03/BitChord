package com.music.bitchord.data.taste

import android.content.Context
import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Similar-track candidates from ListenBrainz's open collaborative-filtering data.
 *
 * The seed is the track's *song*, not the upload: a cover is resolved through
 * [CoverTitles] so the station asks "what is like the song that was covered",
 * not "what is like this cover video". That resolution is a fuzzy MusicBrainz
 * recording search; the result's MBID is then given to the similar-recordings
 * data set, whose entries already carry title and artist, so no third metadata
 * call is needed. Similar artists are deliberately not used: their top
 * recordings would need an extra MusicBrainz browse call, while the
 * similar-recordings rows are already named.
 *
 * Everything is optional. No account or token is involved, [candidatesFor]
 * returns an empty list on any failure, and the whole feature is skipped in low
 * power and offline. Results are cached per seed for seven days in memory and
 * in one small JSON file under `filesDir`, so the common case — the same track
 * topping the queue up again — costs no network at all.
 *
 * AutoPlay tops up on almost every track change, so the data set is fetched at
 * most once per [SIMILAR_MIN_INTERVAL_MS] and only when the seed's original
 * artist differs from the last fetch's. Otherwise the last answer is reused,
 * minus the rows the queue already holds. One fetch resolves at most
 * [MAX_SEARCHES_PER_FETCH] rows; the rest are kept for a later top-up and only
 * resolved when fewer than [MIN_LAZY_SURVIVORS] of the first batch survived.
 */
object SimilarCandidates {

    /** At most this many similar recordings are offered per seed. */
    const val MAX_SIMILAR = 8

    /** One SIMILAR fetch resolves at most this many YouTube searches. */
    const val MAX_SEARCHES_PER_FETCH = 4

    /** A later top-up starts resolving the kept tail only when this few survived. */
    const val MIN_LAZY_SURVIVORS = 2

    /** SIMILAR is fetched at most once per this window, whichever artist is playing. */
    const val SIMILAR_MIN_INTERVAL_MS = 15L * 60 * 1000

    /** How long a seed's resolved YouTube rows stay useful. */
    const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** Per-call ceiling. A slow data set must not hold up the queue. */
    const val CALL_TIMEOUT_MS = 4_000L

    /**
     * Total time one top-up may spend resolving similar rows. At most eight
     * searches fit inside it on a healthy connection; on a slow one the rest
     * are left for the next top-up rather than delaying playback.
     */
    private const val TOTAL_BUDGET_MS = 14_000L

    /** ListenBrainz asks clients for no more than one call per second. */
    private const val MIN_CALL_INTERVAL_MS = 1_100L

    private const val MAX_CACHE_ENTRIES = 40

    /** 2: cached rows now keep the music-video type; older files are ignored. */
    private const val CACHE_VERSION = 2
    private const val TAG = "BitChordSimilar"
    private const val CACHE_FILE = "taste/similar.json"

    private const val SEARCH_URL = "https://labs.api.listenbrainz.org/recording-search/json"
    private const val SIMILAR_URL = "https://labs.api.listenbrainz.org/similar-recordings/json"

    /** The broad, token-free similarity run used by ListenBrainz's own explorer. */
    private const val ALGORITHM =
        "session_based_days_7500_session_300_contribution_5_threshold_15_limit_50_skip_30"

    private val USER_AGENT =
        "BitChord/${BuildConfig.VERSION_NAME.ifBlank { "1.0" }} ( https://github.com/kkorii03/BitChord )"

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private var file: File? = null

    private val lock = Any()
    private val memory = LinkedHashMap<String, SimilarCacheEntry>()
    private val loadLock = Mutex()
    private val saveLock = Mutex()

    @Volatile
    private var loaded = false

    /**
     * The last SIMILAR answer and what came of it, kept for reuse inside the
     * throttle window. All fields are guarded by [lock].
     */
    private var lastFetchAtMs = 0L
    private var lastArtistKey = ""
    private var lastSimilar: List<SimilarRecording> = emptyList()
    private var lastResolved: List<Song> = emptyList()
    private var pendingTail: List<SimilarRecording> = emptyList()

    // ListenBrainz's window, learned from response headers. Guarded by [lock].
    private var notBeforeMs = 0L
    private var lastCallStartedMs = 0L

    fun init(context: Context) {
        file = File(context.filesDir, CACHE_FILE)
    }

    /** The song and credited artist a similar lookup should start from. */
    data class Seed(val title: String, val artist: String) {
        /** What to send to the fuzzy recording search. */
        val query: String get() = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** One row of the ListenBrainz similar-recordings answer. */
    data class SimilarRecording(
        val mbid: String,
        val title: String,
        val artist: String,
    )

    /**
     * Resolves similar songs to YouTube Music rows, never more than
     * [MAX_SEARCHES_PER_FETCH] searches per data-set fetch.
     *
     * Returns the per-seed cache when it is fresh; otherwise, inside the
     * throttle window or for the artist that was just fetched, reuses the last
     * data-set answer (resolving a kept tail only when too few rows survived);
     * otherwise asks ListenBrainz and then the anonymous YouTube Music search.
     * Any failure is an empty answer: a station can run on its radio alone, and
     * a failure must never stop the top-up. A failed ListenBrainz call is not
     * cached, so the next top-up retries. A successful seed's answer is cached
     * even when empty, unless a YouTube search failed while resolving it: that
     * is a transient fault, not the seed having no similar songs.
     */
    suspend fun candidatesFor(song: Song, nowMs: Long = System.currentTimeMillis()): List<Song> {
        // Battery first: nothing here runs in low power or without a network.
        if (AppSettings.lowPower.value) return emptyList()
        if (AppSettings.meteredConnection.value == null) return emptyList()
        if (rateLimited(nowMs)) return emptyList()

        val seed = seedOf(song) ?: return emptyList()
        val key = cacheKey(seed.title, seed.artist)
        cached(key, nowMs)?.let { return it }

        val artistKey = normalise(seed.artist)
        val fetch = synchronized(lock) {
            shouldFetchSimilar(lastFetchAtMs, lastArtistKey, artistKey, nowMs)
        }
        if (!fetch) {
            // Inside the window, or the same artist again: the last answer is
            // the answer. Only its kept tail may cost a few searches, and only
            // when the first batch barely matched.
            val reused = reuseLastFetch(seed) ?: return emptyList()
            store(SimilarCacheEntry(key, nowMs, reused.map { it.asCached() }))
            return reused
        }

        val recording = when (val lookup = resolveRecording(seed)) {
            RecordingLookup.Failed -> return emptyList()
            RecordingLookup.None -> {
                // MusicBrainz genuinely does not know this song. Remember that
                // for a week rather than asking on every top-up.
                store(SimilarCacheEntry(key, nowMs, emptyList()))
                return emptyList()
            }
            is RecordingLookup.Found -> lookup.recording
        }
        val similar = fetchSimilar(recording.mbid) ?: return emptyList()
        val ranked = similar.asSequence()
            .filterNot { titleMatches(it.title, seed.title) && artistMatches(it.artist, seed.artist) }
            .take(MAX_SIMILAR)
            .toList()
        val head = ranked.take(MAX_SEARCHES_PER_FETCH)
        val tail = ranked.drop(MAX_SEARCHES_PER_FETCH)
        val resolution = resolveOnYouTube(head, seed)
        val resolved = resolution.songs

        synchronized(lock) {
            lastFetchAtMs = nowMs
            lastArtistKey = artistKey
            lastSimilar = similar
            lastResolved = resolved
            pendingTail = tail
        }

        // The seed resolved and ListenBrainz answered, so this outcome is the
        // seed's for a week even when nothing on YouTube matched. An empty
        // answer produced while a YouTube search failed is not the seed's: a
        // transient network fault must not empty this source for seven days. A
        // failed ListenBrainz call is likewise not cached and retried next
        // top-up.
        if (shouldCacheSimilarAnswer(resolved.size, resolution.failedSearches)) {
            store(SimilarCacheEntry(key, nowMs, resolved.map { it.asCached() }))
        }
        return resolved
    }

    /**
     * Whether a SIMILAR fetch may happen now.
     *
     * AutoPlay tops up on nearly every track change, so this is the brake: one
     * whole data-set answer serves every top-up for [SIMILAR_MIN_INTERVAL_MS],
     * and the next fetch is only worth making for a different original artist.
     * A blank seed artist cannot be compared, so it reuses rather than refetches;
     * a blank *previous* artist counts as different, since nothing was learnt.
     */
    internal fun shouldFetchSimilar(
        lastFetchAtMs: Long,
        lastArtistKey: String,
        seedArtistKey: String,
        nowMs: Long,
    ): Boolean {
        if (lastFetchAtMs <= 0L) return true
        if (nowMs - lastFetchAtMs < SIMILAR_MIN_INTERVAL_MS) return false
        if (seedArtistKey.isBlank()) return false
        if (lastArtistKey.isBlank()) return true
        return seedArtistKey != lastArtistKey
    }

    /**
     * Whether a similar answer may be remembered for a week.
     *
     * A seed ListenBrainz does not know is worth remembering, and so is an
     * answer that resolved rows. An empty answer produced while a YouTube
     * search failed is not: a transient network fault must not empty this
     * seed's similar source until the cache expires.
     */
    internal fun shouldCacheSimilarAnswer(found: Int, failedSearches: Int): Boolean =
        found > 0 || failedSearches == 0

    /**
     * The last fetch's answer for a seed inside the throttle window.
     *
     * Nothing is fetched from ListenBrainz. When the first batch resolved fewer
     * than [MIN_LAZY_SURVIVORS] rows, the next [MAX_SEARCHES_PER_FETCH] kept
     * rows are resolved instead of being lost; that is the only network this
     * path may spend, and it stops once enough rows survive. Returns null when
     * there is nothing to reuse, so failures are not cached as empty answers.
     */
    private suspend fun reuseLastFetch(seed: Seed): List<Song>? {
        var resolved: List<Song> = emptyList()
        var tail: List<SimilarRecording> = emptyList()
        var hadSimilar = false
        synchronized(lock) {
            hadSimilar = lastSimilar.isNotEmpty()
            resolved = lastResolved
            tail = pendingTail
        }
        if (!hadSimilar) return null

        if (resolved.size < MIN_LAZY_SURVIVORS && tail.isNotEmpty()) {
            val batch = tail.take(MAX_SEARCHES_PER_FETCH)
            val rest = tail.drop(MAX_SEARCHES_PER_FETCH)
            val extra = resolveOnYouTube(batch, seed).songs
            resolved = (resolved + extra).distinctBy { it.videoId }
            synchronized(lock) {
                lastResolved = resolved
                pendingTail = rest
            }
        }

        return resolved.filterNot {
            titleMatches(it.title, seed.title) && artistMatches(it.artist, seed.artist)
        }
    }

    // ── Seeds ───────────────────────────────────────────────────────────────

    /**
     * The song to look up for [song].
     *
     * A queue row already shown as a cover keeps the upload's fields; those are
     * read first. A raw cover upload is read through [CoverTitles]. When a cover
     * title never names its original artist the artist stays blank and the
     * recording search is made by title alone — better than asking what is
     * similar to the cover singer.
     */
    internal fun seedOf(song: Song): Seed? {
        if (song.originalTitle != null) {
            val title = song.title.trim()
            if (title.isEmpty() || normalise(title).isEmpty()) return null
            return Seed(title, song.coverOriginalArtist.orEmpty().trim())
        }
        val cover = CoverTitles.read(song)
        val title = cover?.title?.trim().orEmpty().ifEmpty { song.title.trim() }
        if (title.isEmpty() || normalise(title).isEmpty()) return null
        val artist = cover?.originalArtist.orEmpty().trim().ifEmpty {
            CoverTitles.creditedArtist(song).trim()
        }
        return Seed(title, artist)
    }

    // ── Parsing and matching (pure) ─────────────────────────────────────────

    /** The fuzzy recording answer, in the order ListenBrainz ranked it. */
    internal fun parseRecordingSearch(raw: String): List<SimilarRecording>? =
        runCatching {
            jsonFormat.decodeFromString(ListSerializer(RecordingHit.serializer()), raw)
        }.getOrNull()
            ?.mapNotNull { hit ->
                val mbid = hit.recordingMbid.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = hit.recordingName.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SimilarRecording(mbid, title, hit.artistCreditName)
            }

    /**
     * The first recording whose title matches and whose artist matches, or the
     * first title match when no credited artist is known.
     *
     * A cover's title often contains the original's, so title matching alone
     * would land on a cover by someone else. The artist check is what keeps the
     * seed on the original recording.
     */
    internal fun pickRecording(
        hits: List<SimilarRecording>,
        seed: Seed,
    ): SimilarRecording? {
        val byTitle = hits.filter { titleMatches(it.title, seed.title) }
        return byTitle.firstOrNull { artistMatches(it.artist, seed.artist) }
            // Only a seed that never knew its artist may settle for a title
            // match; otherwise a same-titled cover would become the seed.
            ?: byTitle.firstOrNull()?.takeIf { seed.artist.isBlank() }
    }

    /** The similar-recording rows, in ListenBrainz's score order. */
    internal fun parseSimilarRecordings(raw: String): List<SimilarRecording>? =
        runCatching {
            jsonFormat.decodeFromString(ListSerializer(SimilarHit.serializer()), raw)
        }.getOrNull()
            ?.mapNotNull { hit ->
                val mbid = hit.recordingMbid.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = hit.recordingName.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SimilarRecording(mbid, title, hit.artistCreditName)
            }

    /**
     * The first YouTube song result that is the wanted recording.
     *
     * Search rows are re-ordered by relevance rather than guaranteed exact, and
     * a live or cover version often comes first. Taking the first row whose
     * normalised title and artist both match is the same rule the rest of the
     * app uses for a resolved track.
     */
    internal fun pickYtSong(songs: List<Song>, recording: SimilarRecording): Song? =
        songs.firstOrNull { song ->
            titleMatches(song.title, recording.title) &&
                artistMatches(song.artist, recording.artist)
        }?.takeIf { it.videoId.isNotBlank() }

    /** Letters and digits only, lower-cased: punctuation and spacing stop mattering. */
    internal fun normalise(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    internal fun titleMatches(a: String, b: String): Boolean {
        val left = normalise(a)
        val right = normalise(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        // A result or a seed may carry a version note the other lacks. Only a
        // substantial substring counts; "Me" inside "Take On Me" would not.
        return min(left.length, right.length) >= 5 &&
            (left.contains(right) || right.contains(left))
    }

    internal fun artistMatches(a: String, b: String): Boolean {
        if (b.isBlank()) return true
        val left = normalise(a)
        val right = normalise(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        return min(left.length, right.length) >= 3 &&
            (left.contains(right) || right.contains(left))
    }

    /** One cache bucket per song, so a cover and its original share nothing. */
    internal fun cacheKey(title: String, artist: String): String =
        normalise(title) + "|" + normalise(artist)

    /** Whether [fetchedAtMs] is still inside [ttlMs] at [nowMs]. */
    internal fun isFresh(
        fetchedAtMs: Long,
        nowMs: Long,
        ttlMs: Long = CACHE_TTL_MS,
    ): Boolean = fetchedAtMs > 0L && nowMs < fetchedAtMs + ttlMs

    // ── Network ─────────────────────────────────────────────────────────────

    /** A seed lookup, with failure kept apart from "MusicBrainz has no match". */
    private sealed interface RecordingLookup {
        data class Found(val recording: SimilarRecording) : RecordingLookup
        data object None : RecordingLookup
        data object Failed : RecordingLookup
    }

    private suspend fun resolveRecording(seed: Seed): RecordingLookup {
        if (!readyForCall()) return RecordingLookup.Failed
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("query", seed.query)
            .build()
        val raw = getJson(url.toString()) ?: return RecordingLookup.Failed
        val hits = parseRecordingSearch(raw) ?: return RecordingLookup.Failed
        val picked = pickRecording(hits, seed)
        return if (picked == null) RecordingLookup.None else RecordingLookup.Found(picked)
    }

    private suspend fun fetchSimilar(recordingMbid: String): List<SimilarRecording>? {
        if (!readyForCall()) return null
        val url = SIMILAR_URL.toHttpUrl().newBuilder()
            .addQueryParameter("recording_mbids", recordingMbid)
            .addQueryParameter("algorithm", ALGORITHM)
            .build()
        val raw = getJson(url.toString()) ?: return null
        return parseSimilarRecordings(raw)
    }

    private suspend fun getJson(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                absorbRateLimit(response.headers)
                if (response.code == 429) {
                    noteRateLimited(response.headers)
                    return@use null
                }
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "ListenBrainz request failed", it)
        }.getOrNull()
    }

    /**
     * Waits out the one-second spacing, then answers whether the call may go.
     *
     * A 429 that arrived on the previous call sets [notBeforeMs]; its reset
     * window is usually longer than a top-up should wait, so the call is simply
     * skipped and the seed is retried later.
     */
    private suspend fun readyForCall(): Boolean {
        if (rateLimited(System.currentTimeMillis())) return false
        awaitCallSlot()
        return !rateLimited(System.currentTimeMillis())
    }

    /**
     * One call per second, as ListenBrainz asks. The wait is a suspend on this
     * top-up's own coroutine, not a scheduled task, and only happens between two
     * calls the current top-up is already making.
     */
    private suspend fun awaitCallSlot() {
        val wait = synchronized(lock) {
            val now = System.currentTimeMillis()
            (lastCallStartedMs + MIN_CALL_INTERVAL_MS - now).coerceAtLeast(0L)
        }
        if (wait > 0L) delay(wait)
        synchronized(lock) { lastCallStartedMs = System.currentTimeMillis() }
    }

    private fun rateLimited(nowMs: Long): Boolean = synchronized(lock) { nowMs < notBeforeMs }

    /** Uses the headers the API recommends, not the wall clock alone. */
    private fun absorbRateLimit(headers: Headers) {
        val remaining = headers["X-RateLimit-Remaining"]?.trim()?.toIntOrNull() ?: return
        if (remaining > 0) return
        val resetIn = headers["X-RateLimit-Reset-In"]?.trim()?.toLongOrNull()
            ?.coerceIn(1L, 300L) ?: return
        synchronized(lock) {
            notBeforeMs = maxOf(notBeforeMs, System.currentTimeMillis() + resetIn * 1_000L)
        }
    }

    private fun noteRateLimited(headers: Headers) {
        val resetIn = headers["X-RateLimit-Reset-In"]?.trim()?.toLongOrNull()
            ?.coerceIn(1L, 300L)
            ?: 60L
        synchronized(lock) {
            notBeforeMs = maxOf(notBeforeMs, System.currentTimeMillis() + resetIn * 1_000L)
        }
    }

    /** What one batch of YouTube lookups produced, and how many could not answer. */
    private data class YouTubeResolution(
        val songs: List<Song>,
        val failedSearches: Int,
    )

    /** What one YouTube search produced; a failure is not the same as no match. */
    private sealed interface SearchOutcome {
        data class Found(val song: Song) : SearchOutcome
        data object NoMatch : SearchOutcome
        data object Failed : SearchOutcome
    }

    private suspend fun resolveOnYouTube(
        recordings: List<SimilarRecording>,
        seed: Seed,
    ): YouTubeResolution {
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        val out = ArrayList<Song>(recordings.size)
        val seen = HashSet<String>()
        var failed = 0
        for (recording in recordings) {
            if (titleMatches(recording.title, seed.title) &&
                artistMatches(recording.artist, seed.artist)
            ) {
                continue
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            when (val outcome = resolveOne(recording, min(CALL_TIMEOUT_MS, remaining))) {
                is SearchOutcome.Found -> if (seen.add(outcome.song.videoId)) out += outcome.song
                SearchOutcome.NoMatch -> Unit
                SearchOutcome.Failed -> failed++
            }
        }
        return YouTubeResolution(out, failed)
    }

    private suspend fun resolveOne(recording: SimilarRecording, timeoutMs: Long): SearchOutcome {
        val query = listOf(recording.title, recording.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val result = withTimeoutOrNull(timeoutMs) {
            YtMusicRepository.search(query, SearchFilter.SONGS)
        } ?: return SearchOutcome.Failed
        if (result.isFailure) return SearchOutcome.Failed
        val rows = result.getOrElse { return SearchOutcome.Failed }
        val songs = rows.mapNotNull { row ->
            when (row) {
                is SearchResult.Track -> row.song
                is SearchResult.TopTrack -> row.song
                is SearchResult.Browse -> null
            }
        }
        return pickYtSong(songs, recording)?.let { SearchOutcome.Found(it) } ?: SearchOutcome.NoMatch
    }

    // ── Cache ───────────────────────────────────────────────────────────────

    private suspend fun cached(key: String, nowMs: Long): List<Song>? {
        ensureLoaded()
        val entry = synchronized(lock) {
            val stored = memory[key]
            if (stored == null || !isFresh(stored.fetchedAt, nowMs)) {
                memory.remove(key)
                null
            } else {
                stored
            }
        } ?: return null
        return entry.songs.mapNotNull { it.asSong() }
    }

    private suspend fun store(entry: SimilarCacheEntry) {
        synchronized(lock) {
            memory.remove(entry.key)
            memory[entry.key] = entry
            pruneLocked(System.currentTimeMillis())
        }
        persist()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            val raw = withContext(Dispatchers.IO) {
                runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull()
            }
            if (raw != null) {
                runCatching {
                    jsonFormat.decodeFromString(SimilarCacheFile.serializer(), raw)
                }.getOrNull()?.takeIf { it.version == CACHE_VERSION }?.let { stored ->
                    synchronized(lock) {
                        memory.clear()
                        stored.entries.forEach { memory[it.key] = it }
                        pruneLocked(System.currentTimeMillis())
                    }
                }
            }
            loaded = true
        }
    }

    private suspend fun persist() {
        val target = file ?: return
        val snapshot = synchronized(lock) {
            SimilarCacheFile(entries = memory.values.toList())
        }
        saveLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    target.parentFile?.mkdirs()
                    val temporary = File(target.path + ".tmp")
                    temporary.writeText(
                        jsonFormat.encodeToString(SimilarCacheFile.serializer(), snapshot),
                    )
                    if (!temporary.renameTo(target)) {
                        if (target.delete() && temporary.renameTo(target)) Unit else temporary.delete()
                    }
                }.onFailure { Log.w(TAG, "Could not write similar cache", it) }
            }
        }
    }

    private fun pruneLocked(nowMs: Long) {
        memory.entries.removeAll { !isFresh(it.value.fetchedAt, nowMs) }
        val excess = memory.size - MAX_CACHE_ENTRIES
        if (excess <= 0) return
        memory.entries.sortedBy { it.value.fetchedAt }
            .take(excess)
            .forEach { memory.remove(it.key) }
    }

    private fun Song.asCached(): CachedSong = CachedSong(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        isVideo = isVideo,
        isVideoOrigin = isVideoOrigin,
    )

    private fun CachedSong.asSong(): Song? {
        if (videoId.isBlank()) return null
        return Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationText = durationText,
            isVideo = isVideo,
            isVideoOrigin = isVideoOrigin,
        )
    }

    // ── Wire shapes ─────────────────────────────────────────────────────────

    @Serializable
    private data class RecordingHit(
        @SerialName("recording_mbid") val recordingMbid: String = "",
        @SerialName("recording_name") val recordingName: String = "",
        @SerialName("artist_credit_name") val artistCreditName: String = "",
    )

    @Serializable
    private data class SimilarHit(
        @SerialName("recording_mbid") val recordingMbid: String = "",
        @SerialName("recording_name") val recordingName: String = "",
        @SerialName("artist_credit_name") val artistCreditName: String = "",
    )

    @Serializable
    internal data class CachedSong(
        val videoId: String = "",
        val title: String = "",
        val artist: String = "",
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val isVideo: Boolean = false,
        val isVideoOrigin: Boolean = false,
    )

    @Serializable
    internal data class SimilarCacheEntry(
        val key: String = "",
        val fetchedAt: Long = 0L,
        val songs: List<CachedSong> = emptyList(),
    )

    @Serializable
    internal data class SimilarCacheFile(
        val version: Int = 2,
        val entries: List<SimilarCacheEntry> = emptyList(),
    )
}
