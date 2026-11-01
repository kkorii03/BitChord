package com.music.bitchord.playback.smart

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 24-bit head was the slow part of the comparison (~30 s for 24/96 on a
 * 2 MB/s link). The same row at a lossy tier is enough to hear which recording
 * it is; anything that is not clearly a lighter copy keeps the old path.
 */
class HqVerifyLighterComparisonTest {

    private fun stream(url: String, codec: String, depth: Int? = null, rate: Int? = null, kbps: Int? = null) =
        SourceStream(
            url = url,
            format = StreamFormat(codec = codec, kbps = kbps, sampleRateHz = rate, bitDepth = depth),
            sourceConfigId = "qobuz",
            trackId = "1",
        )

    @Test
    fun `only hi-res candidates ask for a lighter copy`() {
        assertTrue(HqAudioVerify.worthLighterComparison(stream("https://a/96", "flac", 24, 96_000, 4_000)))
        assertTrue(HqAudioVerify.worthLighterComparison(stream("https://a/44", "flac", 24, 44_100, 2_000)))
        assertFalse(HqAudioVerify.worthLighterComparison(stream("https://a/16", "flac", 16, 44_100, 1_000)))
        assertFalse(HqAudioVerify.worthLighterComparison(stream("https://a/mp3", "mp3", kbps = 320)))
    }

    @Test
    fun `telegram candidates keep their own reader`() {
        assertFalse(
            HqAudioVerify.worthLighterComparison(
                stream("bitchord-telegram://track/-100_1", "flac", 24, 96_000, 4_000),
            ),
        )
    }

    @Test
    fun `a lighter copy must be lossy and at another url`() {
        val original = stream("https://a/96", "flac", 24, 96_000, 4_000)
        assertTrue(HqAudioVerify.lighterIsUsable(original, stream("https://a/320", "mp3", kbps = 320)))
        // A source that ignored the cap and sent lossless again.
        assertFalse(HqAudioVerify.lighterIsUsable(original, stream("https://a/flac", "flac", 16, 44_100, 1_000)))
        // The very URL that was already going to be fetched.
        assertFalse(HqAudioVerify.lighterIsUsable(original, stream("https://a/96", "mp3", kbps = 320)))
        assertFalse(HqAudioVerify.lighterIsUsable(original, stream("", "mp3", kbps = 320)))
    }
}
