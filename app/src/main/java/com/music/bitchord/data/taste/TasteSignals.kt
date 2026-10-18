package com.music.bitchord.data.taste

import android.content.Context
import android.util.Log
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlin.math.pow

/**
 * What this listener reliably likes, skips and comes back to.
 *
 * ## Counters, not history
 *
 * The same choice [ListeningStats] makes, for the same reason: a station asks
 * about every candidate at once, and a row per event would make that a scan over
 * months of playback. Tracks, artists and cover singers each carry a handful of
 * counters — plays, completions, skips, likes, dislikes — and the only facts
 * kept per event are the timestamps a hard rule actually needs (the last played
 * moment, and the handful of recent skips behind "skipped twice this month").
 *
 * ## Decay on read
 *
 * A preference is not a permanent fact. A counter is stored together with the
 * instant it was last touched and decayed with a 60-day half-life every time it
 * is read, so an artist heard constantly last spring fades without anything
 * having to run in the background to age it. Writes only bring a counter up to
 * the present and add one, which is the same arithmetic done at a different
 * moment.
 *
 * The cost is that "what did I like a year ago" cannot be answered exactly:
 * the raw sums are gone. Nothing the recommender asks needs them.
 *
 * ## When a play is a play
 *
 * Recording is hooked where the answer is already known, never on a timer:
 *
 * - [ListeningRecorder][com.music.bitchord.data.stats.ListeningRecorder] calls
 *   [onPlayed] once a track passes the same half-or-four-minutes threshold it
 *   counts as a play, and [onCompleted] at 80% of the track.
 * - [PlaybackService][com.music.bitchord.playback.PlaybackService] calls
 *   [onUserAdvance] from the session's own skip commands only. An automatic
 *   advance never passes through those, and neither does an error recovery, so
 *   an autoplay handoff or a failed stream is not mistaken for a listener
 *   walking away.
 * - The two places a rating is actually written (the UI and the notification)
 *   call [onRated] once YouTube has accepted it. The optimistic update and its
 *   rollback are deliberately not events: only the listener's decision is.
 *
 * ## One file, and no upload
 *
 * Everything lives in one JSON file under `filesDir`, written aside and renamed
 * on an IO coroutine after a short debounce, and capped so it stays a small
 * file forever. Nothing here leaves the device.
 */
object TasteSignals {

    private lateinit var file: File

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    private val tracks = LinkedHashMap<String, StoredTrack>()
    private val artists = LinkedHashMap<String, StoredArtist>()
    private val coverSingers = LinkedHashMap<String, StoredArtist>()

    /** Set once the file has been read, so an early play is not overwritten by the load. */
    @Volatile
    private var loaded = false

    private var dirty = false

    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var scheduled: Job? = null

    // ── Session ─────────────────────────────────────────────────────────────

    /** The last completed tracks of this process — see [SessionContext]. */
    private val completed = ArrayDeque<Pair<String, String?>>()

    /** The last skipped artists of this process. */
    private val skipped = ArrayDeque<String>()

    fun init(context: Context) {
        file = File(context.filesDir, FILE)
        // Off the main thread, like every other store opened at startup: the
        // first track can start before this returns, and [loaded] keeps an
        // early counter from being replaced by the file.
        writer.launch {
            writeLock.withLock {
                runCatching {
                    if (file.exists()) {
                        val stored = json.decodeFromString(StoredSignals.serializer(), file.readText())
                        synchronized(lock) { restore(stored) }
                    }
                }.onFailure { Log.w(TAG, "Discarding unreadable taste signals", it) }
                loaded = true
            }
        }
    }

    // ── Recording ───────────────────────────────────────────────────────────

    /**
     * A track that has now been listened to enough to count as a play.
     *
     * Called once per playthrough by the listening recorder, not per sample.
     */
    fun onPlayed(song: Song) {
        if (!ready()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val singer = singerOf(song)
            val track = trackFor(song, singer)
            track.plays.add(1.0, now)
            track.lastPlayedAt = now
            track.artist.takeIf { it.isNotBlank() }?.let { artistFor(it).lastAt = now }
            singer?.let { singerFor(it).lastAt = now }
            markDirtyLocked()
        }
    }

