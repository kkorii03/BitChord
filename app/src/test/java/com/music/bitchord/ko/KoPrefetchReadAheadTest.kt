package com.music.bitchord.ko

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The byte half of read-ahead must not depend on substitution being considered. */
class KoPrefetchReadAheadTest {

    @Test
    fun readAheadFillsPartiallyCachedNextTrackWhenSubstitutionIsOff() {
        assertTrue(
            readAheadBytesAllowed(
                metered = false,
                lowPower = false,
                substitutable = false,
                substitutePinned = false,
                pinnedToYouTube = false,
                playerHoldsHead = false,
            ),
        )
    }

    @Test
    fun aSubstituteThatIsNotPinnedDoesNotWriteBytes() {
        assertFalse(
            readAheadBytesAllowed(
                metered = false,
                lowPower = false,
                substitutable = true,
                substitutePinned = false,
                pinnedToYouTube = false,
                playerHoldsHead = false,
            ),
        )
        assertTrue(
            readAheadBytesAllowed(
                metered = false,
                lowPower = false,
                substitutable = true,
                substitutePinned = true,
                pinnedToYouTube = false,
                playerHoldsHead = false,
            ),
        )
    }

    @Test
    fun pinnedOriginalPlayerHeldAndMeteredStillRefuseBytes() {
        assertFalse(
            readAheadBytesAllowed(
                metered = false,
                lowPower = false,
                substitutable = false,
                substitutePinned = false,
                pinnedToYouTube = true,
                playerHoldsHead = false,
            ),
        )
        assertFalse(
            readAheadBytesAllowed(
                metered = false,
                lowPower = false,
                substitutable = false,
                substitutePinned = false,
                pinnedToYouTube = false,
                playerHoldsHead = true,
            ),
        )
        assertFalse(
            readAheadBytesAllowed(
                metered = true,
                lowPower = false,
                substitutable = false,
                substitutePinned = false,
                pinnedToYouTube = false,
                playerHoldsHead = false,
            ),
        )
        assertFalse(
            readAheadBytesAllowed(
                metered = false,
                lowPower = true,
                substitutable = false,
                substitutePinned = false,
                pinnedToYouTube = false,
                playerHoldsHead = false,
            ),
        )
    }
}
