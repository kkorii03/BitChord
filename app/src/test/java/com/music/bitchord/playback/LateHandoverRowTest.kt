package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A late song-version handover only commits onto the exact row it was
 * started for (review F1): a "Revert to original" in the alignment window
 * keeps the id and the index but changes the URI, and must win.
 */
class LateHandoverRowTest {

    private val mv = LateHandoverRow("mv1", "bitchord://watch?v=mv1&m=1")

    @Test
    fun `the same row still matches`() {
        assertTrue(mv.matches("mv1", "bitchord://watch?v=mv1&m=1"))
    }

    @Test
    fun `a revert to the original upload keeps the id but is another row`() {
        assertFalse(
            mv.matches("mv1", "bitchord://watch?v=mv1&m=1&$DIRECT_YOUTUBE_PARAMETER=1&q=original"),
        )
    }

    @Test
    fun `a manual switch or a skip is another row`() {
        assertFalse(mv.matches("aud1", "bitchord://watch?v=aud1"))
        assertFalse(mv.matches(null, null))
    }

    @Test
    fun `a downloaded row without a uri is compared as such`() {
        val local = LateHandoverRow("mv1", null)
        assertTrue(local.matches("mv1", null))
        assertFalse(local.matches("mv1", "bitchord://watch?v=mv1&m=1"))
    }
}
