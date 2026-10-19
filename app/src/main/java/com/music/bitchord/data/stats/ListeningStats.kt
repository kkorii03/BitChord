package com.music.bitchord.data.stats

import android.content.Context
import android.util.Log
import com.music.bitchord.data.DebugLog as Debug
import com.music.bitchord.data.HomeFallbackRules
import com.music.bitchord.data.LocalArtist
import com.music.bitchord.data.LocalPlay
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.normalizedArtistName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * What this device has listened to, kept on this device.
 *
 * ## Aggregates, not an event log
 *
 * The obvious shape for listening history is a row per play, summed up when
 * something asks. It is also the shape that grows without bound on a phone
 * nobody is going to garbage-collect: a year of ordinary listening is tens of
 * thousands of rows, and every one of them is carried around so that a page
 * almost nobody opens can add them up once.
 *
 * So the addition happens on the way in. A track's total is a counter that goes
 * up while it plays, and reading the Replay is a merge of a handful of already
 * finished sums rather than a pass over history. The cost of that is what any
 * aggregate costs: the raw plays are gone, so a question nobody thought of in
 * advance cannot be answered later. Every question Replay asks is here.
 *
 * ## One file per month
 *
 * Buckets are calendar months in the device's own time zone, one JSON file
 * each. That is what makes "this year" and "all time" the same operation on
 * different numbers of files, and it means the only bucket held in memory is
 * the one being written to — the rest are read, merged and released.
 *
 * A month is also the granularity below which nothing on the Replay page asks a
 * question. Days are still counted, as a total per day inside the bucket, which
 * is enough for "your biggest day" without a file per day to get there.
 *
 * ## Nothing leaves the device
 *
 * There is no upload, no account and no id. [Backup] can write the whole thing
 * out as JSON because the user asked it to, and that is the only way any of this
 * goes anywhere.
 */
object ListeningStats {

    private lateinit var directory: File

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The month being written to, held open between samples.
     *
     * Guarded by its own lock rather than made thread-safe piecewise: a sample
     * touches four maps and two arrays and has to leave them agreeing with each
     * other, and the writer is one coroutine ticking every few seconds, so
     * there is nothing here for finer locking to win.
     */
    private var open: OpenBucket? = null

    private val lock = Any()

    /** Set when [open] has changes not yet on disk. */
    private var dirty = false

    /**
     * Where every write to this folder happens.
     *
     * Its own scope rather than the caller's, because the caller is the
     * playback service and its scope is the main thread. [writeLock] serialises
     * them so a flush and a month rollover cannot be halfway into the same file
     * at once.
     */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    /**
     * The most recently queued write.
     *
     * A throttled [flush] can take the snapshot and hand it to [writer] while
     * the caller is already on its way to read the file back. Joining this in
     * [flushAndAwait] closes that gap; the [writeLock] keeps the writes in
     * order, so joining the newest one also waits for anything ahead of it.
     */
    @Volatile
    private var lastWrite: Job? = null

    /**
     * Routine writes are at most one every two minutes. Playback samples every
     * few seconds; the month file is up to six hundred tracks, and rewriting it
     * at that rate is flash wear for numbers nobody reads at that resolution.
     * Forced writes (pause, track end, service stop, and every read of the
     * summary) are not throttled.
     */
    private val writeThrottle = WriteThrottle(MIN_WRITE_INTERVAL_MS)

    /**
     * Bumped by every completed write and every import, so [summary] can tell
     * whether the answer it already has is still the answer.
     */
    @Volatile
    private var version = 0L

    /**
     * Emits when a playthrough starts and when a play is counted, so Home's
     * Recents can follow this device's record immediately. Event-driven: no
     * timer and no polling, and at most twice per track. A slow collector may
     * miss an intermediate event, which is harmless because it reads the whole
     * recent list when it wakes.
     */
    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** The last summary handed out, and what it was computed from. */
    private var cached: Cached? = null

    private class Cached(
        val period: ReplayPeriod,
        val version: Long,
        val facts: Int,
        val day: LocalDate,
        val summary: ReplaySummary,
    )

    fun init(context: Context) {
        directory = File(context.filesDir, DIRECTORY)
        // Opening a month means reading and parsing it, and the first thing to
        // ask for one is the playback sampler — on the main thread, a few
        // seconds into the first track. Done here instead, it has happened long
        // before anything is playing.
        writer.launch { synchronized(lock) { bucketFor(YearMonth.now()) } }
    }

    private val ready: Boolean get() = this::directory.isInitialized

