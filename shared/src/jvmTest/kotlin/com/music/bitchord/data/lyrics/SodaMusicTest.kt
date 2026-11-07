package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Soda Music answers a name search with catalogue rows and only then, by track
 * id, a KRC-shaped word sheet. Both are held to size bounds and the search row
 * to [RecordingMatch]; anything else is no answer at all.
 *
 * The endpoints were reconfirmed on 2026-11-07 (`ship\lanes\soda.md`); these
 * fixtures are synthetic — the names and times are what the checks care about,
 * and the words are made-up placeholders.
 */
class SodaMusicTest {

    /** A catalogue answer: one unrelated group, then the track row. */
    private val searchAnswer =
        """{"result_groups":[{"id":"artists","data":[{"entity":{}}]},{"id":"tracks","data":[""" +
            """{"entity":{"track":{"id":"42","name":"Zonk","artists":[{"name":"Zed"}],"duration":200000}}}]}]}"""

    /** A KRC-shaped word sheet for the row above. */
    private val detailAnswer =
        """{"lyric":{"content":"[1000,2000]<0,500,0>zonk <500,500,0>blip\n""" +
            """[3000,1500]<0,750,0>flim <750,750,0>fram"},""" +
            """"seo_track":{"track":{"id":"42"}}}"""

    /** The same sheet, timed only per line. */
    private val lineOnlyAnswer =
        """{"lyric":{"content":"[00:01.00]zonk blip"},"seo_track":{"track":{"id":"42"}}}"""

    private val answer: suspend (String) -> String? = { url ->
        if (url.contains("seo_track")) detailAnswer else searchAnswer
    }

    @Test
    fun `a made-up krc answer becomes word synced lines`() = runBlocking {
        val lines = assertNotNull(SodaMusic.lyrics("Zonk", "Zed", 200_000, get = answer))
            .filterNot { it.isGap }
        assertEquals(2, lines.size)
        assertEquals("zonk blip", lines[0].text)
        assertEquals(listOf(1_000L, 1_500L), lines[0].words.map { it.startMs })
        assertEquals(listOf(1_500L, 2_000L), lines[0].words.map { it.endMs })
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals(listOf(3_000L, 3_750L), lines[1].words.map { it.startMs })
        assertTrue(lines.all { it.isWordSynced })
    }

    @Test
    fun `an error answer is refused`() = runBlocking {
        // Nothing answered at all.
        assertNull(SodaMusic.lyrics("Zonk", "Zed", 200_000, get = { null }))
        // An error envelope rather than a search result.
        assertNull(
            SodaMusic.lyrics("Zonk", "Zed", 200_000, get = { """{"status_info":{"status_code":-1}}""" }),
        )
        // Not JSON at all.
        assertNull(SodaMusic.lyrics("Zonk", "Zed", 200_000, get = { "<html>refused</html>" }))
        // A detail that fails or lies about the track id.
        assertNull(
            SodaMusic.lyrics("Zonk", "Zed", 200_000) { url ->
                if (url.contains("seo_track")) """{"lyric":{"content":"[1000,2000]<0,500,0>zonk"},""" +
                    """"seo_track":{"track":{"id":"43"}}}""" else searchAnswer
            },
        )
        assertNull(
            SodaMusic.lyrics("Zonk", "Zed", 200_000) { url ->
                if (url.contains("seo_track")) null else searchAnswer
            },
        )
    }

    @Test
    fun `an empty answer is refused`() = runBlocking {
        val emptySearch = """{"result_groups":[{"id":"tracks","data":[]}]}"""
        assertNull(SodaMusic.lyrics("Zonk", "Zed", 200_000, get = { emptySearch }))
        // A row but no words, and a sheet with no word stamps.
        assertNull(
            SodaMusic.lyrics("Zonk", "Zed", 200_000) { url ->
                if (url.contains("seo_track")) {
                    """{"lyric":{"content":""},"seo_track":{"track":{"id":"42"}}}"""
                } else {
                    searchAnswer
                }
            },
        )
        assertNull(
            SodaMusic.lyrics("Zonk", "Zed", 200_000) { url ->
                if (url.contains("seo_track")) lineOnlyAnswer else searchAnswer
            },
        )
        // No seo_track at all is not the shape this endpoint answers.
        assertNull(
            SodaMusic.lyrics("Zonk", "Zed", 200_000) { url ->
                if (url.contains("seo_track")) """{"lyric":{"content":"[1000,2000]<0,500,0>zonk"}}""" else searchAnswer
            },
        )
    }

    @Test
    fun `the recording guard refuses a namesake and another length`() {
        val hit = SodaMusic.Hit(id = "42", title = "Zonk", artist = "Zed", seconds = 200.0)
        assertNull(SodaMusic.best(listOf(hit), "Zonk", "Nope", 200_000))
        assertNull(SodaMusic.best(listOf(hit), "Zonk", "Zed", 100_000))
        assertEquals(hit, SodaMusic.best(listOf(hit), "Zonk", "Zed", 200_000))
    }

    @Test
    fun `an oversized answer is refused`() = runBlocking {
        // A body past the search bound.
        val huge = """{"result_groups":[]}""" + " ".repeat(1_000_000)
        assertNull(SodaMusic.lyrics("Zonk", "Zed", 200_000, get = { huge }))
        // More credited artists on one row than a catalogue row carries.
        val artists = (1..17).joinToString(",") { """{"name":"A$it"}""" }
        val crowded =
            """{"result_groups":[{"id":"tracks","data":[{"entity":{"track":{"id":"42",""" +
                """"name":"Zonk","artists":[$artists],"duration":200000}}}]}]}"""
        assertNull(
            SodaMusic.lyrics("Zonk", "Zed", 200_000) { url ->
                if (url.contains("seo_track")) detailAnswer else crowded
            },
        )
    }
}