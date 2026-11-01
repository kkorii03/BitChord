package com.music.bitchord.lockscreen

import com.music.bitchord.data.lyrics.LyricLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The lock-screen lyrics screen must close when the playback service ends.
 *
 * The activity used to wait for the controller's song to become null, but a
 * teardown does not guarantee that promptly, so the screen could stay over the
 * lock screen for a player that no longer existed.
 */
class LockScreenLyricsExitTest {

    @Before
    fun setUp() {
        NowPlayingLyrics.serviceStarted()
        NowPlayingLyrics.publish(null, null)
    }

    @Test
    fun `ending the service closes the lock screen even with the last lyrics`() {
        NowPlayingLyrics.publish("song-1", listOf(LyricLine(timeMs = 0L, text = "line")))

        assertFalse(NowPlayingLyrics.ended.value)

        NowPlayingLyrics.serviceEnded()

        assertTrue(NowPlayingLyrics.ended.value)
        assertNull(NowPlayingLyrics.current.value)
    }

    @Test
    fun `a new service clears the ended state`() {
        NowPlayingLyrics.serviceEnded()
        assertTrue(NowPlayingLyrics.ended.value)

        NowPlayingLyrics.serviceStarted()

        assertFalse(NowPlayingLyrics.ended.value)
    }

    @Test
    fun `publishing lyrics for a live service clears the ended state`() {
        NowPlayingLyrics.serviceEnded()

        NowPlayingLyrics.publish("song-2", listOf(LyricLine(timeMs = 1_000L, text = "line")))

        assertFalse(NowPlayingLyrics.ended.value)
        assertTrue(NowPlayingLyrics.current.value?.videoId == "song-2")
    }
}
