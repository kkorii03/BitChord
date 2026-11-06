package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.smart.HqAudioVerify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The speed rules of a hand-made quality upgrade.
 *
 * The reported run spent 40s on one song: one search per source candidate, the
 * same resting addon probed again for every candidate, the same 403 Deezer row
 * downloaded twice, and a 30-second LastWave preview decoded before an
 * obviously better Qobuz copy was tried. None of these tests changes what is
 * accepted — only how many requests and decodes it takes to find it.
 */
class UpgradeSpeedTest {

    @Before
    fun reset() {
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        com.music.bitchord.ko.KoRefusedRows.resetForTest()
        HqHostTracker.resetForTest()
        HqAudioVerify.UnmeasuredMemory.clearForTest()
        install(emptyList())
    }

    @After
    fun tearDown() {
        install(emptyList())
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        com.music.bitchord.ko.KoRefusedRows.resetForTest()
    }

    private val instancesField = SourceRegistry::class.java
        .getDeclaredField("instances")
        .apply { isAccessible = true }

    /** Puts just these sources in the registry, in this order. */
    private fun install(sources: List<FakeSource>) {
        SourceRegistry.configs.value = sources.map { source ->
            SourceConfig(
                id = source.configId,
                kind = source.kind,
                label = source.configId,
                baseUrl = if (source.kind == SourceKind.YOUTUBE) "" else "https://${source.configId}",
            )
        }
        instancesField.set(SourceRegistry, sources.associateBy { it.configId })
    }

    private class FakeSource(
        override val configId: String,
        private val rows: List<Song> = emptyList(),
        private val format: StreamFormat = StreamFormat("flac"),
        private val failSearch: Boolean = false,
        override val kind: SourceKind = SourceKind.ADDON,
        /** The stream endpoint answers 403 — the source's own failure, not a bad row. */
        private val refuseStream: Boolean = false,
    ) : MusicSource {
        override val displayName: String get() = configId
        var asked = 0
            private set

        /** Every row opened, in order, so a walk's order is visible. */
        val opened = mutableListOf<String>()

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            asked += 1
            if (failSearch) throw IllegalStateException("source is down")
            return rows
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream {
            opened += trackId
            if (refuseStream) throw java.io.IOException("HTTP 403")
            return SourceStream(
                url = "https://$configId/$trackId",
                format = format,
                sourceConfigId = configId,
                trackId = trackId,
            )
        }
    }

    private fun target() = TrackMatcher.Target("Golden Boy", "Eljohnzc", durationSec = 200)

    private fun row(id: String) = Song(
        videoId = id,
        title = "Golden Boy",
        artist = "Eljohnzc",
        thumbnailUrl = null,
        durationText = "3:20",
    )

    @Test
    fun `one walk collects every candidate without asking a source again`() = runBlocking {
        val deezer = FakeSource("Deezer", listOf(row("deezer-1")), StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100))
        val qobuz = FakeSource("Qobuz", listOf(row("qobuz-1")), StreamFormat("flac", bitDepth = 24, sampleRateHz = 96_000))
        val collected = mutableListOf<Pair<MusicSource, SourceStream>>()

        val found = SourceResolver.bestAcross(
            listOf(deezer, qobuz),
            target(),
            StreamRequest.Lossless,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            strictOriginal = true,
            allowArtistUnverified = true,
            collect = collected,
        )

