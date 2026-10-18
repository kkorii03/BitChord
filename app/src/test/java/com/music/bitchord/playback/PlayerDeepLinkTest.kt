package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDeepLinkTest {

    @Test
    fun `a widget tap on a fresh launch opens the player`() {
        assertTrue(
            PlayerDeepLink.shouldExpandOnIntent(
                requested = true,
                recreating = false,
                launchedFromHistory = false,
            ),
        )
    }

    @Test
    fun `recreating the activity never reopens the player`() {
        assertFalse(
            PlayerDeepLink.shouldExpandOnIntent(
                requested = true,
                recreating = true,
                launchedFromHistory = false,
            ),
        )
    }

    @Test
    fun `a task relaunched from recents never reopens the player`() {
        assertFalse(
            PlayerDeepLink.shouldExpandOnIntent(
                requested = true,
                recreating = false,
                launchedFromHistory = true,
            ),
        )
    }

    @Test
    fun `an intent without the extra never opens the player`() {
        for (recreating in listOf(false, true)) {
            for (history in listOf(false, true)) {
                assertFalse(
                    PlayerDeepLink.shouldExpandOnIntent(
                        requested = false,
                        recreating = recreating,
                        launchedFromHistory = history,
                    ),
                )
            }
        }
    }
}
