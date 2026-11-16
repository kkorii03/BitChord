package com.music.bitchord.data.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * LRCLIB's fuzzy search: a search without a length keeps the provider's
 * relevance order, a known length picks the closest same recording, and a hit
 * that is another song or another version is never taken (L10).
 */
class LrcLibDurationTest {

    private fun hit(name: String, seconds: Double, sheet: String): JsonObject =
        Json.parseToJsonElement(
            """{"trackName":"$name","artistName":"Artist","duration":$seconds,"syncedLyrics":"$sheet"}""",
        ).jsonObject

    private val sameSong = listOf(
        hit("Song", 210.0, "album cut"),
        hit("Song", 35.0, "short radio edit"),
        hit("Song", 206.0, "single"),
    )

    @Test
    fun anUnknownLengthDoesNotPreferTheShortestHit() {
        assertEquals("album cut", LrcLib.pickHit(sameSong, "Song", seconds = 0))
    }

    @Test
    fun aKnownLengthStillPicksTheClosestRecording() {
        assertEquals("single", LrcLib.pickHit(sameSong, "Song", seconds = 205))
    }

    @Test
    fun anotherSongOrALiveCutIsNotTaken() {
        val hits = listOf(hit("Other Song", 230.0, "other"), hit("Wanted (Live)", 372.0, "live"))
        assertNull(LrcLib.pickHit(hits, "Wanted", seconds = 215))
    }

    @Test
    fun aLiveCutOfTheRightLengthIsStillAnotherVersion() {
        val hits = listOf(hit("Wanted (Live)", 216.0, "live"), hit("Wanted", 214.0, "studio"))
        assertEquals("studio", LrcLib.pickHit(hits, "Wanted", seconds = 215))
    }
}
