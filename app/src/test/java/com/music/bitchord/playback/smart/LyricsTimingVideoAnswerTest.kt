package com.music.bitchord.playback.smart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one AI slot belongs to the sheet asked about last, whichever answer lands last. */
class LyricsTimingVideoAnswerTest {

    @Test
    fun `only the sheet asked about last may take the slot`() {
        LyricsTiming.newestSongKey["sheets"] = 1
        LyricsTiming.newestSongKey["sheets"] = 2

        assertTrue(LyricsTiming.replacedSheet("sheets", 1))
        assertFalse(LyricsTiming.replacedSheet("sheets", 2))
    }

    @Test
    fun `a video never asked about has nothing to be replaced by`() {
        assertFalse(LyricsTiming.replacedSheet("never-asked", 1))
    }

    /**
     * 11-07 7PgA6kyqVTc and ekzHIouo8Q4: the AI answer for the replaced sheet
     * returned silently, so the sheet now showing was never fitted and kept
     * base=none for the rest of the row.
     */
    @Test
    fun `a dropped AI answer asks the screen to fit the sheet now shown`() = runBlocking {
        LyricsTiming.newestSongKey["replaced"] = 2
        val seen = CompletableDeferred<String>()
        val collect = launch(start = CoroutineStart.UNDISPATCHED) {
            seen.complete(LyricsTiming.changes.first())
        }

        assertTrue(LyricsTiming.dropForReplacedSheet("replaced", 1))
        assertEquals("replaced", withTimeout(5_000L) { seen.await() })
        collect.cancel()
    }
}
