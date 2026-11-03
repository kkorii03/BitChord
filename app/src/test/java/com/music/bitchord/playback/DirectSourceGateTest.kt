package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
     * The audible row must not sit out the whole addon window when YouTube is
     * already ready. Order is recorded so a fix that awaits the addon first
     * (the old resolveDirect) fails this test: it would reach addon-end and
     * return the addon even though YouTube answered in the same turn.
     */
    @Test
    fun theAudibleRowTakesYouTubeWhenItAnswersBeforeTheAddon() = runBlocking {
        val order = mutableListOf<String>()
        val addon = async {
            order += "addon-start"
            delay(5_000)
            order += "addon-end"
            "addon"
        }
        val youtube = async {
            order += "youtube-start"
            Result.success("youtube")
        }
        val first = DirectSourceGate.firstAnswer(addon, youtube)
        assertTrue(first is DirectSourceGate.FirstAnswer.YouTube)
        assertEquals(listOf("addon-start", "youtube-start"), order)
        addon.cancel()
    }

    @Test
    fun aFastAddonStillWinsTheAudibleRow() = runBlocking {
        val order = mutableListOf<String>()
        val youtube = async {
            order += "youtube-start"
            delay(5_000)
            Result.success("youtube")
        }
        val addon = async {
            order += "addon-start"
            "addon"
        }
        val first = DirectSourceGate.firstAnswer(addon, youtube)
        assertTrue(first is DirectSourceGate.FirstAnswer.Addon)
        assertEquals("addon", (first as DirectSourceGate.FirstAnswer.Addon).stream)
        assertEquals(listOf("youtube-start", "addon-start"), order)
        youtube.cancel()
    }

    /** Only the row that is audible now races; the next one still waits for its addon. */
    @Test
    fun onlyTheAudibleRowIsCurrent() {
        DirectSourceGate.onPlaying(setOf("current"))
        try {
            DirectSourceGate.openNext("next")
            assertTrue(DirectSourceGate.isCurrent("current"))
            assertFalse(DirectSourceGate.isCurrent("next"))
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }

    /**
     * A cached YouTube copy is ready to read now. The direct gate must not hold
     * its first note for an addon: the lookup is started beside playback and
     * the ordinary second look replaces the bytes if it finds a better copy.
     */
    @Test
    fun aCachedCurrentTrackStartsBeforeWaitingForAnAddon() {
        val root = File(".").canonicalFile
        val cache = listOf("app/", "").map {
            File(root, "${it}src/main/java/com/music/bitchord/playback/AudioCache.kt")
        }.first { it.exists() }.readText()
        val settle = cache.substringAfter("private fun settleDirectEntry(uri: Uri)")
            .substringBefore("try {")
        assertTrue(
            "the cached current row must be admitted before the blocking lookup",
            "DirectSourceGate.isCurrent(videoId)" in settle,
        )
        assertTrue(
            "the cached current row must start its lookup without awaiting it",
            "DirectSourceGate.startLookupNow(" in settle,
        )
    }

    /**
     * A cache-only row has no YouTube walk, so it must measure its own floor
     * from the bytes on disk. With a null floor the second look accepts only
     * lossless and a 320kbps addon over the cached Opus is refused.
     */
    @Test
    fun aCachedCurrentRowSuppliesItsOwnFloor() {
        val look = AudioCache.cachedLookToStart(
            pending = false,
            answered = false,
            refused = false,
            contentLength = 5_000_000L,
            durationSec = 250,
        )
        assertNotNull("a fresh cached row must start its lookup", look)
        assertEquals(160, look!!.floor!!.kbps)
    }

    /**
     * The second look runs once per play. A seek or reopen of the same cached
     * row must not start it again and re-light pending.
     */
    @Test
    fun aSettledCachedRowDoesNotLookAgain() {
        assertNull(AudioCache.cachedLookToStart(true, false, false, 5_000_000L, 250))
        assertNull(AudioCache.cachedLookToStart(false, true, false, 5_000_000L, 250))
        assertNull(AudioCache.cachedLookToStart(false, false, true, 5_000_000L, 250))
    }

    @Test
    fun aCachedRowWithNoMeasurementStartsWithNoFloor() {
        assertNull(AudioCache.cachedLookToStart(false, false, false, 0L, 250)?.floor)
        assertNull(AudioCache.cachedLookToStart(false, false, false, 5_000_000L, null)?.floor)
    }

    /** The audible row takes an addon that answers before YouTube's walk. */
    @Test
    fun theAudibleRowTakesAnAddonThatAnswersFirst() = runBlocking {
        val addon = CompletableDeferred<SourceStream?>()
        val addonWork = CompletableDeferred<SourceStream?>()
        val youtube = CompletableDeferred<Result<String>>()
        addon.complete(SourceStream("addon-url"))
        var awaited = false
        val direct = DirectSourceGate.directAnswer(addonWork, current = true, addonAnswer = addon, youtube = youtube) {
            awaited = true
            it.await()
        }
        assertEquals("addon-url", direct?.url)
        assertFalse(awaited)
    }

    /** YouTube answering first plays at once; the addon lookup is not cancelled. */
    @Test
    fun theAudibleRowTakesYouTubeWhenItAnswersFirst() = runBlocking {
        val addon = CompletableDeferred<SourceStream?>()
        val addonWork = CompletableDeferred<SourceStream?>()
        val youtube = CompletableDeferred<Result<String>>()
        youtube.complete(Result.success("youtube-url"))
        var awaited = false
        val direct = DirectSourceGate.directAnswer(addonWork, current = true, addonAnswer = addon, youtube = youtube) {
            awaited = true
            it.await()
        }
        assertNull(direct)
        assertTrue("the deadline wrapper is cancelled, the lookup itself stays", addon.isCancelled)
        assertFalse("a YouTube win must not wait for the addon", awaited)
    }

    /** A YouTube failure leaves the addon as the only thing that can play. */
    @Test
    fun theAudibleRowFallsBackToTheAddonWhenYouTubeFails() = runBlocking {
        val addon = CompletableDeferred<SourceStream?>()
        val addonWork = CompletableDeferred<SourceStream?>()
        val youtube = CompletableDeferred<Result<String>>()
        youtube.complete(Result.failure(IllegalStateException("age-gated")))
        val direct = DirectSourceGate.directAnswer(addonWork, current = true, addonAnswer = addon, youtube = youtube) {
            SourceStream("addon-url")
        }
        assertEquals("addon-url", direct?.url)
    }

    /**
     * A row being prepared ahead waits for its addon even when YouTube is
     * already ready. Racing it would start the next track on the fallback that
     * Direct Source Stream exists to avoid.
     */
    @Test
    fun aNextRowWaitsForTheAddonEvenWhenYouTubeIsReady() = runBlocking {
        val addonWork = CompletableDeferred<SourceStream?>()
        val youtube = CompletableDeferred<Result<String>>()
        youtube.complete(Result.success("youtube-url"))
        var awaited = false
        val direct = DirectSourceGate.directAnswer(addonWork, current = false, addonAnswer = null, youtube = youtube) {
            awaited = true
            addonWork.complete(SourceStream("addon-url"))
            addonWork.await()
        }
        assertTrue("the next row must take the addon path", awaited)
        assertEquals("addon-url", direct?.url)
    }
}
