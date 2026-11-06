package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.LyricsWordAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * review-fit finding 1: refit judges words already kept and makes no request, yet it marked
 * Whisper as spent and cleared a waiting retry, so the song check that would have heard the
 * whole song never ran.
 *
 * shortcut: checks the rule, not refit itself, because Uri.parse is a stub on the plain JVM here;
 * read `song-sync: refit` then the song check's request in an emulator log.
 */
class LyricsTimingRefitSpendTest {

    @Test
    fun `words judged again spend no engine`() {
        val fromFile = SongHearing.Heard(
            words = listOf(LyricsWordAlign.Heard("a", 1_000L, 1_400L)),
            audioMs = 90_000L,
            clock = LyricsTiming.Clock.YouTube,
            httpCode = 200,
            fromStore = true,
        )
        assertTrue("a kept transcript says 200, which spends", AiTimingPolicy.songReplySpends(fromFile.httpCode))

        val asked = fromFile.askedNothing()

        assertFalse(AiTimingPolicy.songReplySpends(asked.httpCode))
        assertEquals(fromFile.words, asked.words)
        assertEquals(fromFile.audioMs, asked.audioMs)
        assertEquals(fromFile.clock, asked.clock)
    }
}
