package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Test

class HqReplayPolicyTest {

    @Test
    fun `lossy cache with a remembered original resolves instead of using the cache`() {
        assertEquals(
            HqReplayPolicy.Choice.RESOLVE,
            HqReplayPolicy.choose(
                cacheIsLossless = false,
                hasRememberedOriginal = true,
                offline = false,
            ),
        )
    }

    @Test
    fun `a lossless cache copy is kept even when an original is remembered`() {
        assertEquals(
            HqReplayPolicy.Choice.CACHE,
            HqReplayPolicy.choose(
                cacheIsLossless = true,
                hasRememberedOriginal = true,
                offline = false,
            ),
        )
    }

    @Test
    fun `without a remembered original the cache is used`() {
        assertEquals(
            HqReplayPolicy.Choice.CACHE,
            HqReplayPolicy.choose(
                cacheIsLossless = false,
                hasRememberedOriginal = false,
                offline = false,
            ),
        )
    }

    @Test
    fun `offline always uses the cache`() {
        assertEquals(
            HqReplayPolicy.Choice.CACHE,
            HqReplayPolicy.choose(
                cacheIsLossless = false,
                hasRememberedOriginal = true,
                offline = true,
            ),
        )
    }
}
