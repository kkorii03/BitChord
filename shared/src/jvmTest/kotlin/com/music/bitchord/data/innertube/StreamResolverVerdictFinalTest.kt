package com.music.bitchord.data.innertube

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C4c-1: a gated start whose proxy only ran out of time (cold TLS, VM load, a
 * read-ahead on the same proxy) used to be stored as "unplayable" for ten
 * minutes, so tapping the song again failed at once without asking the proxy.
 * The start still skips or switches like any verdict; it is just not stored.
 */
class StreamResolverVerdictFinalTest {

    @Test
    fun `a slow proxy's budget exit is not a final verdict, even wrapped`() {
        val slow = StreamResolver.PermanentlyUnplayableException("blocked", verdictFinal = false)
        assertFalse(StreamResolver.isFinalVerdict(slow))
        assertFalse(StreamResolver.isFinalVerdict(IOException("load error", slow)))
        // A coroutine stack-trace copy wraps the original as its cause.
        val copy = StreamResolver.PermanentlyUnplayableException("blocked")
        copy.initCause(slow)
        assertFalse(StreamResolver.isFinalVerdict(copy))
    }

    @Test
    fun `a real refusal and an unrelated failure stay final`() {
        assertTrue(StreamResolver.isFinalVerdict(StreamResolver.PermanentlyUnplayableException("gone")))
        assertTrue(StreamResolver.isFinalVerdict(IOException("no stream")))
        assertTrue(StreamResolver.isFinalVerdict(null))
    }

    @Test
    fun `every place that stores a verdict checks that it is final`() {
        val resolver = source("shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt")
            .substringAfter("private suspend fun resolveUncached(")
            .substringBefore("private suspend fun resolveViaProxy(")
        val stores = Regex("""rememberUnplayable\(videoId, reason\)""").findAll(resolver).toList()
        assertEquals(2, stores.size, "the two blocked-start stores")
        for (store in stores) {
            val before = resolver.substring((store.range.first - 700).coerceAtLeast(0), store.range.first)
            assertTrue("isFinalVerdict(" in before, "a store without the final check at ${store.range.first}")
        }
        assertFalse(
            "PermanentlyUnplayableException(blockedReason())" in resolver,
            "a budget exit must say whether the proxy refused",
        )

        val prefetch = source("app/src/main/java/com/music/bitchord/playback/PrefetchController.kt")
        assertTrue("isFinalVerdict(failure)" in prefetch)
        assertTrue("if (finalVerdict) StreamResolver.markUnplayable(" in prefetch)

        val service = source("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        val mark = service.lines().single { "StreamResolver.markUnplayable(mediaId" in it }
        assertTrue("isFinalVerdict(error)" in mark, "recoverFrom must not store a slow proxy's verdict")
    }

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText().replace("\r\n", "\n")
    }
}