    /**
     * A track that reached 80% or its end.
     *
     * A completion is the signal a skip can never be: the listener chose to let
     * the song finish. It feeds both long-term affinity and the session's
     * momentum, which is why the two are recorded together.
     */
    fun onCompleted(song: Song) {
        if (!ready()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val singer = singerOf(song)
            val track = trackFor(song, singer)
            track.completes.add(1.0, now)
            track.lastPlayedAt = maxOf(track.lastPlayedAt, now)
            track.artist.takeIf { it.isNotBlank() }?.let { name ->
                artistFor(name).apply {
                    completes.add(1.0, now)
                    lastAt = now
                }
            }
            singer?.let { name ->
                singerFor(name).apply {
                    completes.add(1.0, now)
                    lastAt = now
                }
            }
            rememberCompleted(track.artist, singer)
            markDirtyLocked()
        }
    }

    /**
     * The listener moved off [song] before [min(30 s, 25%)] of it played.
     *
     * Only the session's own skip commands call this — see the class note. The
     * position is the playhead the listener was at when they left, so a skip
     * after a long seek back is not counted as a fresh one.
     */
    fun onUserAdvance(song: Song, positionMs: Long, durationMs: Long?) {
        if (!ready()) return
        // A position the player could not report is not evidence of a skip.
        if (positionMs < 0L) return
        val length = durationMs?.takeIf { it > 0L } ?: song.durationMillis()
        val threshold = if (length > 0L) min(SKIP_FLOOR_MS, length / 4) else SKIP_FLOOR_MS
        if (positionMs >= threshold) return

        val now = System.currentTimeMillis()
        synchronized(lock) {
            val singer = singerOf(song)
            val track = trackFor(song, singer)
            track.skips.add(1.0, now)
            track.recentSkips = (track.recentSkips + now).takeLast(RECENT_SKIPS_KEPT)
            track.lastSkippedAt = now
            track.artist.takeIf { it.isNotBlank() }?.let { artistFor(it).skips.add(1.0, now) }
            singer?.let { singerFor(it).skips.add(1.0, now) }
            rememberSkipped(track.artist)
            markDirtyLocked()
        }
    }

