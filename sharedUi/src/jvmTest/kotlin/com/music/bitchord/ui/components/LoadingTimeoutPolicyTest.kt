package com.music.bitchord.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingTimeoutPolicyTest {
    @Test
    fun browsePagesShowTheirRetryBeforeTheTourMovesOn() {
        assertTrue(BROWSE_LOADING_TIMEOUT_MS < PAGE_LOADING_TIMEOUT_MS)
        assertTrue(BROWSE_LOADING_TIMEOUT_MS <= 5_000L)
    }
}
