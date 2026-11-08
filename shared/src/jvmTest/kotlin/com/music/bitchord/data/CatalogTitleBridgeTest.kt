package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.ko.KoTrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NPZtKA6343o (ILLIT "You're Under Arrest" Official MV, HYPE UNIVERSE) never
 * got its song version on any build (vc181 10/10, candidates 8/8). Emulator
 * 182a/184d: `catalogue mapping: no matching track videoId=NPZtKA6343o (video
 * source) reason=no-track albums=16 matching=3 pages=3 tracks=14`.
 *
 * The album search ("ILLIT You’re Under Arrest", hash 90990ff4) opened the
 * three ILLIT releases below, 7 + 4 + 3 = 14 tracks. BREAK EVEN holds the song,
 * but only as "체포하겠어", so no title comparison could reach it. The audio
 * switch's search pages did hold the rows that print both names (the Korean MV
 * V2V9-9VhYJo and the fanchant 89Hb35BWzRw), and the lookup never saw them.
 * Titles, ids and runtimes are the real ones (album pages read 2026-11-09).
 */
class CatalogTitleBridgeTest {

    private fun track(id: String, title: String, duration: String) = Song(
        videoId = id,
        title = title,
        artist = "ILLIT",
        thumbnailUrl = null,
        durationText = duration,
    )

    private fun video(id: String, title: String, artist: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isVideo = true,
    )

    /**
     * The row as the log names it (`no official song match videoId=NPZtKA6343o
     * for 'You’re Under Arrest' by 'ILLIT'`), with the MV's video timing.
     */
    private fun target() = TrackMatcher.Target(
        title = "You’re Under Arrest",
        artist = "ILLIT",
        durationSec = 192,
        isVideo = true,
    )

    private val remixes = listOf(
        track("_oxkM9D6ZlY", "빌려온 고양이 (Do the Dance)", "3:09"),
        track("1npRQWACRUg", "빌려온 고양이 (Do the Dance) (Snail's House Remix)", "3:16"),
        track("blQn1jHarV0", "빌려온 고양이 (Do the Dance) (Sunlight Remix)", "3:15"),
        track("wcfu-Sm7SAo", "빌려온 고양이 (Do the Dance) (Fig Tape Remix)", "2:54"),
        track("Wz1tglvY4uw", "빌려온 고양이 (Do the Dance) (Do the Dance) (Breakbot & Irfane Remix)", "3:05"),
        track("tOpicgenvYs", "빌려온 고양이 (Do the Dance) (Slowed + Reverb)", "3:56"),
        track("bxBhPAXK9ec", "빌려온 고양이 (Do the Dance) (Instrumental)", "3:09"),
    )

    private val breakEven = listOf(
        track("AQUrMavKqVg", "Super in Love", "3:00"),
        track("oSJX4sCLFfo", "More", "2:54"),
        track("qsqvJpOgrWw", "체포하겠어", "3:02"),
        track("M2Tb2kjzzxo", "Pop", "2:24"),
    )

    private val alarmRemix = listOf(
        track("XQSMUmmBcoo", "체포하겠어 (Alarm Remix)", "0:27"),
        track("Jl10YLW3DVY", "체포하겠어 (Alarm Remix English ver.)", "0:27"),
        track(
            "BgxngIgIzqw",
            "You're Under Arrest (Alarm Remix Japanese ver.) - 체포하겠어 (Alarm Remix Japanese ver.)",
            "0:27",
        ),
    )

    /** Rows of the logged search page (184d, query "youre under arrest"). */
    private val searchPage = listOf(
        video("V2V9-9VhYJo", "체포하겠어 (You’re Under Arrest)", "ILLIT"),
        video("89Hb35BWzRw", "ILLIT (아일릿) '체포하겠어 (You’re Under Arrest)' 응원법", "ILLIT"),
        video("UVHz23YE1nY", "You’re Under Arrest", "ILLIT"),
        video("xB5Ia_Nnlvg", "ILLIT 'You're Under Arrest' (4K) | STUDIO CHOOM ORIGINAL", "STUDIO CHOOM"),
        video("Jl10YLW3DVY", "You're Under Arrest (Alarm Remix English ver.)", "ILLIT"),
    )

