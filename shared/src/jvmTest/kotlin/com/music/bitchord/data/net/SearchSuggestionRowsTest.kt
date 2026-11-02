package com.music.bitchord.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionRowsTest {

    @Test
    fun `the typed text stays first and is not repeated`() {
        val rows = suggestionRows(
            "coldplay",
            listOf("coldplay", "coldplay yellow", "Coldplay live"),
            listOf("Yellow", "Song · Coldplay", "Live 2012"),
        )

        assertEquals(listOf("coldplay", "coldplay yellow", "Coldplay live"), rows)
    }

    @Test
    fun `duplicate completions collapse so lazy list keys stay unique`() {
        val rows = suggestionRows(
            "ab",
            listOf("abc", "abc", "ABD", "abd"),
            listOf("abc", "abd"),
        )

        assertEquals(listOf("ab", "abc", "ABD"), rows)
        assertEquals(rows.size, rows.distinctBy { it.lowercase() }.size)
    }

    @Test
    fun `punctuation only input keeps just the typed query action`() {
        val rows = suggestionRows(
            "·",
            listOf(
                "· 時々ボソッと ロシア語でデレる隣のアーリャさん ed",
                "· please stay with me",
                "· 로또 당첨 번호",
            ),
            musicTerms = emptyList(),
        )

        assertEquals(listOf("·"), rows)
    }

    @Test
    fun `general youtube rows and emoji channel names are dropped`() {
        val rows = suggestionRows(
            "강진",
            listOf(
                "강진🙏👯‍♀️💃 가수이현희tv(월",
                "강진 노래",
                "강진 뉴스",
                "로또 당첨 번호",
            ),
            musicTerms = listOf("강진", "Song · 강진"),
        )

        assertEquals(listOf("강진"), rows)
    }

    @Test
    fun `a prefix matching non-music row is not a music suggestion`() {
        val rows = suggestionRows(
            "강진",
            listOf("강진 뉴스", "강진 - 주제"),
            musicTerms = listOf("강진", "Song · 강진"),
        )

        assertEquals(listOf("강진"), rows)
    }

    @Test
    fun `star and other symbol emoji channel names are dropped`() {
        val rows = suggestionRows(
            "iu",
            listOf("iu⭐채널", "iu✅", "iu☀", "iu✨", "iu good day"),
            musicTerms = listOf("Good Day", "Song · IU"),
        )

        assertEquals(listOf("iu", "iu good day"), rows)
    }

    @Test
    fun `a row that does not extend the typed prefix is not a completion`() {
        val rows = suggestionRows(
            "iu",
            listOf("lotto numbers", "iu good day"),
            musicTerms = listOf("Good Day", "Song · IU"),
        )

        assertEquals(listOf("iu", "iu good day"), rows)
    }

    @Test
    fun `a stale response is not current`() {
        assertTrue(isCurrentSearchResponse(7, 7))
        assertFalse(isCurrentSearchResponse(6, 7))
    }
}
