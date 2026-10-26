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

    @Test
    fun `a partial millisecond of audio is not a whole millisecond`() {
        assertEquals(1_000L, SongCheckWindow.wavMs(44 + 32 * 1_000 + 31))
        assertEquals(1_001L, SongCheckWindow.wavMs(44 + 32 * 1_001))
        assertEquals(0L, SongCheckWindow.wavMs(44 + 31))
    }

    @Test
    fun `the window end is inclusive and a negative window is empty`() {
        assertEquals(listOf(0, 1), SongCheckWindow.indices(listOf(4_000L, 5_000L, 5_001L), 5_000L))
        assertEquals(emptyList<Int>(), SongCheckWindow.indices(listOf(0L), -1L))
        assertEquals(emptyList<Int>(), SongCheckWindow.indices(emptyList(), 90_000L))
    }

    @Test
    fun `the wav constants describe 16 khz mono 16 bit audio`() {
        assertEquals(44, SongCheckWindow.WAV_HEADER_BYTES)
        assertEquals(32, SongCheckWindow.BYTES_PER_MS)
    }
}
