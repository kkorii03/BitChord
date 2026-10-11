package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Lyrics lined up against a speech model's timed words — misheard, missed and all. */
class LyricsWordAlignTest {

    private fun heard(text: String, from: Long, to: Long) = LyricsWordAlign.Heard(text, from, to)

    @Test
    fun linesStartWhereTheirOpeningWasHeard() {
        val lines = listOf("비겁한 족쇄를 벗고", "거인과 맞서리라", "날렵한 펜과 날 센 칼은", "최고의 든든한 동무", "사랑해 너를 영원히")
        val words = listOf(
            heard("비겁한", 10_100, 10_700), heard("족세를", 10_800, 11_500),
            heard("거인과", 14_050, 14_700), heard("맞서", 14_800, 15_300),
            heard("음", 16_000, 16_500),
            heard("날렵한", 18_550, 19_200), heard("칼은", 21_000, 21_600),
            heard("든든한", 23_900, 24_500), heard("동무", 24_600, 25_200),
            heard("사랑해", 27_050, 27_700), heard("영원히", 28_500, 29_300),
        )
        val starts = LyricsWordAlign.placeLines(lines, words)
        val truth = listOf(10_000L, 14_000L, 18_500L, 23_000L, 27_000L)
        starts.zip(truth).forEach { (got, want) ->
            val at = assertNotNull(got)
            assertTrue(kotlin.math.abs(at - want) <= 600, "$at vs $want")
        }
    }

    @Test
    fun englishAndNothingHeard() {
        val lines = listOf("Cause you forgot yesterday", "I'm still here waiting", "Payphone calling home")
        val words = listOf(
            heard("cause", 5_000, 5_300), heard("you", 5_300, 5_500), heard("forgot", 5_500, 6_000),
            heard("im", 9_000, 9_200), heard("still", 9_200, 9_600), heard("payphone", 13_000, 13_800),
        )
        assertEquals(listOf<Long?>(5_000L, 9_000L, 13_000L), LyricsWordAlign.placeLines(lines, words))
        assertEquals(listOf<Long?>(null, null, null), LyricsWordAlign.placeLines(lines, emptyList()))
    }

    @Test
    fun deepgramAnswersAreRead() {
        val body = """{"results":{"channels":[{"alternatives":[{"transcript":"사랑해 너를","words":[""" +
            """{"word":"사랑해","start":1.25,"end":1.9},{"word":"너를","start":2.0,"end":2.4}]}]}]}}"""
        val words = assertNotNull(Deepgram.parse(body))
        assertEquals(listOf("사랑해", "너를"), words.map { it.text })
        assertEquals(1_250L, words.first().startMs)
        assertEquals("ko", Deepgram.languageFor(listOf("사랑해 너를 영원히")))
        assertEquals("ja", Deepgram.languageFor(listOf("昨日のことを忘れたから")))
        assertEquals("multi", Deepgram.languageFor(listOf("Cause you forgot yesterday")))
        assertEquals("en", Deepgram.languageFor(listOf("I know you love me", "and I don't know what to do with all of it")))
        assertEquals("multi", Deepgram.languageFor(listOf("Despacito quiero respirar tu cuello despacito", "deja que te diga cosas al oído")))
    }
}
