package com.music.bitchord.playback

import com.music.bitchord.data.sources.HqReplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class HqReplayPolicyTest {

    @Test
    fun `lossy cache with a remembered original resolves`() {
        assertEquals(
            HqReplayPolicy.Choice.RESOLVE,
            HqReplayPolicy.choose(cacheIsLossless = false, hasRememberedOriginal = true, offline = false),
        )
    }

    @Test
    fun `lossless cache is kept`() {
        assertEquals(
            HqReplayPolicy.Choice.CACHE,
            HqReplayPolicy.choose(cacheIsLossless = true, hasRememberedOriginal = true, offline = false),
        )
    }

    @Test
    fun `no remembered original uses the cache`() {
        assertEquals(
            HqReplayPolicy.Choice.CACHE,
            HqReplayPolicy.choose(cacheIsLossless = false, hasRememberedOriginal = false, offline = false),
        )
    }

    @Test
    fun `offline uses the cache`() {
        assertEquals(
            HqReplayPolicy.Choice.CACHE,
            HqReplayPolicy.choose(cacheIsLossless = false, hasRememberedOriginal = true, offline = true),
        )
    }
}
