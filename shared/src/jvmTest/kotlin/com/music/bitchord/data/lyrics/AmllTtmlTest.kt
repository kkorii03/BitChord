package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AMLL TTML DB: the iTunes id guard, and the human-timed document behind it. */
class AmllTtmlTest {

    /** A trimmed iTunes Search response, as saved from the live API. */
    private val searchJson = """
        {"resultCount":4,"results":[
          {"wrapperType":"track","kind":"song","trackId":1193701392,
           "artistName":"Ed Sheeran","trackName":"Shape of You",
           "trackTimeMillis":233713},
          {"wrapperType":"track","kind":"song","trackId":1275062558,
           "artistName":"Ed Sheeran","trackName":"Shape of You (NOTD Remix)",
           "trackTimeMillis":190098},
          {"wrapperType":"track","kind":"song","trackId":1202046879,
           "artistName":"Ed Sheeran","trackName":"Shape of You (Acoustic)",
           "trackTimeMillis":223573},
          {"wrapperType":"track","kind":"song","trackId":999000111,
           "artistName":"Ed Sheeran","trackName":"Shape of You",
           "trackTimeMillis":250000}
        ]}
    """.trimIndent()

    @Test
    fun `saved iTunes json gives the apple id and the guard fields`() {
        val hits = AmllTtml.parseSearch(searchJson)!!
        assertEquals(4, hits.size)
        assertEquals(1193701392L, hits.first().trackId)
        assertEquals("Shape of You", hits.first().trackName)
        assertEquals("Ed Sheeran", hits.first().artistName)
        assertEquals(233713L, hits.first().trackTimeMillis)
    }

    @Test
    fun `ranking takes the hit whose length matches`() {
        val hits = AmllTtml.parseSearch(searchJson)!!
        assertEquals(
            1193701392L,
            AmllTtml.best(hits, "Shape of You", "Ed Sheeran", 233_000)?.trackId,
        )
        assertEquals(
            999000111L,
            AmllTtml.best(hits, "Shape of You", "Ed Sheeran", 250_000)?.trackId,
        )
    }

    @Test
    fun `ranking refuses a length, artist or version that differs`() {
        val hits = AmllTtml.parseSearch(searchJson)!!
        // Nothing is within 3 s of this one.
        assertNull(AmllTtml.best(hits, "Shape of You", "Ed Sheeran", 220_000))
        // The acoustic cut is close in length but is another recording.
        assertNull(AmllTtml.best(hits, "Shape of You", "Ed Sheeran", 223_000))
        // The remix is another recording too.
        assertNull(AmllTtml.best(hits, "Shape of You", "Ed Sheeran", 190_000))
        // Same title, different artist.
        assertNull(AmllTtml.best(hits, "Shape of You", "Someone Else", 233_000))
    }

    @Test
    fun `a named version only matches the same version`() {
        val hits = AmllTtml.parseSearch(searchJson)!!
        assertEquals(
            1202046879L,
            AmllTtml.best(hits, "Shape of You (Acoustic)", "Ed Sheeran", 223_000)?.trackId,
        )
    }

    @Test
    fun `document url is the amll apple folder`() {
        assertEquals(
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/am-lyrics/1193701392.ttml",
            AmllTtml.documentUrl(1193701392),
        )
    }

    @Test
    fun `ttml keeps lines words nested spans and background vocals`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head>
            <body><div>
            <p begin="1.0" end="3.0" ttm:agent="v1"><span begin="1.0" end="1.4">Hel</span><span begin="1.4" end="1.8">lo</span> <span begin="1.8" end="2.2"><span begin="1.8" end="2.0">wor</span><span begin="2.0" end="2.2">ld</span></span></p>
            <p begin="3.0" end="5.0" ttm:agent="v1"><span begin="3.0" end="3.5">lead</span><span ttm:role="x-bg" begin="3.5" end="5.0">echo</span><span ttm:role="x-translation" xml:lang="ko">번역</span></p>
            </div></body></tt>
        """.trimIndent()

        val lines = TtmlLyrics.parse(ttml)
        assertEquals(listOf("Hello world", "lead"), lines.map { it.text })
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals(listOf("Hello", "world"), lines[0].words.map { it.text })
        assertEquals(2, lines[0].words[1].syllables.size)
        assertEquals("echo", lines[1].background?.text)
        assertTrue(lines.none { "번역" in it.text })
    }
}