    /**
     * Whether this store has been opened by an application.
     *
     * Read by [ListeningRecorder] at the first sample of a track so its
     * diagnostic log can say that the play was not recorded because the store
     * was not ready yet, rather than leaving a silent gap.
     */
    internal val isReady: Boolean get() = ready

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Adds [playedMs] of [song] to the month [atMs] falls in.
     *
     * [countsAsPlay] separates the two things a listener means by "played". The
     * minutes are what actually came out of the speaker and are added on every
     * sample; a *play* is a whole listen and is counted once, by whoever is
     * watching the track rather than here — see [ListeningRecorder], which holds
     * the same threshold the scrobbler uses.
     *
     * [atMs] exists for the debug seed, which has to place plays in the months
     * the emulator tour is meant to show. Ordinary callers leave it at the
     * current time.
     */
    fun record(
        song: Song,
        playedMs: Long,
        countsAsPlay: Boolean,
        atMs: Long = System.currentTimeMillis(),
    ) {
        if (!ready) return
        if (playedMs <= 0 && !countsAsPlay) return
        if (song.videoId.isBlank()) return
        val at = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())
        synchronized(lock) {
            val bucket = bucketFor(YearMonth.from(at))
            val track = bucket.tracks.getOrPut(song.videoId) {
                TrackEntry(
                    id = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    album = song.albumName,
                    albumId = song.albumId,
                    artistId = song.artistId,
                    art = song.thumbnailUrl,
                )
            }
            track.ms += playedMs
            track.last = atMs
            // Filled in as it becomes known: a track queued from search reaches
            // the player with no album, and the lookup that finds one lands
            // while it is already playing. The entry was created without it.
            if (track.album == null) track.album = song.albumName
            if (track.albumId == null) track.albumId = song.albumId
            if (track.artistId == null) track.artistId = song.artistId
            if (track.art == null) track.art = song.thumbnailUrl
            if (countsAsPlay) track.plays++

            // The lead artist, not the credit as a string. A track billed
            // "Cheema Y & Gur Sidhu" is not a third artist who happens to share
            // both their names, and filing it as one is how a chart lists the
            // same person twice — once solo and again in each collaboration —
            // with their listening split between the rows.
            //
            // The *lead* rather than everyone named, which is the reading the
            // rest of the app already makes: a row's artist id points at
            // whoever its page opens, and crediting a feature the same as a
            // headline act puts a guest verse on a chart of what someone
            // listens to.
            // A cover counts for the person singing it, not the channel that
            // posted it nor the artist whose song it is — see CoverTitles.
            primaryArtist(com.music.bitchord.data.cover.CoverTitles.creditedArtist(song))?.let { name ->
                // Asked here rather than in the recorder, because here is where
                // the credit has already been reduced to a person: asking about
                // "Cheema Y & Gur Sidhu" gets a picture of nobody and a genre of
                // nothing.
                ArtistFacts.noticed(name)
                val artist = bucket.artists.getOrPut(name.lowercase(Locale.ROOT)) {
                    NameEntry(name = name, art = song.thumbnailUrl)
                }
                artist.ms += playedMs
                if (countsAsPlay) artist.plays++
                if (artist.id == null) artist.id = song.artistId
            }

            song.albumName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                // Keyed on album *and* artist: "Greatest Hits" is not one
                // release, and merging every one of them into a single row is
                // how a compilation nobody owns tops the album chart.
                val album = bucket.albums.getOrPut(albumKey(name, song.artist)) {
                    NameEntry(name = name, sub = song.artist, art = song.thumbnailUrl)
                }
                album.ms += playedMs
                if (countsAsPlay) album.plays++
                if (album.id == null) album.id = song.albumId
            }

