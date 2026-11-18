package com.music.bitchord.ko

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
import com.music.bitchord.playback.DirectSourceGate
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-18 16:xx (vc213, Private Hi-Res proving in 5-25 s): the window opened
 * 20.5-21.4 s before the transition and the standby opened the next item about
 * 5.5 s later, so a lookup begun at the window (APT DiTd771WumE: cold search
 * timed out at 4 s) rarely had its proof before the standby fell back to
 * YouTube. The next track's lookup and proof now run [KoDirectLead.LEAD_MS]
 * ahead, off the same playing tick, and the window's own lookup reuses them.
 *
 * Old code has no lead (this class does not compile against it), and
 * [theSamplerTickStartsTheLeadBeforeTheWindow] fails on its driveDirectWindow,
 * which returned before the window with nothing.
 */
class KoDirectLeadTest {

    private val current = "lead-current"
    private val next = "lead-next-1"
    private val target = TrackMatcher.Target("Lead One", "Eljohnzc", durationSec = 200)
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
    fun theLeadIsOncePerPairInsideSixtySecondsAndNeverInTheWindow() {
        assertFalse("too early", KoDirectLead.due(current, next, 70_000))
        assertTrue("inside the lead", KoDirectLead.due(current, next, 58_000))
        assertFalse("once per pair", KoDirectLead.due(current, next, 53_000))
        assertFalse("the window's own lookup takes over", KoDirectLead.due(current, "lead-other", 20_000))
        assertTrue("another next track is led again", KoDirectLead.due(current, "lead-other", 48_000))
        assertTrue(KoDirectLead.inLead(DirectSourceGate.WINDOW_MS + 1))
        assertFalse(KoDirectLead.inLead(DirectSourceGate.WINDOW_MS))
        assertFalse(KoDirectLead.inLead(KoDirectLead.LEAD_MS + 1))
    }

