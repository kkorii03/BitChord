package com.music.bitchord

import com.music.bitchord.ui.player.marqueeShouldCrawl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarqueeGateTest {
    @Test
    fun `crawls only when playing on screen with motion allowed`() {
        assertTrue(marqueeShouldCrawl(running = true, reduceAnimation = false, lowPower = false, foreground = true))
    }

    @Test
    fun `stops for a paused player`() {
        assertFalse(marqueeShouldCrawl(running = false, reduceAnimation = false, lowPower = false, foreground = true))
    }

    @Test
    fun `stops while the app is in the background`() {
        assertFalse(marqueeShouldCrawl(running = true, reduceAnimation = false, lowPower = false, foreground = false))
    }

    @Test
    fun `stops for reduced motion or low power`() {
        assertFalse(marqueeShouldCrawl(running = true, reduceAnimation = true, lowPower = false, foreground = true))
        assertFalse(marqueeShouldCrawl(running = true, reduceAnimation = false, lowPower = true, foreground = true))
    }
}
