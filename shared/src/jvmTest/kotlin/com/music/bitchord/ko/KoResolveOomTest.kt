package com.music.bitchord.ko

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * oom3: the shared stream walk (StreamResolver.coalescedResolve) hit
 * OutOfMemoryError inside the innertubex cipher solve on 11-19. Every caller
 * joining that walk got the Error; a root launch that catches Exception (most
 * of them) let it out, which kills the app. The walk now fails it as an
 * IOException, the retryable failure every caller already handles.
 */
class KoResolveOomTest {

    @Test
    fun `an OutOfMemoryError in the walk reaches callers as an IOException`() = runBlocking {
        val e = assertFailsWith<IOException> {
            KoResolveOom.failSoft("vX") { throw OutOfMemoryError("Failed to allocate a 7907344 byte allocation") }
        }
        assertIs<OutOfMemoryError>(e.cause)
        assertTrue(e.message!!.contains("vX"))
    }

    @Test
    fun `a joined walk that runs out of memory kills no caller`() = runBlocking {
        // The production shape: one lazy walk in a SupervisorJob scope, awaited by
        // a root launch that catches Exception, as most resolve callers do.
        val escaped = CopyOnWriteArrayList<Throwable>()
        val callers = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, t -> escaped += t })
        val walks = CoroutineScope(SupervisorJob())
        val walk = walks.async(start = CoroutineStart.LAZY) {
            KoResolveOom.failSoft("vX") { throw OutOfMemoryError("Java heap space") }
        }
        val seen = CopyOnWriteArrayList<String>()
        val jobs = List(2) {
            callers.launch {
                try {
                    walk.await()
                } catch (e: Exception) {
                    seen += e.javaClass.simpleName
                }
            }
        }
        walk.start()
        withTimeout(5_000) { jobs.forEach { it.join() } }
        assertTrue(escaped.isEmpty(), "an Error escaped a caller: $escaped")
        assertEquals(listOf("IOException", "IOException"), seen.toList())
        callers.cancel()
        walks.cancel()
    }

    @Test
    fun `cancellation and other failures pass unchanged`() = runBlocking {
        assertFailsWith<CancellationException> { KoResolveOom.failSoft("vX") { throw CancellationException("skipped") } }
        assertFailsWith<IllegalStateException> { KoResolveOom.failSoft("vX") { error("no stream") } }
        assertFailsWith<StackOverflowError> { KoResolveOom.failSoft("vX") { throw StackOverflowError() } }
        assertEquals("url", KoResolveOom.failSoft("vX") { "url" })
    }

    @Test
    fun `the shared walk resolves through it`() {
        val root = File(".").canonicalFile
        val source = listOf("shared/", "").map { File(root, "${it}src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt") }
            .first { it.exists() }
            .readText()
        val walk = source.substringAfter("private suspend fun coalescedResolve(").substringBefore("val running = inFlight.putIfAbsent")
        val guarded = walk.indexOf("KoResolveOom.failSoft(videoId)")
        assertTrue(guarded >= 0, "coalescedResolve's walk does not go through KoResolveOom")
        assertTrue(guarded < walk.indexOf("resolveUncached(videoId)"), "resolveUncached runs outside the guard")
    }
}
