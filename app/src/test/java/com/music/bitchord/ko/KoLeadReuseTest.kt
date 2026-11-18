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
import com.music.bitchord.playback.StreamChoice
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * nextprep fix 1 (vc213 log: the window opened 100 times, 1 next track started
 * as FLAC). The lead (KoDirectLead, T-60) proved the next track's copy and threw
 * the answer away; the window's lookup at T-25 then ran its own search, `/stream`
 * and probe, which is what timed out and sent proved rows to YouTube. The window
 * now takes the lead's answer, or joins the lead still proving.
 *
 * Old code: [KoDirectLead.reusable] does not exist, and the window's lookup
 * searched and opened the sources a second time.
 */
class KoLeadReuseTest {

    private val current = "reuse-current"
    private val target = TrackMatcher.Target("Reuse One", "Eljohnzc", durationSec = 200)
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
    fun theWindowTakesTheLeadsProvedCopyWithoutSearchingOrOpeningAgain() = runBlocking<Unit> {
        val next = "reuse-next-1"
        val addon = FakeSource("Addon")
        val telegram = FakeSource("Telegram", listOf(row("tg-reuse-1")), flac, SourceKind.TELEGRAM)
        install(listOf(addon, telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))

        KoDirectLead.onTick(this, current, next, next, 58_000) { target }
        val led = withTimeout(5_000) { KoDirectLead.reusable(next)!!.await() }
        assertEquals("https://Telegram/tg-reuse-1", led?.url)
        val searched = addon.searches + telegram.searches
        val opened = telegram.opens

        DirectSourceGate.openNext(next)
        val started = DirectSourceGate.lookup(next, target)

        assertEquals("the window serves the lead's proved copy", led?.url, started?.url)
        assertEquals("no second search", searched, addon.searches + telegram.searches)
        assertEquals("no second /stream open", opened, telegram.opens)
    }

    @Test
    fun theWindowJoinsALeadStillProvingInsteadOfStartingItsOwn() = runBlocking<Unit> {
        val next = "reuse-next-2"
        val addon = FakeSource("Addon")
        val telegram = FakeSource("Telegram", listOf(row("tg-reuse-2")), flac, SourceKind.TELEGRAM)
        install(listOf(addon, telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        val proving = CompletableDeferred<Unit>()
        val proved = SourceStream(url = "https://Addon/lead-2", format = flac, sourceConfigId = "Addon", trackId = "lead-2")
        KoDirectLead.work = { _, _ -> proving.await(); proved }

        KoDirectLead.onTick(this, current, next, next, 58_000) { target }
        assertNotNull("a lead still proving is reusable", KoDirectLead.reusable(next))
        DirectSourceGate.openNext(next)
        val window = async { DirectSourceGate.lookup(next, target) }
        repeat(5) { yield() }
        assertFalse("the window waits on the lead", window.isCompleted)
        proving.complete(Unit)

        assertEquals(proved.url, withTimeout(5_000) { window.await() }?.url)
        assertEquals("the window never searched for itself", 0, addon.searches + telegram.searches)
    }

    @Test
    fun aLeadThatFoundNothingLeavesTheWindowsOwnSearch() = runBlocking<Unit> {
        val next = "reuse-next-3"
        val telegram = FakeSource("Telegram", listOf(row("tg-reuse-3")), flac, SourceKind.TELEGRAM)
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        KoDirectLead.work = { _, _ -> null }

        KoDirectLead.onTick(this, current, next, next, 58_000) { target }
        withTimeout(5_000) { KoDirectLead.reusable(next)?.await() }
        assertEquals("an empty lead is not reused", null, KoDirectLead.reusable(next))
        DirectSourceGate.openNext(next)

        assertEquals("https://Telegram/tg-reuse-3", DirectSourceGate.lookup(next, target)?.url)
        assertTrue("the window searched as before", telegram.searches > 0)
    }

    /**
     * nextprep B5: a row still pinned to YouTube from an earlier play is led,
     * and replay-ahead's lookup (KoReplayAhead -> DirectSourceGate.startLookup)
     * now takes that answer instead of searching again at T-25.
     */
    @Test
    fun aReplayRowPinnedToYouTubeTakesTheLeadsAnswerToo() = runBlocking<Unit> {
        val next = "reuse-next-4"
        val telegram = FakeSource("Telegram", listOf(row("tg-reuse-4")), flac, SourceKind.TELEGRAM)
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        try {
            StreamChoice.remember(
                next,
                SourceStream(url = "https://youtube/$next", format = StreamFormat("opus"), sourceConfigId = "YouTube"),
                substituted = false,
            )
            assertTrue("pinned to YouTube is still led", KoDirectLead.eligible(next, next))
            KoDirectLead.onTick(this, current, next, next, 58_000) { target }
            val led = withTimeout(5_000) { KoDirectLead.reusable(next)!!.await() }
            val searched = telegram.searches

            DirectSourceGate.openNext(next)
            val replay = DirectSourceGate.startLookup(next, target)

            assertEquals(led?.url, withTimeout(5_000) { replay!!.await() }?.url)
            assertEquals("no second search", searched, telegram.searches)
        } finally {
            StreamChoice.forget(next)
        }
    }

    @Test
    fun theWindowsLookupAsksTheLeadFirst() {
        val start = source("src/main/java/com/music/bitchord/playback/DirectSourceGate.kt")
            .substringAfter("private fun start(videoId: String, target: TrackMatcher.Target): Lookup? {")
            .substringBefore("internal sealed interface FirstAnswer")
        val reuse = start.indexOf("KoDirectLead.reused(videoId)?.let { return@async it }")
        assertTrue("the lookup's work takes the lead's answer", reuse >= 0)
        assertTrue("before its own search", reuse < start.indexOf("SourceResolver.directStream("))
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

        @Volatile
        var opens = 0

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

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream {
            opens++
            return SourceStream(url = "https://$configId/$trackId", format = format, sourceConfigId = configId, trackId = trackId)
        }
    }
}
