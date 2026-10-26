package com.music.bitchord.ui

import com.music.bitchord.data.lyrics.LyricsFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The provider sheet's row states must not collapse into one another. */
class LyricsProviderStateEdgeTest {

    @Test
    fun everyFinishedMissStateIsAMissAndNeverFound() {
        val misses = listOf(
            LyricsProviderState.NOT_FOUND,
            LyricsProviderState.TIMEOUT,
            LyricsProviderState.NETWORK_ERROR,
            LyricsProviderState.BLOCKED,
        )

        for (state in misses) {
            assertTrue("$state must count as a miss", state.isMiss)
            assertNotEquals(LyricsProviderState.FOUND, state)
            assertNotEquals(LyricsProviderState.FETCHING, state)
            assertNotEquals(LyricsProviderState.NOT_FETCHED, state)
        }
    }

    @Test
    fun activeAndFoundStatesAreNotMisses() {
        assertFalse(LyricsProviderState.NOT_FETCHED.isMiss)
        assertFalse(LyricsProviderState.FETCHING.isMiss)
        assertFalse(LyricsProviderState.FOUND.isMiss)
    }

    @Test
    fun everyFailureEnumMapsToAMissState() {
        for (failure in LyricsFailure.values()) {
            val state = LyricsProviderState.fromFailure(failure)
            assertTrue("$failure mapped to $state, which is not a miss", state.isMiss)
            assertNotEquals(LyricsProviderState.NOT_FETCHED, state)
            assertNotEquals(LyricsProviderState.FETCHING, state)
            assertNotEquals(LyricsProviderState.FOUND, state)
        }
    }

    @Test
    fun failureReasonsStayDistinct() {
        assertNotEquals(
            LyricsProviderState.fromFailure(LyricsFailure.BLOCKED),
            LyricsProviderState.fromFailure(LyricsFailure.NETWORK),
        )
        assertNotEquals(
            LyricsProviderState.fromFailure(LyricsFailure.TIMEOUT),
            LyricsProviderState.fromFailure(LyricsFailure.NOT_FOUND),
        )
        assertNotEquals(
            LyricsProviderState.fromFailure(LyricsFailure.NETWORK),
            LyricsProviderState.fromFailure(LyricsFailure.NOT_FOUND),
        )
    }

    @Test
    fun aMissingFailureIsANotFoundMiss() {
        val state = LyricsProviderState.fromFailure(null)
        assertEquals(LyricsProviderState.NOT_FOUND, state)
        assertTrue(state.isMiss)
    }
}
