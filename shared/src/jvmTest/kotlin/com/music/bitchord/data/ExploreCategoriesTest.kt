package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The curated fallback Explore catalogue: every tile must have a unique key
 * and a browse id that categoryShelves can resolve back to it.
 */
class ExploreCategoriesTest {

    @Test
    fun `keys are unique across both sections`() {
        val keys = ExploreCategories.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate key in $keys")
    }

    @Test
    fun `every tile resolves from its browse id`() {
        ExploreCategories.all.forEach { category ->
            val resolved = ExploreCategories.find(ExploreCategories.browseId(category))
            assertEquals(category, resolved, "unresolved tile ${category.key}")
        }
    }

    @Test
    fun `every tile has a title and a search query`() {
        ExploreCategories.all.forEach { category ->
            assertTrue(category.title.isNotBlank(), "blank title for ${category.key}")
            assertTrue(category.query.isNotBlank(), "blank query for ${category.key}")
        }
    }

    @Test
    fun `sections contain exactly the two catalogues`() {
        assertEquals(listOf("장르", "분위기·상황"), ExploreCategories.sections.map { it.first })
        assertEquals(16, ExploreCategories.genres.size)
        assertEquals(12, ExploreCategories.moods.size)
    }

    @Test
    fun `foreign browse ids do not resolve`() {
        assertNotNull(ExploreCategories.find("ytq:kpop"))
        assertNull(ExploreCategories.find("kpop"))
        assertNull(ExploreCategories.find("ytc:0"))
        assertNull(ExploreCategories.find("ytq:nope"))
    }
}
