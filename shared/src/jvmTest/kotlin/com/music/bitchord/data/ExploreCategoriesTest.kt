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
    fun `queries are the Korean mood and genre terms themselves`() {
        assertEquals("운동", ExploreCategories.find("ytq:workout")!!.query)
        assertEquals("잠잘 때", ExploreCategories.find("ytq:sleep")!!.query)
        assertEquals("편안한", ExploreCategories.find("ytq:chill")!!.query)
        assertEquals("K-pop", ExploreCategories.find("ytq:kpop")!!.query)
        assertEquals("로맨스", ExploreCategories.find("ytq:romance")!!.query)
        // The measured per-tile override is optional and unused by default.
        assertNull(ExploreCategories.find("ytq:workout")!!.queryOverride)
    }

    @Test
    fun `sections put moods before genres`() {
        assertEquals(listOf("분위기·상황", "장르"), ExploreCategories.sections.map { it.first })
        assertEquals(ExploreCategories.moods, ExploreCategories.sections[0].second)
        assertEquals(ExploreCategories.genres, ExploreCategories.sections[1].second)
        assertEquals(ExploreCategories.moods + ExploreCategories.genres, ExploreCategories.all)
    }

    @Test
    fun `mood titles match the music app grid`() {
        assertEquals(
            listOf(
                "게임", "에너지 충전", "잠잘 때", "집중할 때", "출퇴근 & 등하교",
                "편안한 분위기", "행복한 기분", "파티", "로맨스", "슬플 때",
                "운동", "가족", "비 오는 날", "드라이브", "공부",
            ),
            ExploreCategories.moods.map { it.title },
        )
        assertEquals(15, ExploreCategories.moods.size)
    }

    @Test
    fun `genre titles keep YouTube Music's order`() {
        assertEquals(
            listOf(
                "K-pop", "발라드", "힙합", "R&B", "인디", "록", "팝", "J-pop",
                "트로트", "OST", "재즈", "클래식", "EDM", "애니메이션",
                "우타이테·VTuber 커버", "로파이",
            ),
            ExploreCategories.genres.map { it.title },
        )
        assertEquals(16, ExploreCategories.genres.size)
    }

    @Test
    fun `foreign browse ids do not resolve`() {
        assertNotNull(ExploreCategories.find("ytq:kpop"))
        assertNull(ExploreCategories.find("kpop"))
        assertNull(ExploreCategories.find("ytc:0"))
        assertNull(ExploreCategories.find("ytq:nope"))
    }
}
