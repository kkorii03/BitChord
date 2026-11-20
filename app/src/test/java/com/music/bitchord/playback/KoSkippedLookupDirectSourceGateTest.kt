package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.ko.KoDirectHqAhead
import com.music.bitchord.ko.KoDirectLead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Review AHEAD-2 (UPG-ahead, 11-20): a Direct Source lookup started for a row
 * that was playing or next ran on after the listener skipped it: its addon
 * search and its proof download (a 19-63 MB FLAC head) for songs nobody hears,
 * and the song the listener stopped on queued behind them on the one addon host.
 *
 * The old gate stopped only the Telegram/JioSaavn half, so the skip tests here
 * fail on it: the skipped lookups stay active, and a return to the first song
 * joins the old one instead of starting fresh.
 */
class KoSkippedLookupDirectSourceGateTest {

    private val addon = SlowAddon()

    @Before
    fun reset() {
        com.music.bitchord.ko.KoRefusedRows.resetForTest()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.clearSearchAnswers()
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = false
        KoDirectHqAhead.proofForTest = { true }
        KoDirectHqAhead.noteNextStream("", null)
        KoDirectLead.reset()
        install(listOf(addon, SlowAddon("YouTube", SourceKind.YOUTUBE)))
        DirectSourceGate.onPlaying(setOf("gc-start"))
    }

    @After
    fun restore() {
        DirectSourceGate.onPlaying(emptySet())
        KoDirectLead.reset()
        KoDirectHqAhead.proofForTest = null
        AppSettings.meteredConnection.value = null
        install(emptyList())
        SourceResolver.SourceCooldown.resetForTest()
    }

    @Test
    fun skippingThroughThreeSongsStopsTheSkippedLookupsAndAReturnStartsFresh() = runBlocking<Unit> {
        DirectSourceGate.onPlaying(setOf("gc-a"))
        val a = DirectSourceGate.startLookup("gc-a", target("Skip Alpha"))
        assertNotNull(a)
        withTimeout(3_000) { addon.searching("Skip Alpha").await() }

        DirectSourceGate.onPlaying(setOf("gc-b"))
        val b = DirectSourceGate.startLookup("gc-b", target("Skip Bravo"))
        assertNotNull(b)
        withTimeout(3_000) { addon.searching("Skip Bravo").await() }

        DirectSourceGate.onPlaying(setOf("gc-c"))

        assertTrue("A was skipped: its search must stop", a!!.isCancelled)
        assertTrue("B was skipped: its search must stop", b!!.isCancelled)
        assertNull("and leave the table", DirectSourceGate.stillLooking("gc-a"))
        assertNull(DirectSourceGate.stillLooking("gc-b"))
        withTimeout(3_000) { addon.stopped("Skip Alpha").await() }
        withTimeout(3_000) { addon.stopped("Skip Bravo").await() }
        assertNotNull("the song the listener stopped on is untouched", DirectSourceGate.startLookup("gc-c", target("Skip Charlie")))

        DirectSourceGate.onPlaying(setOf("gc-a"))
        val again = DirectSourceGate.startLookup("gc-a", target("Skip Alpha"))
        assertNotNull(again)
        assertNotSame("a return starts a fresh lookup, not the stopped one", a, again)
        val answered = runCatching { withTimeout(3_000) { again!!.await() } }
        assertTrue("it answers rather than throwing a cancellation: $answered", answered.isSuccess)
    }

    @Test
    fun aWaiterOnASkippedNextRowGetsNoAnswerAtOnce() = runBlocking<Unit> {
        DirectSourceGate.openNext("gc-next")
        val pending = async(Dispatchers.Default) {
            runCatching { DirectSourceGate.lookup("gc-next", target("Skip Next")) }
        }
        withTimeout(3_000) { addon.searching("Skip Next").await() }

        DirectSourceGate.openNext("gc-other")

        val answer = withTimeout(2_000) { pending.await() }
        assertTrue("no cancellation reaches a waiter that is still running: $answer", answer.isSuccess)
        assertNull(answer.getOrNull())
        withTimeout(3_000) { addon.stopped("Skip Next").await() }
    }

    @Test
    fun theNextRowBecomingThePlayingOneKeepsItsLookup() = runBlocking<Unit> {
        DirectSourceGate.openNext("gc-n")
        val lookup = DirectSourceGate.startLookup("gc-n", target("Keep November"))
        assertNotNull(lookup)
        withTimeout(3_000) { addon.searching("Keep November").await() }

        DirectSourceGate.onPlaying(setOf("gc-n"))

        assertTrue("next -> playing must lose nothing", lookup!!.isActive)
        assertTrue(DirectSourceGate.stillLooking("gc-n") === lookup)
        assertFalse(addon.stopped("Keep November").isCompleted)
    }

