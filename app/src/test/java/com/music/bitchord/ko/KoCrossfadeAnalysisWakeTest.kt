package com.music.bitchord.ko

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Analysis finishing is an event the idle ticker waits for: the planned cue can
 * move earlier than the end-of-track window its sleep was computed from.
 */
class KoCrossfadeAnalysisWakeTest {

    @Test
    fun `analysis completion wakes a ticker parked on events`() = runBlocking {
        val wake = Channel<Unit>(Channel.CONFLATED)
        KoCrossfadeAnalysisWake.bind { wake.trySend(Unit) }
        try {
            val parked = async { withTimeout(1_000L) { wake.receive() } }
            koCrossfadeAnalysisCompleted()
            parked.await()
        } finally {
            KoCrossfadeAnalysisWake.bind(null)
        }
    }

    @Test
    fun `an unbound ticker ignores completion instead of keeping a stale listener`() = runBlocking {
        var wakes = 0
        KoCrossfadeAnalysisWake.bind { wakes++ }
        KoCrossfadeAnalysisWake.bind(null)
        koCrossfadeAnalysisCompleted()
        assertEquals(0, wakes)
    }

    @Test
    fun `releasing the old ticker leaves the replacement bound`() {
        var oldWakes = 0
        var newWakes = 0
        val old: () -> Unit = { oldWakes++ }
        val replacement: () -> Unit = { newWakes++ }
        KoCrossfadeAnalysisWake.bind(old)
        KoCrossfadeAnalysisWake.bind(replacement)
        KoCrossfadeAnalysisWake.unbind(old)
        try {
            koCrossfadeAnalysisCompleted()
            assertEquals(0, oldWakes)
            assertEquals(1, newWakes)
        } finally {
            KoCrossfadeAnalysisWake.bind(null)
        }
    }
}
