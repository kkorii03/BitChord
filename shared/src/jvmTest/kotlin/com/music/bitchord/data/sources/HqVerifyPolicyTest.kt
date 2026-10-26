package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HqVerifyPolicyTest {

    @Test
    fun `a manual request ignores the retry cooldown`() {
        assertEquals(
            0L,
            HqVerifyPolicy.cooldownRemaining(
                nowMs = 1_000L,
                lastAcceptedAtMs = 900L,
                cooldownMs = 30_000L,
                manual = true,
            ),
        )
        assertEquals(
            29_900L,
            HqVerifyPolicy.cooldownRemaining(
                nowMs = 1_000L,
                lastAcceptedAtMs = 900L,
                cooldownMs = 30_000L,
                manual = false,
            ),
        )
    }

    @Test
    fun `no cooldown is charged before the first accepted attempt`() {
        assertEquals(
            0L,
            HqVerifyPolicy.cooldownRemaining(
                nowMs = 9_000_000L,
                lastAcceptedAtMs = null,
                cooldownMs = 30_000L,
                manual = false,
            ),
        )
    }

    @Test
    fun `a missing reference is never stored as a rejection`() {
        assertFalse(HqVerifyPolicy.shouldPersistRejection(referenceAvailable = false, accepted = false))
        assertTrue(HqVerifyPolicy.shouldPersistRejection(referenceAvailable = true, accepted = false))
        assertFalse(HqVerifyPolicy.shouldPersistRejection(referenceAvailable = true, accepted = true))
    }

    @Test
    fun `only a manual request retries a remembered missing reference`() {
        assertTrue(HqVerifyPolicy.shouldRetryMissingReference(manual = true))
        assertFalse(HqVerifyPolicy.shouldRetryMissingReference(manual = false))
    }
}
