package com.music.bitchord.playback

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * A malformed bounded snapshot is cleared before the LastPlayed fallback runs.
 * `clearImmediately` commits synchronously, so the commit must land off the
 * thread that started the restore.
 */
class QueueRestoreDispatcherTest {

    @Test
    fun `malformed bounded restore clears off the calling thread`() = runBlocking {
        val caller = Thread.currentThread()
        var clearThread: Thread? = null

        discardMalformedBoundedContext { clearThread = Thread.currentThread() }

        assertNotSame(caller, requireNotNull(clearThread))
    }

    @Test
    fun `an injected dispatcher receives the malformed clear`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "restore-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            var clearThread: String? = null
            discardMalformedBoundedContext(dispatcher) {
                clearThread = Thread.currentThread().name
            }
            assertTrue(clearThread!!.startsWith("restore-io"))
        } finally {
            dispatcher.close()
        }
    }
}
