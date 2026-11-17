package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.DayStats
import com.music.bitchord.data.stats.TrackEntry

/**
 * [KO] F1 version-swap listen
 *
 * A song-version swap (a music-video or cover-video row handing over to its
 * song version mid-play, or back) is the same listen under a new video id.
 * The recorder carries its playthrough to the new id; this moves the part of
 * that playthrough already filed under the old id onto the new id's row, so
 * Recents and Replay show one row with the whole listen instead of two halves.
 *
 * Pure over the open month's track map so it can be tested without a player
 * or a directory. The caller holds the store's lock.
 */
internal object KoListenVersionSwap {

    /**
     * Moves [playedMs] (and one play when [countedPlay]) from [fromId]'s row to
     * [to]'s row in [tracks]. Never moves more than the old row holds. The old
     * row is dropped when nothing is left on it; a row with earlier listening of
     * its own stays. Returns whether anything moved.
     */
    fun move(
        tracks: MutableMap<String, TrackEntry>,
        fromId: String,
        to: Song,
        playedMs: Long,
        countedPlay: Boolean,
        atMs: Long,
    ): Boolean {
        if (to.videoId.isBlank() || fromId == to.videoId) return false
        val from = tracks[fromId] ?: return false
        val ms = playedMs.coerceIn(0L, from.ms.coerceAtLeast(0L))
        val plays = if (countedPlay && from.plays > 0) 1 else 0
        if (ms <= 0L && plays == 0) return false

        // The same fields [com.music.bitchord.data.stats.ListeningStats.record]
        // files a new row with, so the next sample finds this row and only fills
        // in what is still missing.
        val target = tracks.getOrPut(to.videoId) {
            TrackEntry(
                id = to.videoId,
                title = to.title,
                artist = to.artist,
                album = to.albumName,
                albumId = to.albumId,
                artistId = to.artistId,
                art = to.thumbnailUrl,
                durationText = to.durationText,
                isVideo = to.isVideo,
            )
        }
        from.ms -= ms
        from.plays -= plays
        target.ms += ms
        target.plays += plays

        // The playthrough is the newest listening on the old row, so its days
        // are taken newest first ("YYYY-MM-DD" sorts by date).
        var msLeft = ms
        var playsLeft = plays
        for (day in from.days.keys.sortedDescending()) {
            if (msLeft <= 0L && playsLeft <= 0) break
            val source = from.days[day] ?: continue
            val dayMs = minOf(msLeft, source.ms.coerceAtLeast(0L))
            val dayPlays = minOf(playsLeft, source.plays.coerceAtLeast(0))
            if (dayMs <= 0L && dayPlays <= 0) continue
            source.ms -= dayMs
            source.plays -= dayPlays
            target.days.getOrPut(day) { DayStats() }.also {
                it.ms += dayMs
                it.plays += dayPlays
            }
            if (source.ms <= 0L && source.plays <= 0) from.days.remove(day)
            msLeft -= dayMs
            playsLeft -= dayPlays
        }

        target.last = maxOf(target.last, atMs)
        if (from.ms <= 0L && from.plays <= 0) tracks.remove(fromId)
        return true
    }
}
