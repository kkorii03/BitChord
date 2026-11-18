package com.music.bitchord.ko

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Log 11-18 18:11 Pompeii (gXLzCTQ76I4): the mid-song swap's download opened the
 * upgraded item, the resolver recorded "Private Hi-Res FLAC" over the YouTube
 * stream still playing, and the swap captured that as the copy to revert to. The
 * FLAC failed its length proof (216760ms against 214562ms), the revert relabelled
 * the YouTube Opus as the FLAC source, and the re-look and the retry found the same
 * FLAC and were both skipped `current-clock-unknown`.
 */
class KoRevertedUpgradeTest {

    private val id = "ko-revert-test"
    private val flac = SourceStream(
        url = "https://flac-host.example/track.flac",
        format = StreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16),
        sourceConfigId = "src-private-flac",
    )

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
    }

    @Before
    @After
    fun reset() {
        KoQualityUpgrade.onPlaybackStarted(id)
        QualityUpgrade.forget(id)
        StreamChoice.forget(id)
    }

    @Test
    fun `a reverted copy stays out of this play's later looks`() {
        QualityUpgrade.force(id, flac)
        StreamChoice.remember(id, flac, substituted = true)

        QualityUpgrade.forgetReverted(id)

        val look = KoQualityUpgrade.beginLook(id, null)
        assertTrue(look.excludedSources.contains("src-private-flac"))
        assertTrue(look.excludedHosts.contains("flac-host.example"))
        assertFalse("the in-flight answer must not bring it back", KoQualityUpgrade.allowsInFlight(id, flac))
        // What the revert already released stays released.
        assertNull(QualityUpgrade.forced[id])
        assertNull(StreamChoice.of(id))
    }

    @Test
    fun `the next play may try the reverted copy again`() {
        QualityUpgrade.force(id, flac)
        QualityUpgrade.forgetReverted(id)

        KoQualityUpgrade.onPlaybackStarted(id)

        assertTrue(KoQualityUpgrade.beginLook(id, null).excludedSources.isEmpty())
        assertTrue(KoQualityUpgrade.allowsInFlight(id, flac))
    }

    @Test
    fun `a revert with nothing parked excludes nothing`() {
        QualityUpgrade.forgetReverted(id)
        assertTrue(KoQualityUpgrade.beginLook(id, null).excludedSources.isEmpty())
        assertTrue(KoQualityUpgrade.beginLook(id, null).excludedHosts.isEmpty())
    }

    @Test
    fun `a marked cache fill does not record its upgrade as playing`() {
        assertTrue(KoCacheFill.recordsServedUpgrade(proving = false))
        assertFalse(KoCacheFill.recordsServedUpgrade(proving = true))
        KoCacheFill.run(marks = true) {
            assertFalse(KoCacheFill.recordsServedUpgrade(proving = false))
            // Another thread (the player's loader) is not the fill.
            val other = AtomicBoolean(false)
            thread { other.set(KoCacheFill.recordsServedUpgrade(proving = false)) }.join()
            assertTrue(other.get())
        }
        assertTrue("the mark ends with the fill", KoCacheFill.recordsServedUpgrade(proving = false))
        KoCacheFill.run(marks = false) {
            assertTrue("a next row's fill still records", KoCacheFill.recordsServedUpgrade(proving = false))
        }
    }

    @Test
    fun `the mark survives a fill that throws`() {
        runCatching { KoCacheFill.run(marks = true) { error("cut short") } }
        assertFalse(KoCacheFill.isFilling())
    }

    @Test
    fun `the resolver and the playing track's fill use the mark`() {
        val service = source("playback/PlaybackService.kt")
        val forcedBranch = service.substringAfter("QualityUpgrade.forcedStream(dataSpec.uri)?.let { upgraded ->")
            .substringBefore("return@Resolver")
        assertTrue(forcedBranch.contains("KoCacheFill.recordsServedUpgrade(proving)"))
        assertFalse(forcedBranch.contains("if (!proving) {"))
        val cache = source("ko/KoLosslessCache.kt")
        assertEquals(
            1,
            Regex(Regex.escape("KoCacheFill.run(marks = mediaId != null && mediaId == currentMediaId) { writer.cache() }"))
                .findAll(cache).count(),
        )
    }

    @Test
    fun `a saved lossless file on the same rung needs no next-play copy`() {
        val saved16 = StreamFormat(codec = "flac", kbps = 1074, sampleRateHz = 44_100, bitDepth = 16)
        val saved24 = StreamFormat(codec = "flac", kbps = 1657, sampleRateHz = 44_100, bitDepth = 24)
        val addon16 = StreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16)
        val addon24 = StreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 24)
        val opus = StreamFormat(codec = "opus", kbps = 151, sampleRateHz = 48_000)
        // Opalite 16:10, Espresso 17:35: 16-bit file playing, 16-bit FLAC fetched again.
        assertTrue(KoQualityUpgrade.nextPlayPrefetchRedundant(true, saved16, addon16))
        // 73ouhDibImA 16:29: 24-bit file playing, 24-bit FLAC fetched again.
        assertTrue(KoQualityUpgrade.nextPlayPrefetchRedundant(true, saved24, addon24))
        assertTrue(KoQualityUpgrade.nextPlayPrefetchRedundant(true, saved24, addon16))
        // A better rung, a lossy file or YouTube still prefetch as before.
        assertFalse(KoQualityUpgrade.nextPlayPrefetchRedundant(true, saved16, addon24))
        assertFalse(KoQualityUpgrade.nextPlayPrefetchRedundant(true, opus, addon16))
        assertFalse(KoQualityUpgrade.nextPlayPrefetchRedundant(false, saved16, addon16))
        assertFalse(KoQualityUpgrade.nextPlayPrefetchRedundant(true, null, addon16))
    }

    @Test
    fun `the next-play prefetch asks before fetching`() {
        val swap = source("ko/KoPlaybackLosslessSwap.kt")
        val body = swap.substringAfter("internal fun PlaybackService.koPrefetchLosslessForNextPlay(")
            .substringBefore("AudioCache.prefetchVerifiedRendition(")
        assertTrue(body.contains("KoQualityUpgrade.nextPlayPrefetchRedundant(playsFile,"))
    }
}
