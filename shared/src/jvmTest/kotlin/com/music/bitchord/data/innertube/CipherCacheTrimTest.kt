package com.music.bitchord.data.innertube

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 11-02 heap report: InnerTubeX kept up to four player versions in each
 * in-memory cipher cache. [trimCipherCaches] keeps only the newest entry, so
 * 23 MB of strings for versions YouTube had already retired go away.
 */
class CipherCacheTrimTest {

    private class FakeEjs {
        private val preprocessedMutex = Mutex()
        private val preprocessedByPlayerUrl = linkedMapOf("old" to 1, "older" to 2, "newest" to 3)

        fun remaining(): Set<String> = preprocessedByPlayerUrl.keys.toSet()
    }

    private class FakeCipherService {
        private val cacheMutex = Mutex()
        private val ejs = FakeEjs()
        private val playerCodeCache = linkedMapOf("old" to "a", "older" to "b", "newest" to "c")
        private val cache = linkedMapOf("old" to Any(), "older" to Any(), "newest" to Any())
        private val zemerCache = linkedMapOf("old" to Any(), "older" to Any(), "newest" to Any())

        fun remainingPlayerCodes(): Set<String> = playerCodeCache.keys.toSet()
        fun remainingSolvers(): Set<String> = cache.keys.toSet()
        fun remainingZemer(): Set<String> = zemerCache.keys.toSet()
        fun remainingPreprocessed(): Set<String> = ejs.remaining()
    }

    @Test
    fun onlyTheNewestVersionStaysInEveryCipherCache() = runBlocking {
        val service = FakeCipherService()

        trimCipherCaches(service)

        assertEquals(setOf("newest"), service.remainingPlayerCodes())
        assertEquals(setOf("newest"), service.remainingSolvers())
        assertEquals(setOf("newest"), service.remainingZemer())
        assertEquals(setOf("newest"), service.remainingPreprocessed())
    }

    @Test
    fun aCacheWithOneEntryIsLeftAlone() {
        val cache = linkedMapOf("only" to Any())
        trimToNewest(cache)
        assertEquals(setOf("only"), cache.keys)
    }
}
