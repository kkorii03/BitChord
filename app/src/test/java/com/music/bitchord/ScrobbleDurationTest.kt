package com.music.bitchord

import com.music.bitchord.data.scrobbling.ScrobbleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Scrobbling needs a length in whole seconds. The old parser accepted only
 * `M:SS`, so an hour-plus track read as zero and was never submitted.
 */
class ScrobbleDurationTest {

    @Test
    fun minuteSecondRowsParse() {
        assertEquals(225, ScrobbleManager.durationSeconds("3:45"))
        assertEquals(62, ScrobbleManager.durationSeconds("1:02"))
    }

    @Test
    fun hourPlusRowsAreNoLongerZero() {
        assertEquals(3_753, ScrobbleManager.durationSeconds("1:02:33"))
    }

    @Test
    fun nothingUsableIsNullRatherThanZero() {
        assertNull(ScrobbleManager.durationSeconds(null))
        assertNull(ScrobbleManager.durationSeconds(""))
        assertNull(ScrobbleManager.durationSeconds("LIVE"))
    }
}
