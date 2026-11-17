package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KO] P1-4: "don't repeat songs in current session" off must allow repeats again. */
class KoAutoplaySessionExclusionTest {

    private val longSession = LinkedHashSet((1..100).map { "id$it" })

    @Test
    fun `with the switch on the whole session is excluded`() {
        val excluded = KoAutoplaySessionExclusion.excludedIds(true, longSession, currentId = "id100")
        assertEquals(longSession, excluded)
    }

    @Test
    fun `with the switch off a song from early in the session may come back`() {
        val excluded = KoAutoplaySessionExclusion.excludedIds(false, longSession, currentId = "id100")
        assertFalse("id1" in excluded)
        assertFalse("id70" in excluded)
        assertEquals(KoAutoplaySessionExclusion.RECENT_WHEN_REPEATS_ALLOWED, excluded.size)
    }

    @Test
    fun `with the switch off the most recent rows and the seed stay excluded`() {
        val excluded = KoAutoplaySessionExclusion.excludedIds(false, longSession, currentId = "seed")
        assertTrue("id100" in excluded)
        assertTrue("id71" in excluded)
        assertTrue("seed" in excluded)
    }

    @Test
    fun `a short session is excluded whole either way`() {
        val short = LinkedHashSet(listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), KoAutoplaySessionExclusion.excludedIds(false, short, currentId = "c"))
    }
}
