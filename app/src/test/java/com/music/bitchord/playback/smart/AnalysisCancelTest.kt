package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A replaced next row must stop the analysis it queued or started, while the
 * same track becomes analysable again when a later request wants it.
 */
class AnalysisCancelTest {

    @Test
    fun aQueuedPassIsDroppedOnceAndTheNextRequestRuns() {
        val cancel = AnalysisCancel()
        cancel.cancel("oldNext")
        assertTrue("the queued pass was not dropped", cancel.consume("oldNext"))
        assertFalse("the cancellation leaked into the next request", cancel.consume("oldNext"))
        assertFalse(cancel.isCancelled("oldNext"))
    }

    @Test
    fun aRunningPassStaysAbortedUntilItFinishes() {
        val cancel = AnalysisCancel()
        cancel.cancel("oldNext")
        assertTrue("the running pass would not abort at its next decode check", cancel.isCancelled("oldNext"))
        cancel.finished("oldNext")
        assertFalse("a finished pass left the track cancelled", cancel.isCancelled("oldNext"))
    }

    @Test
    fun cancellingOneTrackLeavesAnotherAlone() {
        val cancel = AnalysisCancel()
        cancel.cancel("oldNext")
        assertFalse(cancel.isCancelled("playing"))
        assertFalse(cancel.consume("playing"))
    }
}
