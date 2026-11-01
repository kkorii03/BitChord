package com.music.bitchord.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * The next-prep bundle runs on the playback service's main scope, and the
 * verification store parses its stored rows on the first read. That read must
 * be dispatched to IO instead of stalling a track change on the main thread.
 */
class NextPrepOffMainTest {

    @Test
    fun `a store read does not run on the calling thread`() = runBlocking {
        val caller = Thread.currentThread()
        var readOn: Thread? = null

        val answer = nextPrepOffMain {
            readOn = Thread.currentThread()
            7
        }

        assertEquals(7, answer)
        assertNotSame(caller, readOn)
    }
}
