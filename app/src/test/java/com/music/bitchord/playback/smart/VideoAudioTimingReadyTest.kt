package com.music.bitchord.playback.smart

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI timing button must only open when `VideoAudio.wav` can actually
 * decode the same copy: a partial stream (the usual case while a track plays)
 * used to be treated as ready, then `wav` returned null and the action ended
 * in `aiFailed`.
 */
class VideoAudioTimingReadyTest {

    private val min = AudioCache.MIN_ALIGNMENT_PREFIX_BYTES

    private fun rendition(key: String, contentLength: Long, cachedPrefix: Long) =
        AudioCache.Rendition(key = key, contentLength = contentLength, cachedPrefix = cachedPrefix)

    @Test
    fun anEmptyCacheIsNotReady() {
        assertFalse(VideoAudio.timingAudioReady(recentCached = false, screenOn = true, onDisk = emptyList()))
    }

    @Test
    fun aPartiallyStreamedCopyIsNotReady() {
        // The regression: 2 MB of a 10 MB stream is enough for the analysis
        // head but not for `wav`'s complete-copy decode.
        assertFalse(
            VideoAudio.timingAudioReady(
                recentCached = false,
                screenOn = true,
                onDisk = listOf(rendition("video", contentLength = 10_000_000L, cachedPrefix = 2_000_000L)),
            ),
        )
    }

    @Test
    fun aCompleteCopyIsReady() {
        assertTrue(
            VideoAudio.timingAudioReady(
                recentCached = false,
                screenOn = true,
                onDisk = listOf(rendition("video", contentLength = 10_000_000L, cachedPrefix = 10_000_000L)),
            ),
        )
    }

    @Test
    fun aCompleteSubstituteIsReadyWhenThereIsNoMeasurableSameSource() {
        assertTrue(
            VideoAudio.timingAudioReady(
                recentCached = false,
                screenOn = true,
                onDisk = listOf(rendition("video#alt", contentLength = 5_000_000L, cachedPrefix = 5_000_000L)),
            ),
        )
    }

    @Test
    fun aMeasurablePartialSameSourceHidesTheCompleteSubstitute() {
        // `wav` opens the same-source copy once it reaches the measurement
        // floor even if a complete substitute exists, then finds it partial.
        // Readiness has to name that copy too, not just "some copy is whole".
        assertFalse(
            VideoAudio.timingAudioReady(
                recentCached = false,
                screenOn = true,
                onDisk = listOf(
                    rendition("video", contentLength = 10_000_000L, cachedPrefix = min + 1),
                    rendition("video#alt", contentLength = 5_000_000L, cachedPrefix = 5_000_000L),
                ),
            ),
        )
    }

    @Test
    fun aTinyPartialSameSourceFallsBackToTheCompleteSubstitute() {
        assertTrue(
            VideoAudio.timingAudioReady(
                recentCached = false,
                screenOn = true,
                onDisk = listOf(
                    rendition("video", contentLength = 10_000_000L, cachedPrefix = min - 1),
                    rendition("video#alt", contentLength = 5_000_000L, cachedPrefix = 5_000_000L),
                ),
            ),
        )
    }

    @Test
    fun screenOffHidesTheAction() {
        assertFalse(
            VideoAudio.timingAudioReady(
                recentCached = false,
                screenOn = false,
                onDisk = listOf(rendition("video", contentLength = 10_000_000L, cachedPrefix = 10_000_000L)),
            ),
        )
    }

    @Test
    fun anAlreadyDecodedWavStaysReadyEvenWithTheScreenOff() {
        // `wav` returns its in-memory copy before it looks at the screen.
        assertTrue(VideoAudio.timingAudioReady(recentCached = true, screenOn = false, onDisk = emptyList()))
    }
}
