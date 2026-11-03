package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Timing agreement decides between sources; priority only breaks ties. */
class ProviderChoiceTest {

    private fun candidate(source: LyricsSource, firstStartMs: Long?) =
        ProviderChoice.Candidate(source, firstStartMs)

    private fun choose(vararg starts: Long?): Int? =
        ProviderChoice.choose(starts.mapIndexed { index, start ->
            candidate(LyricsSource.entries[index], start)
        })

    @Test
    fun `two sources that disagree keep the higher priority one`() {
        assertEquals(0, choose(1_000L, 9_000L))
    }

    @Test
    fun `two sources that agree keep the higher priority one`() {
        assertEquals(0, choose(1_000L, 1_500L))
    }

    @Test
    fun `the source agreeing with the others beats a higher priority outlier`() {
        assertEquals(1, choose(1_000L, 9_000L, 9_100L))
    }

    @Test
    fun `a source with no timed line never wins over a timed one`() {
        assertEquals(1, choose(null, 9_000L))
    }

    @Test
    fun `when no timed sources agree an untimed leader still loses`() {
        assertEquals(1, choose(null, 1_000L, 9_000L))
    }

    @Test
    fun `a single source is kept as-is`() {
        assertEquals(0, choose(9_000L))
    }

    @Test
    fun `no candidates means nothing to choose`() {
        assertNull(ProviderChoice.choose(emptyList()))
    }
}
