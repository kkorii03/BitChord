package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class SongCheckWindowTest {

    @Test
    fun `lines before the window start are inside and later ones are not`() {
        val times = listOf(0L, 1_000L, 89_999L, 90_000L, 90_001L, 300_000L)
        assertEquals(listOf(0, 1, 2, 3), SongCheckWindow.indices(times, 90_000L))
    }

    @Test
    fun `a line timed before the song starts is left out`() {
        val times = listOf(-500L, 0L, 4_000L)
        assertEquals(listOf(1, 2), SongCheckWindow.indices(times, 90_000L))
    }

    @Test
    fun `an empty window has no lines`() {
        assertEquals(emptyList<Int>(), SongCheckWindow.indices(listOf(0L, 1L), 0L))
    }

    @Test
    fun `the window is the real wav length, not what was asked for`() {
        // A ninety-second window, the full size and one shorter than the header.
        assertEquals(90_000L, SongCheckWindow.wavMs(44 + 32 * 90_000))
        assertEquals(42_500L, SongCheckWindow.wavMs(44 + 32 * 42_500))
        assertEquals(0L, SongCheckWindow.wavMs(44))
        assertEquals(0L, SongCheckWindow.wavMs(10))
    }

    @Test
    fun `a partial millisecond of audio is not a whole millisecond`() {
        assertEquals(1_000L, SongCheckWindow.wavMs(44 + 32 * 1_000 + 31))
        assertEquals(1_001L, SongCheckWindow.wavMs(44 + 32 * 1_001))
        assertEquals(0L, SongCheckWindow.wavMs(44 + 31))
    }

    @Test
    fun `the window end is inclusive and a negative window is empty`() {
        assertEquals(listOf(0, 1), SongCheckWindow.indices(listOf(4_000L, 5_000L, 5_001L), 5_000L))
        assertEquals(emptyList<Int>(), SongCheckWindow.indices(listOf(0L), -1L))
        assertEquals(emptyList<Int>(), SongCheckWindow.indices(emptyList(), 90_000L))
    }

    @Test
    fun `the wav constants describe 16 khz mono 16 bit audio`() {
        assertEquals(44, SongCheckWindow.WAV_HEADER_BYTES)
        assertEquals(32, SongCheckWindow.BYTES_PER_MS)
    }

    @Test
    fun `a playing video hears the quick opening beside its whole-song request`() {
        // 11-08 WwFUZS8dv_4: only the whole-song request was asked, and it answered after the play.
        assertEquals(true, SongCheckWindow.videoQuickFirst(isVideo = true, nextRow = false, wordEngineDue = true, quickEngine = true))
        // A next row has its head only; a song has its own song check; a spent engine is not bought twice.
        assertEquals(false, SongCheckWindow.videoQuickFirst(isVideo = true, nextRow = true, wordEngineDue = true, quickEngine = true))
        assertEquals(false, SongCheckWindow.videoQuickFirst(isVideo = false, nextRow = false, wordEngineDue = true, quickEngine = true))
        assertEquals(false, SongCheckWindow.videoQuickFirst(isVideo = true, nextRow = false, wordEngineDue = false, quickEngine = true))
        // The quick request's window: the song check's opening, inside what Groq answers (120 s).
        assertEquals(true, SongCheckWindow.VIDEO_QUICK_SECONDS in SongCheckWindow.FIRST_SECONDS..120.0)
    }

    @Test
    fun `a streamed video is heard as its track starts when the quick engine may run`() {
        assertEquals(true, SongCheckWindow.videoHearAtStart(true, streamed = true, quickEngine = true, heavyWorkAllowed = true, aiTimingAhead = true, wordEngine = true, lyricsLookup = true))
        assertEquals(false, SongCheckWindow.videoHearAtStart(false, streamed = true, quickEngine = true, heavyWorkAllowed = true, aiTimingAhead = true, wordEngine = true, lyricsLookup = true))
        assertEquals(false, SongCheckWindow.videoHearAtStart(true, streamed = false, quickEngine = true, heavyWorkAllowed = true, aiTimingAhead = true, wordEngine = true, lyricsLookup = true))
        assertEquals(false, SongCheckWindow.videoHearAtStart(true, streamed = true, quickEngine = false, heavyWorkAllowed = true, aiTimingAhead = true, wordEngine = true, lyricsLookup = true))
        assertEquals(false, SongCheckWindow.videoHearAtStart(true, streamed = true, quickEngine = true, heavyWorkAllowed = false, aiTimingAhead = true, wordEngine = true, lyricsLookup = true))
        assertEquals(false, SongCheckWindow.videoHearAtStart(true, streamed = true, quickEngine = true, heavyWorkAllowed = true, aiTimingAhead = false, wordEngine = true, lyricsLookup = true))
    }

    @Test
    fun `no quick engine, no second paid request beside the whole song`() {
        // Sol review F4: without Groq the quick request was a 90 s Deepgram one for every video.
        assertEquals(false, SongCheckWindow.videoQuickFirst(isVideo = true, nextRow = false, wordEngineDue = true, quickEngine = false))
        // ...and without Deepgram nothing reads the words heard at the start.
        assertEquals(false, SongCheckWindow.videoHearAtStart(true, streamed = true, quickEngine = true, heavyWorkAllowed = true, aiTimingAhead = true, wordEngine = false, lyricsLookup = true))
    }

    @Test
    fun `no lyrics lookup, nothing heard at the start`() {
        // Sol review F5: with every lyrics source off, no sheet can ever read the words.
        assertEquals(false, SongCheckWindow.videoHearAtStart(true, streamed = true, quickEngine = true, heavyWorkAllowed = true, aiTimingAhead = true, wordEngine = true, lyricsLookup = false))
    }

    @Test
    fun `the quick listen reads the words heard at the start when their language may serve`() {
        // 11-08 n5: "multi" words at the start, asked again in "ko" and "en" for the same words.
        assertEquals("multi", SongCheckWindow.quickLanguage("multi", "ko"))
        assertEquals("ko", SongCheckWindow.quickLanguage("ko", "ko"))
        // The sheet's language unknown: the start's words serve.
        assertEquals("ja", SongCheckWindow.quickLanguage("ja", "multi"))
        // Another language, or nothing heard at the start: the sheet's.
        assertEquals("ko", SongCheckWindow.quickLanguage("en", "ko"))
        assertEquals("ko", SongCheckWindow.quickLanguage(null, "ko"))
    }
}