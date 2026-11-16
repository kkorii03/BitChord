package com.music.bitchord.data.lyrics

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** L18: an Apple search row must name the song; length alone no longer picks it. */
class PaxSenixPickTest {

    private fun root(vararg songs: Triple<String, String, Long>) = Json.parseToJsonElement(
        songs.mapIndexed { i, (name, artist, ms) ->
            """{"id":"$i","type":"songs","attributes":{"name":"$name","artistName":"$artist","durationInMillis":$ms}}"""
        }.joinToString(",", prefix = """{"results":{"songs":{"data":[""", postfix = "]}}}"),
    )

    @Test
    fun aRowWithAnotherTitleIsNotPickedOnLengthAlone() {
        val root = root(Triple("Other Song", "Someone", 200_500))
        assertNull(PaxSenix.bestCandidateId(root, "Wanted", "Artist", 200_000))
    }

    @Test
    fun theArtistAloneDoesNotPickAnotherSongOfTheirs() {
        val root = root(Triple("Old Hit", "Artist", 201_000))
        assertNull(PaxSenix.bestCandidateId(root, "New Single", "Artist", 200_000))
    }

    @Test
    fun theRightRowIsStillPicked() {
        val root = root(Triple("Other Song", "Artist", 200_000), Triple("Wanted", "Artist", 203_000))
        assertEquals("1", PaxSenix.bestCandidateId(root, "Wanted", "Artist", 200_000))
    }

    @Test
    fun aRowSpelledInAnotherScriptStillDecidesOnTheOldRules() {
        val root = root(Triple("Good Day", "IU", 233_000))
        assertEquals("0", PaxSenix.bestCandidateId(root, "좋은 날", "아이유", 233_400))
    }
}
