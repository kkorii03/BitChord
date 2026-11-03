package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 11-03 phone logs: an InnerTubeX client minted a URL whose probe was REFUSED,
 * the direct path was benched (never asked for this track), the proxy failed,
 * and the resolver still skipped extraction. A refusal from one InnerTubeX
 * client is not a verdict about the recording, so extraction must not be
 * dropped unless the direct path itself refused too.
 */
class BlockedStartFirstRefusalTest {

    @Test
    fun `an InnerTubeX refusal alone with a benched direct path still tries extraction`() {
        assertFalse(
            BlockedStartPolicy.shouldSkipExtractionAfterLadder(
                blockedSignal = true,
                directRefused = false,
                proxyFailed = true,
            ),
            "InnerTubeX refused but the direct path was benched, not refused: extraction can still answer",
        )
    }

    @Test
    fun `an InnerTubeX refusal with no proxy attempt still tries extraction`() {
        assertFalse(
            BlockedStartPolicy.shouldSkipExtractionAfterLadder(
                blockedSignal = true,
                directRefused = false,
                proxyFailed = false,
            ),
        )
    }

    @Test
    fun `a direct refusal plus a failed proxy still skips extraction`() {
        assertTrue(
            BlockedStartPolicy.shouldSkipExtractionAfterLadder(
                blockedSignal = true,
                directRefused = true,
                proxyFailed = true,
            ),
            "both routes refused the track itself, so extraction reads the same refusal",
        )
    }

    @Test
    fun `no blocked signal never skips extraction`() {
        assertFalse(
            BlockedStartPolicy.shouldSkipExtractionAfterLadder(
                blockedSignal = false,
                directRefused = true,
                proxyFailed = true,
            ),
        )
    }
}
