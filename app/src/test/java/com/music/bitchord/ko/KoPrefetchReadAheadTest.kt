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

    /**
     * anr1120: prefetchQueue runs on the main thread at every track change.
     * The SimpleCache reads it needs (heldByYouTube and the key factory) wait
     * on the cache monitor that loader threads hold across index writes, so
     * they must run inside the IO job, not before it is launched.
     */
    @Test
    fun theYouTubeFilledCacheReadRunsInsideTheIoJob() {
        val source = java.io.File("src/main/java/com/music/bitchord/ko/KoPrefetchQueue.kt").readText()
        val body = source.substringAfter("fun prefetchQueue(")
        val launch = body.indexOf("scope.launch {")
        val read = body.indexOf("AudioCache.heldByYouTube(")
        assertTrue("prefetchQueue launches its job", launch >= 0)
        assertTrue("heldByYouTube is still read", read >= 0)
        assertTrue("heldByYouTube must be read inside scope.launch, off the main thread", read > launch)
    }
}
