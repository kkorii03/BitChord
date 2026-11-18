package com.music.bitchord.ko

import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.StreamChoice
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Night review 11-19 (ship/lanes/review-night1119.md).
 *
 * F1: a row started from YouTube's partial cached copy ([KoCachedPrefixStart])
 * whose YouTube walk failed dropped the addon's answer, so the continuation
 * past the cached bytes failed mid-song in the resolver's YouTube branch. Now
 * the answer moves the row onto the addon as its own rendition before the
 * bytes run out, a whole copy hands the lookup to the second look, and a plain
 * open of a rescued row is refused rather than continue YouTube's entry.
 *
 * F3: a "definitely gone" failure of the walk that skipped the dead clients
 * ([KoDeadClients]) was the video's verdict; it is now only the full walk's.
 * The stored memo is also keyed by transport.
 *
 * Old code: [KoCachedPrefixStart.Settled], settleBehind's complete /
 * lastResort / onSettled, eligible's youTubeGone, refusesSplice,
 * [KoDeadClients.walkSkippingDead] and [KoDeadClients.onTransport] do not
 * exist (this class does not compile), and the source checks fail.
 */
class KoReviewFix1119Test {
    @get:org.junit.Rule val koTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60) // a hang fails with its stack instead of stalling CI


    private val id = "fix1119-row"
    private val target = TrackMatcher.Target("Cold One", "Eljohnzc", durationSec = 220)
    private val addon = SourceStream(url = "https://addon.example/t.flac", sourceConfigId = "src", trackId = "trk")
    private val defaultBehind = KoCachedPrefixStart.behind

    @Before
    fun reset() {
        KoCachedPrefixStart.resetForTest()
        KoDirectLead.reset()
        StreamChoice.forget(id)
        Downloads.setSavedMetadata(emptyMap())
        DirectSourceGate.onPlaying(setOf(id))
        KoDeadClients.onTransport("")
    }

    @After
    fun restore() {
        KoCachedPrefixStart.behind = defaultBehind
        DirectSourceGate.onPlaying(emptySet())
        KoCachedPrefixStart.resetForTest()
        StreamChoice.forget(id)
        Downloads.setSavedMetadata(emptyMap())
        KoDeadClients.onTransport("")
    }

    // ---- F1: the settle behind the cached copy ----

    @Test
    fun aFailedWalkUnderAPartialCopyMovesOntoTheAddonsAnswer() = runBlocking {
        val events = mutableListOf<KoCachedPrefixStart.Settled>()
        var pinned = false
        var handedOver = false
        val pinnedYouTube = KoCachedPrefixStart.settleBehind(
            videoId = id,
            lookup = { CompletableDeferred(addon) },
            walk = { error("every client refused") },
            stillWanted = { true },
            pin = { pinned = true },
            handOver = { handedOver = true },
            complete = false,
            lastResort = { it.await() },
            onSettled = { events += it },
        )
        assertFalse(pinnedYouTube)
        assertFalse("nothing is pinned under the cached copy's reader", pinned)
        assertFalse(handedOver)
        assertEquals(listOf<KoCachedPrefixStart.Settled>(KoCachedPrefixStart.Settled.Rescue(id, addon)), events)
    }

    @Test
    fun aLookupStillRunningIsWaitedForBeforeTheRescue() = runBlocking {
        val running = CompletableDeferred<SourceStream?>()
        val events = mutableListOf<KoCachedPrefixStart.Settled>()
        var waitedOn: Deferred<SourceStream?>? = null
        KoCachedPrefixStart.settleBehind(
            videoId = id,
            lookup = { running },
            walk = { running.complete(addon); error("walk failed") },
            stillWanted = { true },
            pin = {},
            handOver = {},
            lastResort = { waitedOn = it; it.await() },
            onSettled = { events += it },
        )
        assertSame(running, waitedOn)
        assertEquals(listOf<KoCachedPrefixStart.Settled>(KoCachedPrefixStart.Settled.Rescue(id, addon)), events)
    }

    @Test
    fun noAddonAnswerLeavesTheRowToRecovery() = runBlocking {
        val events = mutableListOf<KoCachedPrefixStart.Settled>()
        assertFalse(
            KoCachedPrefixStart.settleBehind(
                videoId = id,
                lookup = { CompletableDeferred<SourceStream?>().apply { complete(null) } },
                walk = { error("walk failed") },
                stillWanted = { true },
                pin = {},
                handOver = {},
                lastResort = { it.await() },
                onSettled = { events += it },
            ),
        )
        assertTrue(events.isEmpty())
        // A refused row starts no lookup, so it has nothing to move onto.
        KoCachedPrefixStart.settleBehind(
            videoId = id,
            lookup = { null },
            walk = { error("walk failed") },
            stillWanted = { true },
            pin = {},
            handOver = {},
            lastResort = { fail("no lookup to wait for"); null },
            onSettled = { events += it },
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun aRowThatStoppedWaitingIsNotMoved() = runBlocking {
        val events = mutableListOf<KoCachedPrefixStart.Settled>()
        var asks = 0
        KoCachedPrefixStart.settleBehind(
            videoId = id,
            lookup = { CompletableDeferred(addon) },
            walk = { error("walk failed") },
            stillWanted = { asks++ == 0 }, // still wanted when the walk failed, not once the addon answered
            pin = {},
            handOver = {},
            lastResort = { it.await() },
            onSettled = { events += it },
        )
        assertEquals(2, asks)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aFailedWalkUnderAWholeCopyHandsTheLookupToTheSecondLook() = runBlocking {
        val lookup = CompletableDeferred<SourceStream?>(addon)
        val events = mutableListOf<KoCachedPrefixStart.Settled>()
        var handed: Deferred<SourceStream?>? = null
        assertFalse(
            KoCachedPrefixStart.settleBehind(
                videoId = id,
                lookup = { lookup },
                walk = { error("walk failed") },
                stillWanted = { true },
                pin = { fail("nothing to pin") },
                handOver = { handed = it },
                complete = true,
                lastResort = { fail("a whole copy needs no rescue"); null },
                onSettled = { events += it },
            ),
        )
        assertSame(lookup, handed)
        assertEquals(listOf<KoCachedPrefixStart.Settled>(KoCachedPrefixStart.Settled.LookAgain(id)), events)
    }

    @Test
    fun aPinnedWalkAsksTheSecondLookAgain() = runBlocking {
        val events = mutableListOf<KoCachedPrefixStart.Settled>()
        assertTrue(
            KoCachedPrefixStart.settleBehind(
                videoId = id,
                lookup = { CompletableDeferred<SourceStream?>() },
                walk = { "https://yt.example/a" },
                stillWanted = { true },
                pin = {},
                handOver = {},
                onSettled = { events += it },
            ),
        )
        assertEquals(listOf<KoCachedPrefixStart.Settled>(KoCachedPrefixStart.Settled.LookAgain(id)), events)
    }

    @Test
    fun takesOverPassesWhetherTheCopyIsWhole() {
        val seen = mutableListOf<Boolean>()
        KoCachedPrefixStart.behind = { _, _, complete -> seen += complete }
        assertTrue(KoCachedPrefixStart.takesOver(id, target, 0, complete = true))
        assertEquals(listOf(true), seen)
    }

    @Test
    fun aVideoYouTubeAlreadyRefusedKeepsTheOldSettle() {
        val enough = KoCachedPrefixStart.MIN_AHEAD_BYTES
        assertTrue(KoCachedPrefixStart.eligible(true, true, false, enough, false, youTubeGone = false))
        assertFalse(KoCachedPrefixStart.eligible(true, true, false, enough, false, youTubeGone = true))

        val gone = "fix1119-gone"
        KoCachedPrefixStart.behind = { _, _, _ -> fail("the walk behind would only fail") }
        DirectSourceGate.onPlaying(setOf(gone))
        StreamResolver.markUnplayable(gone, "This video is unavailable")
        assertFalse(KoCachedPrefixStart.takesOver(gone, target, 3_100_000, complete = false))
        assertFalse(KoCachedPrefixStart.startedForTest(gone))
    }

    // ---- F1: no splice after the rescue ----

    @Test
    fun aRescuedRowRefusesAPlainOpenOfItsAddonPin() {
        StreamChoice.remember(id, addon, substituted = true)
        assertFalse("not rescued: the resolver serves as before", KoCachedPrefixStart.refusesSplice(id, ownRendition = false))
        KoCachedPrefixStart.onRescued(id)
        assertTrue(KoCachedPrefixStart.refusesSplice(id, ownRendition = false))
        assertFalse("the upgraded rendition has its own entry", KoCachedPrefixStart.refusesSplice(id, ownRendition = true))
        StreamChoice.remember(id, SourceStream("https://yt.example/a"), substituted = false)
        assertFalse("a YouTube pin continues YouTube's entry", KoCachedPrefixStart.refusesSplice(id, ownRendition = false))
        StreamChoice.remember(id, addon, substituted = true)
        DirectSourceGate.onPlaying(setOf("next-row"))
        assertFalse("the mark ends with the play", KoCachedPrefixStart.refusesSplice(id, ownRendition = false))
    }

    @Test
    fun theServiceCarriesOutTheSettleAndTheResolverRefusesTheSplice() {
        val service = source("src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        assertTrue(service.contains("KoCachedPrefixStart.settled.collect { koOnCachedPrefixSettled(it) }"))
        val guard = service.indexOf("KoCachedPrefixStart.refusesSplice(videoId")
        val serve = service.indexOf("            if (serving != null) {\n")
        assertTrue("the guard runs before the pinned stream is served", guard in 0 until serve)
        val rescue = source("src/main/java/com/music/bitchord/ko/KoCachedPrefixStart.kt")
            .substringAfter("private suspend fun PlaybackService.koRescueCachedPrefix(")
        val replace = rescue.indexOf("setUri(upgraded)")
        val pin = rescue.indexOf("StreamChoice.remember(id, stream, substituted = true)")
        assertTrue("the addon goes in as its own rendition", rescue.indexOf("QualityUpgrade.upgradedUri(") in 0 until replace)
        assertTrue("marked before the pin the guard reads", rescue.indexOf("KoCachedPrefixStart.onRescued(id)") in 0 until pin)
    }

    // ---- F3: dead clients ----

    @Test
    fun aDefinitelyGoneAnswerWithoutTheDeadClientsAsksThemToo() = runBlocking {
        val asked = mutableListOf<Boolean>()
        val retried = mutableListOf<String?>()
        val stream = KoDeadClients.walkSkippingDead<String>(
            dead = setOf("VISIONOS"),
            walk = { skipDead ->
                asked += skipDead
                if (skipDead) throw IllegalStateException("This video is unavailable") else "served"
            },
            onRetry = { retried += it.message },
        )
        assertEquals("served", stream)
        assertEquals(listOf(true, false), asked)
        assertEquals(listOf<String?>("This video is unavailable"), retried)
    }

    @Test
    fun theVerdictIsTheFullWalksOwn() = runBlocking {
        val asked = mutableListOf<Boolean>()
        try {
            KoDeadClients.walkSkippingDead<String>(dead = setOf("VISIONOS"), walk = { skipDead ->
                asked += skipDead
                throw IllegalStateException(if (skipDead) "unavailable" else "gone for every client")
            })
            fail("the full walk's failure is the answer")
        } catch (e: IllegalStateException) {
            assertEquals("gone for every client", e.message)
        }
        assertEquals(listOf(true, false), asked)

        asked.clear()
        assertNull(KoDeadClients.walkSkippingDead<String>(dead = emptySet(), walk = { skipDead -> asked += skipDead; null }))
        assertEquals("no dead clients: one walk", listOf(false), asked)

        asked.clear()
        assertEquals(
            "found nothing without them: ask them",
            "late",
            KoDeadClients.walkSkippingDead<String>(dead = setOf("IOS"), walk = { skipDead -> asked += skipDead; if (skipDead) null else "late" }),
        )
        assertEquals(listOf(true, false), asked)
    }

    @Test
    fun theResolverNoLongerTakesAVerdictFromTheShortWalk() {
        val resolver = source("../shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnerTubeXResolver.kt")
        assertFalse(resolver.contains("if (UnplayableReason.isDefinitelyGone(e.message)) throw e\n            TrackLog.w(TAG, \"InnerTubeX walk without"))
        assertTrue(resolver.contains("KoDeadClients.walkSkippingDead(dead,"))
    }

    @Test
    fun theStoredMemoIsKeptPerTransport() {
        assertEquals("off", KoDeadClients.place())
        KoDeadClients.onTransport("cell")
        assertEquals("off.cell", KoDeadClients.place())
        KoDeadClients.onTransport("wifi")
        assertEquals("off.wifi", KoDeadClients.place())

        var clock = 10_000_000L
        val writes = mutableListOf<String>()
        KoDeadClients.init(null, { writes += it }, { clock })
        val onWifi = KoDeadClients.route(viaProxy = false, proxyGeneration = 1, place = KoDeadClients.place())
        listOf("a", "b", "c").forEach { KoDeadClients.failed(onWifi, "VISIONOS", it) }
        assertEquals(setOf("VISIONOS"), KoDeadClients.dead(onWifi))

        // A new process on LTE does not inherit Wi-Fi's record.
        clock += 60_000
        KoDeadClients.init(writes.last(), {}, { clock })
        KoDeadClients.onTransport("cell")
        val onCell = KoDeadClients.route(viaProxy = false, proxyGeneration = 2, place = KoDeadClients.place())
        assertTrue(KoDeadClients.dead(onCell).isEmpty())
        KoDeadClients.onTransport("wifi")
        val backOnWifi = KoDeadClients.route(viaProxy = false, proxyGeneration = 3, place = KoDeadClients.place())
        assertEquals(setOf("VISIONOS"), KoDeadClients.dead(backOnWifi))
        KoDeadClients.clear("test")
        KoDeadClients.init(null, {})
    }

    @Test
    fun theTransportIsNamedFromTheCapabilities() {
        assertEquals("", transportKindOf(known = false, wifi = false, cellular = false, ethernet = false))
        assertEquals("wifi", transportKindOf(known = true, wifi = true, cellular = true, ethernet = false))
        assertEquals("cell", transportKindOf(known = true, wifi = false, cellular = true, ethernet = false))
        assertEquals("eth", transportKindOf(known = true, wifi = false, cellular = false, ethernet = true))
        assertEquals("other", transportKindOf(known = true, wifi = false, cellular = false, ethernet = false))
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        val candidates = if (path.startsWith("../")) {
            listOf(File(root, path), File(root, path.removePrefix("../")))
        } else {
            listOf(File(root, "app/$path"), File(root, path))
        }
        return candidates.first { it.exists() }.readText().replace("\r\n", "\n")
    }
}
