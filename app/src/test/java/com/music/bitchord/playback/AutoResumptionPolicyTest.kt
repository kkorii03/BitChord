package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A car connection asks what to resume. Returning the first downloaded, local
 * or recommended row started a song the listener never chose just by plugging
 * the phone in. Only the live queue or the last played queue is a resume.
 */
class AutoResumptionPolicyTest {

    @Test
    fun `the live queue resumes at its own playhead`() {
        val plan = AutoResumptionPolicy.choose(
            activeItems = listOf("current", "next"),
            activeIndex = 1,
            activePositionMs = 4_200L,
            lastPlayedItems = listOf("old"),
            lastPlayedIndex = 0,
            lastPlayedPositionMs = 9_000L,
        )

        assertEquals(listOf("current", "next"), plan?.items)
        assertEquals(1, plan?.startIndex)
        assertEquals(4_200L, plan?.startPositionMs)
    }

    @Test
    fun `with no live queue the last played queue resumes instead of an arbitrary list`() {
        val plan = AutoResumptionPolicy.choose(
            activeItems = emptyList(),
            activeIndex = 0,
            activePositionMs = 0L,
            lastPlayedItems = listOf("last-a", "last-b"),
            lastPlayedIndex = 1,
            lastPlayedPositionMs = 12_000L,
        )

        assertEquals(listOf("last-a", "last-b"), plan?.items)
        assertEquals(1, plan?.startIndex)
        assertEquals(12_000L, plan?.startPositionMs)
    }

    @Test
    fun `no queue and no history starts nothing when a car connects`() {
        val plan = AutoResumptionPolicy.choose<String>(
            activeItems = emptyList(),
            activeIndex = 0,
            activePositionMs = 0L,
            lastPlayedItems = emptyList(),
            lastPlayedIndex = 0,
            lastPlayedPositionMs = 0L,
        )

        assertNull(plan)
    }

    @Test
    fun `a stale playhead cannot point past the restored queue`() {
        val plan = AutoResumptionPolicy.choose(
            activeItems = emptyList(),
            activeIndex = 0,
            activePositionMs = 0L,
            lastPlayedItems = listOf("only"),
            lastPlayedIndex = 7,
            lastPlayedPositionMs = -1L,
        )

        assertEquals(0, plan?.startIndex)
        assertEquals(0L, plan?.startPositionMs)
    }
}
