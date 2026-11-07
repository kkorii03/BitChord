package com.music.bitchord

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
import com.music.bitchord.ko.KoQualityUpgrade
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.smart.HqAudioVerify
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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
 * The automatic path from "this track played on YouTube's copy" to a lossless
 * copy, and what it may not wait on.
 *
 * 11-07 'Stressed Out', 91.9 s from the tap to the swap: 8.1 s waiting on a
 * lookup that could only find a 30-second preview, then a race that returned
 * Dolby Atmos over two FLACs it had in hand, then nothing at all until the
 * listener asked by hand.
 */
class HqFastUpgradeTest {

    private val flac16 = StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100)
    private val flac24 = StreamFormat("flac", bitDepth = 24, sampleRateHz = 44_100)
    private val atmos = StreamFormat("dolby-atmos")
    private val aac320 = StreamFormat("mp4", kbps = 320)
    private val opus = StreamFormat("opus", kbps = 160)

    @Before
    fun reset() {
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        com.music.bitchord.ko.KoRefusedRows.resetForTest()
        HqHostTracker.resetForTest()
        install(emptyList())
    }

    @After
    fun tearDown() {
        install(emptyList())
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        QualityUpgrade.forgetLastSession()
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

    private inner class FakeSource(
        override val configId: String,
        private val format: StreamFormat = flac16,
        private val answersAfterMs: Long = 0L,
        override val kind: SourceKind = SourceKind.ADDON,
    ) : MusicSource {
        override val displayName: String get() = configId
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
            if (answersAfterMs > 0L) delay(answersAfterMs)
            return listOf(
                Song(
                    videoId = "$configId-row",
                    title = "Stressed Out",
                    artist = "twenty one pilots",
                    thumbnailUrl = null,
                    durationText = "3:22",
                ),
            )
        }

        override suspend fun stream(trackId: String, request: StreamRequest) = SourceStream(
            url = "https://$configId/$trackId",
            format = format,
            durationSec = 202,
            sourceConfigId = configId,
            trackId = trackId,
        )
    }

    private fun target() = TrackMatcher.Target("Stressed Out", "twenty one pilots", durationSec = 202)

    private fun youtube() = FakeSource("YouTube", kind = SourceKind.YOUTUBE)

    private fun pending(mediaId: String, inFlight: Deferred<SourceStream?>? = null) {
        assertTrue(QualityUpgrade.settledForLess(mediaId, target(), inFlight = inFlight, playing = opus))
    }

    /** 14:10:58: Atmos returned over a 24-bit and a 16-bit FLAC, and the look ended there. */
    @Test
    fun `an answer the automatic path cannot use does not beat the lossless ones`() = runBlocking {
        install(listOf(FakeSource("Trial", atmos), FakeSource("Bridge", flac16), youtube()))
        pending("atmos-first")

        val found = QualityUpgrade.lookAgain("atmos-first", 202) { _, _ -> true }

        assertEquals("https://Bridge/Bridge-row", found?.url)
    }

    /** 11-03: `verify=34756ms` for an answer nothing on this path reads. */
    @Test
    fun `a lossy answer is neither proved nor returned`() = runBlocking {
        install(listOf(FakeSource("Saavn", aac320), youtube()))
        pending("lossy-only")
        val proved = mutableListOf<String>()

        val found = QualityUpgrade.lookAgain("lossy-only", 202) { _, stream ->
            proved += stream.url
            true
        }

        assertNull(found)
        assertTrue("proved: $proved", proved.isEmpty())
    }

    /** A cover from a fast source used to end the look with the real one unasked. */
    @Test
    fun `a candidate that fails its proof hands over to the next one`() = runBlocking {
        install(listOf(FakeSource("Cover", flac24), FakeSource("Real", flac16), youtube()))
        pending("fails-proof")

        val found = QualityUpgrade.lookAgain("fails-proof", 202) { _, stream ->
            !stream.url.contains("Cover")
        }

        assertEquals("https://Real/Real-row", found?.url)
    }

    @Test
    fun `one look stops proving after three failures`() = runBlocking {
        install(
            listOf(
                FakeSource("A", flac16),
                FakeSource("B", flac16),
                FakeSource("C", flac16),
                FakeSource("D", flac16),
                youtube(),
            ),
        )
        pending("many-covers")
        var proofs = 0

        val found = QualityUpgrade.lookAgain("many-covers", 202) { _, _ ->
            proofs++
            false
        }

        assertNull(found)
        assertEquals(3, proofs)
    }

    /** 14:01:20: Telegram's FLAC in hand at 1.5 s, then 2.5 s more for an addon that never came. */
    @Test
    fun `the first proved lossless copy ends the race`() = runBlocking {
        val slow = FakeSource("Slow", flac24, answersAfterMs = 30_000L)
        install(listOf(slow, FakeSource("Quick", flac16), youtube()))
        pending("first-proved")

        val found = withTimeout(5_000L) {
            QualityUpgrade.lookAgain("first-proved", 202) { _, _ -> true }
        }

        assertEquals("https://Quick/Quick-row", found?.url)
    }

    /** 14:10:49 to 14:10:57: nothing asked while the direct lookup proved a preview. */
    @Test
    fun `the search does not wait for a lookup still running`() = runBlocking {
        install(listOf(FakeSource("Bridge", flac16), youtube()))
        val neverAnswers = CompletableDeferred<SourceStream?>()
        pending("still-running", inFlight = neverAnswers)

        val found = withTimeout(5_000L) {
            QualityUpgrade.lookAgain("still-running", 202) { _, _ -> true }
        }

        assertEquals("https://Bridge/Bridge-row", found?.url)
        assertFalse("the lookup is left to finish", neverAnswers.isCancelled)
    }

    @Test
    fun `a lossless answer from the lookup still wins`() = runBlocking {
        val slow = FakeSource("Slow", flac16, answersAfterMs = 30_000L)
        install(listOf(slow, youtube()))
        val late = SourceStream(
            url = "https://Lookup/row",
            format = flac16,
            durationSec = 202,
            sourceConfigId = "Lookup",
            trackId = "row",
        )
        pending("lookup-wins", inFlight = CompletableDeferred(late))

        val found = withTimeout(5_000L) {
            QualityUpgrade.lookAgain("lookup-wins", 202) { _, _ -> true }
        }

        assertEquals("https://Lookup/row", found?.url)
    }

    /** A 320 from the lookup used to end the look, and the lossless sources were never asked. */
    @Test
    fun `a lossy answer from the lookup does not end the look`() = runBlocking {
        install(listOf(FakeSource("Bridge", flac16, answersAfterMs = 200L), youtube()))
        val late = SourceStream(
            url = "https://Lookup/row",
            format = aac320,
            durationSec = 202,
            sourceConfigId = "Lookup",
            trackId = "row",
        )
        pending("lookup-lossy", inFlight = CompletableDeferred(late))

        val found = QualityUpgrade.lookAgain("lookup-lossy", 202) { _, _ -> true }

        assertEquals("https://Bridge/Bridge-row", found?.url)
    }

    /** Two addons missed the live path's four seconds on 11-07, and each answered within ten. */
    @Test
    fun `a source slower than the live window still gets to answer`() = runBlocking {
        install(listOf(FakeSource("Bridge", flac16, answersAfterMs = 4_400L), youtube()))
        pending("slow-source")

        val found = QualityUpgrade.lookAgain("slow-source", 202) { _, _ -> true }

        assertEquals("https://Bridge/Bridge-row", found?.url)
    }

    @Test
    fun `the race waits for the other side when one has nothing`() = runBlocking {
        assertEquals(
            "search",
            KoQualityUpgrade.firstAnswerOf<String>({ null }) {
                delay(50L)
                "search"
            },
        )
        assertEquals(
            "lookup",
            KoQualityUpgrade.firstAnswerOf<String>({
                delay(50L)
                "lookup"
            }) { null },
        )
        assertNull(KoQualityUpgrade.firstAnswerOf<String>({ null }) { null })
        assertEquals("search", KoQualityUpgrade.firstAnswerOf<String>(null) { "search" })
    }

    @Test
    fun `the winner stops the race`() = runBlocking {
        assertEquals(
            "lookup",
            withTimeout(5_000L) {
                KoQualityUpgrade.firstAnswerOf<String>({ "lookup" }) { awaitCancellation() }
            },
        )
    }

    /** `auditioning … at 1048576`, no seek, and 25 s of waiting for a position it could not reach. */
    @Test
    fun `an audition that cannot seek to the listener is known at once`() {
        assertTrue(KoQualityUpgrade.auditionCannotReach(seekable = false, positionMs = 59_700L))
        assertFalse(KoQualityUpgrade.auditionCannotReach(seekable = true, positionMs = 59_700L))
        assertFalse(KoQualityUpgrade.auditionCannotReach(seekable = false, positionMs = 400L))
    }

    @Test
    fun `a Telegram stream is told from a plain one`() {
        assertTrue(KoQualityUpgrade.isTelegramStream(SourceStream(url = "bitchord-telegram://track/1_2")))
        assertFalse(KoQualityUpgrade.isTelegramStream(SourceStream(url = "https://bridge/1")))
    }

    /**
     * A decode that finishes ahead of the network used to be reported as a
     * failed download: stopping the transfer breaks its read, and that exception
     * reached the parent.
     */
    @Test
    fun `stopping the transfer does not fail a finished decode`() = runBlocking {
        val file = File.createTempFile("hq-fast", ".bin")
        try {
            val stop = CompletableDeferred<Unit>()
            HqAudioVerify.copyBesideDecode(
                file,
                expected = 8L,
                decode = { _, _, _ -> true },
                copy = {
                    stop.await()
                    throw IOException("Socket closed")
                },
                stop = { stop.complete(Unit) },
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a transfer that really fails still fails`() {
        val file = File.createTempFile("hq-fast", ".bin")
        try {
            val thrown = runCatching {
                runBlocking {
                    HqAudioVerify.copyBesideDecode(
                        file,
                        expected = 8L,
                        decode = { _, _, progress ->
                            while (!progress.finished) delay(5L)
                            false
                        },
                        copy = { throw IOException("HTTP 500") },
                    )
                }
            }.exceptionOrNull()
            assertEquals("HTTP 500", thrown?.message)
        } finally {
            file.delete()
        }
    }

    /**
     * What makes the whole-file read fast for a Telegram copy: the read carries
     * on past the head for as long as the decode runs, and stops when it ends.
     */
    @Test
    fun `the transfer reads on past the head until the decode ends`() = runBlocking {
        val file = File.createTempFile("hq-fast", ".bin")
        try {
            val readingOn = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val run = async {
                HqAudioVerify.copyBesideDecode(
                    file,
                    expected = 8L,
                    decode = { _, _, _ ->
                        readingOn.await()
                        order += "decoded"
                        true
                    },
                    copy = { order += "head" },
                    afterHead = {
                        order += "tail"
                        readingOn.complete(Unit)
                        awaitCancellation()
                    },
                    stop = { stopped.complete(Unit) },
                )
            }
            withTimeout(5_000L) { run.await() }
            assertTrue(stopped.isCompleted)
            assertEquals(listOf("head", "tail", "decoded"), order)
        } finally {
            file.delete()
        }
    }

    /** A decode that gave up needs the complete head, not everything after it as well. */
    @Test
    fun `a decode that gives up waits for the head and not the tail`() = runBlocking {
        val file = File.createTempFile("hq-fast", ".bin")
        try {
            var headLanded = false
            withTimeout(5_000L) {
                HqAudioVerify.copyBesideDecode(
                    file,
                    expected = 8L,
                    decode = { _, _, _ -> false },
                    copy = {
                        delay(100L)
                        headLanded = true
                    },
                    afterHead = { awaitCancellation() },
                )
            }
            assertTrue(headLanded)
        } finally {
            file.delete()
        }
    }
}
