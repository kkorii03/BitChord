package com.music.bitchord.data.innertube.potoken

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outer timeout must outlive the page's own init budget (forty-five
 * seconds) plus the first mint (fifteen). When both were forty-five, a cold
 * page that used its full allowance was cancelled from outside before it could
 * mint, and no PoToken was ever produced on a slow connection.
 */
class PoTokenGeneratorTimeoutBudgetTest {

    @Test
    fun `the outer budget covers page creation and the first mint`() {
        assertTrue(
            "outer ${PoTokenGenerator.POTOKEN_TIMEOUT_MS}ms must exceed 45s init + 15s mint",
            PoTokenGenerator.POTOKEN_TIMEOUT_MS > 45_000L + 15_000L,
        )
    }
}
