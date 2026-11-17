package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C4c-4: the gated fallback's continuation tokens are bounded by dropping the
 * oldest one, not by wiping every token. A wipe broke "load more" on lists that
 * were still on screen and lost the anonymous flag of official playlists.
 */
class YouTubeFallbackContinuationTest {

    @Test
    fun `going past the limit drops only the oldest token`() {
        val limit = YouTubeFallback.CONTINUATION_LIMIT
        val tokens = (1..limit + 2).map { "c4c4-evict-$it" }
        tokens.forEachIndexed { i, token ->
            YouTubeFallback.rememberContinuation(token, anonymous = i == limit)
        }

        assertFalse(YouTubeFallback.isContinuation(tokens[0]))
        assertFalse(YouTubeFallback.isContinuation(tokens[1]))
        val kept = tokens.drop(2)
        assertEquals(limit, kept.count(YouTubeFallback::isContinuation))
        // The anonymous flag of a surviving token is still there.
        assertTrue(YouTubeFallback.isAnonymousContinuation(tokens[limit]))
        assertFalse(YouTubeFallback.isAnonymousContinuation(tokens[limit + 1]))
    }
}