    @Test
    fun aTickInTheLeadStartsOneLookupAndNothingElseDoes() = runBlocking<Unit> {
        install(listOf(FakeSource("Addon"), FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        val calls = AtomicInteger()
        val ran = CompletableDeferred<String>()
        KoDirectLead.work = { id, _ -> calls.incrementAndGet(); ran.complete(id); null }
        val targets = AtomicInteger()
        val targetOf = { targets.incrementAndGet(); target }

        KoDirectLead.onTick(this, current, next, next, 90_000, targetOf)
        KoDirectLead.onTick(this, current, next, next, 58_000, targetOf)
        assertEquals(next, withTimeout(3_000) { ran.await() })
        KoDirectLead.onTick(this, current, next, next, 53_000, targetOf)
        KoDirectLead.onTick(this, current, next, next, 20_000, targetOf)

        assertEquals("one lead for the pair", 1, calls.get())
        assertEquals("the target is built only for the lead that runs", 1, targets.get())
    }

    @Test
    fun lowPowerMeteredASongVersionAndRestingAddonsStartNothing() = runBlocking<Unit> {
        install(listOf(FakeSource("Addon"), FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        val calls = AtomicInteger()
        KoDirectLead.work = { _, _ -> calls.incrementAndGet(); null }

        AppSettings.lowPower.value = true
        KoDirectLead.onTick(this, current, next, next, 58_000) { target }
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = true
        // [KO] metered at a lossless ceiling: a metered network below lossless still starts nothing.
        val cellular = AppSettings.audioQualityCellular.value
        AppSettings.audioQualityCellular.value = com.music.bitchord.data.settings.AudioQuality.HIGH
        try {
            KoDirectLead.onTick(this, current, next, next, 58_000) { target }
        } finally {
            AppSettings.audioQualityCellular.value = cellular
        }
        AppSettings.meteredConnection.value = false
        // A song version streams another video; its proof would be for the wrong recording.
        KoDirectLead.onTick(this, current, next, "lead-other-video", 58_000) { target }
        // A local or addon row has no YouTube id to stream.
        KoDirectLead.onTick(this, current, next, null, 58_000) { target }
        install(emptyList())
        KoDirectLead.onTick(this, current, next, next, 58_000) { target }

        assertEquals(0, calls.get())
    }

    @Test
    fun aQueueMoveStopsTheLeadButTheWindowAndTheStartKeepIt() = runBlocking<Unit> {
        install(listOf(FakeSource("Addon"), FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        KoDirectLead.work = { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                stopped.complete(Unit)
                throw e
            }
        }
        KoDirectLead.onTick(this, current, next, next, 58_000) { target }
        withTimeout(3_000) { started.await() }

        KoDirectLead.keepOnly(setOf(current), null)
        KoDirectLead.keepOnly(setOf(current), next)
        KoDirectLead.keepOnly(setOf(current, next), next)
        KoDirectLead.keepOnly(setOf(next), null)
        assertFalse("still next, or now playing: the lead runs on", stopped.isCompleted)

        KoDirectLead.keepOnly(setOf("lead-skipped-to"), null)
        withTimeout(3_000) { stopped.await() }

        // Coming back to the same pair does not ask the addon a second time.
        val again = AtomicInteger()
        KoDirectLead.work = { _, _ -> again.incrementAndGet(); null }
        KoDirectLead.onTick(this, current, next, next, 50_000) { target }
        assertEquals(0, again.get())
    }

    @Test
    fun theLeadProvesAheadOutsideTheWindowAndTheWindowReusesItsSearch() = runBlocking<Unit> {
        val telegram = FakeSource("Telegram", listOf(row("tg-lead-1")), flac, SourceKind.TELEGRAM)
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))

        val led = DirectSourceGate.leadLookup(next, target)

        assertEquals("the lead proves the copy the next song should start on", "https://Telegram/tg-lead-1", led?.url)
        // Outside the gate's table: a later window lookup must get its own full
        // LOOKUP_TIMEOUT_MS, not a wait already spent by the lead.
        assertNull(DirectSourceGate.stillLooking(next))
        val searches = telegram.searches

        DirectSourceGate.openNext(next)
        val started = DirectSourceGate.lookup(next, target)

        assertEquals("the window's lookup answers with the same copy", led?.url, started?.url)
        assertEquals("from the lead's search, not a second one", searches, telegram.searches)
    }

    @Test
    fun noProofNoLead() = runBlocking<Unit> {
        val telegram = FakeSource("Telegram", listOf(row("tg-lead-2")), flac, SourceKind.TELEGRAM)
        val addon = FakeSource("Addon")
        install(listOf(addon, telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))

        assertNull(KoDirectLead.lookup(next, target, proof = null) { null })
        assertEquals(0, addon.searches)
        assertEquals(0, telegram.searches)
    }

    /**
     * The lead hangs off the window's early return in driveDirectWindow, so it
     * inherits every return above it: not playing, inside the seek settle, no
     * next item, a music-only row.
     */
    @Test
    fun theSamplerTickStartsTheLeadBeforeTheWindow() {
        val body = source("src/main/java/com/music/bitchord/playback/PlaybackService.kt")
            .substringAfter("private fun driveDirectWindow() {")
            .substringBefore("private fun cancelPrefetch()")
        val playing = body.indexOf("!exoPlayer.isPlaying) return")
        val settled = body.indexOf("lastSeekAt < SEEK_SETTLE_MS) return")
        val musicOnly = body.indexOf("if (MusicOnlyPlayback.wants(next)) return")
        val lead = body.indexOf("if (remaining > DirectSourceGate.WINDOW_MS) return com.music.bitchord.ko.KoDirectLead.onTick(")
        val window = body.indexOf("if (!DirectSourceGate.openNext(next.mediaId)) return")
        assertTrue("paused and seek returns first", playing in 0 until lead && settled in 0 until lead)
        assertTrue("music-only rows return first", musicOnly in 0 until lead)
        assertTrue("the lead runs on the window's early return", lead in 0 until window)

        val drop = source("src/main/java/com/music/bitchord/playback/DirectSourceGate.kt")
            .substringAfter("private fun dropAheadNotAdmitted() {")
            .substringBefore("private fun Open.admits")
        assertTrue("a queue move stops the lead", "KoDirectLead.keepOnly(now.playing, now.next)" in drop)
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "$it$path") }.first { it.exists() }
            .readText().replace("\r\n", "\n")
    }

    private fun row(id: String) = Song(
        videoId = id,
        title = target.title,
        artist = "Eljohnzc",
        thumbnailUrl = null,
        durationText = "3:20",
    )

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
        private val rows: List<Song> = emptyList(),
        private val format: StreamFormat = StreamFormat("flac"),
        override val kind: SourceKind = SourceKind.ADDON,
    ) : MusicSource {
        override val displayName: String get() = configId

        @Volatile
        var searches = 0

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            searches++
            return rows
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId/$trackId", format = format, sourceConfigId = configId, trackId = trackId)
    }
}
