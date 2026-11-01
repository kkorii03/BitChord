package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The URL warm-up runs after the read-ahead grace delay, and power saving or a
 * metered connection can begin during it. It must re-read that state there: the
 * byte fetch decided before the delay skips itself, so the immediate next track
 * still needs its URL, and the old lookahead must not keep walking the queue.
 */
class CacheUrlWarmupAfterDelayTest {

    private val queue = listOf("next", "after1", "after2")

    @Test
    fun `low power that begins during the delay keeps the immediate next URL`() {
        assertEquals(
            listOf("next"),
            BatteryPolicy.cacheUrlWarmupTargets(
                candidates = queue,
                metered = false,
                lowPower = true,
                queueLookahead = 1,
                bytesPlanned = true,
            ),
        )
    }

    @Test
    fun `metering that begins during the delay keeps the immediate next URL`() {
        assertEquals(
            listOf("next"),
            BatteryPolicy.cacheUrlWarmupTargets(
                candidates = queue,
                metered = true,
                lowPower = false,
                queueLookahead = 1,
                bytesPlanned = true,
            ),
        )
    }

    @Test
    fun `ordinary power still skips the next track whose bytes are being fetched`() {
        assertEquals(
            listOf("after1"),
            BatteryPolicy.cacheUrlWarmupTargets(
                candidates = queue,
                metered = false,
                lowPower = false,
                queueLookahead = 1,
                bytesPlanned = true,
            ),
        )
    }

    @Test
    fun `bytes that were never planned keep the next track and stop at one in low power`() {
        assertEquals(
            listOf("next"),
            BatteryPolicy.cacheUrlWarmupTargets(
                candidates = queue,
                metered = false,
                lowPower = true,
                queueLookahead = 1,
                bytesPlanned = false,
            ),
        )
    }

    @Test
    fun `ordinary power with no byte fetch warms the configured lookahead`() {
        assertEquals(
            queue,
            BatteryPolicy.cacheUrlWarmupTargets(
                candidates = queue,
                metered = false,
                lowPower = false,
                queueLookahead = 2,
                bytesPlanned = false,
            ),
        )
    }

    @Test
    fun `an empty queue warms nothing`() {
        assertEquals(
            emptyList<String>(),
            BatteryPolicy.cacheUrlWarmupTargets(
                candidates = emptyList<String>(),
                metered = false,
                lowPower = false,
                queueLookahead = 1,
                bytesPlanned = true,
            ),
        )
    }
}
