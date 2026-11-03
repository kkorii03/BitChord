package com.music.bitchord.data.innertube.potoken

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real S24 logs (11-03) warned on every page that a minted 556-byte PoToken
 * was "outside expected range (110-128)". The token was still used and playback
 * worked, so the upper bound was stale, not a broken mint.
 */
class PoTokenPageTokenSizeTest {

    @Test
    fun `a long valid token is not warned as out of range`() {
        assertFalse(
            "BotGuard now returns tokens past the old 110-128 range",
            PO_TOKEN_HTML.contains("result.length > 140"),
        )
        assertFalse(
            "the stale 110-128 wording must not reach the diagnostic log",
            PO_TOKEN_HTML.contains("may be outside expected range (110-128)"),
        )
        assertTrue(
            "a truncated mint is still worth a warning",
            PO_TOKEN_HTML.contains("result.length < 100"),
        )
    }
}
