package com.music.bitchord

import com.music.bitchord.ko.tasteWorkOffMain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * AutoPlay's taste ranking used to run on the playback coroutine's own
 * (Main-scoped) dispatcher, so one top-up could hold the main thread for the
 * few hundred milliseconds seen in the 11-03 stall log.
 */
class AutoplayTasteDispatchTest {

    @Test
    fun `taste ranking work runs off the calling thread`() = runBlocking {
        val caller = Thread.currentThread()
        var worker: Thread? = null

        tasteWorkOffMain { worker = Thread.currentThread() }

        assertNotNull(worker)
        assertNotSame(caller, worker)
    }
}
