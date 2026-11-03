package com.music.bitchord.data.innertube

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A picture with no playable stream must settle instead of being asked forever. */
class VideoPictureRetryTest {

    @BeforeTest
    fun reset() = VideoPictureRetry.clear()

    @Test
    fun `a picture is asked three times and then settles`() {
        assertTrue(VideoPictureRetry.canTry(0))
        assertTrue(VideoPictureRetry.canTry(1))
        assertTrue(VideoPictureRetry.canTry(2))
        assertFalse(VideoPictureRetry.canTry(3))
        assertFalse(VideoPictureRetry.canTry(4))
    }

    @Test
    fun `a permanent failure settles at once`() {
        assertTrue(VideoPictureRetry.failed("v", permanent = true))
        assertEquals(0, VideoPictureRetry.attempts("v"))
    }

    @Test
    fun `a network failure never settles, even after the budget runs out`() {
        repeat(VideoPictureRetry.MAX_ATTEMPTS) { ask ->
            assertFalse(VideoPictureRetry.failed("v", permanent = false), "ask $ask")
        }
        assertEquals(0, VideoPictureRetry.attempts("v"))
        repeat(2) { ask ->
            assertFalse(VideoPictureRetry.failed("v", permanent = false), "later ask $ask")
        }
    }

    @Test
    fun `failed asks are counted per video`() {
        assertFalse(VideoPictureRetry.failed("a", permanent = false))
        assertFalse(VideoPictureRetry.failed("b", permanent = false))
        assertFalse(VideoPictureRetry.failed("a", permanent = false))

        assertEquals(2, VideoPictureRetry.attempts("a"))
        assertEquals(1, VideoPictureRetry.attempts("b"))
    }

    @Test
    fun `the oldest video is evicted, not every other one`() {
        repeat(VideoPictureRetry.MAX_VIDEOS) { index ->
            assertFalse(VideoPictureRetry.failed("v$index", permanent = false))
        }
        assertFalse(VideoPictureRetry.failed("new", permanent = false))

        assertEquals(VideoPictureRetry.MAX_VIDEOS, VideoPictureRetry.rememberedVideos())
        assertEquals(0, VideoPictureRetry.attempts("v0"))
        assertEquals(1, VideoPictureRetry.attempts("v1"))
        assertEquals(1, VideoPictureRetry.attempts("new"))
    }
}

/** The picture's client exclusions are its own, not the audio walk's. */
class PictureExcludedClientsTest {

    @Test
    fun `audio refusals do not bench a client for the picture`() {
        assertEquals(
            setOf("PICTURE_REFUSED"),
            InnerTubeXResolver.pictureExcludedClients(
                audioRefused = setOf("AUDIO_REFUSED"),
                pictureRefused = setOf("PICTURE_REFUSED"),
            ),
        )
    }
}
