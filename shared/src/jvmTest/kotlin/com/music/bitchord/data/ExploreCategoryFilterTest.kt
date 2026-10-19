package com.music.bitchord.data

import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreCategoryFilterTest {

    private fun tile(title: String, browseId: String = "ytq:${title.hashCode()}") =
        MoodGenre(title = title, browseId = browseId, params = null)

    private fun section(title: String, vararg titles: String) =
        MoodGenreSection(title, titles.map { tile(it) })

    @Test
    fun `normalisation treats ampersand and and and 및 as the same word`() {
        val expected = ExploreCategoryFilter.normalize("발리우드 & 인도")
        assertEquals("발리우드 및 인도", expected)
        assertEquals(expected, ExploreCategoryFilter.normalize("발리우드 및 인도"))
        assertEquals(expected, ExploreCategoryFilter.normalize("  발리우드   &   인도  "))
        assertEquals(
            ExploreCategoryFilter.normalize("Bollywood & Indian"),
            ExploreCategoryFilter.normalize("Bollywood and Indian"),
        )
    }

    @Test
    fun `hides by normalised title and keeps order`() {
        val sections = listOf(
            section("분위기", "게임", "아랍 음악", "잠잘 때"),
            section("장르", "K-pop", "발리우드 & 인도", "록"),
        )

        val visible = ExploreCategoryFilter.visible(sections, setOf("아랍 음악", "발리우드 & 인도"))

        assertEquals(listOf("게임", "잠잘 때"), visible[0].items.map { it.title })
        assertEquals(listOf("K-pop", "록"), visible[1].items.map { it.title })
    }

    @Test
    fun `both seeded spellings hide the tile whichever language YouTube answers in`() {
        val sections = listOf(section("장르", "Bollywood & Indian", "Arabic"))
        val visible = ExploreCategoryFilter.visible(
            sections,
            setOf("발리우드 & 인도", "아랍 음악", "Bollywood & Indian", "Arabic"),
        )
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `drops a section whose tiles are all hidden`() {
        val sections = listOf(
            section("분위기", "아랍 음악"),
            section("장르", "K-pop"),
        )

        val visible = ExploreCategoryFilter.visible(sections, setOf("아랍 음악"))

        assertEquals(1, visible.size)
        assertEquals("장르", visible[0].title)
    }

    @Test
    fun `never hides the charts tile even when its title is hidden`() {
        val charts = tile("차트", browseId = "FEmusic_charts")
        val alsoCharts = tile("Charts", browseId = "ytq:charts")
        val hot = tile("인기 급상승", browseId = "ytc:0")
        val sections = listOf(MoodGenreSection("차트", listOf(charts, alsoCharts, hot)))

        val visible = ExploreCategoryFilter.visible(
            sections,
            setOf("차트", "Charts", "인기 급상승"),
        )

        assertEquals(listOf(charts, alsoCharts, hot), visible.single().items)
        assertTrue(ExploreCategoryFilter.isProtected(charts))
        assertTrue(ExploreCategoryFilter.isProtected(hot))
    }

    @Test
    fun `an empty hidden set leaves every tile alone`() {
        val sections = listOf(section("분위기", "게임", "운동"))
        assertEquals(sections, ExploreCategoryFilter.visible(sections, emptySet()))
    }

    @Test
    fun `a chart tile is not offered for hiding`() {
        assertTrue(ExploreCategoryFilter.isProtected(tile("차트", "FEmusic_charts")))
        assertFalse(ExploreCategoryFilter.isProtected(tile("발라드", "ytq:ballad")))
    }
}

class ExploreCategorySeedTest {

    @Test
    fun `first run seeds the regional categories`() {
        val seeded = ExploreCategoryFilter.seedHidden(emptySet(), alreadySeeded = false)
        assertTrue("발리우드 & 인도" in seeded)
        assertTrue("아랍 음악" in seeded)
        assertTrue("Bollywood & Indian" in seeded)
        assertTrue("Arabic" in seeded)
    }

    @Test
    fun `an already seeded install is never re-seeded`() {
        val edited = setOf("라틴")
        assertEquals(
            edited,
            ExploreCategoryFilter.seedHidden(edited, alreadySeeded = true),
        )
        assertEquals(
            emptySet<String>(),
            ExploreCategoryFilter.seedHidden(emptySet(), alreadySeeded = true),
        )
    }
}
