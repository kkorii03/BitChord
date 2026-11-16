package com.music.bitchord.data.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** L13: the YouTube Music Lyrics tab must never fall through to the Related tab. */
class YouTubeLyricsTabTest {

    private fun next(lyricsTab: String, relatedId: String = "MPTRt_related") = Json.parseToJsonElement(
        """
        {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
          {"tabRenderer":{"title":"Up next","content":{}}},
          $lyricsTab,
          {"tabRenderer":{"title":"Related","endpoint":{"browseEndpoint":{"browseId":"$relatedId"}}}}
        ]}}}}}
        """.trimIndent(),
    )

    private fun idOf(next: kotlinx.serialization.json.JsonElement): String? =
        (lyricsEndpoint(next)?.get("browseId") as? JsonPrimitive)?.content

    @Test
    fun anUnselectableLyricsTabDoesNotTakeTheRelatedPage() {
        val tab = """{"tabRenderer":{"title":"Lyrics","unselectable":true}}"""
        assertNull(idOf(next(tab)))
    }

    @Test
    fun theLyricsPageIsStillFound() {
        val tab = """{"tabRenderer":{"title":"Lyrics","endpoint":{"browseEndpoint":{"browseId":"MPLYt_abc"}}}}"""
        assertEquals("MPLYt_abc", idOf(next(tab)))
    }

    @Test
    fun aLocalizedLyricsTabIsStillFoundByItsPage() {
        val tab = """{"tabRenderer":{"title":"가사","endpoint":{"browseEndpoint":{"browseId":"MPLYt_ko"}}}}"""
        assertEquals("MPLYt_ko", idOf(next(tab)))
    }

    @Test
    fun aLocalizedUnselectableLyricsTabFindsNothing() {
        val tab = """{"tabRenderer":{"title":"가사","unselectable":true}}"""
        assertNull(idOf(next(tab)))
    }
}
