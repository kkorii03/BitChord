package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-screen allowance: a Home build may start thirty new catalogue
 * lookups, cached answers are free, and the thirty-first new one is refused.
 */
class CatalogSongMapperBudgetTest {

    @Test
    fun `a build has thirty new lookups`() {
        val budget = CatalogSongMapper.newBudget()
        repeat(CatalogSongMapper.MAX_NEW_LOOKUPS) {
            assertTrue(budget.take(), "lookup ${it + 1} should be allowed")
        }
        assertFalse(budget.take(), "the thirty-first new lookup should be refused")
        assertEquals(CatalogSongMapper.MAX_NEW_LOOKUPS, budget.used())
        assertEquals(0, budget.remaining())
    }

    @Test
    fun `a smaller cap stops the extra lookup`() {
        val budget = CatalogSongMapper.LookupBudget(2)
        assertTrue(budget.take())
        assertTrue(budget.take())
        assertFalse(budget.take())
        assertEquals(2, budget.used())
        assertEquals(0, budget.remaining())
    }

    @Test
    fun `an empty budget refuses without going negative`() {
        val budget = CatalogSongMapper.LookupBudget(0)
        assertFalse(budget.take())
        assertFalse(budget.take())
        assertEquals(0, budget.used())
        assertEquals(0, budget.remaining())
    }
}
