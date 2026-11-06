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

    /**
     * 11-05 smoke r2-b: `What It Sounds Like` decoded 35 s of its opening,
     * the read was abandoned with the play, and the whole opening was thrown
     * away. The quality comparison then decoded the same ninety seconds again,
     * waited 59 s at the one-at-a-time gate behind it, and was cancelled as
     * "no reference". What was heard is real audio and the next check can use
     * it.
     */
    @Test
    fun `an abandoned read keeps the opening it already heard`() {
        assertTrue(VideoAudio.keepAbandonedOpening(bytes = 35 * 32_000, minimumBytes = 10 * 32_000, failedStage = null))
    }

    /**
     * What it heard is not the whole window: a read that stopped at 12 s used to
     * answer every later ask for ninety seconds, so the song check's full pass
     * paid for a transcript of the same 12 s.
     */
    @Test
    fun `only a caller that says so gets the abandoned opening`() {
        assertEquals(null, VideoAudio.pickOpening<String>(null, "partial", acceptPartial = false))
        assertEquals("partial", VideoAudio.pickOpening<String>(null, "partial", acceptPartial = true))
        assertEquals("full", VideoAudio.pickOpening("full", "partial", acceptPartial = true))
        assertEquals("full", VideoAudio.pickOpening("full", null, acceptPartial = false))
    }

    @Test
    fun `a read that failed at a named stage keeps nothing`() {
        assertFalse(
            VideoAudio.keepAbandonedOpening(
                bytes = 35 * 32_000,
                minimumBytes = 10 * 32_000,
                failedStage = AudioDecoder.FAILURE_NO_FIRST_SAMPLE,
            ),
        )
        assertFalse(
            VideoAudio.keepAbandonedOpening(bytes = 35 * 32_000, minimumBytes = 10 * 32_000, failedStage = "gate"),
        )
    }

    @Test
    fun `an abandoned read under the ten second floor keeps nothing`() {
        assertFalse(VideoAudio.keepAbandonedOpening(bytes = 4 * 32_000, minimumBytes = 10 * 32_000, failedStage = null))
    }

    /**
     * 11-05 auto8b: the quality comparison's reference read waited 24 s, never
     * got the decoder and decoded zero audio, and the play change reported it
     * as "no reference reason=cancelled". The comparison now starts only when
     * its reference is in hand.
     */
    @Test
    fun `an opening already read for this window is ready`() {
        assertTrue(
            VideoAudio.referenceReady(
                cachedOpening = true,
                copy = null,
                isLocalFile = false,
                untilSeconds = 90.0,
                durationMs = 0L,
            ),
        )
    }

    @Test
    fun `a downloaded file is ready without any copy`() {
        assertTrue(
            VideoAudio.referenceReady(
                cachedOpening = false,
                copy = null,
                isLocalFile = true,
                untilSeconds = 90.0,
                durationMs = 0L,
            ),
        )
    }

    @Test
    fun `a stream whose opening is still filling is not ready`() {
        // 10 MB * 91 s / 215 s = 4.2 MB of the 90 s window; 3 MB is short of it.
        assertFalse(
            VideoAudio.referenceReady(
                cachedOpening = false,
                copy = rendition("stream", contentLength = 10_000_000L, cachedPrefix = 3_000_000L),
                isLocalFile = false,
                untilSeconds = 90.0,
                durationMs = 215_000L,
            ),
        )
    }

    @Test
    fun `a copy that holds the whole window is ready`() {
        assertTrue(
            VideoAudio.referenceReady(
                cachedOpening = false,
                copy = rendition("stream", contentLength = 10_000_000L, cachedPrefix = 5_000_000L),
                isLocalFile = false,
                untilSeconds = 90.0,
                durationMs = 215_000L,
            ),
        )
    }
}