            bucket.hours[at.hour] += playedMs
            val day = at.dayOfMonth
            bucket.days[day] = (bucket.days[day] ?: 0L) + playedMs
            dirty = true
        }
        // Outside the lock and only for a counted play — see [recordStarted]
        // for the other, once-per-playthrough event. A track is announced as
        // the newest Recents row at most twice: when it starts and when it
        // counts, never on every five-second sample.
        if (countsAsPlay) _changes.tryEmit(Unit)
    }

    /**
     * Files the first sample of a new playthrough and announces it at once.
     *
     * A listener expects Recents to follow the track they just started, not
     * only once it has passed the play threshold — see [ListeningRecorder],
     * which holds that threshold. [record] files the time (and the `last` the
     * shelf orders by), and [changes] wakes the shelf without waiting for a
     * counted play or a disk write: [recentTracks] merges the open month in
     * memory. Play counting itself is unchanged and stays on [record]'s
     * `countsAsPlay` path.
     */
    fun recordStarted(
        song: Song,
        playedMs: Long,
        atMs: Long = System.currentTimeMillis(),
    ) {
        if (!ready) return
        record(song, playedMs, countsAsPlay = false, atMs = atMs)
        if (song.videoId.isBlank()) return
        _changes.tryEmit(Unit)
    }

    /**
     * Records [plays] at the times they happened, then waits for every touched
     * month to reach disk.
     *
     * The debug tour force-stops the app between seeding and the launch whose
     * screenshots matter, so a write still queued behind the force-stop would
     * be a Home with no Recents on the second launch.
     */
    internal suspend fun seedHistorical(plays: List<SeedPlay>): Int = withContext(Dispatchers.IO) {
        if (!ready || plays.isEmpty()) return@withContext 0
        // Oldest first, so each month is opened once and retired in order.
        // The whole run holds the bucket lock: the startup warm-up opens the
        // current month on its own coroutine, and letting it interleave would
        // retire a half-written older month.
        synchronized(lock) {
            plays.sortedBy { it.playedAtMs }.forEach { play ->
                record(play.song, play.playedMs, play.countsAsPlay, play.playedAtMs)
            }
        }
        flushAndAwait()
        awaitWriter()
        plays.size
    }

    /** Seeds the tour's history only when this device has not listened yet. */
    internal suspend fun seedIfEmpty(nowMs: Long = System.currentTimeMillis()): Int {
        if (!ready) return 0
        if (recentTracks(1).isNotEmpty()) return 0
        return seedHistorical(ListeningStatsSeed.plan(nowMs))
    }

    /** Joins month writes [bucketFor] queued while the seed rolled over. */
    private suspend fun awaitWriter() {
        writer.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
    }

    /**
     * Writes the open month out, if anything has changed.
     *
     * Called on a cadence rather than on every sample: the numbers are worth
     * losing a few seconds of, and a music player has better uses for its disk
     * than re-serialising a month every time a track ticks over. The routine
     * path is throttled to [MIN_WRITE_INTERVAL_MS]; [force] is for a pause or
     * a stop, where losing the tail would actually matter.
     */
    fun flush(force: Boolean = false) {
        if (!force && !writeThrottle.shouldWrite(System.currentTimeMillis())) return
        val now = System.currentTimeMillis()
        pending()?.let { (key, snapshot) ->
            writeThrottle.wrote(now)
            lastWrite = writer.launch { write(key, snapshot) }
        }
    }

    /** As [flush], for a caller that is about to read back what it wrote. */
    private suspend fun flushAndAwait() {
        val queued = pending()?.let { (key, snapshot) ->
            writeThrottle.wrote(System.currentTimeMillis())
            writer.launch { write(key, snapshot) }.also { lastWrite = it }
        }
        // No snapshot here means a throttled flush may already have taken one;
        // its write can still be running, so wait for that too. When one was
        // queued, joining it is enough: [writeLock] makes it run after any
        // write already in flight.
        (queued ?: lastWrite)?.join()
    }

    /** The open month as bytes-to-be, or null when nothing has changed. */
    private fun pending(): Pair<String, StoredBucket>? {
        if (!ready) return null
        return synchronized(lock) {
            if (!dirty) return null
            val bucket = open ?: return null
            dirty = false
            bucket.key to bucket.snapshot()
        }
    }

    /**
     * Serialises a month and swaps it into place.
     *
     * Never on the caller's thread. The caller is the playback service's
     * sampler, which runs on the main thread — see its `reportProgress` — so
     * encoding a month of listening and writing it there was a JSON pass and a
     * file write on the UI thread every thirty seconds of playback, growing
     * with the size of the month.
     *
     * Written aside and renamed, so a kill mid-write leaves the previous
     * month rather than a truncated one.
     */
    private suspend fun write(key: String, snapshot: StoredBucket) = writeLock.withLock {
        runCatching {
            directory.mkdirs()
            val file = File(directory, "$key.json")
            val temporary = File(directory, "$key.json.tmp")
            temporary.writeText(json.encodeToString(StoredBucket.serializer(), snapshot))
            if (!temporary.renameTo(file)) {
                // renameTo will not replace on some filesystems; the delete is
                // the only thing standing between that and a .tmp per flush.
                if (file.delete() && temporary.renameTo(file)) Unit else temporary.delete()
            }
            version++
        }.onFailure { Log.w(TAG, "Could not write listening bucket $key", it) }
    }

    /** The bucket for [month], loading or creating it and retiring the last one. */
    private fun bucketFor(month: YearMonth): OpenBucket {
        val key = month.toString()
        open?.takeIf { it.key == key }?.let { return it }
        // The month rolled over mid-session. Whatever the last one still held
        // has to reach disk before it is dropped on the floor.
        open?.let { previous ->
            val stale = previous.snapshot()
            lastWrite = writer.launch { write(previous.key, stale) }
        }
        val loaded = read(key) ?: StoredBucket(month = key)
        return OpenBucket.of(key, loaded).also {
            open = it
            prune()
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /**
     * The Replay for [period], merged off disk.
     *
     * Off the main thread and off the open bucket both: the months it needs are
     * files, and the one being written to is flushed first so that the page
     * agrees with what has just been playing.
     */
    suspend fun summary(period: ReplayPeriod): ReplaySummary = withContext(Dispatchers.IO) {
        flushAndAwait()
        val today = LocalDate.now()
        val facts = ArtistFacts.revision.value
        // Every input to the merge, so the cache cannot be stale: which months
        // are on disk and what is in them ([version]), what is known about the
        // artists in them, which period was asked for, and — because "this
        // month" and "this year" are relative — what day it is. Opening the
        // Library tab asks for this, so it is asked often and usually for an
        // answer that has not changed.
        cached?.takeIf {
            it.period == period && it.version == version && it.facts == facts && it.day == today
        }?.let { return@withContext it.summary }

        val merged = MergedBucket()
        months().filter { period.covers(it, today) }
            .forEach { month -> read(month.toString())?.let(merged::add) }
        merged.toSummary(period, today).also {
            cached = Cached(period, version, facts, today, it)
        }
    }

    /**
     * Each month's track totals, oldest first — what [SmartPlaylists] ranks.
     * The open month is flushed first so the lists include what just played.
     */
    suspend fun monthlyTracks(): List<Pair<YearMonth, List<TrackEntry>>> = withContext(Dispatchers.IO) {
        flushAndAwait()
        months().mapNotNull { month -> read(month.toString())?.let { month to it.tracks } }
    }

    /**
     * The most recently played tracks across every month, newest first and one
     * row per video id.
     *
     * [TrackEntry.last] is the play time that orders this: the monthly buckets
     * exist for the Replay's arithmetic, not to imply that a track heard last
     * month is newer than one heard this morning.
     *
     * The open month is merged from memory as well as read off disk. A routine
     * write is throttled and runs on the writer coroutine, so the file can lag
     * behind the track that just started; merging the live bucket means Recents
     * never waits for it and no write is forced just to read. Only a write for
     * a month that has already rolled over has to land, because that data is no
     * longer in `open`.
     */
    suspend fun recentTracks(limit: Int = RECENT_LIMIT): List<TrackEntry> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            if (!ready) return@withContext emptyList()
            lastWrite?.join()
            val (openKey, openTracks) = synchronized(lock) {
                val bucket = open
                bucket?.key to (bucket?.tracks?.values?.map { it.copy() }.orEmpty())
            }
            val fileTracks = months()
                .filter { it.toString() != openKey }
                .flatMap { month -> read(month.toString())?.tracks.orEmpty() }
            mergeRecentTracks(fileTracks, openTracks, limit).also { tracks ->
                Debug.i(
                    RECENTS_TAG,
                    "recents: local read n=${tracks.size} top=" +
                        tracks.take(3).joinToString(" | ") { it.title },
                )
            }
        }

    /**
     * The newest [limit] tracks across [fileTracks] and the open month's
     * [openTracks], one row per id.
     *
     * [openTracks] are listed first so a track heard again in the open month
     * wins a tie against the older copy already on disk. Pure so the merge can
     * be tested without a directory.
     */
    internal fun mergeRecentTracks(
        fileTracks: List<TrackEntry>,
        openTracks: List<TrackEntry>,
        limit: Int,
    ): List<TrackEntry> {
        if (limit <= 0) return emptyList()
        return (openTracks + fileTracks)
            .asSequence()
            .filter { it.id.isNotBlank() }
            .sortedByDescending { it.last }
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }

    /**
     * The artists with at least [minPlays] whole plays in the last [days] days,
     * most played first.
     *
     * Months are the bucket keys, so the window reaches back to the first whole
     * month inside it: a 90-day look on the 20th starts at the month holding
     * that day, not at the same day of the month, which is the granularity this
     * store has. Artists are folded by their lead name, matching the chart the
     * Replay pages draw.
     */
    suspend fun topArtists(days: Int, minPlays: Int): List<LocalArtist> =
        withContext(Dispatchers.IO) {
            if (days <= 0 || minPlays <= 0) return@withContext emptyList()
            flushAndAwait()
            val today = LocalDate.now()
            val from = YearMonth.from(today.minusDays(days.toLong()))
            val until = YearMonth.from(today)
            val merged = LinkedHashMap<String, NameEntry>()
            months().filter { !it.isBefore(from) && !it.isAfter(until) }
                .forEach { month ->
                    read(month.toString())?.artists?.forEach { stored ->
                        val name = primaryArtist(stored.name) ?: stored.name
                        val key = normalizedArtistName(name)
                        if (key.isEmpty()) return@forEach
                        val entry = stored.copy(name = name)
                        merged.merge(key, entry) { a, b -> a.also { it.absorb(b) } }
                    }
                }
            merged.values
                .filter { it.plays >= minPlays }
                .sortedWith(
                    compareByDescending<NameEntry> { it.plays }
                        .thenBy { normalizedArtistName(it.name) },
                )
                .map {
                    LocalArtist(
                        name = it.name,
                        artistId = it.id,
                        plays = it.plays,
                        thumbnailUrl = it.art,
                    )
                }
        }

    /**
     * Rediscoveries: the highest lifetime play counts whose last play is older
     * than [olderThanDays] days, oldest last play first — see
     * [HomeFallbackRules.rediscover] for the ranking itself.
     */
    suspend fun rediscover(limit: Int, olderThanDays: Int): List<LocalPlay> {
        if (limit <= 0) return emptyList()
        val totals = LinkedHashMap<String, TrackEntry>()
        monthlyTracks().forEach { (_, tracks) ->
            tracks.forEach { track ->
                totals.merge(track.id, track.copy()) { a, b -> a.also { it.absorb(b) } }
            }
        }
        return HomeFallbackRules.rediscover(
            candidates = totals.values.map { LocalPlay(it.asSong(), it.last, it.plays) },
            nowMs = System.currentTimeMillis(),
            limit = limit,
            olderThanDays = olderThanDays,
        )
    }

    /** Every month with a file, oldest first. */
    fun months(): List<YearMonth> {
        if (!ready) return emptyList()
        val files = directory.listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            file.name.removeSuffix(".json").takeIf { it != file.name }
                ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        }.sorted()
    }

    private fun read(key: String): StoredBucket? {
        val file = File(directory, "$key.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(StoredBucket.serializer(), file.readText()) }
            .onFailure { Log.w(TAG, "Discarding unreadable listening bucket $key", it) }
            .getOrNull()
    }

    // ── Housekeeping ────────────────────────────────────────────────────────

    /**
     * Keeps the folder to [KEEP_MONTHS] and each bucket to its entry caps.
     *
     * Both are the same bet: what survives is what was actually listened to, so
     * eviction is by time played rather than by recency. A track heard once in
     * passing is the first thing out of a full bucket, and it was never going to
     * appear on any page this data exists to draw.
     */
    private fun prune() {
        val existing = months()
        if (existing.size > KEEP_MONTHS) {
            existing.take(existing.size - KEEP_MONTHS).forEach {
                File(directory, "$it.json").delete()
            }
        }
        val bucket = open ?: return
        bucket.tracks.trimTo(MAX_TRACKS) { it.ms }
        bucket.artists.trimTo(MAX_NAMES) { it.ms }
        bucket.albums.trimTo(MAX_NAMES) { it.ms }
    }

    /**
     * [this] keyed by [key], with anything that lands on the same key added
     * together rather than overwriting.
     *
     * Needed because a key is *recomputed* on load rather than read back from
     * the file — see [NameEntry.key]. Two entries written under different keys
     * by an older build can therefore arrive at the same key now, and the
     * obvious `associateByTo` would silently drop one of their totals.
     */
    private inline fun <T : Any> List<T>.mergedBy(
        into: LinkedHashMap<String, T>,
        key: (T) -> String,
    ): LinkedHashMap<String, T> {
        forEach { entry ->
            val k = key(entry)
            val existing = into[k]
            if (existing == null) {
                into[k] = entry
            } else {
                @Suppress("UNCHECKED_CAST")
                (existing as NameEntry).absorb(entry as NameEntry)
            }
        }
        return into
    }

    private inline fun <K, V> MutableMap<K, V>.trimTo(limit: Int, crossinline weight: (V) -> Long) {
        if (size <= limit) return
        entries.sortedBy { weight(it.value) }
            .take(size - limit)
            .forEach { remove(it.key) }
    }

    // ── Backup ──────────────────────────────────────────────────────────────

    /** Every bucket, for [Backup]. */
    suspend fun exportAll(): List<StoredBucket> {
        if (!ready) return emptyList()
        flushAndAwait()
        return months().mapNotNull { read(it.toString()) }
    }

    /**
     * Replaces everything held with [buckets].
     *
     * A replace rather than a merge, and deliberately so: an import is a restore
     * onto a new device, and two copies of the same month added together would
     * silently double every number on the page with no way to tell afterwards.
     */
    fun importAll(buckets: List<StoredBucket>) {
        if (!ready) return
        synchronized(lock) {
            open = null
            dirty = false
            directory.listFiles()?.forEach { it.delete() }
            directory.mkdirs()
            version++
            cached = null
            buckets.forEach { bucket ->
                val key = runCatching { YearMonth.parse(bucket.month).toString() }.getOrNull()
                    ?: return@forEach
                runCatching {
                    File(directory, "$key.json")
                        .writeText(json.encodeToString(StoredBucket.serializer(), bucket))
                }.onFailure { Log.w(TAG, "Could not import bucket $key", it) }
            }
        }
    }

    // ── Shapes ──────────────────────────────────────────────────────────────

    /**
     * The open month, in mutable form.
     *
     * Apart from [StoredBucket] because the stored shape is a schema and this
     * one is a working set: a counter that goes up thousands of times a session
     * wants to be a `var` in a map, and a file format wants to be immutable and
     * boring.
     */
    private class OpenBucket(
        val key: String,
        val tracks: MutableMap<String, TrackEntry>,
        val artists: MutableMap<String, NameEntry>,
        val albums: MutableMap<String, NameEntry>,
        val hours: LongArray,
        val days: MutableMap<Int, Long>,
    ) {
        fun snapshot() = StoredBucket(
            month = key,
            tracks = tracks.values.toList(),
            artists = artists.map { (key, entry) -> entry.copy(key = key) },
            albums = albums.map { (key, entry) -> entry.copy(key = key) },
            hours = hours.toList(),
            days = days.toMap(),
        )

        companion object {
            fun of(key: String, stored: StoredBucket) = OpenBucket(
                key = key,
                tracks = stored.tracks.associateByTo(LinkedHashMap()) { it.id },
                // Re-keyed on the lead artist, so entries a previous build
                // filed under a whole credit fold into the person on read
                // instead of sitting beside them forever.
                artists = stored.artists
                    .map { it.copy(name = primaryArtist(it.name) ?: it.name) }
                    .mergedBy(LinkedHashMap()) { it.name.lowercase(Locale.ROOT) },
                albums = stored.albums.mergedBy(LinkedHashMap()) { albumKey(it.name, it.sub.orEmpty()) },
                hours = LongArray(24) { stored.hours.getOrElse(it) { 0L } },
                days = stored.days.toMutableMap(),
            )
        }
    }

    /** Several months added together, on the way to a [ReplaySummary]. */
    private class MergedBucket {
        val tracks = HashMap<String, TrackEntry>()
        val artists = HashMap<String, NameEntry>()
        val albums = HashMap<String, NameEntry>()
        val hours = LongArray(24)
        val days = HashMap<String, Long>()
        var earliest: String? = null

        fun add(bucket: StoredBucket) {
            bucket.tracks.forEach { entry ->
                tracks.merge(entry.id, entry.copy()) { a, b -> a.also { it.absorb(b) } }
            }
            bucket.artists.forEach { entry ->
                val lead = entry.copy(name = primaryArtist(entry.name) ?: entry.name)
                artists.merge(lead.name.lowercase(Locale.ROOT), lead) { a, b -> a.also { it.absorb(b) } }
            }
            bucket.albums.forEach { entry ->
                val key = albumKey(entry.name, entry.sub.orEmpty())
                albums.merge(key, entry.copy()) { a, b -> a.also { it.absorb(b) } }
            }
            repeat(24) { hours[it] += bucket.hours.getOrElse(it) { 0L } }
            bucket.days.forEach { (day, ms) ->
                val date = "${bucket.month}-%02d".format(day)
                days[date] = (days[date] ?: 0L) + ms
            }
            if (earliest == null || bucket.month < earliest!!) earliest = bucket.month
        }

        fun toSummary(period: ReplayPeriod, today: LocalDate): ReplaySummary {
            val rankedSongs = tracks.values
                .sortedWith(compareByDescending<TrackEntry> { it.ms }.thenByDescending { it.plays })
                .map {
                    RankedSong(
                        song = it.asSong(),
                        ms = it.ms,
                        plays = it.plays,
                    )
                }
            // The artist's own picture and page where one has been found, and
            // the track's sleeve where one hasn't -- see [ArtistFacts]. Resolved
            // here rather than stored in the bucket because it is a fact about
            // the artist, not about the evening they were played on: written
            // into every month it would freeze at whatever was known the first
            // time they came up, and be missing from every month before that.
            val rankedArtists = artists.values
                .sortedWith(compareByDescending<NameEntry> { it.ms }.thenByDescending { it.plays })
                .map {
                    RankedEntry(
                        title = it.name,
                        subtitle = it.sub,
                        artworkUrl = ArtistFacts.imageFor(it.name) ?: it.art,
                        browseId = it.id ?: ArtistFacts.browseIdFor(it.name),
                        ms = it.ms,
                        plays = it.plays,
                    )
                }
            val rankedAlbums = albums.values
                .sortedWith(compareByDescending<NameEntry> { it.ms }.thenByDescending { it.plays })
                .map { RankedEntry(it.name, it.sub, it.art, it.id, it.ms, it.plays) }

            // Genres are derived rather than stored — see [ArtistFacts]. An
            // artist with no tag yet simply doesn't vote, which is why this can
            // be an empty list on a page whose other charts are full.
            val genreMs = LinkedHashMap<String, Long>()
            val genrePlays = LinkedHashMap<String, Int>()
            val genreArt = LinkedHashMap<String, String?>()
            rankedArtists.forEach { artist ->
                ArtistFacts.genresFor(artist.title).forEach { genre ->
                    genreMs[genre] = (genreMs[genre] ?: 0L) + artist.ms
                    genrePlays[genre] = (genrePlays[genre] ?: 0) + artist.plays
                    if (genreArt[genre] == null) genreArt[genre] = artist.artworkUrl
                }
            }
            // Anything still missing a picture is asked about now, so opening
            // the page is a second way for these to fill in — the first being
            // playing the music, which is no help to someone who has just
            // installed a build that started collecting them.
            rankedArtists.take(ARTISTS_TO_RESOLVE)
                .filter { it.artworkUrl == null || it.browseId == null }
                .forEach { ArtistFacts.noticed(it.title) }

            val rankedGenres = genreMs.entries
                .sortedByDescending { it.value }
                .map {
                    RankedEntry(
                        title = it.key,
                        subtitle = null,
                        artworkUrl = genreArt[it.key],
                        browseId = null,
                        ms = it.value,
                        plays = genrePlays[it.key] ?: 0,
                    )
                }

            val busiest = days.entries.maxByOrNull { it.value }
            return ReplaySummary(
                period = period,
                label = period.label(today),
                totalMs = tracks.values.sumOf { it.ms },
                totalPlays = tracks.values.sumOf { it.plays },
                songs = rankedSongs,
                artists = rankedArtists,
                albums = rankedAlbums,
                genres = rankedGenres,
                hourOfDay = hours.toList(),
                busiestDay = busiest?.key,
                busiestDayMs = busiest?.value ?: 0L,
                distinctSongs = tracks.size,
                distinctArtists = artists.size,
                distinctAlbums = albums.size,
                since = earliest,
            )
        }
    }

    /**
     * How an album is filed: its name and its artist, folded to lower case.
     *
     * Joined on a NUL rather than a space, because that is the one character
     * neither half can contain: with a space, "Greatest Hits" by "Vol 2" and
     * "Greatest Hits Vol" by "2" are the same key. Contrived, but the class of
     * bug is not, and a separator that cannot collide costs nothing.
     *
     * Concatenated rather than interpolated so the separator is visible in the
     * source — a NUL inside a template string is an invisible character in the
     * middle of a line, which is exactly how this file spent a while being
     * treated as a binary by every tool that looked at it.
     */
    private fun albumKey(name: String, artist: String): String =
        name.lowercase(Locale.ROOT) + ALBUM_KEY_SEPARATOR + artist.lowercase(Locale.ROOT)

    /** @see albumKey */
    private val ALBUM_KEY_SEPARATOR = Char(UNIT_SEPARATOR).toString()

    /**
     * ASCII 0x1F, which exists for exactly this and cannot occur in a title.
     *
     * Built from its code point rather than written as an escape: a control
     * character spelt out in a string literal is an invisible byte in the
     * middle of a line, and this file spent a while being treated as a binary
     * by every tool that looked at it because of one.
     */
    private const val UNIT_SEPARATOR = 31

    /**
     * The people named on a track's credit.
     *
     * Split conservatively, and on punctuation rather than on words: `,`, `&`,
     * `x` and the feature markers are how every catalogue this app reads joins
     * two artists, while " and " and " with " are as often part of a band's
     * actual name — "Florence and the Machine", "Nick Cave and the Bad Seeds" —
     * so they are left alone. A name is still split wrongly now and then
     * ("Simon & Garfunkel"), which costs one duplicated chart row; not splitting
     * at all costs a row for every collaboration anyone has ever recorded.
     */
    fun primaryArtist(credit: String): String? {
        lastCredit?.let { (raw, name) -> if (raw == credit) return name }
        val name = credit.split(CREDIT_SEPARATORS)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Memoised on the last credit seen, because the caller is the playback
        // sampler: it asks about the same string a dozen times a minute for as
        // long as a track is playing, and a regex split per tick is work with a
        // known answer.
        lastCredit = credit to name
        return name
    }

    @Volatile
    private var lastCredit: Pair<String, String?>? = null

    private val CREDIT_SEPARATORS = Regex(
        """\s*,\s*|\s+&\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+""",
        RegexOption.IGNORE_CASE,
    )

    private const val TAG = "BitChordListening"

    /**
     * Tag for the Recents diagnostic trail, shared with the shared module's
     * logs so one export reads as a single sequence.
     */
    private const val RECENTS_TAG = "BitChord"

    private const val DIRECTORY = "listening"

    /** At most one routine month write per two minutes of playback. */
    private const val MIN_WRITE_INTERVAL_MS = 2L * 60L * 1000L

    /** How far down the artist chart a page opening will send lookups for. */
    private const val ARTISTS_TO_RESOLVE = 15

    /** Three years, which is longer than "all time" means to most people. */
    private const val KEEP_MONTHS = 36

    /** How many recent tracks a shelf asks this store for. */
    private const val RECENT_LIMIT = 20

    /**
     * Per-month caps. Generous enough that nobody reaches them by listening,
     * tight enough that a bucket stays well under a hundred kilobytes.
     */
    private const val MAX_TRACKS = 600
    private const val MAX_NAMES = 400
}

