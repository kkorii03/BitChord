package com.music.bitchord.playback

import com.music.bitchord.playback.TranslationAheadPolicy.Plan
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAheadPolicyTest {

    @Test
    fun `metered and saving power prepares nothing`() {
        assertEquals(
            Plan.NONE,
            TranslationAheadPolicy.plan(
                metered = true,
                lowPower = true,
                allowHeavy = false,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `metered without low power gets the cheap lyrics pass`() {
        assertEquals(
            Plan.LYRICS_ONLY,
            TranslationAheadPolicy.plan(
                metered = true,
                lowPower = false,
                allowHeavy = true,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `wifi gets the whole pass without a charger`() {
        assertEquals(
            Plan.FULL,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = false,
                allowHeavy = true,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `wifi while charging still relaxes low power`() {
        assertEquals(
            Plan.FULL,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = true,
                allowHeavy = true,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `wifi in low power off the charger only fetches lyrics`() {
        assertEquals(
            Plan.LYRICS_ONLY,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = true,
                allowHeavy = false,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `no network means nothing to fetch`() {
        assertEquals(
            Plan.NONE,
            TranslationAheadPolicy.plan(
                metered = null,
                lowPower = false,
                allowHeavy = true,
                autoplayUncertain = false,
            ),
        )
    }

    // 11-08 user log: all 21 natural transitions went into an AutoPlay row,
    // and the old NONE here meant no translation was ever made ahead of one.
    @Test
    fun `an autoplay slot on wifi gets its translation ahead`() {
        assertEquals(
            Plan.TRANSLATION_ONLY,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = false,
                allowHeavy = true,
                autoplayUncertain = true,
            ),
        )
    }

    @Test
    fun `an autoplay slot off wifi or saving power is not prepared`() {
        assertEquals(
            Plan.NONE,
            TranslationAheadPolicy.plan(metered = true, lowPower = false, allowHeavy = true, autoplayUncertain = true),
        )
        assertEquals(
            Plan.NONE,
            TranslationAheadPolicy.plan(metered = false, lowPower = true, allowHeavy = false, autoplayUncertain = true),
        )
        assertEquals(
            Plan.NONE,
            TranslationAheadPolicy.plan(metered = null, lowPower = false, allowHeavy = true, autoplayUncertain = true),
        )
    }

    // l1121-prepai (LYR-ahead AHEAD-1): the bundle's LYRICS step passed heavyWorkAllowed alone, so a
    // lyric-less next row was transcribed (audio pulled, decoded, uploaded) on LTE ~1 s into the song.
    @Test
    fun `a row not playing yet is not transcribed on mobile data`() {
        assertEquals("metered", TranslationAheadPolicy.transcribeAheadHeld(heavyAllowed = true, metered = true, playedMs = 120_000L))
    }

    @Test
    fun `a row not playing yet is not transcribed before the 30 s gate`() {
        assertEquals("ai-too-early", TranslationAheadPolicy.transcribeAheadHeld(heavyAllowed = true, metered = false, playedMs = 1_000L))
        assertEquals("ai-too-early", TranslationAheadPolicy.transcribeAheadHeld(heavyAllowed = true, metered = false, playedMs = 29_999L))
    }

    @Test
    fun `wifi past the gate with heavy work allowed may transcribe ahead`() {
        assertNull(TranslationAheadPolicy.transcribeAheadHeld(heavyAllowed = true, metered = false, playedMs = 30_000L))
        assertEquals("low-power", TranslationAheadPolicy.transcribeAheadHeld(heavyAllowed = false, metered = false, playedMs = 60_000L))
        assertEquals("offline", TranslationAheadPolicy.transcribeAheadHeld(heavyAllowed = true, metered = null, playedMs = 60_000L))
    }

    @Test
    fun `the full pass asks the providers before the gate and Gemini only after it`() = runBlocking {
        val asked = mutableListOf<Boolean>()
        var gateAsked = 0
        val closed = TranslationAheadPolicy.lookUpThenTranscribe<String>(true, { gateAsked++; false }) { asked += it; null }
        assertNull(closed)
        assertEquals(listOf(false), asked)
        assertEquals(1, gateAsked)

        asked.clear()
        val open = TranslationAheadPolicy.lookUpThenTranscribe(true, { true }) { asked += it; if (it) "heard" else null }
        assertEquals("heard", open)
        assertEquals(listOf(false, true), asked)

        asked.clear()
        gateAsked = 0
        val found = TranslationAheadPolicy.lookUpThenTranscribe(true, { gateAsked++; true }) { asked += it; "sheet" }
        assertEquals("sheet", found)
        assertEquals(listOf(false), asked)
        assertEquals("a found sheet never waits on the gate", 0, gateAsked)

        asked.clear()
        TranslationAheadPolicy.lookUpThenTranscribe<String>(false, { true }) { asked += it; null }
        assertEquals(listOf(false), asked)
    }

    @Test
    fun `the bundle and the full pass are wired through the transcription gate`() {
        val prep = File("src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt").readText()
        val ahead = File("src/main/java/com/music/bitchord/playback/TranslationAhead.kt").readText()
        assertTrue(prep.contains("allowHeavy = transcribeHeld == null"))
        assertTrue(prep.contains("TranslationAheadPolicy.transcribeAheadHeld("))
        assertFalse("the old step transcribed on heavyWorkAllowed alone", prep.contains("allowHeavy = AppSettings.heavyWorkAllowed"))
        assertTrue(ahead.contains("TranslationAheadPolicy.lookUpThenTranscribe(allowHeavy && song.localUri == null, paidGate)"))
        assertFalse(
            "the full pass transcribed before its paid gate",
            ahead.contains("val found = lookUp(song, transcribe = allowHeavy && song.localUri == null) ?: return"),
        )
    }
}
