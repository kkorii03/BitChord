package com.music.bitchord.data.sources

import com.music.bitchord.data.NerdStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one answer every quality surface reads.
 *
 * The bug this covers: a stored hq-tier verdict made the app call a track
 * "already best" while the decoder was plainly reporting Opus. These cases
 * pin the answer to the decoder's own format, including the moment before it
 * has reported one.
 */
class PlayingAudioFormatTest {

    private val videoId = "dQw4w9WgXcQ"

    private fun snapshot(
        mediaId: String? = videoId,
        mime: String,
        bitrate: Int? = null,
        depth: Int? = null,
        rate: Int? = null,
    ) = NerdStats.Snapshot(
        mimeType = mime,
        bitrateKbps = bitrate,
        sampleRateHz = rate,
        channels = 2,
        bitDepth = depth,
        mediaId = mediaId,
    )

    @Test
    fun `opus is not lossless however a stored verdict described the track`() {
        val opus = snapshot(mime = "audio/opus", bitrate = 160)

        assertFalse(PlayingAudioFormat.isLossless(videoId, opus))
        assertFalse(PlayingAudioFormat.isLosslessOrAtmos(videoId, opus))
        assertEquals("opus", PlayingAudioFormat.streamFormat(videoId, opus)?.codec)
    }

    @Test
    fun `flac is lossless from the decoder's own mime type`() {
        val flac = snapshot(mime = "audio/flac", bitrate = 900, depth = 16, rate = 44_100)

        assertTrue(PlayingAudioFormat.isLossless(videoId, flac))
        assertTrue(PlayingAudioFormat.isLosslessOrAtmos(videoId, flac))
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(PlayingAudioFormat.streamFormat(videoId, flac)))
    }

    @Test
    fun `a snapshot for another track cannot answer for this one`() {
        val other = snapshot(mediaId = "otherTrack1", mime = "audio/flac")

        assertFalse(PlayingAudioFormat.isLossless(videoId, other))
        assertNull(PlayingAudioFormat.streamFormat(videoId, other))
    }

    @Test
    fun `no measurement yet is unknown rather than best`() {
        assertFalse(PlayingAudioFormat.isLossless(videoId, null))
        assertFalse(PlayingAudioFormat.isDolbyAtmos(videoId, null))
        assertNull(PlayingAudioFormat.streamFormat(videoId, null))
    }

    @Test
    fun `a fake lossless container does not earn the badge or block an upgrade`() {
        val fake = snapshot(mime = "audio/flac", bitrate = 128, depth = 16, rate = 44_100)

        assertTrue(PlayingAudioFormat.isLossless(videoId, fake))
        assertFalse(PlayingAudioFormat.showsLosslessBadge(videoId, fake))
        assertFalse(PlayingAudioFormat.isLosslessOrAtmos(videoId, fake))
    }

    @Test
    fun `dolby atmos is recognised from the decoder`() {
        val atmos = snapshot(mime = "audio/eac3-joc", bitrate = 768)

        assertTrue(PlayingAudioFormat.isDolbyAtmos(videoId, atmos))
        assertFalse(PlayingAudioFormat.isLossless(videoId, atmos))
        assertEquals(HqTier.DOLBY_ATMOS, HqQuality.tierOf(PlayingAudioFormat.streamFormat(videoId, atmos)))
    }
}
