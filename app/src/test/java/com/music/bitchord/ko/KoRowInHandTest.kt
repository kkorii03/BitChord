package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ModuleSource
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-19 02:08 (vc215, vampire aHmg0jsmNhg): the second look opened row 1
 * of the Private Hi-Res addon at once (FLAC 24-bit), went on to compare row 2
 * (an 11 s cold read) and row 3, and the 12 s per-source timeout dropped the
 * whole walk, open row 1 included: "no better copy on any source", and the
 * timeout rested the addon for 30 minutes.
 *
 * Old code: [aRowAlreadyOpenSurvivesTheSourcesTimeout] gets null and a failure
 * mark on the addon.
 */
class KoRowInHandTest {

    private val target = TrackMatcher.Target("Row In Hand", "Eljohnzc", durationSec = 200)
    private val hires = StreamFormat("flac", bitDepth = 24, sampleRateHz = 48_000)

    @Before
    fun reset() {
        KoRefusedRows.resetForTest()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.clearSearchAnswers()
        install(emptyList())
    }

    @After
    fun restore() {
        install(emptyList())
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.clearSearchAnswers()
    }

    @Test
    fun aRowAlreadyOpenSurvivesTheSourcesTimeout() = runBlocking<Unit> {
        val addon = Catalogue("Addon", listOf(row("hr-row-1"), row("hr-row-2"), row("hr-row-3")), hires)
        install(listOf(addon))

        val found = SourceResolver.bestAcross(
            listOf(addon),
            target,
            StreamRequest.Lossless,
            waitForAll = true,
            sourceTimeoutMs = 800,
        )

        assertEquals("row 1 answered and row 2 was still opening", 2, addon.opened.size)
        assertEquals("the open row is the answer", "https://Addon/${addon.opened.first()}", found?.second?.url)
        assertEquals("a source that served a stream did not fail toward its rest", 0L, SourceResolver.SourceCooldown.failureMark("Addon"))
        assertFalse(SourceResolver.SourceCooldown.isResting("Addon"))
        assertTrue(TrackLog.everything().any { "Addon timed out after 800ms comparing rows; keeps" in it })
    }

    @Test
    fun aWalkWithNothingOpenStillTimesOutAsBefore() = runBlocking<Unit> {
        val addon = Catalogue("Addon", listOf(row("hr-none-1")), hires, hangFrom = 1)
        install(listOf(addon))

        val found = SourceResolver.bestAcross(
            listOf(addon),
            target,
            StreamRequest.Lossless,
            waitForAll = true,
            sourceTimeoutMs = 500,
        )

        assertEquals(null, found)
        assertEquals("the timeout still counts", 1L, SourceResolver.SourceCooldown.failureMark("Addon"))
    }

    private fun row(id: String) = Song(
        videoId = id,
        title = "Row In Hand",
        artist = "Eljohnzc",
        thumbnailUrl = null,
        durationText = "3:20",
        sourceQuality = ModuleSource.LOSSLESS,
    )

    private fun install(sources: List<Catalogue>) {
        SourceRegistry.configs.value = sources.map { source ->
            SourceConfig(
                id = source.configId,
                kind = source.kind,
                label = source.configId,
                baseUrl = "https://${source.configId}",
                enabled = true,
            )
        }
        instancesField.set(SourceRegistry, sources.associateBy { it.configId })
    }

    private val instancesField = SourceRegistry::class.java
        .getDeclaredField("instances")
        .apply { isAccessible = true }

    /** Opens rows in order; from the [hangFrom]th open on, the read never answers. */
    private class Catalogue(
        override val configId: String,
        private val rows: List<Song>,
        private val format: StreamFormat,
        private val hangFrom: Int = 2,
    ) : MusicSource {
        override val kind: SourceKind = SourceKind.ADDON
        override val displayName: String get() = configId
        val opened = mutableListOf<String>()

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> = rows

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream {
            opened += trackId
            if (opened.size >= hangFrom) awaitCancellation()
            return SourceStream(url = "https://$configId/$trackId", format = format, sourceConfigId = configId, trackId = trackId)
        }
    }
}
