package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.smart.HqAudioVerify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        HqHostTracker.resetForTest()
        HqAudioVerify.UnmeasuredMemory.clearForTest()
    }

    private class FakeSource(
        override val configId: String,
        private val rows: List<Song> = emptyList(),
        private val format: StreamFormat = StreamFormat("flac"),
        private val failSearch: Boolean = false,
    ) : MusicSource {
        override val displayName: String get() = configId
        override val kind: SourceKind get() = SourceKind.ADDON
        var asked = 0
            private set

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

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(
                url = "https://$configId/$trackId",
                format = format,
                sourceConfigId = configId,
                trackId = trackId,
            )
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
