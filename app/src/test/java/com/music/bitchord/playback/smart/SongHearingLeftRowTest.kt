package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.Deepgram
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOG6 R1 (11-17 phone): Deepgram asks for rows the user had left held all 3 slots for
 * 240 s each (hedge at 120 s, up to 2 resends), so the playing song never got one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SongHearingLeftRowTest {

    /** A Deepgram that never answers: OkHttp's 240 s timeout ends it with no reply. */
    private suspend fun neverAnswers(): Deepgram.Answer {
        delay(240_000L)
        return Deepgram.Answer(null)
    }

    @Test
    fun aRowThatLeavesAfterTheFirstSendAsksOnceAndLetsGoAtOnce() = runTest {
        val rows = MutableStateFlow<Any?>("FcXRwUsA6xs" to "DyDfgMOUjCI")
        var gone = false
        var asked = 0
        launch {
            delay(28_000L)
            gone = true
            rows.value = "DyDfgMOUjCI" to "To4SWGZkEPk"
        }
        val permits = SongHearing.requests.availablePermits
        val outcome = SongHearing.askWhileWanted(
            hedgeAfterMs = 120_000L,
            gone = { gone },
            moved = rows,
            resendable = { true },
            ask = {
                asked++
                neverAnswers()
            },
        )
        assertEquals(1, asked)
        assertEquals(1, outcome.requests)
        assertTrue(outcome.left)
        assertNull(outcome.answer.words)
        assertNull(outcome.answer.httpCode)
        // Ended when the row left, not after 240 s or 3 x 240 s.
        assertEquals(28_000L, testScheduler.currentTime)
        assertEquals(permits, SongHearing.requests.availablePermits)
    }

    @Test
    fun aRowThatStaysGetsItsHedgeAndResendsAsBefore() = runTest {
        val rows = MutableStateFlow<Any?>("To4SWGZkEPk" to null)
        var asked = 0
        val outcome = SongHearing.askWhileWanted(
            hedgeAfterMs = 120_000L,
            gone = { false },
            moved = rows,
            resendable = { true },
            ask = {
                asked++
                neverAnswers()
            },
        )
        // 3 tries (the first and 2 resends), each with its hedge.
        assertEquals(6, asked)
        assertFalse(outcome.left)
        assertNull(outcome.answer.httpCode)
    }

    @Test
    fun aRowGoneBeforeTheSendAsksNothing() = runTest {
        var asked = 0
        val outcome = SongHearing.askWhileWanted(
            hedgeAfterMs = 120_000L,
            gone = { true },
            moved = MutableStateFlow<Any?>(null),
            resendable = { true },
            ask = {
                asked++
                neverAnswers()
            },
        )
        assertEquals(0, asked)
        assertTrue(outcome.left)
    }

    @Test
    fun aServersAnswerEndsTheAskWithoutWaitingForTheRow() = runTest {
        val outcome = SongHearing.askWhileWanted(
            hedgeAfterMs = 120_000L,
            gone = { false },
            moved = MutableStateFlow<Any?>(null),
            resendable = { true },
            ask = { Deepgram.Answer(emptyList(), httpCode = 200) },
        )
        assertFalse(outcome.left)
        assertEquals(200, outcome.answer.httpCode)
        assertEquals(1, outcome.requests)
    }

    @Test
    fun aRowGoneAtTheHedgeBuysNoSecondCopy() = runTest {
        var gone = false
        var asked = 0
        launch {
            delay(60_000L)
            gone = true
        }
        val answer = SongHearing.hedged(120_000L, ask = {
            asked++
            delay(200_000L)
            "first"
        }, heardSomething = { true }, gone = { gone })
        assertEquals("first", answer)
        assertEquals(1, asked)
    }

    @Test
    fun theRowsFlowFollowsTheSoundingAndNextRows() {
        val state = com.music.bitchord.data.DiagnosticsState
        val before = Triple(state.currentTrackId, state.currentSwapId, state.nextTrackId)
        try {
            state.currentTrackId = "FcXRwUsA6xs"
            state.nextTrackId = "DyDfgMOUjCI"
            assertEquals("FcXRwUsA6xs" to "DyDfgMOUjCI", state.rows.value)
            state.currentSwapId = "To4SWGZkEPk"
            assertEquals("To4SWGZkEPk" to "DyDfgMOUjCI", state.rows.value)
            state.currentTrackId = "DyDfgMOUjCI"
            assertEquals("DyDfgMOUjCI" to "DyDfgMOUjCI", state.rows.value)
        } finally {
            state.currentTrackId = before.first
            state.currentSwapId = before.second
            state.nextTrackId = before.third
        }
    }
}
