package com.music.bitchord.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app search list is local: private mode keeps YouTube history clean but
 * must not take Recents away. Pinned here as well as in the shared sink matrix
 * because the search list is written from the UI path, away from the player's
 * own records.
 */
class SearchHistoryPauseTest {

    @Test
    fun `private listening keeps recording searches`() {
        assertTrue(
            PrivateListening.allows(ListeningSink.SEARCH_HISTORY, privateListening = true),
        )
    }

    @Test
    fun `ordinary listening keeps recording searches`() {
        assertTrue(
            PrivateListening.allows(ListeningSink.SEARCH_HISTORY, privateListening = false),
        )
    }

    @Test
    fun `a private session is still on just before six hours`() {
        val since = 1_000L
        assertTrue(
            PrivateListening.isActive(
                enabled = true,
                sinceMs = since,
                nowMs = since + PrivateListening.SESSION_MS - 1,
            ),
        )
    }

    @Test
    fun `a private session expires at six hours`() {
        val since = 1_000L
        assertFalse(
            PrivateListening.isActive(
                enabled = true,
                sinceMs = since,
                nowMs = since + PrivateListening.SESSION_MS,
            ),
        )
    }

    @Test
    fun `a switched-off session is inactive even inside the window`() {
        val since = 1_000L
        assertFalse(
            PrivateListening.isActive(
                enabled = false,
                sinceMs = since,
                nowMs = since + PrivateListening.SESSION_MS / 2,
            ),
        )
    }
}
