package com.music.bitchord.data.lyrics

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L notes on [RecordingPick]: a version marker inside an ordinary title
 * ("Undercover", "Live Forever", "Piano Man", "Mr. Brightside") threw the
 * original away for a Hangul query, and the first English row of a stranger
 * was taken as the translation of a Hangul title with nothing agreeing.
 */
class RecordingPickVersionWordTest {

    private fun row(id: String, title: String, artist: String, length: String = "3:40") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length)

    private fun pick(title: String, artist: String, rows: List<Song>, videoMs: Long = 220_000L) =
        RecordingPick.rank(listOf(title), listOf(artist), rows, videoMs).map { it.videoId }

    @Test
    fun titlesThatOnlyContainAMarkerAreOriginals() {
        for (title in listOf("Undercover", "Discover", "Live Forever", "Piano Man", "Mr. Brightside", "Alive")) {
            val picked = pick("언더커버", "아티스트", listOf(row("o", title, "Some Band")))
            assertEquals(listOf("o"), picked, title)
        }
    }

    @Test
    fun realVersionRowsAreStillVersions() {
        for (title in listOf("Song (Live)", "Song - Live at Budokan", "Song Live", "Song (Cover)",
            "Song (Piano Ver.)", "Song 8D Audio", "Song (MR)", "노래 라이브", "노래요Cover")
        ) {
            val picked = pick("노래", "가수", listOf(row("v", title, "가수"), row("o", "노래", "가수")))
            assertEquals("o", picked.first(), title)
        }
    }

    @Test
    fun aStrangersEnglishRowIsNotTheTranslationOfAHangulTitle() {
        // Same script on both sides, no agreement: nothing says it is this song.
        val stranger = listOf(row("s", "Midnight Drive", "Unrelated Band", "3:38"))
        assertEquals(emptyList(), pick("밤의 드라이브", "Raon Lee", stranger))
    }

    @Test
    fun anArtistAcrossScriptsStillAllowsTheTranslation() {
        val rows = listOf(row("a", "This Love", "Davichi", "4:05"))
        assertEquals(listOf("a"), pick("이 사랑", "다비치", rows, videoMs = 248_000L))
    }

    @Test
    fun theSameArtistInTheSameScriptAllowsTheTranslation() {
        val rows = listOf(row("a", "Midnight Drive", "Raon Lee", "3:38"))
        assertTrue("a" in pick("밤의 드라이브", "Raon Lee", rows))
    }
}
