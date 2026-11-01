package com.music.bitchord.ko

import com.music.bitchord.data.DebugLog as Debug
import com.music.bitchord.data.HomeFallbackRules
import com.music.bitchord.data.LocalArtist
import com.music.bitchord.data.LocalPlay
import com.music.bitchord.data.model.normalizedArtistName
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.NameEntry
import com.music.bitchord.data.stats.TrackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * [KO] listening-recents
 *
 * The fork's recent, monthly and rediscovery reads over the upstream listening
 * buckets. Pure reads apart from the flush that makes the open month visible;
 * no timer and no polling.
 */
internal object KoListeningStatsRecent {

    private const val RECENTS_TAG = "BitChord"

    /** How many recent tracks a shelf asks this store for. */
    private const val RECENT_LIMIT = 20

    fun recentLimit(): Int = RECENT_LIMIT

    /**
     * Each month's track totals, oldest first — what SmartPlaylists ranks.
     * The open month is flushed first so the lists include what just played.
     */
    suspend fun monthlyTracks(): List<Pair<YearMonth, List<TrackEntry>>> =
        withContext(Dispatchers.IO) {
            ListeningStats.koFlushAndAwait()
            ListeningStats.months().mapNotNull { month ->
                ListeningStats.koRead(month.toString())?.let { month to it.tracks }
            }
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
     * longer in the open bucket.
     */
    suspend fun recentTracks(limit: Int = RECENT_LIMIT): List<TrackEntry> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            if (!ListeningStats.koReady()) return@withContext emptyList()
            ListeningStats.koAwaitLastWrite()
            val (openKey, openTracks) = ListeningStats.koOpenTracks()
            val fileTracks = ListeningStats.months()
                .filter { it.toString() != openKey }
                .flatMap { month -> ListeningStats.koRead(month.toString())?.tracks.orEmpty() }
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
            ListeningStats.koFlushAndAwait()
            val today = LocalDate.now()
            val from = YearMonth.from(today.minusDays(days.toLong()))
            val until = YearMonth.from(today)
            val merged = LinkedHashMap<String, NameEntry>()
            ListeningStats.months().filter { !it.isBefore(from) && !it.isAfter(until) }
                .forEach { month ->
                    ListeningStats.koRead(month.toString())?.artists?.forEach { stored ->
                        val name = ListeningStats.primaryArtist(stored.name) ?: stored.name
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
}
