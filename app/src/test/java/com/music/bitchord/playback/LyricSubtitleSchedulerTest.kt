package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricSubtitleSchedulerTest {

    private val lines = listOf(0L, 5_000L, 10_000L, 20_000L)

    @Test
    fun `schedules the next boundary at normal speed`() {
        assertEquals(3_000L, LyricSubtitleScheduler.delayUntilNextLine(lines, 2_000L, 1f))
    }

    @Test
    fun `faster playback reaches the line sooner`() {
        assertEquals(1_500L, LyricSubtitleScheduler.delayUntilNextLine(lines, 2_000L, 2f))
    }

    @Test
    fun `slower playback stretches the wall clock delay`() {
        assertEquals(6_000L, LyricSubtitleScheduler.delayUntilNextLine(lines, 2_000L, 0.5f))
    }

    @Test
    fun `a boundary exactly at the position already counts as applied`() {
        assertEquals(5_000L, LyricSubtitleScheduler.delayUntilNextLine(lines, 5_000L, 1f))
    }

    @Test
    fun `past the last line there is nothing to schedule`() {
        assertNull(LyricSubtitleScheduler.delayUntilNextLine(lines, 20_000L, 1f))
        assertNull(LyricSubtitleScheduler.delayUntilNextLine(emptyList(), 0L, 1f))
    }

    @Test
    fun `a boundary in the past still yields a minimum wake-up`() {
        assertEquals(
            LyricSubtitleScheduler.MIN_DELAY_MS,
            LyricSubtitleScheduler.delayUntilNextLine(listOf(1_000L), 999L, 4f),
        )
    }

    @Test
    fun `a broken speed falls back to normal`() {
        assertEquals(3_000L, LyricSubtitleScheduler.delayUntilNextLine(lines, 2_000L, 0f))
        assertEquals(
            3_000L,
            LyricSubtitleScheduler.delayUntilNextLine(lines, 2_000L, Float.NaN),
        )
    }
}
