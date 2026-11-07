package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsWordAlign
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-08 npFa435R5bU (a cover): a 1.124 pace fitted on a 92 s opening was carried over
 * the whole song, 4.4 s late by its 137th second, and the whole-song words that came
 * later were not fitted during the play.
 */
class LyricsTimingHeardTailTest {

    private val sheet = (0 until 30).map { LyricLine(10_000L + it * 6_000L, "line number $it") }
    private val all = sheet.indices.toList()
    private val rate = 1.124
    private val offsetMs = 2_232L
    private val lastHeardMs = 110_000L

    private fun video(recordMs: Long): Long = (rate * recordMs + offsetMs).roundToLong()

    // Heard up to 110 s of the video: the lines sung by then are placed, the rest are not.
    private val starts: List<Long?> = sheet.map { line -> video(line.timeMs).takeIf { it <= lastHeardMs - 5_000L } }

    private val placement = LyricsWordAlign.Placement(
        starts = starts,
        attempted = true,
        matchedShare = starts.map { if (it != null) 1.0 else 0.0 },
    )

    @Test
    fun `the opening keeps its fitted pace and the rest runs at the record's own`() {
        val fitted = LyricsTiming.fitHeard("tail", sheet, all, placement, lastHeardMs) { _, _ -> true }!!
        val map = fitted.map
        // Where the words reach, the fit stands.
        assertTrue(abs(map.map(sheet[5].timeMs) - video(sheet[5].timeMs)) <= 2)
        // Past the last heard word, no pace other than 1.0: 30 s of record is 30 s of video.
        val late = sheet[29].timeMs
        assertTrue(abs((map.map(late) - map.map(late - 30_000L)) - 30_000L) <= 2)
        // And no jump where the words end.
        val end = map.sourceAt(lastHeardMs)
        assertTrue(abs(map.map(end + 1_000L) - (map.map(end) + 1_000L)) <= 2)
        assertTrue(abs(map.map(end) - lastHeardMs) <= 2)
    }

    @Test
    fun `words heard to the song's end leave the fit as it is`() {
        val whole = sheet.map { video(it.timeMs) }
        val fitted = LyricsTiming.fitHeard(
            "whole",
            sheet,
            all,
            LyricsWordAlign.Placement(whole, attempted = true, matchedShare = whole.map { 1.0 }),
            video(sheet.last().timeMs) + 5_000L,
        ) { _, _ -> true }!!
        val late = sheet[29].timeMs
        assertTrue(abs(fitted.map.map(late) - video(late)) <= 2)
    }

    @Test
    fun `exact words kept ask the screen to refit now, rough ones do not`() = runBlocking {
        val seen = CompletableDeferred<String>()
        val collect = launch(start = CoroutineStart.UNDISPATCHED) {
            seen.complete(LyricsTiming.changes.first { it == "kept-exact" || it == "kept-rough" })
        }
        LyricsTiming.wordsKept("kept-rough", rough = true)
        LyricsTiming.wordsKept("kept-exact", rough = false)
        assertEquals("kept-exact", withTimeout(5_000L) { seen.await() })
        collect.cancel()
    }

    @Test
    fun `a refit held for a request in flight asks again when it ends, once`() = runBlocking {
        val first = CompletableDeferred<String>()
        val collect = launch(start = CoroutineStart.UNDISPATCHED) {
            first.complete(LyricsTiming.changes.first { it == "held-refit" })
        }
        LyricsTiming.noteRefitWaiting("held-refit")
        LyricsTiming.hearingFreed("held-refit")
        assertEquals("held-refit", withTimeout(5_000L) { first.await() })
        collect.cancel()
        // Nothing waits any more: a second end asks nothing.
        val again = CompletableDeferred<String>()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            again.complete(LyricsTiming.changes.first { it == "held-refit" })
        }
        LyricsTiming.hearingFreed("held-refit")
        assertNull(withTimeoutOrNull(300L) { again.await() })
        second.cancel()
    }
}
