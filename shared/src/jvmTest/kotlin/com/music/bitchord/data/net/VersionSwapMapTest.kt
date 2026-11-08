package com.music.bitchord.data.net

import com.music.bitchord.data.model.VersionTimeMap
import com.music.bitchord.data.model.VersionTimeMap.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A version swap of an officially paired MV and song uses YouTube's own
 * section map (upstream v1.9.1 77634fb60), and a broken map never moves the
 * playhead far off: it is refused and the swap measures as before.
 */
class VersionSwapMapTest {

    // MV: 10 s spoken intro, 60 s of song, a 20 s skit, then the remaining 200 s.
    // Song: the same 260 s with neither.
    private val skitMap = VersionTimeMap(
        listOf(
            Segment(fromStartMs = 10_000, toStartMs = 0, durationMs = 60_000),
            Segment(fromStartMs = 90_000, toStartMs = 60_000, durationMs = 200_000),
        ),
    )
    private val mvMs = 290_000L
    private val songMs = 260_000L

    @Test
    fun `a moment after a mid-video skit lands on the same bar of the song`() {
        val map = assertNotNull(VersionSwapMap.usable(skitMap, mvMs, songMs))
        // 100 s into the MV is 10 s after the skit: 70 s into the song. One
        // offset measured on the opening (-10 s) would land on 90 s, 20 s off.
        assertEquals(70_000L, VersionSwapMap.positionMs(map, 100_000L, songMs))
        assertEquals(20_000L, VersionSwapMap.positionMs(map, 30_000L, songMs))
        // Inside the intro or the skit: where the music picks up again.
        assertEquals(0L, VersionSwapMap.positionMs(map, 4_000L, songMs))
        assertEquals(60_000L, VersionSwapMap.positionMs(map, 80_000L, songMs))
    }

    @Test
    fun `the inverted map takes the song back to the video`() {
        val back = assertNotNull(VersionSwapMap.usable(skitMap.inverted(), songMs, mvMs))
        assertEquals(100_000L, VersionSwapMap.positionMs(back, 70_000L, mvMs))
    }

    @Test
    fun `a map reaching past either recording is refused`() {
        // Song is only 200 s: the last stretch would end 60 s past it.
        assertNull(VersionSwapMap.usable(skitMap, mvMs, 200_000L))
        // MV is only 250 s.
        assertNull(VersionSwapMap.usable(skitMap, 250_000L, songMs))
        // Whole-second runtimes are within the slack.
        assertNotNull(VersionSwapMap.usable(skitMap, 289_000L, 259_000L))
    }

    @Test
    fun `an overlapping, backwards or negative map is refused`() {
        val overlapping = VersionTimeMap(
            listOf(Segment(0, 0, 60_000), Segment(30_000, 60_000, 60_000)),
        )
        assertNull(VersionSwapMap.usable(overlapping, mvMs, songMs))
        val backwards = VersionTimeMap(
            listOf(Segment(0, 100_000, 60_000), Segment(60_000, 0, 60_000)),
        )
        assertNull(VersionSwapMap.usable(backwards, mvMs, songMs))
        assertNull(VersionSwapMap.usable(VersionTimeMap(listOf(Segment(0, -5_000, 60_000))), mvMs, songMs))
        assertNull(VersionSwapMap.usable(VersionTimeMap(listOf(Segment(0, 0, 0))), mvMs, songMs))
        assertNull(VersionSwapMap.usable(VersionTimeMap(emptyList()), mvMs, songMs))
        assertNull(VersionSwapMap.usable(null, mvMs, songMs))
    }

    @Test
    fun `unknown lengths leave only the order checks`() {
        assertNotNull(VersionSwapMap.usable(skitMap, null, 0L))
    }

    @Test
    fun `the mapped position never passes the song's end`() {
        val shortSong = 150_000L
        // Accepted only without lengths; the clamp still holds the seek inside the song.
        assertEquals(shortSong - 1, VersionSwapMap.positionMs(skitMap, 280_000L, shortSong))
        assertEquals(0L, VersionSwapMap.positionMs(skitMap, -500L, shortSong))
    }

    @Test
    fun `a mapped late handover skips the measured-opening bound and the alignment setting`() {
        fun ask(position: Long, smart: Boolean, low: Boolean, mapped: Boolean) = LateVersionSwapPolicy.refusal(
            musicOnly = true,
            inParty = false,
            casting = false,
            listenerKeptVideo = false,
            listenerChoseOriginal = false,
            rowStillCurrent = true,
            isSongVersion = true,
            videoPositionMs = position,
            videoDurationMs = mvMs,
            smartAlignment = smart,
            lowPower = low,
            mapped = mapped,
        )
        // Unmapped: 120 s is past the measured opening, and the setting off refuses.
        assertEquals(LateVersionSwapPolicy.Refusal.PAST_MEASURED_OPENING, ask(120_000L, true, false, false))
        assertEquals(LateVersionSwapPolicy.Refusal.SMART_ALIGNMENT_OFF, ask(20_000L, false, false, false))
        // Mapped: neither applies, nothing is measured.
        assertNull(ask(120_000L, true, false, true))
        assertNull(ask(120_000L, false, false, true))
        // Low power and the video's end still refuse.
        assertEquals(LateVersionSwapPolicy.Refusal.LOW_POWER, ask(120_000L, true, true, true))
        assertEquals(LateVersionSwapPolicy.Refusal.NEAR_END, ask(mvMs - 5_000L, true, false, true))
    }

    @Test
    fun `a mapped start too close to the song's end is refused`() {
        assertEquals(70_000L, LateVersionSwapPolicy.mappedSongStartMs(70_000L, songMs))
        assertNull(LateVersionSwapPolicy.mappedSongStartMs(songMs - 10_000L, songMs))
    }
}
