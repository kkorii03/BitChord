package com.music.bitchord.ko

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * rs22-rs24 (emulator): InnerTubeX's own EJS full solve has a 30 s QuickJS cap.
 * When it ran over, no preprocessed player was ever saved, so every later song
 * repeated the same full solve and timed out again (187b: 10 of 10, ~33 s each,
 * plus a 90-350 s parser fallback that never produced a URL), all inside the
 * serialized cipher queue. The preprocessed player is per player version; it has
 * to be made once and then found under the library's own cache key.
 */
class KoEjsPlayerPrepTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val url = "https://www.youtube.com/s/player/a5c2aafe/player_ias.vflset/en_US/base.js"
    private val bundle = "0123456789abcdef0123456789abcdef01234567"
    private val key = sha1("$url:$bundle")

    private val downloads = AtomicInteger()
    private val solves = AtomicInteger()
    private val stored = mutableListOf<Pair<String, String>>()

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun prep(
        gate: CompletableDeferred<Unit>? = null,
        waitMs: Long = 5_000,
        output: String? = "PREPROCESSED",
    ) = KoEjsPlayerPrep(
        bundleHash = { bundle },
        download = { downloads.incrementAndGet(); "PLAYER JS for $it" },
        preprocess = { js ->
            solves.incrementAndGet()
            assertEquals("PLAYER JS for $url", js)
            gate?.await()
            output
        },
        store = { k, v -> synchronized(stored) { stored += k to v } },
        scope = scope,
        waitMs = waitMs,
    )

    @Test
    fun `a missing preprocessed player is made once and stored under the library key`() = runBlocking {
        val prep = prep()
        prep.onPlayerUrl(url)

        assertEquals("PREPROCESSED", prep.onMissing(key))
        assertEquals(listOf(key to "PREPROCESSED"), stored)
        assertEquals(1, solves.get())
    }

    @Test
    fun `a key no seen player url hashes to starts nothing`() = runBlocking {
        val prep = prep()
        prep.onPlayerUrl(url)

        assertNull(prep.onMissing(sha1("https://www.youtube.com/s/player/ffffffff/base.js:$bundle")))
        assertEquals(0, downloads.get())
        assertEquals(0, solves.get())
    }

    @Test
    fun `concurrent misses share one solve`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val prep = prep(gate)
        prep.onPlayerUrl(url)

        val a = async(Dispatchers.Default) { prep.onMissing(key) }
        val b = async(Dispatchers.Default) { prep.onMissing(key) }
        withTimeout(5_000) { while (solves.get() == 0) kotlinx.coroutines.delay(5) }
        gate.complete(Unit)

        assertEquals("PREPROCESSED", a.await())
        assertEquals("PREPROCESSED", b.await())
        assertEquals(1, solves.get())
        assertEquals(1, downloads.get())
    }

    @Test
    fun `a skipped track's cancelled wait does not cancel the solve`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val prep = prep(gate)
        prep.onPlayerUrl(url)

        val skipped = launch(Dispatchers.Default) { prep.onMissing(key) }
        withTimeout(5_000) { while (solves.get() == 0) kotlinx.coroutines.delay(5) }
        skipped.cancel()
        gate.complete(Unit)

        // The next row's miss joins the same run instead of starting over.
        assertEquals("PREPROCESSED", prep.onMissing(key))
        assertEquals(1, solves.get())
        assertEquals(listOf(key to "PREPROCESSED"), stored)
    }

    @Test
    fun `the wait is bounded and the run keeps going for the next song`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val prep = prep(gate, waitMs = 50)
        prep.onPlayerUrl(url)

        assertNull(prep.onMissing(key))
        gate.complete(Unit)
        withTimeout(5_000) { while (synchronized(stored) { stored.isEmpty() }) kotlinx.coroutines.delay(5) }
        assertEquals(listOf(key to "PREPROCESSED"), synchronized(stored) { stored.toList() })
    }

    @Test
    fun `a key already tried is not solved again in this process`() = runBlocking {
        val prep = prep(output = null)
        prep.onPlayerUrl(url)

        assertNull(prep.onMissing(key))
        assertNull(prep.onMissing(key))
        assertEquals(1, solves.get())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `an oversized output is not stored`() = runBlocking {
        val prep = prep(output = "x".repeat(KoEjsPlayerPrep.MAX_PLAYER_JS_LENGTH + 1))
        prep.onPlayerUrl(url)

        assertNull(prep.onMissing(key))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `the EJS bundle InnerTubeX hashes is on the classpath`() {
        val hash = KoEjsPlayerPrep.classpathBundle()?.hash
        assertTrue(hash != null && Regex("[a-f0-9]{40}").matches(hash), "bundle hash: $hash")
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
