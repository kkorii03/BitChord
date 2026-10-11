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
}
