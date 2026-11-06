package com.music.bitchord.playback.smart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SongHearingTest {

    private fun saved(
        seconds: Double,
        whole: Boolean = false,
        recording: String = "stream|yt",
        language: String = "en",
    ) = SongHearing.Saved(recording, language, seconds, whole)

    @Test
    fun aKeptTranscriptServesAnyWindowItReaches() {
        val kept = listOf(saved(90.0))
        assertNotNull(SongHearing.covering(kept, "stream|yt", "en", 40.0))
        assertNotNull(SongHearing.covering(kept, "stream|yt", "en", 90.0))
        // A server's duration can read a hair under the window it was sent.
        assertNotNull(SongHearing.covering(listOf(saved(89.4)), "stream|yt", "en", 90.0))
    }

    @Test
    fun aKeptTranscriptShortOfTheWindowIsNotUsed() {
        assertNull(SongHearing.covering(listOf(saved(40.0)), "stream|yt", "en", 90.0))
    }

    @Test
    fun aWholeSongServesEveryWindow() {
        assertNotNull(SongHearing.covering(listOf(saved(185.0, whole = true)), "stream|yt", "en", 420.0))
    }

    @Test
    fun anotherRecordingOrLanguageIsNotThisTranscript() {
        val kept = listOf(saved(90.0))
        assertNull(SongHearing.covering(kept, "file|yt", "en", 40.0))
        assertNull(SongHearing.covering(kept, "stream|yt", "ko", 40.0))
    }

    @Test
    fun theLongerTranscriptStays() {
        val long = saved(90.0)
        assertEquals(listOf(long), SongHearing.merged(listOf(long), saved(40.0)))
        val longer = saved(200.0)
        assertEquals(listOf(longer), SongHearing.merged(listOf(long), longer))
    }

    @Test
    fun aWholeSongIsNotReplacedByAnOpeningButIsByAnotherWholeHearing() {
        val whole = saved(180.0, whole = true)
        assertEquals(listOf(whole), SongHearing.merged(listOf(whole), saved(200.0)))
        val again = saved(181.0, whole = true)
        assertEquals(listOf(again), SongHearing.merged(listOf(whole), again))
    }

    @Test
    fun otherRecordingsAreKeptBeside() {
        val stream = saved(90.0)
        val file = saved(40.0, recording = "file|yt")
        assertEquals(listOf(stream, file), SongHearing.merged(listOf(stream), file))
    }

    @Test
    fun aQuickAnswerSendsNoSecondRequest() = runTest {
        var asked = 0
        val answer = SongHearing.hedged(15_000L, ask = {
            asked++
            "heard"
        }, heardSomething = { true })
        assertEquals("heard", answer)
        assertEquals(1, asked)
    }

    @Test
    fun aSlowAnswerIsBeatenByTheSecondRequest() = runTest {
        var asked = 0
        val stuck = CompletableDeferred<String>()
        val answer = SongHearing.hedged(15_000L, ask = {
            asked++
            if (asked == 1) stuck.await() else "second"
        }, heardSomething = { true })
        assertEquals("second", answer)
        assertEquals(2, asked)
    }

    @Test
    fun aFailureFromOneRequestWaitsForTheOther() = runTest {
        var asked = 0
        val answer = SongHearing.hedged(15_000L, ask = {
            asked++
            if (asked == 1) {
                delay(16_000L)
                "failed"
            } else {
                delay(30_000L)
                "heard"
            }
        }, heardSomething = { it == "heard" })
        assertEquals("heard", answer)
    }

    @Test
    fun aRequestWithNoReplyGoesAgainButAServersAnswerDoesNot() {
        assertEquals(true, SongHearing.shouldResend(null, 0))
        assertEquals(true, SongHearing.shouldResend(null, 1))
        assertEquals(false, SongHearing.shouldResend(null, 2))
        assertEquals(false, SongHearing.shouldResend(200, 0))
        assertEquals(false, SongHearing.shouldResend(429, 0))
        assertEquals(false, SongHearing.shouldResend(400, 0))
    }

    @Test
    fun containersAreToldByTheirMagicBytes() {
        fun head(vararg bytes: Int) = ByteArray(16).also { b -> bytes.forEachIndexed { i, v -> b[i] = v.toByte() } }
        assertEquals("audio/webm", VideoAudio.containerMime(head(0x1A, 0x45, 0xDF, 0xA3)))
        assertEquals("audio/mp4", VideoAudio.containerMime(head(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70)))
        assertEquals("audio/flac", VideoAudio.containerMime(head(0x66, 0x4C, 0x61, 0x43)))
        assertEquals("audio/ogg", VideoAudio.containerMime(head(0x4F, 0x67, 0x67, 0x53)))
        assertEquals("audio/mpeg", VideoAudio.containerMime(head(0x49, 0x44, 0x33)))
        assertNull(VideoAudio.containerMime(head(1, 2, 3, 4)))
        assertNull(VideoAudio.containerMime(ByteArray(0)))
    }
}