        assertEquals("both copies must survive the one search", 2, collected.size)
        assertEquals("Deezer searched once", 1, deezer.asked)
        assertEquals("Qobuz searched once", 1, qobuz.asked)
        assertTrue(found != null)
    }

    @Test
    fun `a resting source is probed once per manual request`() = runBlocking {
        val resting = FakeSource("Monochrome", failSearch = true)
        SourceResolver.SourceCooldown.failed(resting.configId)
        SourceResolver.SourceCooldown.failed(resting.configId)
        assertTrue(SourceResolver.SourceCooldown.isResting(resting.configId))
        val session = Any()

        SourceResolver.bestAcross(
            listOf(resting),
            target(),
            StreamRequest.Lossless,
            waitForAll = true,
            manual = true,
            manualSession = session,
        )
        val afterFirst = resting.asked
        SourceResolver.bestAcross(
            listOf(resting),
            target(),
            StreamRequest.Lossless,
            waitForAll = true,
            manual = true,
            manualSession = session,
        )

        assertTrue("the resting source is asked at all once", afterFirst > 0)
        assertEquals(
            "the same manual request must not probe the resting source again for the next candidate",
            afterFirst,
            resting.asked,
        )
    }

    /**
     * The upgrade re-search is the walk that is specifically looking for the
     * better copy, so a source parked after two failures still gets its one
     * short probe — the same probe a hand-made request gets. Skipping it meant
     * the only search that could have found the FLAC never asked the source
     * that was resting with it (phone 11-04: the lossless sources were resting
     * and every upgrade went straight to YouTube's copy).
     */
    /**
     * Phone 11-06: a dead addon asked on all 72 upgrades, 54 failures in a row,
     * each spending its four second window. A probe on every walk is no rest.
     */
    @Test
    fun `an upgrade walk does not probe a resting source every track`() = runBlocking {
        val resting = FakeSource("Monochrome", failSearch = true)
        SourceResolver.SourceCooldown.failed(resting.configId)
        SourceResolver.SourceCooldown.failed(resting.configId)

        SourceResolver.bestAcross(
            listOf(resting), target(), StreamRequest.Lossless, waitForAll = true, probeResting = true,
        )
        val afterFirst = resting.asked
        SourceResolver.bestAcross(
            listOf(resting), target(), StreamRequest.Lossless, waitForAll = true, probeResting = true,
        )

        assertTrue("the walk still gets its one probe", afterFirst > 0)
        assertEquals("the next walk waits its turn", afterFirst, resting.asked)
    }

    @Test
    fun `the probe turn comes round again`() {
        val id = "turns"
        assertTrue(com.music.bitchord.ko.KoSourceCooldown.takeRestProbe(id, nowMs = 0L))
        assertFalse(com.music.bitchord.ko.KoSourceCooldown.takeRestProbe(id, nowMs = 60_000L))
        assertTrue(com.music.bitchord.ko.KoSourceCooldown.takeRestProbe(id, nowMs = 10 * 60_000L))
    }

    @Test
    fun `a resting source is asked once during an upgrade`() = runBlocking {
        val resting = FakeSource(
            "Monochrome",
            listOf(row("mono-1")),
            StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100),
        )
        install(listOf(resting, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        SourceResolver.SourceCooldown.failed(resting.configId)
        SourceResolver.SourceCooldown.failed(resting.configId)
        assertTrue(SourceResolver.SourceCooldown.isResting(resting.configId))

        val found = SourceResolver.upgradeFor(target(), playing = StreamFormat("opus", kbps = 160))

        assertEquals("the resting source's copy is the upgrade", "https://Monochrome/mono-1", found?.url)
        assertTrue("the resting source must be asked, not skipped: ${resting.asked}", resting.asked >= 1)
        assertFalse(
            "an answer that arrived clears the rest, as it does for a hand-made request",
            SourceResolver.SourceCooldown.isResting(resting.configId),
        )
    }

    /**
     * A catalogue serves one recording as several rows. One row whose file is
     * not what the row claimed is that row's problem, not the source's: the
     * walk asks the source again for another copy of the same song, and only
     * moves on to YouTube when there is none.
     */
    @Test
    fun `a refused row does not stop the same source's next row`() = runBlocking {
        val deezer = FakeSource(
            "Deezer",
            listOf(row("bad-row"), row("good-row")),
            StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100),
        )
        install(listOf(deezer, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))

        val probed = mutableListOf<String>()
        val stream = SourceResolver.directStream(target()) { candidate ->
            probed += candidate.url
            !candidate.url.endsWith("/bad-row")
        }

        assertEquals("https://Deezer/good-row", stream?.url)
        assertEquals(
            "the refused row is stepped over, not the source: ${deezer.opened}",
            listOf("bad-row", "good-row"),
            deezer.opened,
        )
        assertFalse(
            "a row refused for being the wrong file is not the source failing",
            SourceResolver.SourceCooldown.isResting(deezer.configId),
        )
        assertTrue("the good row is probed before it plays: $probed", "https://Deezer/good-row" in probed)
    }

    /**
     * The other half of the same rule: a stream endpoint that answers 403 is
     * the source's own failure, and it keeps the rest rule it has always had.
     */
    @Test
    fun `a genuine 403 still rests the source`() = runBlocking {
        val broken = FakeSource("Qobuz", listOf(row("qobuz-403")), refuseStream = true)

        repeat(2) {
            SourceResolver.bestAcross(
                listOf(broken),
                target(),
                StreamRequest.Lossless,
                strictLength = true,
                strictOriginal = true,
            )
        }

        assertTrue(
            "a refused stream endpoint is a source failure, not a bad row",
            SourceResolver.SourceCooldown.isResting(broken.configId),
        )
    }

    @Test
    fun `the next candidate is a list pop and the highest tier is proved first`() = runBlocking {
        val qobuz = FakeSource(
            "Qobuz",
            listOf(row("qobuz-1")),
            StreamFormat("flac", bitDepth = 24, sampleRateHz = 96_000),
        )
        val deezer = FakeSource(
            "Deezer",
            listOf(row("deezer-1")),
            StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100),
        )
        val youtube = FakeSource("YouTube", kind = SourceKind.YOUTUBE)
        install(listOf(qobuz, deezer, youtube))
        val mediaId = "one-manual-search"

        QualityUpgrade.askByHand(mediaId)
        assertTrue(
            QualityUpgrade.settledForLess(
                mediaId,
                target(),
                playing = StreamFormat("opus", kbps = 160),
            ),
        )

        val proved = mutableListOf<String>()
        val first = QualityUpgrade.lookAgain(mediaId, 200) { _, stream ->
            proved += stream.url
            true
        }

        assertEquals("https://Qobuz/qobuz-1", first?.url)
        assertEquals("the one search asks each source once", 1, qobuz.asked)
        assertEquals(1, deezer.asked)
        assertTrue(
            "the 24-bit copy must be proved before the 16-bit one: $proved",
            proved.first().contains("Qobuz"),
        )

        assertTrue(QualityUpgrade.retryNext(mediaId))
        val second = QualityUpgrade.lookAgain(mediaId, 200) { _, _ -> true }

        assertEquals("https://Deezer/deezer-1", second?.url)
        assertEquals("moving to the next candidate must not search again", 1, qobuz.asked)
        assertEquals(1, deezer.asked)
        QualityUpgrade.forget(mediaId)
    }

    @Test
    fun `a real preview is known before its bytes are downloaded`() {
        assertTrue(HqAudioVerify.isPreview(streamDurationSec = 30, targetDurationSec = 200))
        assertTrue(!HqAudioVerify.isPreview(streamDurationSec = 190, targetDurationSec = 200))
        assertTrue(!HqAudioVerify.isPreview(streamDurationSec = null, targetDurationSec = 200))
    }

    @Test
    fun `an unmeasured source row is remembered for the app run`() {
        HqAudioVerify.UnmeasuredMemory.note("Deezer", "deezer-1", "HTTP 403")

        assertEquals("HTTP 403", HqAudioVerify.UnmeasuredMemory.reason("Deezer", "deezer-1"))
        assertEquals(
            null,
            HqAudioVerify.UnmeasuredMemory.reason("Deezer", "another-row"),
        )
        assertEquals(
            null,
            HqAudioVerify.UnmeasuredMemory.reason("LastWave", "deezer-1"),
        )
    }

    @Test
    fun `candidates are ordered by the quality ladder before verification`() {
        val source = FakeSource("Trial", listOf(row("x")))
        fun candidate(url: String, format: StreamFormat) =
            source to SourceStream(url = url, format = format)

        val ordered = SourceResolver.orderUpgradeCandidates(
            listOf(
                candidate("lossy", StreamFormat("mp4", kbps = 320)),
                candidate("flac16", StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100)),
                candidate("flac24", StreamFormat("flac", bitDepth = 24, sampleRateHz = 96_000)),
            ),
        )

        assertEquals(listOf("flac24", "flac16", "lossy"), ordered.map { it.second.url })
    }
}
