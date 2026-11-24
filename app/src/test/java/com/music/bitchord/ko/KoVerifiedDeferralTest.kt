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
import com.music.bitchord.data.sources.addon.AddonUnavailable
import com.music.bitchord.ko.downloads.KoDownloadPipeline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 11-18 Private Hi-Res addon answering 503 + Retry-After: 60.
 *
 *  - C: a resting source, or an open that failed with a fault, must not drop the
 *    proved pair. It used to return null, which the download turned into a
 *    permanent failure and `dropVerifiedPair`.
 *  - D: the rest a host asked for (Retry-After) is the rest it gets, not the
 *    fixed thirty minutes.
 */
class KoVerifiedDeferralTest {

    @Before
    fun reset() {
        SourceResolver.SourceCooldown.resetForTest()
    }

    @After
    fun clear() {
        install(emptyList())
        SourceResolver.SourceCooldown.resetForTest()
    }

    @Test
    fun `a 503 with Retry-After defers the pair and rests only as long as the host asked`() = runBlocking<Unit> {
        val busy = FakeSource("hires") { throw AddonUnavailable("HTTP 503", restUntilMs = System.currentTimeMillis() + 60_000) }
        install(listOf(busy))

        // First fault: the open failed, the pair is kept as transient.
        val first = deferral { SourceResolver.forDownloadVerified("hires", "t1", StreamRequest.Lossless) }
        assertTrue("waits at least a minute: ${first.waitMs}", first.waitMs >= 60_000)
        assertTrue(KoDownloadPipeline.automaticFailure(first).transient)
        assertEquals(first.waitMs, KoDownloadPipeline.automaticFailure(first).retryAfterMs)

        // Second fault starts the rest, which is the host's 60 s, not 30 min.
        deferral { SourceResolver.forDownloadVerified("hires", "t1", StreamRequest.Lossless) }
        assertTrue(SourceResolver.SourceCooldown.isResting("hires"))
        val left = SourceResolver.SourceCooldown.restLeftMs("hires")
        assertTrue("rest follows Retry-After: $left", left in 1..61_000)

        // While resting the source is not opened at all, and the pair is still kept.
        val opens = busy.opens
        val resting = deferral { SourceResolver.forDownloadVerified("hires", "t1", StreamRequest.Lossless) }
        assertEquals(opens, busy.opens)
        assertTrue(KoDownloadPipeline.automaticFailure(resting).transient)
    }

    @Test
    fun `a source that answers it has no stream is still a final refusal`() = runBlocking<Unit> {
        install(listOf(FakeSource("hires") { null }))
        assertNull(SourceResolver.forDownloadVerified("hires", "gone", StreamRequest.Lossless))
    }

    @Test
    fun `the proved pair opens as before`() = runBlocking<Unit> {
        install(
            listOf(
                FakeSource("hires") { id ->
                    SourceStream(url = "https://hires/$id", format = StreamFormat("flac"), sourceConfigId = "hires", trackId = id)
                },
            ),
        )
        val stream = SourceResolver.forDownloadVerified("hires", "t2", StreamRequest.Lossless)
        assertNotNull(stream)
        assertEquals("t2", stream?.trackId)
    }

    private suspend fun deferral(block: suspend () -> SourceStream?): KoDownloadPipeline.AutoDownloadDeferred {
        try {
            block()
        } catch (e: KoDownloadPipeline.AutoDownloadDeferred) {
            return e
        }
        fail("expected the verified pair to be deferred")
        error("unreachable")
    }

    private fun install(sources: List<FakeSource>) {
        SourceRegistry.configs.value = sources.map { source ->
            SourceConfig(
                id = source.configId,
                kind = source.kind,
                label = source.configId,
                baseUrl = "https://${source.configId}",
            )
        }
        instancesField.set(SourceRegistry, sources.associateBy { it.configId })
    }

    private val instancesField = SourceRegistry::class.java
        .getDeclaredField("instances")
        .apply { isAccessible = true }

    private class FakeSource(
        override val configId: String,
        private val open: (String) -> SourceStream?,
    ) : MusicSource {
        override val displayName: String get() = configId
        override val kind: SourceKind = SourceKind.ADDON

        @Volatile
        var opens = 0

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?, target: TrackMatcher.Target?): List<Song> =
            emptyList()

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
            opens++
            return open(trackId)
        }
    }
}