/** One track's totals inside a bucket. */
@Serializable
data class TrackEntry(
    val id: String,
    val title: String = "",
    val artist: String = "",
    var album: String? = null,
    var albumId: String? = null,
    var artistId: String? = null,
    var art: String? = null,
    var ms: Long = 0L,
    var plays: Int = 0,
    var last: Long = 0L,
) {
    /**
     * The entry as a playable row. A cover was recorded as it was shown —
     * its song, and "원곡자 · 가수 커버" — so it is marked a video again: that
     * byline is what [com.music.bitchord.data.cover.CoverTitles] reads a
     * cover back from, for its lyrics, timing and moving cover.
     */
    fun asSong(): Song = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = art,
        artistId = artistId,
        albumId = albumId,
        albumName = album,
        isVideo = artist.endsWith(" 커버"),
    )

    fun absorb(other: TrackEntry) {
        ms += other.ms
        plays += other.plays
        last = maxOf(last, other.last)
        if (album == null) album = other.album
        if (albumId == null) albumId = other.albumId
        if (artistId == null) artistId = other.artistId
        if (art == null) art = other.art
    }
}

/**
 * One artist's or album's totals.
 *
 * [key] is the map key it was stored under. Written for a reader's benefit and
 * for anything downstream of an export; it is deliberately *not* read back.
 *
 * Reading it was the original design, on the reasoning that a stored key cannot
 * be spelt differently by a later build. What that actually buys is the
 * opposite: an entry keyed by an older build keeps its old key forever, so the
 * same artist ends up in two rows the moment the spelling changes and there is
 * no way back. Recomputing on load means a change heals itself — see
 * [OpenBucket.of] and the merge it does on the way in.
 */