    /**
     * The listener's explicit rating of [videoId].
     *
     * [song] may be null when the row is not on screen any more; the rating is
     * still kept, and the track's metadata is filled in the next time it plays.
     */
    fun onRated(videoId: String, song: Song?, status: LikeStatus) {
        if (!ready() || videoId.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val track = song?.let { trackFor(it, singerOf(it)) }
                ?: tracks.getOrPut(videoId) { StoredTrack(id = videoId) }
            val rating = ratingOf(status)
            if (track.rating == rating) return
            track.rating = rating
            val artist = track.artist.takeIf { it.isNotBlank() }
            val singer = track.coverSinger
            when (rating) {
                RATING_LIKE -> {
                    track.likes.add(1.0, now)
                    artist?.let { artistFor(it).likes.add(1.0, now) }
                    singer?.let { singerFor(it).likes.add(1.0, now) }
                }
                RATING_DISLIKE -> {
                    track.dislikes.add(1.0, now)
                    artist?.let { artistFor(it).dislikes.add(1.0, now) }
                    singer?.let { singerFor(it).dislikes.add(1.0, now) }
                }
                else -> Unit
            }
            markDirtyLocked()
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** The decayed snapshot [Recommender] ranks against. */
    fun taste(nowMs: Long = System.currentTimeMillis()): TasteView = synchronized(lock) {
        val trackView = tracks.mapValues { (_, t) ->
            TrackTaste(
                plays = t.plays.read(nowMs),
                completes = t.completes.read(nowMs),
                skips = t.skips.read(nowMs),
                likes = t.likes.read(nowMs),
                dislikes = t.dislikes.read(nowMs),
                lastPlayedAt = t.lastPlayedAt,
                recentSkips = t.recentSkips,
                rating = when (t.rating) {
                    RATING_LIKE -> TasteRating.LIKE
                    RATING_DISLIKE -> TasteRating.DISLIKE
                    else -> TasteRating.NONE
                },
            )
        }
        val dislikedArtists = tracks.values
            .filter { it.rating == RATING_DISLIKE && it.artist.isNotBlank() }
            .mapTo(HashSet()) { TasteView.key(it.artist) }
        TasteView(
            tracks = trackView,
            artists = artists.mapValues { (_, a) -> a.view(nowMs) },
            coverSingers = coverSingers.mapValues { (_, a) -> a.view(nowMs) },
            dislikedArtists = dislikedArtists,
        )
    }

    /** The last few completed and skipped tracks of this process. */
    fun sessionContext(): SessionContext = synchronized(lock) {
        SessionContext(
            completedArtists = completed.map { it.first },
            completedCoverSingers = completed.mapNotNull { it.second },
            skippedArtists = skipped.toList(),
        )
    }

    // ── Disk ────────────────────────────────────────────────────────────────

    /** Writes whatever has changed. Safe to call from any thread. */
    fun flush() {
        if (!ready()) return
        // Encoded while the lock is held rather than handing the live maps to
        // the writer: counters are mutated from the playback thread and a
        // snapshot sharing them could be read half updated. The encoding itself
        // happens on the debounce coroutine, which is an IO thread.
        val encoded = synchronized(lock) {
            if (!dirty || !loaded) return
            dirty = false
            pruneLocked()
            json.encodeToString(
                StoredSignals.serializer(),
                StoredSignals(
                    tracks = tracks.values.toList(),
                    artists = artists.values.toList(),
                    coverSingers = coverSingers.values.toList(),
                ),
            )
        }
        writer.launch {
            writeLock.withLock {
                runCatching {
                    file.parentFile?.mkdirs()
                    val temporary = File(file.path + ".tmp")
                    temporary.writeText(encoded)
                    if (!temporary.renameTo(file)) {
                        if (file.delete() && temporary.renameTo(file)) Unit else temporary.delete()
                    }
                }.onFailure { Log.w(TAG, "Could not write taste signals", it) }
            }
        }
    }

    /**
     * Marks the store dirty and schedules one write a moment later.
     *
     * Debounced rather than written per event: a station fill asks for dozens of
     * candidates at once and a skip arrives as a burst of callbacks, and a
     * player has better uses for the disk than serialising this file on every
     * one of them.
     */
    private fun markDirtyLocked() {
        dirty = true
        if (scheduled?.isActive == true) return
        scheduled = writer.launch {
            delay(WRITE_DEBOUNCE_MS)
            flush()
        }
    }

    private fun restore(stored: StoredSignals) {
        stored.tracks.forEach { tracks[it.id] = it }
        stored.artists.forEach { artists[nameKey(it.name)] = it }
        stored.coverSingers.forEach { coverSingers[nameKey(it.name)] = it }
        pruneLocked()
    }

    /**
     * Keeps the file bounded.
     *
     * Tracks are kept by the last time they played: a song heard once months
     * ago is the first thing out, and it was never going to rank anything. The
     * per-artist caps then take the same approach with their last activity.
     */
    private fun pruneLocked() {
        tracks.values.sortedByDescending { it.lastPlayedAt }
            .drop(MAX_TRACKS)
            .forEach { tracks.remove(it.id) }
        artists.values.sortedByDescending { it.lastAt }
            .drop(MAX_ARTISTS)
            .forEach { artists.remove(nameKey(it.name)) }
        coverSingers.values.sortedByDescending { it.lastAt }
            .drop(MAX_COVER_SINGERS)
            .forEach { coverSingers.remove(nameKey(it.name)) }
    }

    /**
     * Whether recording is worth doing yet.
     *
     * [loaded] as well as the file: before the read completes the maps are
     * empty, and a play recorded into them would be replaced when the file
     * arrives. The window is the startup read of one small file.
     */
    private fun ready(): Boolean = this::file.isInitialized && loaded

    // ── Shapes ──────────────────────────────────────────────────────────────

    /**
     * The track's entry, filled in as the facts arrive.
     *
     * A rating can land before the track has ever played through this store —
     * a like off a search row — so the entry is created from whatever the row
     * offered and any missing field is completed by a later play. Counters are
     * never overwritten.
     */
    private fun trackFor(song: Song, singer: String?): StoredTrack {
        val track = tracks.getOrPut(song.videoId) {
            StoredTrack(id = song.videoId)
        }
        if (track.title.isBlank()) track.title = CoverTitles.read(song)?.title ?: song.title
        if (track.artist.isBlank()) track.artist = credited(song)
        if (track.coverSinger == null) track.coverSinger = singer
        return track
    }

    private fun artistFor(name: String): StoredArtist =
        artists.getOrPut(nameKey(name)) { StoredArtist(name = name) }

    private fun singerFor(name: String): StoredArtist =
        coverSingers.getOrPut(nameKey(name)) { StoredArtist(name = name) }

    private fun credited(song: Song): String =
        CoverTitles.creditedArtist(song).trim().ifEmpty { song.artist.trim() }

    /**
     * The person singing a cover, where the record names one.
     *
     * [Song.coverSinger] is set when the queue row was shown as a cover; a row
     * that arrived as the upload has to be read. That happens only on a play,
     * completion, skip or rating — never on a sample tick.
     */
    private fun singerOf(song: Song): String? =
        song.coverSinger?.takeIf { it.isNotBlank() } ?: CoverTitles.read(song)?.singer?.takeIf { it.isNotBlank() }

    private fun rememberCompleted(artist: String, singer: String?) {
        if (artist.isBlank() && singer == null) return
        completed.addLast(artist to singer)
        while (completed.size > SESSION_COMPLETED) completed.removeFirst()
    }

    private fun rememberSkipped(artist: String) {
        if (artist.isBlank()) return
        skipped.addLast(artist)
        while (skipped.size > SESSION_SKIPPED) skipped.removeFirst()
    }

    private fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)

    private fun ratingOf(status: LikeStatus): Int = when (status) {
        LikeStatus.LIKE -> RATING_LIKE
        LikeStatus.DISLIKE -> RATING_DISLIKE
        else -> RATING_NONE
    }

    /** Milliseconds under which leaving a track is a skip: 30 s, or a quarter of a short song. */
    private const val SKIP_FLOOR_MS = 30_000L

    /** About a dozen seconds of listening between writes. */
    private const val WRITE_DEBOUNCE_MS = 1_500L

    private const val SESSION_COMPLETED = 5
    private const val SESSION_SKIPPED = 3
    private const val RECENT_SKIPS_KEPT = 8

    private const val MAX_TRACKS = 1_000
    private const val MAX_ARTISTS = 400
    private const val MAX_COVER_SINGERS = 300

    private const val RATING_NONE = 0
    private const val RATING_LIKE = 1
    private const val RATING_DISLIKE = -1

    private const val TAG = "BitChordTaste"
    private const val FILE = "taste/signals.json"
}

/**
 * A counter stored with the moment it was last brought up to date.
 *
 * [value] is the decayed total *as of* [at]; reading it at any later moment is
 * one multiplication, and adding to it is the same multiplication followed by
 * `+ 1`. See [TasteDecay].
 */
@Serializable
data class DecayedValue(
    var value: Double = 0.0,
    var at: Long = 0L,
) {
    fun read(nowMs: Long): Double = TasteDecay.decay(value, at, nowMs)

    fun add(amount: Double, nowMs: Long) {
        value = read(nowMs) + amount
        at = nowMs
    }
}

/**
 * The 60-day half-life.
 *
 * Pure Kotlin, and top-level rather than inside [TasteSignals], so the decay
 * arithmetic can be tested on its own without an Android context.
 */
object TasteDecay {
    const val HALF_LIFE_DAYS = 60L
    const val HALF_LIFE_MS = HALF_LIFE_DAYS * 24L * 60L * 60L * 1000L

