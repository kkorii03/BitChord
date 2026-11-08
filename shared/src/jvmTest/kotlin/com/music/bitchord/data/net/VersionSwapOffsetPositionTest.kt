package com.music.bitchord.data.net

import com.music.bitchord.data.model.VersionTimeMap
import com.music.bitchord.data.model.VersionTimeMap.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A version swap by one measured shift lands inside the other cut, the same
 * way a swap by YouTube's map does (sol-pairing Q3: the shift branch was only
 * floored at zero), and a paused restore uses whatever is known before the
 * raw playhead.
 */
class VersionSwapOffsetPositionTest {

    @Test
    fun `a shift past the other cut's end stops on its last moment, like the map`() {
        // MV with a 15 s intro the song lacks is the other way round here:
        // the song is 15 s *later*, and the MV is near its own end.
        assertEquals(209_999L, VersionSwapMap.offsetPositionMs(200_000L, 15_000L, 210_000L))
        val map = VersionTimeMap(listOf(Segment(fromStartMs = 0, toStartMs = 15_000, durationMs = 196_000)))
        assertEquals(
            VersionSwapMap.positionMs(map, 200_000L, 210_000L),
            VersionSwapMap.offsetPositionMs(200_000L, 15_000L, 210_000L),
        )
    }

    @Test
    fun `a shift before the start stops at zero and an unknown length leaves the end open`() {
        assertEquals(0L, VersionSwapMap.offsetPositionMs(3_000L, -10_000L, 210_000L))
        assertEquals(215_000L, VersionSwapMap.offsetPositionMs(200_000L, 15_000L, null))
        assertEquals(215_000L, VersionSwapMap.offsetPositionMs(200_000L, 15_000L, 0L))
        assertEquals(50_000L, VersionSwapMap.offsetPositionMs(60_000L, -10_000L, 210_000L))
    }

    @Test
    fun `a paused restore uses the map, else the measured shift, else nothing is known`() {
        val map = VersionTimeMap(listOf(Segment(fromStartMs = 10_000, toStartMs = 0, durationMs = 200_000)))
        assertEquals(90_000L, VersionSwapMap.knownPositionMs(map, -4_000L, 100_000L, 200_000L))
        assertEquals(96_000L, VersionSwapMap.knownPositionMs(null, -4_000L, 100_000L, 200_000L))
        assertEquals(199_999L, VersionSwapMap.knownPositionMs(null, 150_000L, 100_000L, 200_000L))
        assertNull(VersionSwapMap.knownPositionMs(null, null, 100_000L, 200_000L))
    }
}
