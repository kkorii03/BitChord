package com.music.bitchord.playback.smart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SongHearingTest {

    @Test
    fun `exact words replace rough ones whatever the length and never the reverse`() {
        val rough = SongHearing.Saved("stream|yt", "en", 88.0, rough = true)
        val exact = SongHearing.Saved("stream|yt", "en", 86.0)
        val shorter = SongHearing.Saved("stream|yt", "en", 40.0)
        val longerRough = SongHearing.Saved("stream|yt", "en", 118.0, rough = true)

        assertEquals(listOf(exact), SongHearing.merged(listOf(rough), exact))
        assertEquals(listOf(shorter), SongHearing.merged(listOf(rough), shorter))
        assertEquals(listOf(exact), SongHearing.merged(listOf(exact), longerRough))
    }

    @Test
    fun `a rough answer never writes over an exact fit of the same sheet`() {
        val apply = com.music.bitchord.data.lyrics.SongCheckWindow::roughMayApply

        assertEquals(false, apply(true, true, false))
        assertEquals(true, apply(true, true, true))
        assertEquals(true, apply(true, false, false))
        assertEquals(true, apply(false, true, false))
    }

    @Test
    fun `a music video handed to its song version has left, the song and the next row have not`() {
        // 11-08 156a: 8gyLR4NfMiI (MV) became R7O2c5Gl1Mc before any audio; its listen went on.
        assertEquals(true, SongHearing.rowGone("8gyLR4NfMiI", playing = "R7O2c5Gl1Mc", next = "V6WWJNpIJN4"))
        assertEquals(false, SongHearing.rowGone("R7O2c5Gl1Mc", playing = "R7O2c5Gl1Mc", next = "V6WWJNpIJN4"))
        assertEquals(false, SongHearing.rowGone("V6WWJNpIJN4", playing = "R7O2c5Gl1Mc", next = "V6WWJNpIJN4"))
        assertEquals(false, SongHearing.rowGone("8gyLR4NfMiI", playing = null, next = null))
    }

    @Test
    fun `a row that has left with nothing on disk is not handed to the decoder`() {
        // 11-08 187a v59g4HRNIkc: skipped at 16:26:54, its two fall-backs asked YouTube for it again.
        assertEquals(false, SongHearing.decodeAfterNoStream(rowGone = true, anythingOnDisk = false))
        // Unchanged: the playing row waits on the decoder (157b Wk3Vxt3knnI's sound came at 158 s),
        // and a gone row's bytes on disk are still decoded.
        assertEquals(true, SongHearing.decodeAfterNoStream(rowGone = false, anythingOnDisk = false))
        assertEquals(true, SongHearing.decodeAfterNoStream(rowGone = true, anythingOnDisk = true))
        assertEquals(true, SongHearing.decodeAfterNoStream(rowGone = false, anythingOnDisk = true))
    }

    @Test
    fun `only an opening someone waits on gets a quick second copy`() {
        assertEquals(15_000L, SongHearing.hedgeAfterMs(90.0, exact = false))
        assertEquals(120_000L, SongHearing.hedgeAfterMs(90.0, exact = true))
        assertEquals(120_000L, SongHearing.hedgeAfterMs(420.0, exact = false))
    }

    @Test
    fun `a rough answer stays rough when it is asked nothing again`() {
        val heard = SongHearing.Heard(emptyList(), 1_000L, LyricsTiming.Clock.YouTube, 200, rough = true)

        assertEquals(true, heard.askedNothing().rough)
    }

    @Test
    fun `the opening goes to Groq only with a key and a later window stays Deepgram's`() {
        val before = com.music.bitchord.data.lyrics.GroqWhisper.apiKey
        try {
            com.music.bitchord.data.lyrics.GroqWhisper.apiKey = "test-key"
            assertEquals(true, SongHearing.groqFirst(40.0))
            assertEquals(true, SongHearing.groqFirst(90.0))
            assertEquals(false, SongHearing.groqFirst(420.0))
            com.music.bitchord.data.lyrics.GroqWhisper.apiKey = " "
            assertEquals(false, SongHearing.groqFirst(40.0))
        } finally {
            com.music.bitchord.data.lyrics.GroqWhisper.apiKey = before
        }
    }

    private fun rendition(
        key: String,
        cached: Long,
        origin: com.music.bitchord.playback.AudioCache.Origin? = null,
    ) = com.music.bitchord.playback.AudioCache.Rendition(key, 8_000_000L, cached, origin)

    @Test
    fun aSongSettledOnYouTubeIsHeardFromItsAltEntry() {
        // 11-06 ekzHIouo8Q4: a source ranked above YouTube files the stream under #alt-noatmos.
        assertEquals(true, VersionAudioAligner.isYouTubeUnderAltKey("abc#alt-noatmos", youTubeChosen = true))
        assertEquals(true, VersionAudioAligner.isYouTubeUnderAltKey("abc#alt", youTubeChosen = true))
        // Nothing settled, or a real substitute: the name means what it says.
        assertEquals(false, VersionAudioAligner.isYouTubeUnderAltKey("abc#alt-noatmos", youTubeChosen = false))
        // An upgrade is another recording whatever was chosen, and the plain key needs no excuse.
        assertEquals(false, VersionAudioAligner.isYouTubeUnderAltKey("abc#hifi-2", youTubeChosen = true))
        assertEquals(false, VersionAudioAligner.isYouTubeUnderAltKey("abc", youTubeChosen = true))
        // Nothing settled, but the cache's own record says YouTube filled it.
        assertEquals(true, VersionAudioAligner.isYouTubeUnderAltKey("abc#alt", youTubeChosen = false, recordedYouTube = true))
        assertEquals(false, VersionAudioAligner.isYouTubeUnderAltKey("abc#alt", youTubeChosen = false, recordedYouTube = false))
    }

    @Test
    fun anAltCopyRecordedAsYouTubesIsMeasuredWhenNothingIsSettled() {
        val head = rendition("abc", 900_000L)
        val alt = rendition("abc#alt-noatmos", 5_000_000L, com.music.bitchord.playback.AudioCache.Origin.YOUTUBE)
        assertEquals(alt, VersionAudioAligner.chooseMeasurementCopy(listOf(head, alt)))
    }

    @Test
    fun anAltCopyRecordedAsAnAddonsIsNotMeasuredWhenNothingIsSettled() {
        val head = rendition("abc", 900_000L)
        val alt = rendition("abc#alt-noatmos", 5_000_000L, com.music.bitchord.playback.AudioCache.Origin.OTHER)
        assertEquals(head, VersionAudioAligner.chooseMeasurementCopy(listOf(head, alt)))
    }

    @Test
    fun anAltCopyWithNoRecordIsNotMeasuredWhenNothingIsSettled() {
        val head = rendition("abc", 900_000L)
        val alt = rendition("abc#alt-noatmos", 5_000_000L)
        assertEquals(head, VersionAudioAligner.chooseMeasurementCopy(listOf(head, alt)))
    }

    @Test
    fun anExplicitYouTubeChoiceStillTakesTheAltCopy() {
        val head = rendition("abc", 900_000L)
        val alt = rendition("abc#alt-noatmos", 5_000_000L, com.music.bitchord.playback.AudioCache.Origin.OTHER)
        assertEquals(alt, VersionAudioAligner.chooseMeasurementCopy(listOf(head, alt), youTubeChosen = true))
    }

    @Test
    fun anAltCopyRecordedAsYouTubesIsYouTubesClock() {
        assertEquals(
            LyricsTiming.Clock.YouTube,
            LyricsTiming.clockForRendition("clock-copy", "clock-copy#alt-noatmos", recordedYouTube = true),
        )
    }

    @Test
    fun anAltCopyRecordedAsAnAddonsIsNotYouTubesClock() {
        assertEquals(
            LyricsTiming.Clock.Unknown,
            LyricsTiming.clockForRendition("clock-copy", "clock-copy#alt-noatmos", recordedYouTube = false),
        )
    }

    @Test
    fun theLongerOfTheStreamsCopiesIsMeasured() {
        val head = rendition("abc", 900_000L)
        val playing = rendition("abc#alt-noatmos", 5_000_000L)
        val candidate = rendition("abc#hifi-1", 7_000_000L)
        assertEquals(playing, VersionAudioAligner.chooseMeasurementCopy(listOf(head, playing, candidate), youTubeChosen = true))
        // Not settled on YouTube: as before, the plain head, never a stranger with more bytes.
        assertEquals(head, VersionAudioAligner.chooseMeasurementCopy(listOf(head, playing, candidate)))
    }

    @Test
    fun aRequestInTheAirAnswersWindowsItReaches() {
        assertEquals(true, SongHearing.flightAnswers(90.0, 40.0))
        assertEquals(true, SongHearing.flightAnswers(420.0, 90.0))
        assertEquals(true, SongHearing.flightAnswers(90.0, 90.0))
        assertEquals(false, SongHearing.flightAnswers(90.0, 420.0))
        assertEquals(false, SongHearing.flightAnswers(40.0, 90.0))
    }

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
    fun aHedgeThatMissedItsMomentComesBackWhenASlotFrees() = runTest {
        var held = 0
        repeat(3) {
            SongHearing.requests.acquire()
            held++
        }
        try {
            var asked = 0
            val slow = CompletableDeferred<String>()
            launch {
                delay(20_000L)
                SongHearing.requests.release()
                held--
            }
            launch {
                delay(40_000L)
                slow.complete("first")
            }
            val answer = SongHearing.hedged(15_000L, ask = {
                asked++
                if (asked == 1) slow.await() else "second"
            }, heardSomething = { true })
            assertEquals("second", answer)
            assertEquals(2, asked)
        } finally {
            repeat(held) { SongHearing.requests.release() }
        }
    }

    @Test
    fun aSecondRequestHoldsAFreeSlotAndGivesItBack() = runTest {
        var held = -1
        var asked = 0
        val stuck = CompletableDeferred<String>()
        val answer = SongHearing.hedged(15_000L, ask = {
            asked++
            if (asked == 1) {
                stuck.await()
            } else {
                held = SongHearing.requests.availablePermits
                "second"
            }
        }, heardSomething = { true })
        assertEquals("second", answer)
        assertEquals(2, held)
        assertEquals(3, SongHearing.requests.availablePermits)
    }

    @Test
    fun aFileMayStartFromTheStreamsWordsOnlyOnTheStreamsOwnClock() {
        val youtube = SongHearing.Saved("stream|yt", "en", 90.0, clock = "yt")
        val short = SongHearing.Saved("stream|yt", "en", 20.0, clock = "yt")
        val other = SongHearing.Saved("stream|alt", "en", 200.0, clock = null)
        assertEquals(youtube, SongHearing.streamWords(listOf(short, youtube, other), "en"))
        assertNull(SongHearing.streamWords(listOf(short), "en"))
        assertNull(SongHearing.streamWords(listOf(other), "en"))
        assertNull(SongHearing.streamWords(listOf(youtube), "ko"))
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

    private val heardWords = listOf(com.music.bitchord.data.lyrics.LyricsWordAlign.Heard("god", 0L, 400L))

    @Test
    fun anOpeningWhoseGroqAskGotNoReplyIsAskedOnceMore() = runTest {
        // 11-09 212-b v59g4HRNIkc: Groq's ask died in 340 ms ("unexpected end of stream"),
        // the opening went to Deepgram, which sat on it 77 s, and the row had left 12 s before.
        var asked = 0
        val answer = SongHearing.groqWithResend {
            asked++
            if (asked == 1) com.music.bitchord.data.lyrics.Deepgram.Answer(null) else com.music.bitchord.data.lyrics.Deepgram.Answer(heardWords, 200, rough = true)
        }
        assertEquals(2, asked)
        assertEquals(heardWords, answer.words)
        // Not at once: the same moment's connection would fail the same way.
        assertEquals(true, testScheduler.currentTime >= 2_000L)
    }

    @Test
    fun aGroqAskIsResentAtMostOnceAndNeverOverAServersAnswer() = runTest {
        var asked = 0
        val dead = SongHearing.groqWithResend {
            asked++
            com.music.bitchord.data.lyrics.Deepgram.Answer(null)
        }
        assertEquals(2, asked)
        assertNull(dead.words)
        for (code in listOf(200, 429, 401, 500)) {
            asked = 0
            val answered = SongHearing.groqWithResend {
                asked++
                com.music.bitchord.data.lyrics.Deepgram.Answer(if (code == 200) heardWords else null, code)
            }
            assertEquals(1, asked)
            assertEquals(code, answered.httpCode)
        }
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
