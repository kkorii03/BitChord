package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDeepLinkTest {

    @Test
    fun `a shared link that is not a video id is refused`() {
        // "links:?mv IllegalStateException" from a smoke run: the word went to
        // the catalogue as if it were an id.
        for (raw in listOf("mv", "?mv", "hq", "cover", "", "   ", "dQw4w9WgXc", "dQw4w9WgXcQQ", "dQw4w9WgXc=")) {
            assertNull(raw, videoIdOrNull(raw))
        }
    }

    @Test
    fun `a real video id passes through`() {
        assertEquals("-RJSbO8UZVY", videoIdOrNull("-RJSbO8UZVY"))
        assertEquals("dQw4w9WgXcQ", videoIdOrNull("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", videoIdOrNull(" dQw4w9WgXcQ\n"))
        assertEquals("KPM_BYl-EaQ", videoIdOrNull("KPM_BYl-EaQ"))
    }

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
