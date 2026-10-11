package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import java.time.YearMonth

/**
 * Playlists made from this device's own listening — the numbers Replay keeps.
 *
 * Listening is stored a month at a time ([ListeningStats]), so "recent" here
 * means this month and the last; a track's last play is kept to the
 * millisecond, which is what the time-based lists lean on.
 */
enum class SmartPlaylist(val browseId: String) {
    /** Played most this month and last. */
    TOP("smart:top"),

    /** Several plays lately, the last within a week: what is on repeat now. */
    ON_REPEAT("smart:repeat"),

    /** Played a lot once, not touched for a month. */
    REDISCOVER("smart:rediscover"),

    /** Liked, but played once or never here. */
    LIKED_UNPLAYED("smart:liked_unplayed"),
    ;

    companion object {
        fun of(browseId: String): SmartPlaylist? = entries.firstOrNull { it.browseId == browseId }
    }
}

object SmartPlaylists {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val TOP_SIZE = 50
    private const val REPEAT_SIZE = 30
    private const val REDISCOVER_SIZE = 50
    private const val LIKED_SIZE = 100

    private class Totals(val entry: TrackEntry) {
        var plays = 0
        var ms = 0L
        var recentPlays = 0
        var recentMs = 0L
        var last = 0L
    }

    /**
     * The songs of [kind], best first. [liked] is the account's liked songs,
     * read only by [SmartPlaylist.LIKED_UNPLAYED].
     */
    suspend fun songs(
        kind: SmartPlaylist,
        liked: List<Song> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): List<Song> {
        val thisMonth = YearMonth.now()
        val lastMonth = thisMonth.minusMonths(1)
        val totals = HashMap<String, Totals>()
        ListeningStats.monthlyTracks().forEach { (month, tracks) ->
            val recent = month == thisMonth || month == lastMonth
            tracks.forEach { track ->
                if (track.id.isBlank()) return@forEach
                val total = totals.getOrPut(track.id) { Totals(track) }
                total.plays += track.plays
                total.ms += track.ms
                total.last = maxOf(total.last, track.last)
                if (total.entry.art == null) total.entry.art = track.art
                if (total.entry.album == null) total.entry.album = track.album
                if (total.entry.albumId == null) total.entry.albumId = track.albumId
                if (total.entry.artistId == null) total.entry.artistId = track.artistId
                if (recent) {
                    total.recentPlays += track.plays
                    total.recentMs += track.ms
                }
            }
        }

        return when (kind) {
            SmartPlaylist.TOP -> totals.values
                .filter { it.recentPlays > 0 }
                .sortedWith(compareByDescending<Totals> { it.recentPlays }.thenByDescending { it.recentMs })
                .take(TOP_SIZE)
                .map { it.toSong() }

            SmartPlaylist.ON_REPEAT -> totals.values
                .filter { it.recentPlays >= 2 && now - it.last <= 7 * DAY_MS }
                .sortedWith(compareByDescending<Totals> { it.recentPlays }.thenByDescending { it.last })
                .take(REPEAT_SIZE)
                .map { it.toSong() }

            SmartPlaylist.REDISCOVER -> totals.values
                .filter { it.plays >= 3 && it.last > 0L && now - it.last >= 30 * DAY_MS }
                .sortedWith(compareByDescending<Totals> { it.plays }.thenByDescending { it.ms })
                .take(REDISCOVER_SIZE)
                .map { it.toSong() }

            SmartPlaylist.LIKED_UNPLAYED -> liked
                .distinctBy { it.videoId }
                .filter { (totals[it.videoId]?.plays ?: 0) <= 1 }
                .take(LIKED_SIZE)
        }
    }

    private fun Totals.toSong() = entry.asSong()
}
