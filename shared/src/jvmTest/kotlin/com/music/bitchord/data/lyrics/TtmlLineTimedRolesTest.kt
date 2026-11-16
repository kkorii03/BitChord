package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * L17: a line-timed paragraph (no word spans) used to take the whole
 * paragraph's text, translation and romanisation spans glued on.
 */
class TtmlLineTimedRolesTest {

    private fun doc(body: String) = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
        $body
        </div></body></tt>
    """.trimIndent()

    @Test
    fun translationAndRomanSpansAreNotTheLine() {
        val lines = TtmlLyrics.parse(
            doc("""<p begin="1.0" end="3.0">본문<span ttm:role="x-translation" xml:lang="zh">译文</span><span ttm:role="x-roman">bonmun</span></p>"""),
        )
        assertEquals(listOf("본문"), lines.map { it.text })
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals(3_000L, lines[0].sungUntilMs)
    }

    @Test
    fun anUntimedBackingSpanStaysInTheLine() {
        val lines = TtmlLyrics.parse(
            doc("""<p begin="1.0" end="3.0">lead <span ttm:role="x-bg">(echo)</span></p>"""),
        )
        assertEquals(listOf("lead (echo)"), lines.map { it.text })
    }

    @Test
    fun aParagraphOfOnlyATranslationIsNoLine() {
        val lines = TtmlLyrics.parse(
            doc(
                """<p begin="1.0" end="2.0">하나</p>
                <p begin="2.0" end="3.0"><span ttm:role="x-translation">译文</span></p>""",
            ),
        )
        assertEquals(listOf("하나"), lines.map { it.text })
    }
}
