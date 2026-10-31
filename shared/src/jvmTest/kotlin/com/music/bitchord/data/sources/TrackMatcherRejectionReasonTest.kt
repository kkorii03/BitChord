package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `hq-search` verdict has to use the same runtime window the real filter
 * applied. The upgrade path is stricter than a substitute search, so a row
 * three seconds off is a `duration` rejection there even though the wider
 * substitute default would accept it.
 */
class TrackMatcherRejectionReasonTest {

    private fun candidate(duration: String) = Song(
        videoId = "flowers-1",
        title = "Flowers",
        artist = "Miley Cyrus",
        thumbnailUrl = null,
        durationText = duration,
    )

    private val target = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)

    @Test
    fun `a three-second drift is a duration rejection under the upgrade window`() {
        assertEquals(
            "duration",
            TrackMatcher.rejectionReason(
                candidate("3:23"),
                target,
                strictLength = true,
                driftSec = 2,
            ),
        )
    }

    @Test
    fun `the same row is a match under the wider substitute window`() {
        assertEquals(
            "match",
            TrackMatcher.rejectionReason(candidate("3:23"), target, strictLength = true),
        )
    }
}
