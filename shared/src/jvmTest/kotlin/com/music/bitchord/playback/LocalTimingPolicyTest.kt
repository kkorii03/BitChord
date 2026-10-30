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
    fun aRecheckOverrideOnADownloadedSongIsApplied() {
        assertTrue(
            LocalTimingPolicy.baseAllowed(
                timed = true,
                fromFile = true,
                isVideo = false,
                overrideRecheck = true,
            ),
        )
        assertFalse(
            LocalTimingPolicy.baseAllowed(
                timed = true,
                fromFile = true,
                isVideo = false,
                overrideRecheck = false,
            ),
        )
    }

    @Test
    fun aVideosFileAndAStreamAreAlwaysAdjustable() {
        assertTrue(LocalTimingPolicy.baseAllowed(true, fromFile = true, isVideo = true, overrideRecheck = false))
        assertTrue(LocalTimingPolicy.baseAllowed(true, fromFile = false, isVideo = false, overrideRecheck = false))
        assertFalse(LocalTimingPolicy.baseAllowed(false, fromFile = false, isVideo = false, overrideRecheck = false))
    }
}
