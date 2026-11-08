package com.music.bitchord.playback.smart

import com.music.bitchord.data.DiagnosticsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-09 phone vc184: 6rS7OUGXUik was swapped before audio to G5Lj9tsCK6A in
 * place. The swap books no transition, so the playing row stayed 6rS7OUGXUik
 * and every hearing for G5Lj9tsCK6A was skipped as "no longer wanted": base
 * none all song, lyrics 0.52 s early. Same on 65RQZICv4iI and 8gyLR4NfMiI.
 */
class SwapHearingTest {

    @After
    fun clear() {
        DiagnosticsState.currentTrackId = null
        DiagnosticsState.nextTrackId = null
    }

    @Test
    fun `the copy an in-place swap put on the row is heard for`() {
        DiagnosticsState.currentTrackId = "6rS7OUGXUik"
        DiagnosticsState.nextTrackId = "mgFVZfGBEpM"
        DiagnosticsState.currentSwapId = "G5Lj9tsCK6A"
        assertTrue(LyricsTiming.hearingWantedNow("G5Lj9tsCK6A"))
        assertFalse(SongHearing.rowGone("G5Lj9tsCK6A"))
        assertTrue(LyricsTiming.hearingWantedNow("mgFVZfGBEpM"))
        // The replaced video never sounds: still not heard for.
        assertFalse(LyricsTiming.hearingWantedNow("6rS7OUGXUik"))
        assertTrue(SongHearing.rowGone("6rS7OUGXUik"))
    }

    @Test
    fun `without a swap the row's own id is the one heard for`() {
        DiagnosticsState.currentTrackId = "6rS7OUGXUik"
        DiagnosticsState.nextTrackId = "mgFVZfGBEpM"
        assertEquals("6rS7OUGXUik", DiagnosticsState.soundingTrackId)
        assertTrue(LyricsTiming.hearingWantedNow("6rS7OUGXUik"))
        assertFalse(LyricsTiming.hearingWantedNow("G5Lj9tsCK6A"))
    }

    @Test
    fun `a new current row forgets the swap`() {
        DiagnosticsState.currentTrackId = "6rS7OUGXUik"
        DiagnosticsState.currentSwapId = "G5Lj9tsCK6A"
        DiagnosticsState.currentTrackId = "mgFVZfGBEpM"
        assertNull(DiagnosticsState.currentSwapId)
        assertFalse(LyricsTiming.hearingWantedNow("G5Lj9tsCK6A"))
    }
}
