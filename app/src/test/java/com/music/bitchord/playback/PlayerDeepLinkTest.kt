package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDeepLinkTest {

    @Test
    fun `a widget tap on a fresh launch opens the player`() {
        assertTrue(PlayerDeepLink.shouldExpandOnIntent(requested = true, recreating = false))
    }

    @Test
    fun `recreating the activity never reopens the player`() {
        assertFalse(PlayerDeepLink.shouldExpandOnIntent(requested = true, recreating = true))
    }

    @Test
    fun `an intent without the extra never opens the player`() {
        assertFalse(PlayerDeepLink.shouldExpandOnIntent(requested = false, recreating = false))
        assertFalse(PlayerDeepLink.shouldExpandOnIntent(requested = false, recreating = true))
    }
}
