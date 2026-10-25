package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class SongCheckWindowTest {

    @Test
    fun `lines before the window start are inside and later ones are not`() {
        val times = listOf(0L, 1_000L, 89_999L, 90_000L, 90_001L, 300_000L)
        assertEquals(listOf(0, 1, 2, 3), SongCheckWindow.indices(times, 90_000L))
    }

    @Test
    fun `a line timed before the song starts is left out`() {
        val times = listOf(-500L, 0L, 4_000L)
        assertEquals(listOf(1, 2), SongCheckWindow.indices(times, 90_000L))
    }

    @Test
    fun `an empty window has no lines`() {
        assertEquals(emptyList<Int>(), SongCheckWindow.indices(listOf(0L, 1L), 0L))
    }

    @Test
    fun `the window is the real wav length, not what was asked for`() {
        // A ninety-second window, the full size and one shorter than the header.
        assertEquals(90_000L, SongCheckWindow.wavMs(44 + 32 * 90_000))
        assertEquals(42_500L, SongCheckWindow.wavMs(44 + 32 * 42_500))
        assertEquals(0L, SongCheckWindow.wavMs(44))
        assertEquals(0L, SongCheckWindow.wavMs(10))
    }
}
