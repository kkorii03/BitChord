package com.music.bitchord.playback.smart

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A song check that already has the opening on disk must not spend the
 * analysis-head wait before reading it: on a cold stream that wait doubled the
 * time to the first answer. The rule asks only for the bytes the decode reads.
 */
class VideoAudioOpeningWaitTest {

    private fun rendition(key: String, contentLength: Long, cachedPrefix: Long) =
        AudioCache.Rendition(key = key, contentLength = contentLength, cachedPrefix = cachedPrefix)

    @Test
    fun `an opening already on disk needs no analysis-head wait`() {
        // 41 s of a 150 s, 10 MB track is ~2.73 MB.
        assertTrue(
            VideoAudio.hasOpeningOnDisk(
                rendition("video", contentLength = 10_000_000L, cachedPrefix = 3_000_000L),
                untilSeconds = 40.0,
                durationMs = 150_000L,
            ),
        )
    }

    @Test
    fun `exactly the opening's bytes are enough`() {
        // 10 MB * 41 s / 150 s = 2_733_333 bytes.
        assertTrue(
            VideoAudio.hasOpeningOnDisk(
                rendition("video", contentLength = 10_000_000L, cachedPrefix = 2_733_333L),
                untilSeconds = 40.0,
                durationMs = 150_000L,
            ),
        )
    }

    @Test
    fun `a short prefix still waits for the head`() {
        assertFalse(
            VideoAudio.hasOpeningOnDisk(
                rendition("video", contentLength = 10_000_000L, cachedPrefix = 2_000_000L),
                untilSeconds = 40.0,
                durationMs = 150_000L,
            ),
        )
    }

    @Test
    fun `a complete copy needs no wait even without a stated length`() {
        assertTrue(
            VideoAudio.hasOpeningOnDisk(
                rendition("video", contentLength = 5_000_000L, cachedPrefix = 5_000_000L),
                untilSeconds = 90.0,
                durationMs = 0L,
            ),
        )
    }

    @Test
    fun `an unknown length or duration keeps waiting for the head`() {
        assertFalse(
            VideoAudio.hasOpeningOnDisk(
                rendition("video", contentLength = 0L, cachedPrefix = 1_000_000L),
                untilSeconds = 40.0,
                durationMs = 150_000L,
            ),
        )
        assertFalse(
            VideoAudio.hasOpeningOnDisk(
                rendition("video", contentLength = 10_000_000L, cachedPrefix = 1_000_000L),
                untilSeconds = 40.0,
                durationMs = 0L,
            ),
        )
    }

    @Test
    fun `no copy waits for the head`() {
        assertFalse(VideoAudio.hasOpeningOnDisk(null, untilSeconds = 40.0, durationMs = 150_000L))
    }

    /**
     * 11-05 smoke 41957291428: an MV's own audio is written only because
     * something asks for it, and the opening of `-RJSbO8UZVY` landed at
     * 03:00:53, 62 s after the late song-version answer at 02:59:51. The pass
     * waited 30 s, gave up silently, and all three MV rows measured nothing.
     */
    @Test
    fun `a late version's opening wait covers the MV opening the smoke measured`() {
        assertTrue(VideoAudio.measurementOpeningWaitMs(lateVersion = true) >= 62_000L)
        assertTrue(
            VideoAudio.measurementOpeningWaitMs(lateVersion = true) >
                VideoAudio.measurementOpeningWaitMs(lateVersion = false),
        )
    }

    @Test
    fun `an ordinary measurement keeps the wait it always had`() {
        assertEquals(30_000L, VideoAudio.measurementOpeningWaitMs(lateVersion = false))
    }
}
