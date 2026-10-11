package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Reading Gemini's "this line starts at" answers. */
class LyricsTranslationParseTest {

    @Test
    fun aiAnswersAreReadByIndexAndClock() {
        assertEquals(83_400L, LyricsTranslation.parseClock("1:23.4"))
        assertEquals(83_000L, LyricsTranslation.parseClock("01:23"))
        assertEquals(12_500L, LyricsTranslation.parseClock("12.5"))
        assertEquals(3_723_000L, LyricsTranslation.parseClock("1:02:03"))
        assertNull(LyricsTranslation.parseClock("-1"))
        assertNull(LyricsTranslation.parseClock("1.5:20"))
        assertNull(LyricsTranslation.parseClock("soon"))
        val starts = assertNotNull(
            LyricsTranslation.parsePlacements(
                """{"placements":[{"i":0,"t":"0:05.0"},{"i":2,"t":"0:15.5"},{"i":9,"t":"1:00"},{"i":3,"t":"-"}]}""",
                count = 4,
            ),
        )
        assertEquals(listOf(5_000L, null, 15_500L, null), starts)
        assertNull(LyricsTranslation.parsePlacements("""{"placements":[{"i":0,"t":"0:05"}]}""", 4))
    }

    @Test
    fun transcriptsAreReadInTimeOrder() {
        val lines = assertNotNull(
            LyricsTranslation.parseTranscript(
                """{"sung":true,"lines":[{"t":"0:12.0","text":"첫 줄"},{"t":"0:16.5","text":"둘째 줄"},""" +
                    """{"t":"0:15.0","text":"셋째 줄"},{"t":"0:20","text":" "},{"t":"0:24.0","text":"넷째 줄"}]}""",
                180_000L,
            ),
        )
        assertEquals(listOf(12_000L, 15_000L, 16_500L, 24_000L), lines.map { it.timeMs })
        assertEquals("첫 줄", lines.first().text)
        // Nothing sung: an empty answer, remembered as such.
        assertEquals(emptyList(), LyricsTranslation.parseTranscript("""{"sung":false,"lines":[]}""", 180_000L))
        // Too thin to show, or past the end of the video.
        assertNull(LyricsTranslation.parseTranscript("""{"sung":true,"lines":[{"t":"0:12","text":"a"}]}""", 180_000L))
        assertNull(
            LyricsTranslation.parseTranscript(
                """{"sung":true,"lines":[{"t":"9:00","text":"a"},{"t":"9:05","text":"b"},{"t":"9:09","text":"c"}]}""",
                180_000L,
            ),
        )
    }
}
