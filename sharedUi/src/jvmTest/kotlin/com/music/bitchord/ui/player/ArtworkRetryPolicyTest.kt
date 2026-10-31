package com.music.bitchord.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cover retry cap must bound failures, not block a normal URL. */
class ArtworkRetryPolicyTest {

    private val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg"

    @Test
    fun `a normal url below the cap is allowed one more try`() {
        assertTrue(artworkRetryAllowed(url, failed = true, attempt = 0, maxRetries = 3))
        assertTrue(artworkRetryAllowed(url, failed = true, attempt = 2, maxRetries = 3))
    }

    @Test
    fun `the cap stops retrying a cover that keeps failing`() {
        assertFalse(artworkRetryAllowed(url, failed = true, attempt = 3, maxRetries = 3))
    }

    @Test
    fun `a missing url is never retried`() {
        assertFalse(artworkRetryAllowed(null, failed = true, attempt = 0, maxRetries = 3))
        assertFalse(artworkRetryAllowed(" ", failed = true, attempt = 0, maxRetries = 3))
    }

    @Test
    fun `a loaded cover has nothing to retry`() {
        assertFalse(artworkRetryAllowed(url, failed = false, attempt = 0, maxRetries = 3))
    }
}
