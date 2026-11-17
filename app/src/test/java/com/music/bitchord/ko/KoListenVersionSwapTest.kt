package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.DayStats
import com.music.bitchord.data.stats.TrackEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review F1: moving a version swap's playthrough between rows of the open month. */
class KoListenVersionSwapTest {

    private val song = Song(videoId = "au", title = "Song", artist = "Singer", thumbnailUrl = null, isVideo = false)

    private fun videoRow(ms: Long, plays: Int, days: Map<String, DayStats>) = TrackEntry(
        id = "mv",
        title = "Song (video)",
        artist = "Singer",
        isVideo = true,
        ms = ms,
        plays = plays,
        last = 10L,
        days = days.toMutableMap(),
    )

    @Test
    fun `a playthrough that crossed midnight moves newest day first and the old row goes`() {
        val tracks = mutableMapOf(
            "mv" to videoRow(
                ms = 30_000L,
                plays = 1,
                days = mapOf("2026-11-16" to DayStats(10_000L, 0), "2026-11-17" to DayStats(20_000L, 1)),
            ),
        )

        assertTrue(KoListenVersionSwap.move(tracks, "mv", song, playedMs = 30_000L, countedPlay = true, atMs = 99L))

        assertNull(tracks["mv"])
        val row = tracks.getValue("au")
        assertEquals(30_000L, row.ms)
        assertEquals(1, row.plays)
        assertEquals(99L, row.last)
        assertEquals("Song", row.title)
        assertEquals(false, row.isVideo)
        assertEquals(DayStats(20_000L, 1), row.days["2026-11-17"])
        assertEquals(DayStats(10_000L, 0), row.days["2026-11-16"])
    }

    @Test
    fun `never more than the old row holds, and an existing row is added to`() {
        val tracks = mutableMapOf(
            "mv" to videoRow(ms = 5_000L, plays = 0, days = mapOf("2026-11-17" to DayStats(5_000L, 0))),
            "au" to TrackEntry(id = "au", title = "Song", ms = 100_000L, plays = 2, last = 50L),
        )

        assertTrue(KoListenVersionSwap.move(tracks, "mv", song, playedMs = 40_000L, countedPlay = true, atMs = 99L))

        assertNull(tracks["mv"])
        assertEquals(105_000L, tracks.getValue("au").ms)
        assertEquals("the old row had no play to move", 2, tracks.getValue("au").plays)
    }

    @Test
    fun `nothing to move leaves the map alone`() {
        val tracks = mutableMapOf("mv" to videoRow(ms = 0L, plays = 0, days = emptyMap()))
        assertFalse(KoListenVersionSwap.move(tracks, "mv", song, playedMs = 8_000L, countedPlay = false, atMs = 99L))
        assertFalse(KoListenVersionSwap.move(tracks, "missing", song, playedMs = 8_000L, countedPlay = false, atMs = 99L))
        assertFalse(KoListenVersionSwap.move(tracks, "au", song, playedMs = 8_000L, countedPlay = false, atMs = 99L))
        assertEquals(setOf("mv"), tracks.keys)
    }
}