@Serializable
data class NameEntry(
    val name: String = "",
    val sub: String? = null,
    var art: String? = null,
    var id: String? = null,
    var ms: Long = 0L,
    var plays: Int = 0,
    val key: String? = null,
) {
    fun absorb(other: NameEntry) {
        ms += other.ms
        plays += other.plays
        if (art == null) art = other.art
        if (id == null) id = other.id
    }
}

/** One calendar month on disk. */
@Serializable
data class StoredBucket(
    val version: Int = 1,
    val month: String,
    val tracks: List<TrackEntry> = emptyList(),
    val artists: List<NameEntry> = emptyList(),
    val albums: List<NameEntry> = emptyList(),
    /** Milliseconds played per hour of the day, 0..23. */
    val hours: List<Long> = List(24) { 0L },
    /** Milliseconds played per day of the month. */
    val days: Map<Int, Long> = emptyMap(),
)

/** A row on one of the four charts. */
data class RankedEntry(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val browseId: String?,
    val ms: Long,
    val plays: Int,
)

/** A song row, which keeps the whole [Song] so tapping it can play it. */
data class RankedSong(val song: Song, val ms: Long, val plays: Int)

/** How far back a Replay reaches. */
enum class ReplayPeriod(val chip: String) {
    THIS_MONTH("This month"),
    THIS_YEAR("This year"),
    ALL_TIME("All time"),
    ;

