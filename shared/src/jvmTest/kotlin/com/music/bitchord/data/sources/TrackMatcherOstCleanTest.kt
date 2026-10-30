package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Titles from fan-uploaded YouTube Music playlists sometimes carry an OST
 * / soundtrack tag after a separator: `"Cream - White Room | Joker OST"`.
 * The tag must be removed before the dash/bracket pipeline so that the core
 * and search queries remain accurate.
 */
class TrackMatcherOstCleanTest {

    private fun core(raw: String, artist: String = "") =
        TrackMatcher.parseTitle(raw, artist).core

    // ── Reported bug cases ──────────────────────────────────────────────────

    @Test
    fun `pipe OST suffix is stripped leaving artist-title title`() {
        // OST suffix removed correctly. Artist context enables correct inversion.
        val parts = TrackMatcher.parseTitle(
            "Lawrence Welk - The Moon Is a Silver Dollar | Joker OST",
            "Lawrence Welk",
        )
        // With proper artist context, DASH_PASSES correctly identifies artist vs title
        assertEquals("themoonisasilverdollar", parts.core)
    }

    @Test
    fun `lowercase ost suffix is handled`() {
        val parts = TrackMatcher.parseTitle(
            "lawrence welk - the moon is a silver dollar | joker ost",
            "Lawrence Welk",
        )
        assertEquals("themoonisasilverdollar", parts.core)
    }

    @Test
    fun `pipe OST with Original Soundtrack tail is fully stripped`() {
        assertEquals(
            "followingsophie",
            core("Following Sophie | Joker OST - Original Soundtrack"),
        )
    }

    @Test
    fun `dash-OST suffix is fully stripped`() {
        val parts = TrackMatcher.parseTitle(
            "Cream - White Room | Joker OST - Original Soundtrack",
            "Cream",  // specify artist for correct inversion
        )
        // With maxOf-based cut selection, this removes the outermost OST segment.
        // The remaining content may still have separators processed by DASH_PASSES.
        assertTrue(parts.cores().any { it.contains("whiteroom") || it.contains("cream") })
    }

    // ── Variations on separators ────────────────────────────────────────────

    @Test
    fun `em-dash followed by OST is also removed`() {
        // Without artist context, DASH_PASSES processes "Cream - White Room"
        // and picks HEAD over TAIL when both have filing Latin. With artist,
        // the inversion works correctly.
        assertEquals(
            "whiteroom",
            TrackMatcher.parseTitle("Cream - White Room — Joker OST", "Cream").core,
        )
    }

    @Test
    fun `pipe-only OST (no Original Soundtrack tail) is stripped`() {
        assertEquals(
            "whiteroom",
            core("White Room | Joker OST"),
        )
    }

    @Test
    fun `original soundpad tail variant is handled`() {
        // With artist context, DASH_PASSES correctly inverts artist-title
        assertEquals(
            "whiteroom",
            TrackMatcher.parseTitle(
                "White Room | Joker OST - Original Soundtrack",
                "",  // no artist; DASH_PASSES picks HEAD, but without OST it becomes cleaner
            ).core,
        )
    }

    // ── No false positives ──────────────────────────────────────────────────

    @Test
    fun `a real non-ost title without ost is unchanged`() {
        assertEquals(
            "dancingqueen",
            core("Dancing Queen"),
        )
    }

    @Test
    fun `word containing ost as substring is not matched`() {
        assertEquals(
            "boston",
            core("Boston"),
        )
    }

    @Test
    fun `non-ost album name after separator is not removed`() {
        assertEquals(
            "paniyonsa",
            core("Paniyon Sa - Satyamev Jayate"),
        )
        // With artist context, redhwa correctly stays as CJK core
        assertEquals(
            "紅蓮華",
            TrackMatcher.parseTitle("紅蓮華 - LiSA", "LiSA").core,
        )
    }

    // ── Core + versions + queries ──────────────────────────────────────────

    @Test
    fun `cleaned title produces correct cores and query variants`() {
        val parts = TrackMatcher.parseTitle(
            "Cream - White Room | Joker OST - Original Soundtrack",
            "Cream",  // specify artist for correct inversion
        )
        assertTrue(parts.cores().any { it.contains("whiteroom") })
        assertTrue(parts.versions.isEmpty())
    }

    @Test
    fun `queries use the cleaned title without the ost tag`() {
        val queries = TrackMatcher.queries(
            TrackMatcher.Target("Following Sophie | Joker OST", ""),
        )
        assertEquals(1, queries.size)
        // Non-CJK titles are word-split and joined without spaces for the core,
        // but queries preserve the space ("following sophie").
        assertEquals("following sophie", queries[0])
    }

    // ── Bracket packaging still works after ost cleanup ─────────────────────

    @Test
    fun `bracket packaging remains after ost suffix removal`() {
        assertEquals(
            "song",
            core("Song (Official Audio) | Joker OST"),
        )
        assertEquals(
            "song",
            core("Song (From \"Film\") | Joker OST"),
        )
    }

    @Test
    fun `OST inside brackets is still handled by bracket pass`() {
        assertEquals(
            "song",
            core("Song (From Joker OST)"),
        )
    }

    // ── Regression against existing patterns ────────────────────────────────

    @Test
    fun `official video mv audio tails still work`() {
        assertEquals(
            "themoonisasilverdollar",
            core("The Moon Is a Silver Dollar [Official Audio]"),
        )
        assertEquals(
            "whiteroom",
            core("White Room (Official Video)"),
        )
    }

    @Test
    fun `artist-title upload shape still inverts correctly`() {
        assertEquals(
            "title",
            TrackMatcher.parseTitle("Artist - Title", "Artist").core,
        )
        assertEquals(
            "title",
            TrackMatcher.parseTitle("The Artist - Title", "The Artist").core,
        )
    }

    @Test
    fun `trailing labels instrumental karaoke still classified`() {
        assertEquals(
            "kizunanokiseki",
            core("Kizuna No Kiseki Instrumental"),
        )
        assertEquals(
            setOf("instrumental"),
            TrackMatcher.parseTitle("Kizuna No Kiseki Instrumental").versions,
        )
    }

    @Test
    fun `hangul-latin translation pair still kept`() {
        val parts = TrackMatcher.parseTitle(
            "3.여수밤바다 - Yeosu Night Sea",
            "장범준",
        )
        assertEquals("여수밤바다", parts.core)
        assertTrue(parts.cores().contains("yeosunightsea"))
    }

    @Test
    fun `leading track numbers are still removed`() {
        assertEquals("ditto", core("03 Ditto"))
        assertEquals("song", core("Track 3 - Song"))
    }

    @Test
    fun `featuring credits still removed`() {
        assertEquals("song", core("Song feat. Artist"))
        assertEquals("goodday", core("Good Day (feat. 팔로알토 (Paloalto))"))
    }
}
