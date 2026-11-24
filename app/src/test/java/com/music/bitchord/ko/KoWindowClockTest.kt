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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-19 02:30 (vc215): Dance The Night's window opened 7.5 s before its
 * addon walk's first step, with no line in between to say where the time went.
 * The window now says, once: `direct window: <id> walk started +<ms> after open`
 * with the lookup's steps. Old code: no such line.
 */
class KoWindowClockTest {

    private val current = "clock-current"
    private val target = TrackMatcher.Target("Clock One", "Eljohnzc", durationSec = 200)
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
        install(listOf(FakeSource("Addon", listOf(row("clock-row"))), FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
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
    fun theWindowSaysWhenItsWalkStarted() = runBlocking<Unit> {
        val next = "clock-next-1"
        DirectSourceGate.openNext(next)

        DirectSourceGate.lookup(next, target)
        DirectSourceGate.lookup(next, target)

        val lines = TrackLog.everything().filter { "direct window: $next walk started +" in it }
        assertEquals("one line per window", 1, lines.size)
        assertTrue(lines.single(), "gate +" in lines.single() && "work +" in lines.single() && "ms after open" in lines.single())
    }

    @Test
    fun aTrackTheWindowDidNotOpenOnSaysNothing() = runBlocking<Unit> {
        DirectSourceGate.openNext("clock-next-2")
        val other = "clock-other"

        DirectSourceGate.lookup(other, target)

        assertTrue(TrackLog.everything().none { "direct window: $other walk started" in it })
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

    private inner class FakeSource(
        override val configId: String,
        private val rows: List<Song> = emptyList(),
        override val kind: SourceKind = SourceKind.ADDON,
    ) : MusicSource {
        override val displayName: String get() = configId

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?, target: TrackMatcher.Target?): List<Song> = rows

        // A reserved host: the probe's request fails at once, which keeps the candidate's chance.
        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId.invalid/$trackId", format = flac, sourceConfigId = configId, trackId = trackId)
    }
}