    fun covers(month: YearMonth, today: LocalDate): Boolean = when (this) {
        THIS_MONTH -> month == YearMonth.from(today)
        THIS_YEAR -> month.year == today.year
        ALL_TIME -> true
    }

    fun label(today: LocalDate): String = when (this) {
        THIS_MONTH -> YearMonth.from(today).month.name.lowercase(Locale.ROOT)
            .replaceFirstChar { it.uppercase(Locale.ROOT) } + " ${today.year}"
        THIS_YEAR -> today.year.toString()
        ALL_TIME -> "All time"
    }
}

/**
 * Everything the Replay page and the stories draw, worked out once.
 *
 * Deliberately a plain value with the charts already sorted: the stories flip
 * between eight views of the same numbers, and recomputing a ranking per page
 * would put a sort on the swipe.
 */
data class ReplaySummary(
    val period: ReplayPeriod,
    val label: String,
    val totalMs: Long,
    val totalPlays: Int,
    val songs: List<RankedSong>,
    val artists: List<RankedEntry>,
    val albums: List<RankedEntry>,
    val genres: List<RankedEntry>,
    val hourOfDay: List<Long>,
    /** `YYYY-MM-DD` of the day with the most listening, or null. */
    val busiestDay: String?,
    val busiestDayMs: Long,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val distinctAlbums: Int,
    /** The earliest month with anything in it, `YYYY-MM`. */
    val since: String?,
) {
    val minutes: Long get() = totalMs / 60_000
    val hours: Long get() = totalMs / 3_600_000

    /**
     * Whether there is anything at all to draw.
     *
     * One track is enough. There was a minimum here — five minutes, on the
     * reasoning that a chart of two songs is not a chart — and it was wrong in
     * the only way that matters: someone who has just played a couple of songs
     * and gone looking for the page they heard about is told they have not
     * listened to anything, which is both untrue and indistinguishable from the
     * feature being broken. A small Replay is a small Replay, and it grows.
     */
    val isEmpty: Boolean get() = songs.isEmpty()

    /** The hour of the day with the most listening, or null if nothing was. */
    val peakHour: Int? get() = hourOfDay.withIndex()
        .filter { it.value > 0 }
        .maxByOrNull { it.value }
        ?.index
}
