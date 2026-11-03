package com.music.bitchord.data

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A quality swap replaces the current item without changing its media id.
 * Media3 reports that as a media-item transition, and treating it like a new
 * track used to clear the freshly measured FLAC snapshot, leaving the seek-bar
 * quality badge blank over the upgraded stream.
 */
class NerdStatsSameRowRefreshTest {

    @BeforeTest
    fun setUp() {
        NerdStats.forgetLastSession()
    }

    private fun flac() = NerdStats.Snapshot(
        mimeType = "audio/flac",
        bitrateKbps = 2304,
        sampleRateHz = 44_100,
        channels = 2,
        bitDepth = 24,
        mediaId = "KPM_BYl-EaQ",
    )

    @Test
    fun `a same-row swap keeps the measured snapshot and its generation`() {
        NerdStats.onTrackTransition("KPM_BYl-EaQ")
        val generation = NerdStats.currentGeneration
        assertTrue(NerdStats.publish(flac(), generation))

        NerdStats.onTrackTransition("KPM_BYl-EaQ")

        assertNotNull(NerdStats.current.value)
        assertEquals("audio/flac", NerdStats.current.value?.mimeType)
        assertEquals(generation, NerdStats.currentGeneration)
        assertTrue(NerdStats.current.value?.showsLosslessBadge == true)
    }

    @Test
    fun `a different track still clears the snapshot`() {
        NerdStats.onTrackTransition("KPM_BYl-EaQ")
        assertTrue(NerdStats.publish(flac(), NerdStats.currentGeneration))

        NerdStats.onTrackTransition("another-track")

        assertNull(NerdStats.current.value)
    }
}