    /**
     * Review of the fix: an Automix/crossfade blend dropped before its handoff
     * (arm timeout on slow cellular, a seek, a swap) shrinks the audible set
     * back to the outgoing song while the incoming one is still the player's
     * next. Its lookup must survive and be joined when the window reopens; a
     * skip afterwards still stops it.
     */
    @Test
    fun aBlendDroppedBeforeItsHandoffKeepsTheNextRowsLookup() = runBlocking<Unit> {
        DirectSourceGate.onPlaying(setOf("gc-a2"))
        DirectSourceGate.openNext("gc-b2")
        val b = DirectSourceGate.startLookup("gc-b2", target("Blend Bravo"))
        assertNotNull(b)
        withTimeout(3_000) { addon.searching("Blend Bravo").await() }

        DirectSourceGate.onPlaying(setOf("gc-a2", "gc-b2"), upcoming = "gc-b2") // arming: both audible
        DirectSourceGate.onPlaying(setOf("gc-a2"), upcoming = "gc-b2") // dropped before the handoff

        assertTrue("a dropped blend's next row keeps its lookup", b!!.isActive)
        assertTrue(DirectSourceGate.stillLooking("gc-b2") === b)
        DirectSourceGate.openNext("gc-b2") // the sampler's next tick reopens the window
        assertTrue("the reopened window joins it", DirectSourceGate.startLookup("gc-b2", target("Blend Bravo")) === b)
        assertFalse(addon.stopped("Blend Bravo").isCompleted)

        DirectSourceGate.onPlaying(setOf("gc-c2"), upcoming = "gc-d2") // a skip still stops it
        assertTrue(b.isCancelled)
        assertNull(DirectSourceGate.stillLooking("gc-b2"))
    }

    /** A blend dropped because its incoming row left the queue: that row is no longer next, so it stops. */
    @Test
    fun aDroppedBlendWhoseRowLeftTheQueueStillStops() = runBlocking<Unit> {
        DirectSourceGate.onPlaying(setOf("gc-a3"))
        DirectSourceGate.openNext("gc-b3")
        val b = DirectSourceGate.startLookup("gc-b3", target("Gone Bravo"))
        assertNotNull(b)
        withTimeout(3_000) { addon.searching("Gone Bravo").await() }

        DirectSourceGate.onPlaying(setOf("gc-a3", "gc-b3"), upcoming = "gc-b3")
        DirectSourceGate.onPlaying(setOf("gc-a3"), upcoming = "gc-x3")

        assertTrue(b!!.isCancelled)
        assertNull(DirectSourceGate.stillLooking("gc-b3"))
    }

    /** A Cast or Wear resolve plays items this phone is not on; its lookup is never the gate's to stop. */
    @Test
    fun anUngatedLookupIsNeverStopped() = runBlocking<Unit> {
        DirectSourceGate.onPlaying(setOf("gc-cast"))
        val cast = DirectSourceGate.ungated { DirectSourceGate.startLookup("gc-cast", target("Cast Oscar")) }
        assertNotNull(cast)
        withTimeout(3_000) { addon.searching("Cast Oscar").await() }

        DirectSourceGate.onPlaying(setOf("gc-elsewhere"))

        assertTrue(cast!!.isActive)
        cast.cancel()
    }

    /** A lookup given up elsewhere (the second look, a lowered ceiling) is no answer to the resolve racing it. */
    @Test
    fun aStoppedLookupIsNoAnswerToTheRaceOrTheLastResort() = runBlocking<Unit> {
        val stopped = CompletableDeferred<SourceStream?>().apply { cancel() }
        val youtube = CompletableDeferred<Result<String>>()

        val race = runCatching { DirectSourceGate.firstAnswer(stopped, youtube) }
        assertTrue("the race reads it as no addon answer: $race", race.isSuccess)
        assertNull(race.getOrNull())

        val last = runCatching { DirectSourceGate.lastResort(null, stopped) }
        assertTrue("the last resort reads it as no addon answer: $last", last.isSuccess)
        assertNull(last.getOrNull())

        val running = CompletableDeferred<SourceStream?>()
        val late = async { runCatching { DirectSourceGate.lastResort(null, running) } }
        delay(50)
        running.cancel()
        assertNull(withTimeout(2_000) { late.await() }.getOrThrow())
    }

    private fun target(title: String) = TrackMatcher.Target(title, "Eljohnzc", durationSec = 200)

    private fun install(sources: List<SlowAddon>) {
        SourceRegistry.configs.value = sources.map { source ->
            SourceConfig(
                id = source.configId,
                kind = source.kind,
                label = source.configId,
                baseUrl = if (source.kind == SourceKind.YOUTUBE) "" else "https://${source.configId}",
                enabled = true,
            )
        }
        instancesField.set(SourceRegistry, sources.associateBy { it.configId })
    }

    private val instancesField = SourceRegistry::class.java
        .getDeclaredField("instances")
        .apply { isAccessible = true }

    /**
     * An addon whose first search of each title takes 3 s (inside the 4 s
     * per-source budget, so only a cancellation ends it early) and finds
     * nothing; a second search of the same title answers at once.
     */
    private class SlowAddon(
        override val configId: String = "Addon",
        override val kind: SourceKind = SourceKind.ADDON,
    ) : MusicSource {
        override val displayName: String get() = configId

        private val asked = Collections.synchronizedSet(mutableSetOf<String>())
        private val started = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val cancelled = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun searching(title: String) = started.getOrPut(title) { CompletableDeferred() }
        fun stopped(title: String) = cancelled.getOrPut(title) { CompletableDeferred() }

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            // The resolver may normalise the query (case, punctuation): match the title loosely.
            val key = listOf("Skip Alpha", "Skip Bravo", "Skip Charlie", "Skip Next", "Keep November", "Cast Oscar", "Blend Bravo", "Gone Bravo")
                .firstOrNull { query.contains(it, ignoreCase = true) } ?: query
            searching(key).complete(Unit)
            if (asked.add(key)) {
                try {
                    delay(3_000)
                } catch (e: CancellationException) {
                    stopped(key).complete(Unit)
                    throw e
                }
            }
            return emptyList()
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId/$trackId", format = StreamFormat("flac"), sourceConfigId = configId, trackId = trackId)
    }
}
