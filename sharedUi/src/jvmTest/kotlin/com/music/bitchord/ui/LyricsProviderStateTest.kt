package com.music.bitchord.ui

import com.music.bitchord.data.lyrics.LyricsFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** The provider sheet's four miss states must stay distinct in the UI. */
class LyricsProviderStateTest {

    @Test
    fun everyFailureReasonMapsToItsOwnRowState() {
        assertEquals(LyricsProviderState.NOT_FOUND, LyricsProviderState.fromFailure(null))
        assertEquals(LyricsProviderState.NOT_FOUND, LyricsProviderState.fromFailure(LyricsFailure.NOT_FOUND))
        assertEquals(LyricsProviderState.TIMEOUT, LyricsProviderState.fromFailure(LyricsFailure.TIMEOUT))
        assertEquals(LyricsProviderState.NETWORK_ERROR, LyricsProviderState.fromFailure(LyricsFailure.NETWORK))
        assertEquals(LyricsProviderState.BLOCKED, LyricsProviderState.fromFailure(LyricsFailure.BLOCKED))
    }

    @Test
    fun everyFailureStateCountsAsAMiss() {
        assertEquals(true, LyricsProviderState.NOT_FOUND.isMiss)
        assertEquals(true, LyricsProviderState.TIMEOUT.isMiss)
        assertEquals(true, LyricsProviderState.NETWORK_ERROR.isMiss)
        assertEquals(true, LyricsProviderState.BLOCKED.isMiss)
        assertEquals(false, LyricsProviderState.FOUND.isMiss)
        assertEquals(false, LyricsProviderState.FETCHING.isMiss)
        assertEquals(false, LyricsProviderState.NOT_FETCHED.isMiss)
    }
}
