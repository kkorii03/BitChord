package com.music.bitchord.data.innertube

import com.music.bitchord.data.YouTubeMusicClassifier
import com.music.bitchord.data.YouTubeMusicClassifier.Verdict
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The classifier's cheap rules and its cache are pure enough to test without a
 * network; the category lookup is replaced with a local lambda so the
 * "unknown keeps the row" contract is exercised too.
 */
class YouTubeMusicClassifierTest {

    private class MemoryStore : MusicGateFallback.Store {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    @BeforeTest
    fun setUp() {
        YouTubeMusicClassifier.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        YouTubeMusicClassifier.resetForTest()
    }

    @Test
    fun `topic and VEVO channels are music without a request`() {
        assertEquals(
            Verdict.MUSIC,
            YouTubeMusicClassifier.ruleVerdict("Dynamite", "BTS - Topic"),
        )
        assertEquals(
            Verdict.MUSIC,
            YouTubeMusicClassifier.ruleVerdict("Dynamite", "BTSVEVO"),
        )
        assertEquals(
            Verdict.MUSIC,
            YouTubeMusicClassifier.ruleVerdict("Dynamite", "Some Channel", album = "BE"),
        )
        assertNull(YouTubeMusicClassifier.ruleVerdict("Dynamite", "Some Vlog Channel"))
    }

    @Test
    fun `only the Music category counts as music`() {
        assertEquals(Verdict.MUSIC, YouTubeMusicClassifier.categoryVerdict("Music"))
        assertEquals(Verdict.MUSIC, YouTubeMusicClassifier.categoryVerdict("music"))
        assertEquals(
            Verdict.NOT_MUSIC,
            YouTubeMusicClassifier.categoryVerdict("Film & Animation"),
        )
        assertEquals(Verdict.UNKNOWN, YouTubeMusicClassifier.categoryVerdict(null))
        assertEquals(Verdict.UNKNOWN, YouTubeMusicClassifier.categoryVerdict("  "))
    }

    @Test
    fun `a failed lookup keeps the row and is remembered as unknown`() = runBlocking {
        var asked = 0
        YouTubeMusicClassifier.categoryLookup = {
            asked++
            null
        }

        assertTrue(YouTubeMusicClassifier.isMusic("v-unknown", "A title", "A channel"))
        assertEquals(Verdict.UNKNOWN, YouTubeMusicClassifier.cached("v-unknown"))
        // A stored unknown is trusted for a while: no second request, still visible.
        assertTrue(YouTubeMusicClassifier.isMusic("v-unknown", "A title", "A channel"))
        assertEquals(1, asked)
    }

    @Test
    fun `a stated non-music category hides the row`() = runBlocking {
        YouTubeMusicClassifier.categoryLookup = { "Film & Animation" }
        assertFalse(YouTubeMusicClassifier.isMusic("v-film", "A short film", "Uploader"))
        assertEquals(Verdict.NOT_MUSIC, YouTubeMusicClassifier.cached("v-film"))
    }

    @Test
    fun `history rows beyond the shown page are not looked up`() = runBlocking {
        var asked = 0
        YouTubeMusicClassifier.categoryLookup = {
            asked++
            "Music"
        }
        val songs = (0 until 40).map {
            Song("v$it", "Song $it", "Artist", null)
        }

        val kept = YouTubeMusicClassifier.filterMusic(songs, limit = 30)

        assertEquals(40, kept.size)
        assertEquals(30, asked)
    }

    @Test
    fun `a video longer than fifteen minutes is not a song`() {
        assertEquals(
            Verdict.NOT_MUSIC,
            YouTubeMusicClassifier.ruleVerdict(
                "Full musical",
                "Some Theatre",
                durationSec = 15 * 60 + 1,
            ),
        )
        assertNull(
            YouTubeMusicClassifier.ruleVerdict(
                "Full musical",
                "\uC0AC\uC6B4\uB4DC\uD2B8\uB799",
                durationSec = 15 * 60,
            ),
        )
    }

    @Test
    fun `liked videos split into songs and hidden videos`() = runBlocking {
        YouTubeMusicClassifier.categoryLookup = { id ->
            if (id == "v-video") "Entertainment" else "Music"
        }
        val songs = listOf(
            Song("v-song", "Song", "Artist", null, durationText = "3:12"),
            Song("v-video", "Fancam", "Channel", null, durationText = "4:00"),
            Song("v-long", "Full musical", "Theatre", null, durationText = "57:44"),
            Song("v-tail", "Tail", "Artist", null),
        )

        val (music, hidden) = YouTubeMusicClassifier.splitMusic(songs, limit = 3)

        assertEquals(listOf("v-song", "v-tail"), music.map { it.videoId })
        assertEquals(listOf("v-video", "v-long"), hidden.map { it.videoId })
    }

    @Test
    fun `the verdict cache drops the oldest entry at its cap`() {
        repeat(YouTubeMusicClassifier.MAX_ENTRIES + 1) { index ->
            YouTubeMusicClassifier.remember("v$index", Verdict.MUSIC, index.toLong())
        }

        assertEquals(YouTubeMusicClassifier.MAX_ENTRIES, YouTubeMusicClassifier.cacheSize())
        assertNull(YouTubeMusicClassifier.cached("v0"))
        assertEquals(Verdict.MUSIC, YouTubeMusicClassifier.cached("v1"))
    }

    @Test
    fun `the verdicts survive a restart`() {
        val store = MemoryStore()
        YouTubeMusicClassifier.resetForTest(store)
        YouTubeMusicClassifier.remember("v-persisted", Verdict.NOT_MUSIC, 42L)

        YouTubeMusicClassifier.resetForTest(store)

        assertEquals(Verdict.NOT_MUSIC, YouTubeMusicClassifier.cached("v-persisted"))
    }
}
