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
    fun `an unmeasurable candidate is never stored as a rejection`() {
        assertFalse(
            HqVerifyPolicy.shouldPersistRejection(
                referenceAvailable = true,
                accepted = false,
                measured = false,
            ),
        )
        assertTrue(
            HqVerifyPolicy.shouldPersistRejection(
                referenceAvailable = true,
                accepted = false,
                measured = true,
            ),
        )
    }

    @Test
    fun `the fetch size follows a stated bitrate with headroom`() {
        assertEquals(
            4_416_000L,
            HqVerifyPolicy.bytesToFetch(StreamFormat(kbps = 320), seconds = 92.0),
        )
        assertEquals(
            8_832_000L,
            HqVerifyPolicy.bytesToFetch(StreamFormat(kbps = 640), seconds = 92.0),
        )
    }

    @Test
    fun `an unstated bitrate falls back to the flac sized cap`() {
        assertEquals(
            HqVerifyPolicy.UNSTATED_KBPS_MAX_BYTES,
            HqVerifyPolicy.bytesToFetch(null, seconds = 92.0),
        )
        assertEquals(
            HqVerifyPolicy.UNSTATED_KBPS_MAX_BYTES,
            HqVerifyPolicy.bytesToFetch(StreamFormat(kbps = 0), seconds = 92.0),
        )
    }

    @Test
    fun `a non positive window asks for nothing`() {
        assertEquals(0L, HqVerifyPolicy.bytesToFetch(StreamFormat(kbps = 320), seconds = 0.0))
        assertEquals(0L, HqVerifyPolicy.bytesToFetch(StreamFormat(kbps = 320), seconds = -1.0))
        assertEquals(
            0L,
            HqVerifyPolicy.bytesToFetch(StreamFormat(kbps = 320), seconds = Double.NaN),
        )
    }

    @Test
    fun `only a manual request retries a remembered missing reference`() {
        assertTrue(HqVerifyPolicy.shouldRetryMissingReference(manual = true))
        assertFalse(HqVerifyPolicy.shouldRetryMissingReference(manual = false))
    }

    @Test
    fun `a lossless stream fetches at least its PCM rate, whatever kbps it states`() {
        // 16-bit/44.1 kHz stereo is 1411 kbps; a FLAC that states 320 must not cut the head short.
        val flac = StreamFormat(codec = "flac", kbps = 320, sampleRateHz = 44_100, bitDepth = 16)
        assertEquals((1411L * 1_000 / 8 * 92 * 1.2).toLong(), HqVerifyPolicy.bytesToFetch(flac, seconds = 92.0))
    }

    @Test
    fun `an inferred bitrate is not trusted`() {
        val inferred = StreamFormat(kbps = 320, kbpsInferred = true)
        assertEquals(HqVerifyPolicy.UNSTATED_KBPS_MAX_BYTES, HqVerifyPolicy.bytesToFetch(inferred, seconds = 92.0))
    }

    @Test
    fun `hi res lossless is capped`() {
        val hiRes = StreamFormat(codec = "flac", sampleRateHz = 192_000, bitDepth = 24)
        assertEquals(HqVerifyPolicy.LOSSLESS_MAX_BYTES, HqVerifyPolicy.bytesToFetch(hiRes, seconds = 92.0))
    }
}