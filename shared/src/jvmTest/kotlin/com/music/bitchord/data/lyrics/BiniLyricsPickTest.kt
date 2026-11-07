package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which row of a BiniLyrics name search is the recording.
 *
 * The API ranks by length, so a music video is answered with the remix that
 * happens to be closest to it before the track itself (pB-5XG-DbAA: a 210 s
 * video, a 212 s Shy FX remix, a 173 s track). Taking the first row handed the
 * remix's ISRC to lrc.red, whose sheet is the same words on a different clock.
 */
class BiniLyricsPickTest {

    private fun hit(title: String, artist: String, seconds: Int, isrc: String) =
        BiniLyrics.Hit(
            trackName = title,
            artistName = artist,
            duration = seconds,
            isrc = isrc,
            lyricsUrl = "https://lrc.red/s/$isrc.ttml",
        )

    /** The live `lyrics-api.binimum.org` answer for `Stay With Me Sam Smith`, 210 s. */
    private val stayWithMe = listOf(
        hit("Stay With Me (Shy FX Remix)", "Sam Smith", 212, "GBUM71401439"),
        hit("Stay With Me", "Sam Smith", 173, "GBUM71308833"),
        hit("Stay with Me (feat. Mary J. Blige)", "Sam Smith", 173, "GBUM71402189"),
        hit("Stay With Me (Re-record)", "Sam Smith", 173, "GBUM72402567"),
    )

    @Test
    fun `the track is picked over the remix the API ranked first`() {
        val picked = BiniLyrics.pick(stayWithMe, "Stay With Me", "Sam Smith", 210_000)
        assertEquals("GBUM71308833", picked?.isrc)
        // The old code took this row.
        assertEquals("GBUM71401439", stayWithMe.first().isrc)
    }

    @Test
    fun `a row under another spelling of the same song is still taken`() {
        // A Korean search answered in English: no row meets the rules, and refusing
        // them all would lose the ISRC that lrc.red, BiniLyrics and LyricsPlus read.
        val answered = listOf(hit("Good Day", "IU", 233, "KRA381001057"))

        assertNull(BiniLyrics.pick(answered, "좋은 날", "아이유", 233_000))
        assertEquals("KRA381001057", BiniLyrics.rowFor(answered, "좋은 날", "아이유", 233_000)?.isrc)
    }

    @Test
    fun `a 37 s music-video overhang is the same recording`() {
        // 210 s video, 173 s track: the pB-5XG-DbAA case. The old 30 s limit
        // refused it, so the Apple hosts had no sheet for the song at all.
        val candidates = listOf(
            RecordingMatch.Candidate(
                value = "track",
                title = "Stay With Me",
                artist = "Sam Smith",
                seconds = 173.0,
            ),
        )
        assertEquals("track", RecordingMatch.best(candidates, "Stay With Me", "Sam Smith", 210_000))
    }

    @Test
    fun `a row far shorter than the song is still refused`() {
        // 60 s shorter than the song is past any intro a video adds.
        assertNull(BiniLyrics.pick(stayWithMe, "Stay With Me", "Sam Smith", 233_000))
    }
}