package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The suggestion answer carries both query completions and the music rows
 * that prove which completions are about music; the general row must not be
 * mistaken for a music term.
 */
class SearchSuggestionParseTest {

    @Test
    fun `queries and music entity terms are read from one answer`() {
        val response = Json.parseToJsonElement(
            """
            { "contents": [
              { "searchSuggestionsSectionRenderer": { "contents": [
                { "searchSuggestionRenderer": {
                  "suggestion": { "runs": [ { "text": "강진 노래" } ] },
                  "navigationEndpoint": { "searchEndpoint": { "query": "강진 노래" } }
                } },
                { "searchSuggestionRenderer": {
                  "suggestion": { "runs": [ { "text": "강진 뉴스" } ] },
                  "navigationEndpoint": { "searchEndpoint": { "query": "강진 뉴스" } }
                } }
              ] } },
              { "searchSuggestionsSectionRenderer": { "contents": [
                { "musicResponsiveListItemRenderer": { "flexColumns": [
                  { "musicResponsiveListItemFlexColumnRenderer": {
                    "text": { "runs": [ { "text": "강진" } ] }
                  } },
                  { "musicResponsiveListItemFlexColumnRenderer": {
                    "text": { "runs": [ { "text": "Song · 강진" } ] }
                  } }
                ] } }
              ] } }
            ] }
            """.trimIndent(),
        ).jsonObject

        val parsed = InnertubeParser.parseSearchSuggestions(response)

        assertEquals(listOf("강진 노래", "강진 뉴스"), parsed.queries)
        assertEquals(listOf("강진", "Song · 강진"), parsed.musicTerms)
    }
}
