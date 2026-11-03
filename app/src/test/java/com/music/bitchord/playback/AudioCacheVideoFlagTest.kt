package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Cached songs folder must not turn a cached video into a plain song. */
class AudioCacheVideoFlagTest {

    @Test
    fun `a video tagged by an older run stays a video`() {
        assertTrue(AudioCache.cachedSongIsVideo(noted = null, metadata = "1"))
    }

    @Test
    fun `a video noted by this run stays a video`() {
        assertTrue(AudioCache.cachedSongIsVideo(noted = true, metadata = null))
    }

    @Test
    fun `an untagged audio row stays audio`() {
        assertFalse(AudioCache.cachedSongIsVideo(noted = false, metadata = null))
    }
}
