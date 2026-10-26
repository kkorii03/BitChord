package com.music.bitchord.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongFileGenerationTest {

    @Test
    fun `a check keeps its result only while its file generation is current`() {
        val generation = SongFileGeneration()
        val captured = generation.current("id")
        assertTrue(generation.isCurrent("id", captured))

        val bumped = generation.bump("id")
        assertEquals(captured + 1, bumped)
        assertFalse(generation.isCurrent("id", captured))
        assertTrue(generation.isCurrent("id", bumped))
    }

    @Test
    fun `replacing one file does not invalidate another song's check`() {
        val generation = SongFileGeneration()
        val other = generation.current("other")

        generation.bump("id")

        assertTrue(generation.isCurrent("other", other))
    }

    @Test
    fun `each replacement advances the generation`() {
        val generation = SongFileGeneration()
        val first = generation.bump("id")
        val second = generation.bump("id")

        assertEquals(first + 1, second)
        assertFalse(generation.isCurrent("id", first))
        assertTrue(generation.isCurrent("id", second))
    }
}
