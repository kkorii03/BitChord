package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
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
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.DirectSourceGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-19 (vc215, Automix, Private Hi-Res only): Pompeii -> STORM II at
 * 01:53 and Cruel Summer -> vampire at 02:07 both reached the window with no
 * `direct-lead:` line at all, because every early return of the lead was
 * silent. And the window's first lookup at 01:54:00 was `no addon served ''`:
 * the mix analysis's head read, held at the gate since 01:51, opens a plain
 * `bitchord://watch?v=` with no title, and as an entry in the gate's table it
 * could hand that empty answer to the titled lookup starting beside it.
 *
 * Old code: [eachSkipReasonIsLoggedOncePerPair] finds no skip line, and
 * [aBlankTargetDoesNotStartALookupOfItsOwn] gets a lookup started for an empty
 * title, so [aBlankTargetTakesTheLeadsAnswer] gets null from it.
 */
class KoLeadSkipTest {

    private val current = "skip-current"
    private val target = TrackMatcher.Target("Skip One", "Eljohnzc", durationSec = 200)
    private val blank = TrackMatcher.Target("", "", durationSec = null)
    private val flac = StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100)
    private val defaultWork = KoDirectLead.work

    @Before
    fun reset() {
        KoRefusedRows.resetForTest()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.clearSearchAnswers()
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = false
        KoDirectHqAhead.proofForTest = { true }
        KoDirectHqAhead.noteNextStream("", null)
        KoDirectLead.reset()
        DirectSourceGate.onPlaying(setOf(current))
    }

    @After
    fun restore() {
        KoDirectLead.reset()
        KoDirectLead.work = defaultWork
        KoDirectHqAhead.proofForTest = null
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = null
        DirectSourceGate.onPlaying(emptySet())
        install(emptyList())
    }

    @Test
    fun eachSkipReasonIsLoggedOncePerPair() = runBlocking<Unit> {
        val next = "skip-next-log"
        install(listOf(FakeSource("Addon"), FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        KoDirectLead.work = { _, _ -> null }

        AppSettings.meteredConnection.value = true
        repeat(3) { KoDirectLead.onTick(this, current, next, next, 58_000 - it * 5_000L) { target } }
        AppSettings.meteredConnection.value = false
        AppSettings.lowPower.value = true
        repeat(2) { KoDirectLead.onTick(this, current, next, next, 43_000 - it * 5_000L) { target } }
        AppSettings.lowPower.value = false
        KoDirectLead.onTick(this, current, next, "skip-other-video", 33_000) { target }
        // Before the lead's span: nothing to say yet.
        KoDirectLead.onTick(this, current, "skip-next-early", "skip-next-early", 90_000) { target }

        assertEquals(1, lines("direct-lead: $next skipped (metered connection)"))
        assertEquals(1, lines("direct-lead: $next skipped (saving power)"))
        assertEquals(1, lines("direct-lead: $next skipped (streams skip-other-video)"))
        assertEquals(0, lines("skip-next-early skipped"))
    }

    @Test
    fun aBlankTitleIsLoggedNotSilent() = runBlocking<Unit> {
        val next = "skip-next-blank"
        install(listOf(FakeSource("Addon"), FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        KoDirectLead.work = { _, _ -> error("no lead without a title") }

        KoDirectLead.onTick(this, current, next, next, 58_000) { blank }
        KoDirectLead.onTick(this, current, next, next, 53_000) { blank }

        assertEquals(1, lines("direct-lead: $next skipped (no title)"))
    }

    @Test
    fun aBlankTargetDoesNotStartALookupOfItsOwn() {
        val next = "skip-next-plain"
        install(listOf(FakeSource("Addon"), FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        DirectSourceGate.openNext(next)

        assertNull("a plain-id read has nothing to search with", DirectSourceGate.startLookup(next, blank))
        assertNull("and leaves no empty answer in the table", DirectSourceGate.stillLooking(next))
    }

    @Test
    fun aBlankTargetJoinsTheTitledLookup() = runBlocking<Unit> {
        val next = "skip-next-join"
        val release = CompletableDeferred<Unit>()
        install(listOf(FakeSource("Addon", gate = release), FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        DirectSourceGate.openNext(next)

        val titled = DirectSourceGate.startLookup(next, target)
        val plain = DirectSourceGate.startLookup(next, blank)

        assertSame("the head read waits on the window's own lookup", titled, plain)
        release.complete(Unit)
        titled?.cancel()
    }

    @Test
    fun aBlankTargetTakesTheLeadsAnswer() = runBlocking<Unit> {
        val next = "skip-next-lead"
        install(listOf(FakeSource("Addon"), FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        val proved = SourceStream(url = "https://Addon/led", format = flac, sourceConfigId = "Addon", trackId = "led")
        KoDirectLead.work = { _, _ -> proved }
        KoDirectLead.onTick(this, current, next, next, 58_000) { target }
        withTimeout(3_000) { KoDirectLead.reusable(next)!!.await() }

        DirectSourceGate.openNext(next)
        val answer = DirectSourceGate.lookup(next, blank)

        assertEquals("the plain read plays the lead's proved copy", proved.url, answer?.url)
    }

    private fun lines(text: String) = TrackLog.everything().count { text in it }

    private fun install(sources: List<FakeSource>) {
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

    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind = SourceKind.ADDON,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : MusicSource {
        override val displayName: String get() = configId

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            gate?.await()
            return emptyList()
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId/$trackId", format = StreamFormat("flac"), sourceConfigId = configId, trackId = trackId)
    }
}
