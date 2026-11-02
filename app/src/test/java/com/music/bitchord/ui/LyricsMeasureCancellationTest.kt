package com.music.bitchord.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A measurement is a blocking decode and may finish only after its job was
 * cancelled. That cancel must skip the timing-store read and the refresh that
 * follow it, rather than run one more pass while unwinding.
 */
class LyricsMeasureCancellationTest {

    @Test
    fun `a cancel during the measurement skips the timing-store read`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var read = false
        val job = launch {
            measureThen(
                // Decoding ignores cancellation, like a blocking call does.
                measure = {
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                    }
                },
                then = { read = true },
            )
        }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertFalse("a cancelled job must not read the timing store", read)
    }

    @Test
    fun `a finished measurement still reads the store`() = runBlocking {
        var measured = false
        val result = measureThen(
            measure = { measured = true },
            then = { 42 },
        )
        assertTrue(measured)
        assertEquals(42, result)
    }
}
