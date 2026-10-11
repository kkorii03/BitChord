package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LyricsMatchingTest {

    private fun line(text: String, at: Long = 1_000L) = LyricLine(timeMs = at, text = text)

    @Test
    fun kugouInstrumentalPlaceholderIsNoLyrics() {
        assertTrue(KuGou.isInstrumentalPlaceholder(listOf(line("纯音乐，请欣赏"))))
        assertTrue(KuGou.isInstrumentalPlaceholder(listOf(line("此歌曲为没有填词的纯音乐，请您欣赏"))))
        assertTrue(KuGou.isInstrumentalPlaceholder(emptyList()))
        assertFalse(
            KuGou.isInstrumentalPlaceholder(
                listOf(line("夜に駆ける", 1_000L), line("沈むように溶けてゆくように", 5_000L)),
            ),
        )
    }

    @Test
    fun kugouFallbackNeedsTheTitleInCommon() {
        // The bug: a Hangul title KuGou doesn't know, answered with an
        // unrelated instrumental of the same length.
        assertFalse(KuGou.titlesOverlap("발묘", "夏日纯音乐"))
        assertFalse(KuGou.titlesOverlap("발묘", "Summer Piano"))
        // Same song, slightly different spelling: kept.
        assertTrue(KuGou.titlesOverlap("Shape of You", "Shape Of You (Acoustic)"))
        assertTrue(KuGou.titlesOverlap("抜錨", "抜錨"))
        assertTrue(KuGou.titlesOverlap("밤편지", "밤편지"))
    }

    @Test
    fun hangulNamesAreWorthARetry() {
        assertTrue(OriginalNames.worthTrying("발묘", "나나호시 관현악단"))
        assertTrue(OriginalNames.worthTrying("Love wins all", "아이유"))
        assertFalse(OriginalNames.worthTrying("抜錨", "ナナホシ管弦楽団"))
        assertFalse(OriginalNames.worthTrying("Shape of You", "Ed Sheeran"))
    }

    @Test
    fun songFirstSingerCoverTitlesAreRead() {
        val drowning = assertNotNull(CoverTitles.read("Drowning - 타비 Cover(06.12)", "아라하시 타비", null))
        assertEquals("Drowning", drowning.title)
        assertEquals("타비", drowning.singer)
        assertEquals(null, drowning.originalArtist)
        assertEquals("타비 커버", drowning.byline)

        val hit = assertNotNull(CoverTitles.read("Hit on Shot - 타비 Cover(06.16)", "아라하시타비 노래 저장소", null))
        assertEquals("Hit on Shot", hit.title)

        val again = assertNotNull(CoverTitles.read("추억의 소각장 - 타비 Cover(06.05)(2)", "아라하시 타비", null))
        assertEquals("추억의 소각장", again.title)

        // The same words on someone else's channel are "artist - song cover",
        // which is not this shape.
        val other = CoverTitles.read("아이유 - 밤편지 Cover(06.12)", "다른 채널", null)
        assertTrue(other == null || other.title != "아이유")
    }

    @Test
    fun theReportedCoverSearchesAsTheOriginal() {
        val cover = assertNotNull(CoverTitles.read("나나호시 관현악단 - 발묘 / cover by 가수", "채널", null))
        assertEquals("발묘", cover.title)
        assertEquals("나나호시 관현악단", cover.originalArtist)
        assertEquals("가수", cover.singer)
    }
}
