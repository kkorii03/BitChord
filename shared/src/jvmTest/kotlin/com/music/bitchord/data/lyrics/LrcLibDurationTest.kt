package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** A search without a length must keep the provider's relevance order. */
class LrcLibDurationTest {

    private val candidates = listOf(
        210.0 to "album cut",
        35.0 to "short radio edit",
        206.0 to "single",
    )

    @Test
    fun anUnknownLengthDoesNotPreferTheShortestHit() {
        assertEquals("album cut", LrcLib.pickByDuration(candidates, seconds = 0))
    }

    @Test
    fun aKnownLengthStillPicksTheClosestRecording() {
        assertEquals("single", LrcLib.pickByDuration(candidates, seconds = 205))
    }
}
