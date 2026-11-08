package com.music.bitchord.ko

import com.music.bitchord.data.net.SharedLookups
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One in-flight search per video id also covers the post-audio retry: it
 * joins an asker already searching the same id instead of starting a second
 * search beside it, and an asker arriving during the retry joins the retry.
 */
class SongVersionRetrySharedLookupTest {

    private fun answer() = KoSongVersion.SongVersionAnswer(null, "none", definitive = false)

    @Test
    fun `a retry joins the search already in flight for its video id`() = runBlocking {
        val lookups = SharedLookups<KoSongVersion.SongVersionAnswer>(CoroutineScope(Dispatchers.IO))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val searches = AtomicInteger()
        val first = lookups.lookup("v1") {
            searches.incrementAndGet()
            started.complete(Unit)
            release.await()
            answer()
        }
        started.await()

        // UNDISPATCHED runs the retry to its suspension point, so the lookup
        // is registered before this thread gets control back.
        val retry = async(start = CoroutineStart.UNDISPATCHED) {
            sharedSongVersionSearch("v1", lookups) {
                searches.incrementAndGet()
                answer()
            }
        }

        assertEquals(1, searches.get(), "the retry waits on the in-flight search")
        release.complete(Unit)
        retry.await()
        first.result.await()
        assertEquals(1, searches.get(), "the video id was searched once")
    }

    @Test
    fun `an asker arriving during the retry joins its search`() = runBlocking {
        val lookups = SharedLookups<KoSongVersion.SongVersionAnswer>(CoroutineScope(Dispatchers.IO))
        val release = CompletableDeferred<Unit>()
        val searches = AtomicInteger()

        val retry = async(start = CoroutineStart.UNDISPATCHED) {
            sharedSongVersionSearch("v2", lookups) {
                searches.incrementAndGet()
                release.await()
                answer()
            }
        }
        assertTrue(lookups.isRunning("v2"), "the retry is visible in the shared map")

        val joined = lookups.lookup("v2") {
            searches.incrementAndGet()
            answer()
        }
        assertTrue(joined.joined, "the asker waits on the retry's search")

        release.complete(Unit)
        retry.await()
        joined.result.await()
        assertEquals(1, searches.get(), "the video id was searched once")
    }
}
