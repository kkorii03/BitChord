package com.music.bitchord.telegram

import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telegram files used to rank as plain 16-bit FLAC whatever they were, so a
 * 24-bit upload could never win against a 24-bit addon row.
 */
class TelegramQualityEstimateTest {

    private fun track(fileName: String, size: Long, seconds: Int = 200, title: String = "Song") = TelegramTrack(
        id = "-100_1",
        chatId = -100,
        messageId = 1,
        fileId = 1,
        size = size,
        title = title,
        artist = "Artist",
        durationSec = seconds,
        format = fileName.substringAfterLast('.'),
        mimeType = "audio/flac",
        fileName = fileName,
    )

    private fun tier(track: TelegramTrack): HqTier = HqQuality.tierOf(TelegramTrackMapper.streamFormat(track))

    @Test
    fun `a name that says 24bit 96kHz is hi-res at the high rate`() {
        val t = track("Artist - Song (24bit-96kHz).flac", size = 100_000_000)
        val format = TelegramTrackMapper.streamFormat(t)
        assertEquals(24, format.bitDepth)
        assertEquals(96_000, format.sampleRateHz)
        assertEquals(HqTier.HI_RES_24_88, tier(t))
        assertTrue(TelegramTrackMapper.toSong(t, "c").sourceHiRes)
    }

    @Test
    fun `a name that says 24 bit alone is hi-res at the base rate`() {
        val t = track("Song [24 Bit].flac", size = 50_000_000)
        assertEquals(HqTier.HI_RES_24_44, tier(t))
    }

    @Test
    fun `a 24bit label on a 16-bit sized file is demoted by the fake rule`() {
        // 25 MB over 200 s is 1000 kbps, what 16-bit costs.
        val t = track("Song 24bit.flac", size = 25_000_000)
        assertEquals(HqTier.LOSSLESS_16, tier(t))
    }

    @Test
    fun `a silent name falls back to the bitrate`() {
        // 2000 kbps: clearly above 16-bit FLAC.
        assertEquals(HqTier.HI_RES_24_44, tier(track("Song.flac", size = 50_000_000)))
        // 3600 kbps: 24-bit at a high rate.
        assertEquals(HqTier.HI_RES_24_88, tier(track("Song.flac", size = 90_000_000)))
        // 1000 kbps: ordinary 16-bit.
        assertEquals(HqTier.LOSSLESS_16, tier(track("Song.flac", size = 25_000_000)))
        assertFalse(TelegramTrackMapper.toSong(track("Song.flac", size = 25_000_000), "c").sourceHiRes)
    }

    @Test
    fun `a file with no runtime says nothing it cannot back`() {
        val format = TelegramTrackMapper.streamFormat(track("Song.flac", size = 50_000_000, seconds = 0))
        assertNull(format.bitDepth)
        assertNull(format.sampleRateHz)
        assertEquals(HqTier.LOSSLESS_16, HqQuality.tierOf(format))
    }

    @Test
    fun `lossy files get no depth or rate`() {
        val format = TelegramTrackMapper.streamFormat(
            track("Song.mp3", size = 8_000_000).copy(mimeType = "audio/mpeg"),
        )
        assertNull(format.bitDepth)
        assertNull(format.kbps)
    }
}
