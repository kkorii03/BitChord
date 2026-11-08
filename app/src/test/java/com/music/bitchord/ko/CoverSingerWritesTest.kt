package com.music.bitchord.ko

import com.music.bitchord.data.cover.CoverTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a cover-singer write is allowed to do: one write per burst, none when
 * the set would not change, and never a view-count or date fragment.
 */
class CoverSingerWritesTest {

    @Test
    fun `no write when unchanged`() {
        val current = linkedSetOf("Gallant", "QWER")
        assertNull(mergeCoverSingers(current, listOf("Gallant", "QWER")))
    }

    @Test
    fun `one write per burst folds duplicates and every new name`() {
        val next = mergeCoverSingers(emptySet(), listOf("Gallant", "QWER", "Gallant", "SEVENTEEN"))
        assertEquals(listOf("Gallant", "QWER", "SEVENTEEN"), next?.toList())
    }

    @Test
    fun `junk names rejected`() {
        assertNull(CoverTitles.cleanSingerName("조회수 1246만회"))
        assertNull(CoverTitles.cleanSingerName("2026년 1월 1일"))
        assertNull(mergeCoverSingers(emptySet(), listOf("조회수 1246만회", "2026년 1월 1일")))
    }

    @Test
    fun `a byline bullet keeps the act and drops the count`() {
        assertEquals("SEVENTEEN", CoverTitles.cleanSingerName("SEVENTEEN • 조회수 1246만회"))
        val next = mergeCoverSingers(emptySet(), listOf("SEVENTEEN • 조회수 1246만회"))
        assertEquals(listOf("SEVENTEEN"), next?.toList())
    }
}
