package com.music.bitchord.playback

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A FileProvider cover is only readable by packages that were explicitly
 * granted it. The lock screen is not the only surface that opens the session
 * metadata: Android Auto and Android Automotive do too, from their own
 * processes, so the reader list has to include them.
 */
class MediaSessionArtworkReadersTest {

    @Test
    fun `the lock screen, Android Auto and Automotive may read a session cover`() {
        val readers = MediaSessionArtwork.readerPackages()

        assertTrue("com.android.systemui" in readers)
        assertTrue("com.google.android.projection.gearhead" in readers)
        assertTrue("com.android.car.media" in readers)
    }
}
