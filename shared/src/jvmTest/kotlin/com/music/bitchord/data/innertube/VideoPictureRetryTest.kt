package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A picture with no playable stream must settle instead of being asked forever. */
class VideoPictureRetryTest {

    @Test
    fun `a picture is asked three times and then settles`() {
        assertTrue(VideoPictureRetry.canTry(0))
        assertTrue(VideoPictureRetry.canTry(1))
        assertTrue(VideoPictureRetry.canTry(2))
        assertFalse(VideoPictureRetry.canTry(3))
        assertFalse(VideoPictureRetry.canTry(4))
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
