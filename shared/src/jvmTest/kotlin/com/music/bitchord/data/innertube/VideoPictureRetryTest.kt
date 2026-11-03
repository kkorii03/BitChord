package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A definite failure is settled at once; a network one gets a few asks. */
class VideoPictureRetryTest {

    @Test
    fun `a definite failure is not asked again`() {
        assertTrue(VideoPictureRetry.giveUp(attempts = 1, transient = false))
    }

    @Test
    fun `a network failure is bounded`() {
        assertFalse(VideoPictureRetry.giveUp(attempts = 1, transient = true))
        assertFalse(VideoPictureRetry.giveUp(attempts = VideoPictureRetry.MAX_ATTEMPTS - 1, transient = true))
        assertTrue(VideoPictureRetry.giveUp(attempts = VideoPictureRetry.MAX_ATTEMPTS, transient = true))
    }

    @Test
    fun `a picture that finally loads clears its missing mark`() {
        val noPicture = mutableSetOf("v")

        assertEquals(setOf("other"), VideoPictureRetry.recovered(setOf("v", "other"), noPicture, "v"))
        assertTrue(noPicture.isEmpty())
    }
}
