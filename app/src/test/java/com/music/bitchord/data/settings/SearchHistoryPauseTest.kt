package com.music.bitchord.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search is one of the sinks private listening has to silence. Pinned here as
 * well as in the shared sink matrix because the search list is written from the
 * UI path, away from the player's own records.
 */
class SearchHistoryPauseTest {

    @Test
    fun `private listening pauses search recording`() {
        assertFalse(
            PrivateListening.allows(ListeningSink.SEARCH_HISTORY, privateListening = true),
        )
    }

    @Test
    fun `ordinary listening keeps recording searches`() {
        assertTrue(
            PrivateListening.allows(ListeningSink.SEARCH_HISTORY, privateListening = false),
        )
    }
}
