package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
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
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.smart.HqAudioVerify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 11-18 '체포하겠어': kkorii12's FLAC passed hq-verify (match=100%), its audition
 * was judged too slow, and the exclusion that followed kept kkorii12 out of
 * every later look that play ("더 높은 음질을 찾지 못했습니다"); the second press,
 * which clears the exclusions, swapped it in within five seconds.
 *
 * An audition failure keeps its source out of the same walk, as before; the
 * play's one bounded retry may ask it again. A mismatch stays out.
 */
class KoAuditionRetryTest {

    private val mediaId = "qsqvJpOgrWw-test"

    @Before
    fun reset() {
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        KoRefusedRows.resetForTest()
        HqHostTracker.resetForTest()
        HqAudioVerify.UnmeasuredMemory.clearForTest()
        QualityUpgrade.forget(mediaId)
        install(emptyList())
    }

    @After
    fun tearDown() {
        QualityUpgrade.forget(mediaId)
        install(emptyList())
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        KoRefusedRows.resetForTest()
    }

    private val instancesField = SourceRegistry::class.java
        .getDeclaredField("instances")
        .apply { isAccessible = true }

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
        override val kind: SourceKind = SourceKind.ADDON,
    ) : MusicSource {
        override val displayName: String get() = configId
        override suspend fun health() = SourceHealth.Ok()
        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
            target: TrackMatcher.Target?,
        ): List<Song> = rows

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream = SourceStream(
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

    private fun startManual() {
        QualityUpgrade.askByHand(mediaId)
        assertTrue(
            QualityUpgrade.settledForLess(mediaId, target(), playing = StreamFormat("opus", kbps = 152)),
        )
    }

    /** What the bounded retry does once it fires: the question is open and the track pending again. */
    private fun retryAdopts() {
        KoQualityUpgrade.reopen(mediaId)
        assertTrue(
            QualityUpgrade.settledForLess(mediaId, target(), playing = StreamFormat("opus", kbps = 152)),
        )
    }

    @Test
    fun `a slow audition keeps its source out of the walk but not out of the play's retry`() = runBlocking {
        val kkorii12 = FakeSource("kkorii12", listOf(row("kk-1")), StreamFormat("flac", bitDepth = 24, sampleRateHz = 44_100))
        install(listOf(kkorii12, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        startManual()

        val first = QualityUpgrade.lookAgain(mediaId, 200) { _, _ -> true }
        assertEquals("https://kkorii12/kk-1", first?.url)

        KoQualityUpgrade.failedAudition(mediaId, first?.sourceConfigId, HqHostTracker.hostOf(first!!.url))
        assertTrue(QualityUpgrade.retryNext(mediaId))
        assertNull(
            "the same walk must not re-try the copy that just failed its audition",
            QualityUpgrade.lookAgain(mediaId, 200) { _, _ -> true },
        )

        assertTrue(KoQualityUpgrade.releaseAuditionExclusions(mediaId))
        retryAdopts()
        val retried = QualityUpgrade.lookAgain(mediaId, 200) { _, _ -> true }
        assertEquals(
            "the play's retry must be able to ask the sound-checked source again",
            "https://kkorii12/kk-1",
            retried?.url,
        )
    }

    @Test
    fun `a mismatch stays excluded when the slow audition is released`() = runBlocking {
        val mismatch = FakeSource("Mismatch", listOf(row("mm-1")), StreamFormat("flac", bitDepth = 24, sampleRateHz = 96_000))
        val kkorii12 = FakeSource("kkorii12", listOf(row("kk-1")), StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100))
        install(listOf(mismatch, kkorii12, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        startManual()

        val first = QualityUpgrade.lookAgain(mediaId, 200) { _, _ -> true }
        assertEquals("https://Mismatch/mm-1", first?.url)
        QualityUpgrade.failedCandidate(mediaId, first?.sourceConfigId, HqHostTracker.hostOf(first!!.url))

        assertTrue(QualityUpgrade.retryNext(mediaId))
        val second = QualityUpgrade.lookAgain(mediaId, 200) { _, _ -> true }
        assertEquals("https://kkorii12/kk-1", second?.url)
        KoQualityUpgrade.failedAudition(mediaId, second?.sourceConfigId, HqHostTracker.hostOf(second!!.url))

        assertTrue(QualityUpgrade.retryNext(mediaId))
        assertNull(QualityUpgrade.lookAgain(mediaId, 200) { _, _ -> true })

        assertTrue(KoQualityUpgrade.releaseAuditionExclusions(mediaId))
        retryAdopts()
        val retried = QualityUpgrade.lookAgain(mediaId, 200) { _, _ -> true }
        assertEquals("only the slow copy comes back, never the mismatch", "https://kkorii12/kk-1", retried?.url)
    }

    @Test
    fun `a walk with no slow audition is not reopened`() {
        KoQualityUpgrade.failedCandidate(mediaId, "Mismatch", "mismatch")
        assertFalse(KoQualityUpgrade.releaseAuditionExclusions(mediaId))
        assertFalse("the mismatch is still out", KoQualityUpgrade.allowsInFlight(mediaId, stream("Mismatch")))
    }

    @Test
    fun `an audition rejected for its bytes stays excluded`() {
        KoQualityUpgrade.noteAuditionRejected(mediaId)
        KoQualityUpgrade.failedAudition(mediaId, "Transcode", "transcode")
        assertFalse(KoQualityUpgrade.releaseAuditionExclusions(mediaId))
        assertFalse(KoQualityUpgrade.allowsInFlight(mediaId, stream("Transcode")))
    }

    private fun stream(source: String) = SourceStream(
        url = "https://${source.lowercase()}/x",
        format = StreamFormat("flac"),
        sourceConfigId = source,
        trackId = "x",
    )
}
