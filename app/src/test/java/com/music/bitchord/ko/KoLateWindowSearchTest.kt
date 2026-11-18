package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-19 02:08 (vc215, LTE): vampire aHmg0jsmNhg's window search crossed
 * the 4 s per-addon cap ("timed out after 4000ms; skipping for this track"),
 * though the addon's own `/stream` had answered 200 a second later. The timeout
 * cancelled the walk, the lookup ended empty, and the late-proof path and the
 * second look were handed nothing to finish. STORM II at 01:54 the same.
 *
 * The standby's wait keeps the 4 s answer; the lookup handed on after it now
 * searches on with the lead's budget. Old code:
 * [aWindowSearchPastItsCapRunsOnForTheLateProof] finds no lookup still running.
 */
class KoLateWindowSearchTest {

    private val current = "late-current"
    private val target = TrackMatcher.Target("Late One", "Eljohnzc", durationSec = 200)
    private val flac = StreamFormat("flac", bitDepth = 24, sampleRateHz = 48_000)

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
        KoDirectHqAhead.proofForTest = null
        AppSettings.meteredConnection.value = null
        DirectSourceGate.onPlaying(emptySet())
        install(emptyList())
        SourceResolver.SourceCooldown.resetForTest()
    }

    @Test
    fun aWindowSearchPastItsCapRunsOnForTheLateProof() = runBlocking<Unit> {
        val next = "late-next-1"
        val addon = FakeSource("Addon", listOf(row("hr-late-1")), firstOpenMs = 6_000)
        install(listOf(addon, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        DirectSourceGate.openNext(next)

        assertNull("the standby still falls back at the window's cap", DirectSourceGate.lookup(next, target))
        val handedOn = DirectSourceGate.stillLooking(next)
        assertNotNull("the lookup handed to the late proof is still running", handedOn)

        assertEquals("https://Addon.invalid/hr-late-1", withTimeout(10_000) { handedOn!!.await() }?.url)
        assertTrue(TrackLog.everything().any { "direct window: $next addon ran past 4000ms" in it })
    }

    @Test
    fun aWindowThatFoundNothingIsNotAskedAgain() = runBlocking<Unit> {
        val next = "late-next-2"
        val addon = FakeSource("Addon")
        install(listOf(addon, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        DirectSourceGate.openNext(next)

        assertNull(DirectSourceGate.lookup(next, target))
        val searched = addon.searches
        withTimeout(3_000) { DirectSourceGate.stillLooking(next)?.await() }

        assertTrue(searched > 0)
        assertEquals("a miss is not a timeout: no second walk", searched, addon.searches)
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

    /** The first `/stream` open takes [firstOpenMs] (a cold read); later ones answer at once. */
    private inner class FakeSource(
        override val configId: String,
        private val rows: List<Song> = emptyList(),
        private val firstOpenMs: Long = 0L,
        override val kind: SourceKind = SourceKind.ADDON,
    ) : MusicSource {
        override val displayName: String get() = configId

        @Volatile
        var searches = 0

        @Volatile
        var opens = 0

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> {
            searches++
            return rows
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream {
            if (opens++ == 0) delay(firstOpenMs)
            // A reserved host: the probe's request fails at once, which keeps the candidate's chance.
            return SourceStream(url = "https://$configId.invalid/$trackId", format = flac, sourceConfigId = configId, trackId = trackId)
        }
    }
}
