package com.music.bitchord.playback.smart

import com.music.bitchord.data.sources.HqVerifyPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored rejection is a property of an automatic attempt, not a verdict the
 * listener cannot ask to revisit. Tapping the download's upgrade button again
 * must re-measure the pair; if it is still wrong, the comparison rejects it.
 */
class HqVerifyManualRetryTest {

    @Test
    fun `an automatic attempt still trusts a stored rejection`() {
        assertFalse(HqVerifyPolicy.shouldRetryRejected(manual = false))
    }

    @Test
    fun `a manual request retries a previously rejected pair`() {
        assertTrue(HqVerifyPolicy.shouldRetryRejected(manual = true))
    }
}
