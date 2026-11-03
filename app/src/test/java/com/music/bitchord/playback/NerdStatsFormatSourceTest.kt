package com.music.bitchord.playback

import androidx.media3.common.Format
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A quality swap replaces the playing source while the player may be paused.
 * The renderer's own audio format keeps naming the replaced stream until it has
 * read a sample of the new one, and with no sampler tick while paused the badge
 * stayed blank. The decoder event names what was actually configured.
 */
class NerdStatsFormatSourceTest {

    private fun format(mime: String) = Format.Builder().setSampleMimeType(mime).build()

    @Test
    fun `the decoder event outranks a lagging renderer format`() {
        val event = format("audio/flac")
        val renderer = format("audio/mp4a-latm")

        assertSame(event, statsFormat(event, renderer))
    }

    @Test
    fun `the renderer is still used before any decoder event`() {
        val renderer = format("audio/opus")

        assertSame(renderer, statsFormat(null, renderer))
        assertNull(statsFormat(null, null))
    }
}
