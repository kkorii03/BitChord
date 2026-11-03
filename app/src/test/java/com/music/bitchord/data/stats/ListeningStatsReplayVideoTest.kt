package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay's top-songs rows are tapped to play (ReplayScreen). They used to be
 * rebuilt from TrackEntry without its video flag, so a downloaded cover played
 * as a plain song and logged "song version skipped (not a video)".
 */
class ListeningStatsReplayVideoTest {

    @Test
    fun `a video track stays a video on the replay top-songs chart`() {
        val summary = summaryOf(
            TrackEntry(
                id = "mv1",
                title = "Some MV",
                artist = "Channel",
                isVideo = true,
                ms = 60_000,
                plays = 1,
            ),
        )

        val song: Song = summary.songs.single().song
        assertTrue("a video row must stay a video out of Replay", song.isVideo)
        assertTrue(song.isVideoOrigin)
    }

    @Test
    fun `an ordinary track stays a song on the replay chart`() {
        val summary = summaryOf(
            TrackEntry(id = "song1", title = "밤편지", artist = "IU", ms = 60_000, plays = 1),
        )

        val song = summary.songs.single().song
        assertTrue(!song.isVideo)
    }

    /** The Replay merge is private; drive it the way summary() does. */
    private fun summaryOf(entry: TrackEntry): ReplaySummary {
        val mergedClass = Class.forName("com.music.bitchord.data.stats.ListeningStats\$MergedBucket")
        val merged = mergedClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        mergedClass
            .getDeclaredMethod("add", StoredBucket::class.java)
            .apply { isAccessible = true }
            .invoke(merged, StoredBucket(month = "2026-11", tracks = listOf(entry)))
        return mergedClass
            .getDeclaredMethod("toSummary", ReplayPeriod::class.java, LocalDate::class.java)
            .apply { isAccessible = true }
            .invoke(merged, ReplayPeriod.THIS_MONTH, LocalDate.of(2026, 11, 3)) as ReplaySummary
    }
}