    private fun lookup(bridges: List<Song>): Song? {
        val target = target()
        val targets = listOf(target) +
            CatalogSongMapper.alternateTitles(target.title).map { target.copy(title = it) }
        for (page in listOf(remixes, breakEven, alarmRemix)) {
            val found = CatalogSongMapper.albumTrackFor(page, targets, sourceIsVideo = true)
                ?: CatalogSongMapper.bridgedAlbumTrack(page, target, bridges)
            if (found != null) return found
        }
        return null
    }

    @Test
    fun `the fetched pages name the song only in korean, so the title rules miss it`() {
        // The exact 182a/184d miss: no page answers without the bridging rows.
        assertNull(lookup(bridges = emptyList()))
    }

    @Test
    fun `the search page's both-names rows map the mv to the break even track`() {
        val bridges = KoTrackMatcher.titleBridges(searchPage, target())
        val ids = bridges.map { it.videoId }
        assertTrue("V2V9-9VhYJo" in ids, ids.toString())
        // One-name rows and a remix take are not evidence of a second name.
        for (id in listOf("UVHz23YE1nY", "xB5Ia_Nnlvg", "Jl10YLW3DVY")) {
            assertTrue(id !in ids, ids.toString())
        }
        assertEquals("qsqvJpOgrWw", lookup(bridges)?.videoId)
        // The Korean MV alone is enough.
        assertEquals(
            "qsqvJpOgrWw",
            lookup(bridges.filter { it.videoId == "V2V9-9VhYJo" })?.videoId,
        )
    }

    @Test
    fun `a bridge never lets the remix teaser or another song of the act through`() {
        val bridges = KoTrackMatcher.titleBridges(searchPage, target())
        // The 0:27 Alarm Remix takes and the other BREAK EVEN tracks.
        assertNull(CatalogSongMapper.bridgedAlbumTrack(alarmRemix, target(), bridges))
        assertNull(CatalogSongMapper.bridgedAlbumTrack(remixes, target(), bridges))
        assertNull(
            CatalogSongMapper.bridgedAlbumTrack(
                breakEven.filterNot { it.videoId == "qsqvJpOgrWw" },
                target(),
                bridges,
            ),
        )
    }

    @Test
    fun `a bridged korean title still refuses covers takes other acts and other runtimes`() {
        val bridges = KoTrackMatcher.titleBridges(searchPage, target())
        val song = breakEven.first { it.videoId == "qsqvJpOgrWw" }
        for (wrong in listOf(
            song.copy(videoId = "COVER000001", title = "체포하겠어 (Cover)"),
            song.copy(videoId = "LIVE0000001", title = "체포하겠어 (Live)"),
            song.copy(videoId = "SPEDUP00001", title = "체포하겠어 (Sped Up)"),
            song.copy(videoId = "INSTR000001", title = "체포하겠어 (Instrumental)"),
            song.copy(videoId = "OTHERACT001", artist = "Another Singer"),
            song.copy(videoId = "LONG0000001", durationText = "6:40"),
            song.copy(videoId = "TEASER00001", durationText = "0:35"),
        )) {
            assertNull(
                CatalogSongMapper.bridgedAlbumTrack(listOf(wrong), target(), bridges),
                wrong.videoId,
            )
        }
    }

    @Test
    fun `only rows that print the title with a second name are bridges`() {
        val target = target()
        // One name only, another act's both-names row, and a remix aside.
        val notBridges = listOf(
            video("UVHz23YE1nY", "You’re Under Arrest", "ILLIT"),
            video("RndQO21B5FE", "Hello (안녕)", "ILLIT"),
            video("Jl10YLW3DVY", "You're Under Arrest (Alarm Remix English ver.)", "ILLIT"),
        )
        assertTrue(KoTrackMatcher.titleBridges(notBridges, target).isEmpty())
        // A both-names row of another act is kept as evidence, but the act
        // check against the track refuses it.
        val foreign = video("FOREIGN0001", "체포하겠어 (You’re Under Arrest)", "Another Singer")
        assertNull(
            CatalogSongMapper.bridgedAlbumTrack(breakEven, target, listOf(foreign)),
        )
    }
}
