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
    fun coverTitlesWithSlashNamesAndUnderscoresAreRead() {
        val golden = assertNotNull(CoverTitles.read("HUNTR/X (헌트릭스) - Golden /cover by 오화요(허니즈)", "젤리의 노래저장소", null))
        assertEquals("Golden", golden.title)
        assertEquals("HUNTR/X", golden.originalArtist)
        assertEquals("오화요", golden.singer)
        val flower = assertNotNull(
            CoverTitles.read("YOUNHA(윤하) _ WINTER FLOWER(雪中梅) / cover by 오화요(허니즈)", "젤리의 노래저장소", null),
        )
        assertEquals("WINTER FLOWER(雪中梅)", flower.title)
        assertEquals("YOUNHA", flower.originalArtist)
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
    fun singerFirstCoverTitlesAreRead() {
        // The singer's own channel and a fan archive of hers, same title shape.
        val rose = assertNotNull(CoverTitles.read("아카네 리제-붉은 장미 Cover.", "아카네 리제", null))
        assertEquals("붉은 장미", rose.title)
        assertEquals("아카네 리제", rose.singer)
        assertEquals(null, rose.originalArtist)

        val gira = assertNotNull(CoverTitles.read("아카네 리제-ギラギラ(기라기라) Cover.(2)", "리제 노래 아카이브", null))
        assertEquals("ギラギラ(기라기라)", gira.title)
        assertEquals("아카네 리제", gira.singer)

        val mask = assertNotNull(CoverTitles.read("아카네 리제 - 가면 무도회 Cover.(2)", "리제 노래 아카이브", null))
        assertEquals("가면 무도회", mask.title)

        val usse = assertNotNull(CoverTitles.read("아카네 리제-うっせぇわ(웃세와) cover.", "리제 노래 아카이브", null))
        assertEquals("うっせぇわ(웃세와)", usse.title)

        // Someone else's name first, on a channel that isn't theirs: not this shape.
        assertEquals(null, CoverTitles.read("아이유-밤편지 Cover.", "리제 노래 아카이브", null))
    }

    @Test
    fun coverByTitlesReadEvenWithoutTheVideoFlag() {
        // A Recents row arrives without its video flag; "cover by" still says it all.
        val cover = assertNotNull(
            CoverTitles.read(
                "에피톤 프로젝트 - 회전목마 / cover by 오화요(허니즈)",
                "젤리의 노래저장소",
                null,
                unambiguousOnly = true,
            ),
        )
        assertEquals("회전목마", cover.title)
        assertEquals("에피톤 프로젝트", cover.originalArtist)
        assertEquals("오화요", cover.singer)
        // The loose shapes stay off for such a row: "IU - Song (Cover)" by IU's topic channel.
        assertEquals(null, CoverTitles.read("가수 - 노래 (Cover)", "가수", null, unambiguousOnly = true))
    }

    @Test
    fun kugouSongSearchNeedsTheTitle() {
        assertFalse(KuGou.titlesOverlap("동거", "同居"))
        assertFalse(KuGou.titlesOverlap("동거", "数算硬币的生活"))
    }

    @Test
    fun koreanServicesMatchTitleAndArtist() {
        val hits = listOf(
            KoreanMatch.Hit("1", "회전목마 (Feat. Zion.T, 원슈타인)", "sokodomo", 220),
            KoreanMatch.Hit("2", "회전목마 (Vocal 손주희)", "에피톤 프로젝트(Epitone Project)", 313),
        )
        // The cover runs 4:56 against the record's 5:13: still the right song.
        assertEquals("2", KoreanMatch.best(hits, "회전목마", "에피톤 프로젝트", 296_000L)?.id)
        assertEquals("2", KoreanMatch.best(hits, "회전목마", "Epitone Project", 296_000L)?.id)
        // A singer's name instead of the original artist finds nothing rather than the wrong song.
        assertEquals(null, KoreanMatch.best(hits, "회전목마", "오화요", 296_000L))
    }

    @Test
    fun bugsTimedLyricsParse() {
        val lines = assertNotNull(BugsLyrics.parseTimed("18.0|저기 하늘 멀리로 애드벌룬＃31.0|도시 불빛은 내게 위로일까?＃42.0|낡은 조명을 켜고"))
        assertEquals(3, lines.size)
        assertEquals(31_000L, lines[1].timeMs)
        assertEquals("도시 불빛은 내게 위로일까?", lines[1].text)
    }

    @Test
    fun theReportedCoverSearchesAsTheOriginal() {
        val cover = assertNotNull(CoverTitles.read("나나호시 관현악단 - 발묘 / cover by 가수", "채널", null))
        assertEquals("발묘", cover.title)
        assertEquals("나나호시 관현악단", cover.originalArtist)
        assertEquals("가수", cover.singer)
    }
}