    /** What is left of a value after [elapsedMs]; 1.0 for anything in the future. */
    fun factor(elapsedMs: Long): Double =
        if (elapsedMs <= 0L) 1.0 else 0.5.pow(elapsedMs.toDouble() / HALF_LIFE_MS)

    /** [value] decayed from [atMs] to [nowMs]. */
    fun decay(value: Double, atMs: Long, nowMs: Long): Double =
        if (value == 0.0 || atMs <= 0L || nowMs <= atMs) value else value * factor(nowMs - atMs)
}

/** One track's counters on disk. */
@Serializable
data class StoredTrack(
    val id: String = "",
    var title: String = "",
    var artist: String = "",
    var coverSinger: String? = null,
    var plays: DecayedValue = DecayedValue(),
    var completes: DecayedValue = DecayedValue(),
    var skips: DecayedValue = DecayedValue(),
    var likes: DecayedValue = DecayedValue(),
    var dislikes: DecayedValue = DecayedValue(),
    /** Timestamps of the last few skips, newest last — for the recent-skip rule only. */
    var recentSkips: List<Long> = emptyList(),
    var lastPlayedAt: Long = 0L,
    var lastSkippedAt: Long = 0L,
    /** The last explicit rating: 1 like, -1 dislike, 0 none. */
    var rating: Int = 0,
)

/** One artist's or cover singer's counters on disk. */
@Serializable
data class StoredArtist(
    val name: String = "",
    var completes: DecayedValue = DecayedValue(),
    var likes: DecayedValue = DecayedValue(),
    var skips: DecayedValue = DecayedValue(),
    var dislikes: DecayedValue = DecayedValue(),
    var lastAt: Long = 0L,
) {
    fun view(nowMs: Long): ArtistTaste = ArtistTaste(
        completes = completes.read(nowMs),
        likes = likes.read(nowMs),
        skips = skips.read(nowMs),
        dislikes = dislikes.read(nowMs),
    )
}

/** The one file the store keeps. */
@Serializable
data class StoredSignals(
    val version: Int = 1,
    val tracks: List<StoredTrack> = emptyList(),
    val artists: List<StoredArtist> = emptyList(),
    val coverSingers: List<StoredArtist> = emptyList(),
)
