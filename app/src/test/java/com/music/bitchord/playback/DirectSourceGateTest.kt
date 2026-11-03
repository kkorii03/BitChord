package com.music.bitchord.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Direct Source Stream's first-audio rule: when every addon is resting after
 * repeated failures, waiting the whole direct-source window can only end in
 * "no addon", while YouTube could have started at once.
 */
class DirectSourceGateTest {

    /**
     * Runs one fake addon lookup with the gate on `current` and returns the
     * answer and the events the lookup produced, so the wait tests differ only
     * in their input.
     */
    private suspend fun TestScope.runLookup(
        mediaId: String,
        startedAtMs: Long = 0L,
        answerAfterMs: Long = 5_000,
    ): Pair<String?, List<String>> {
        DirectSourceGate.onPlaying(setOf("current"))
        try {
            val events = mutableListOf<String>()
            val direct = async {
                events += "direct started"
                delay(answerAfterMs)
                events += "direct answered"
                "addon-stream"
            }
            val result = DirectSourceGate.awaitLookup(
                work = direct,
                startedAtMs = startedAtMs,
                waitMs = DirectSourceGate.lookupWaitMs(mediaId),
                fromJoin = DirectSourceGate.isCurrent(mediaId),
                nowMs = { testScheduler.currentTime },
            )
            return result to events
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }

    @Test
    fun allAddonsRestingSkipsTheWindow() {
        assertFalse(DirectSourceGate.directLookupWorthWaiting(listOf(true, true)))
    }

    @Test
    fun oneAwakeAddonStillDeservesTheWindow() {
        assertTrue(DirectSourceGate.directLookupWorthWaiting(listOf(true, false)))
        assertTrue(DirectSourceGate.directLookupWorthWaiting(listOf(false)))
    }

    @Test
    fun noDirectAddonsCannotProduceAnAnswer() {
        assertFalse(DirectSourceGate.directLookupWorthWaiting(emptyList()))
    }

    /**
     * The current track is the one the listener is waiting on. It gets one
     * short chance at an addon answer; the full read-ahead window belongs to a
     * track that is still next, where the current one's audio covers the wait.
     */
    @Test
    fun theCurrentTrackDoesNotWaitTheReadAheadWindow() {
        DirectSourceGate.onPlaying(setOf("current"))
        try {
            assertEquals(
                DirectSourceGate.CURRENT_LOOKUP_GRACE_MS,
                DirectSourceGate.lookupWaitMs("current"),
            )
            assertTrue(
                DirectSourceGate.lookupWaitMs("current") < DirectSourceGate.LOOKUP_TIMEOUT_MS,
            )
            assertEquals(
                DirectSourceGate.LOOKUP_TIMEOUT_MS,
                DirectSourceGate.lookupWaitMs("next"),
            )
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }

    /**
     * Fake clock and fake lookup, measuring the order of steps: the current
     * track's caller gives up at its short grace while the addon is still
     * searching, so YouTube can start. A read-ahead track keeps waiting and
     * gets the addon's answer.
     */
    @Test
    fun aCurrentLookupGivesUpBeforeASlowAddonAnswers() = runTest {
        val (result, events) = runLookup("current")
        assertNull("the current track must not wait for the slow addon", result)
        assertEquals(listOf("direct started"), events)
    }

    @Test
    fun aReadAheadLookupWaitsForTheAddon() = runTest {
        val (result, events) = runLookup("next")
        assertEquals("addon-stream", result)
        assertEquals(listOf("direct started", "direct answered"), events)
    }

    /**
     * A track that becomes current after its read-ahead lookup has already run
     * for more than a second still gets the short grace from when the caller
     * joins, not what is left of a window that is already spent.
     */
    @Test
    fun aLateCurrentCallerStillGetsItsShortGrace() = runTest {
        val (result, events) = runLookup("current", startedAtMs = -5_000L, answerAfterMs = 500)
        assertEquals("addon-stream", result)
        assertEquals(listOf("direct started", "direct answered"), events)
    }

    /**
     * A song version keeps the row's mediaId but streams another video id.
     * Both are current, and neither may be held for the read-ahead window.
     */
    @Test
    fun bothIdsOfACurrentSongVersionGetTheShortGrace() {
        DirectSourceGate.onPlaying(setOf("row-id", "song-version-id"))
        try {
            assertEquals(
                DirectSourceGate.CURRENT_LOOKUP_GRACE_MS,
                DirectSourceGate.lookupWaitMs("row-id"),
            )
            assertEquals(
                DirectSourceGate.CURRENT_LOOKUP_GRACE_MS,
                DirectSourceGate.lookupWaitMs("song-version-id"),
            )
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }

    /**
     * Phone 11-03: every first play hung. The track change only told the gate
     * about the new row once it had sounded, but its loader was the one held
     * at the gate, so it never sounded. The transition must tell the gate
     * even when the row is not audible yet.
     */
    @Test
    fun aTrackChangeAdmitsTheNewRowBeforeItSounds() {
        val root = File(".").canonicalFile
        val service = listOf("app/", "").map {
            File(root, "${it}src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        }.first { it.exists() }.readText()
        val hook = Regex(
            """if \(currentAudible\) \{\s*prefetchAround\(exoPlayer\)\s*\} else if \(DirectSourceGate\.isActive\(\)\) \{[^}]*driveDirectWindow\(\)""",
        )
        assertTrue("the track change must call driveDirectWindow when not yet audible", hook.containsMatchIn(service))
        val drive = service.substringAfter("private fun driveDirectWindow()").substringBefore("if (!DirectSourceGate.isActive()")
        assertTrue("driveDirectWindow must tell the gate before its isPlaying check", "DirectSourceGate.onPlaying(" in drive)
    }

    /**
     * A lookup can answer successfully during the YouTube walk. It has left
     * [DirectSourceGate.stillLooking] by then, so re-reading it dropped the
     * answer and searched again. The resolve must hand over the deferred it
     * captured before awaiting the fallback, and the log must report the wait
     * it actually spent rather than recomputing it after a track change.
     */
    @Test
    fun theResolveHandsOverTheLookupCapturedBeforeTheFallback() {
        val root = File(".").canonicalFile
        fun read(name: String) = listOf("app/", "").map {
            File(root, "${it}src/main/java/com/music/bitchord/playback/$name")
        }.first { it.exists() }.readText()
        val gate = read("DirectSourceGate.kt")
        val service = read("PlaybackService.kt")
        val cache = read("AudioCache.kt")
        assertTrue(
            "startedOnYouTube must take the captured lookup",
            Regex("""fun startedOnYouTube\([\s\S]{0,400}?inFlight: Deferred<SourceStream\?>\?""").containsMatchIn(gate),
        )
        assertTrue(
            "startedOnYouTube must not re-read stillLooking",
            !Regex("""fun startedOnYouTube\([\s\S]{0,400}?inFlight = stillLooking""").containsMatchIn(gate),
        )
        val resolve = service.substringAfter("private suspend fun resolveDirect").substringBefore("private class Measured")
        val captured = resolve.indexOf("val running = DirectSourceGate.stillLooking(videoId)")
        val awaited = resolve.indexOf("fallback.await()")
        assertTrue("the lookup must be captured before the fallback await", captured in 0 until awaited)
        assertTrue("the captured lookup must be passed on", "startedOnYouTube(videoId, target, running)" in resolve)
        assertTrue("the fallback log must use the wait actually spent", "waitedMs / 1000" in resolve)

        val cacheCapture = cache.indexOf("val running = DirectSourceGate.stillLooking(videoId)")
        val cacheResolve = cache.indexOf("runCatching { StreamResolver.resolve(videoId) }")
        assertTrue("the cache lookup must be captured before its YouTube walk", cacheCapture in 0 until cacheResolve)
        assertTrue(
            "the cache must pass the captured lookup on",
            "startedOnYouTube(videoId, SourceResolver.targetIn(uri), running)" in cache,
        )
        val readAheadCapture = cache.indexOf("val running = if (addon == null) DirectSourceGate.stillLooking(id) else null")
        val readAheadResolve = cache.indexOf("val url = StreamResolver.resolve(id)")
        assertTrue("read-ahead must capture before its YouTube walk", readAheadCapture in 0 until readAheadResolve)
        assertTrue("read-ahead must pass the captured lookup on", "startedOnYouTube(id, upcoming.target, running)" in cache)
    }
}
