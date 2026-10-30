package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalTimingPolicyTest {

    private val file = "file:///storage/emulated/0/Download/cover.m4a"

    @Test
    fun `a video's own file is the audio to measure`() {
        assertEquals(file, LocalTimingPolicy.measurementUri(file, isVideo = true, timingForeign = false))
    }

    @Test
    fun `a file whose lyrics belong to another recording is measured from that file`() {
        assertEquals(file, LocalTimingPolicy.measurementUri(file, isVideo = false, timingForeign = true))
    }

    @Test
    fun `a song file with its own timing is not measured against another recording`() {
        assertNull(LocalTimingPolicy.measurementUri(file, isVideo = false, timingForeign = false))
    }

    @Test
    fun `no file or a blank file is never a source`() {
        assertNull(LocalTimingPolicy.measurementUri(null, isVideo = true, timingForeign = true))
        assertNull(LocalTimingPolicy.measurementUri("   ", isVideo = true, timingForeign = true))
    }

    @Test
    fun `a stream may measure once its opening is there`() {
        assertTrue(LocalTimingPolicy.measureNow(null, isVideo = true, timingForeign = false))
        assertTrue(LocalTimingPolicy.measureNow("", isVideo = false, timingForeign = false))
    }

    @Test
    fun `a local video or a foreign-timed file may measure now but a song file may not`() {
        assertTrue(LocalTimingPolicy.measureNow(file, isVideo = true, timingForeign = false))
        assertTrue(LocalTimingPolicy.measureNow(file, isVideo = false, timingForeign = true))
        assertFalse(LocalTimingPolicy.measureNow(file, isVideo = false, timingForeign = false))
    }

    @Test
    fun `a rechecked download is timeable on every surface`() {
        assertTrue(
            LocalTimingPolicy.downloadedTimingTimeable(
                timed = true,
                fromFile = true,
                isVideo = false,
                recheckOverride = true,
                sourceTimedToVideo = false,
            ),
        )
        assertFalse(
            LocalTimingPolicy.downloadedTimingTimeable(
                timed = true,
                fromFile = true,
                isVideo = false,
                recheckOverride = false,
                sourceTimedToVideo = false,
            ),
        )
    }

    @Test
    fun `a videos own captions are never moved by a record-based answer`() {
        assertFalse(
            LocalTimingPolicy.downloadedTimingTimeable(
                timed = true,
                fromFile = true,
                isVideo = false,
                recheckOverride = true,
                sourceTimedToVideo = true,
            ),
        )
        assertTrue(
            LocalTimingPolicy.downloadedTimingTimeable(
                timed = true,
                fromFile = true,
                isVideo = true,
                recheckOverride = false,
                sourceTimedToVideo = true,
            ),
        )
    }

    @Test
    fun `untimed lines are never timeable`() {
        assertFalse(
            LocalTimingPolicy.downloadedTimingTimeable(
                timed = false,
                fromFile = false,
                isVideo = true,
                recheckOverride = true,
                sourceTimedToVideo = false,
            ),
        )
    }

    @Test
    fun `a substitute stream never applies or records an upload clock`() {        assertFalse(
            LocalTimingPolicy.streamTimingAllowed(
                timeable = true,
                isVideo = false,
                substituted = true,
            ),
        )
        // A video's own upload is not a substitute file, and a normal stream is unchanged.
        assertTrue(
            LocalTimingPolicy.streamTimingAllowed(
                timeable = true,
                isVideo = true,
                substituted = true,
            ),
        )
        assertTrue(
            LocalTimingPolicy.streamTimingAllowed(
                timeable = true,
                isVideo = false,
                substituted = false,
            ),
        )
        assertFalse(
            LocalTimingPolicy.streamTimingAllowed(
                timeable = false,
                isVideo = false,
                substituted = false,
            ),
        )
    }

    @Test
    fun `a local file never waits for a stream opening`() {
        // A local file is already whole: no AudioCache.served event exists for
        // it, so waiting parked the AI fallback until the measure window ended.
        assertFalse(LocalTimingPolicy.measureWaitsForStream(file))
        assertTrue(LocalTimingPolicy.measureWaitsForStream(null))
        assertTrue(LocalTimingPolicy.measureWaitsForStream("   "))
    }
}
