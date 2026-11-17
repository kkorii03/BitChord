package com.music.bitchord.ko

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.playback.smart.HqVerificationStore
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review NR-2: automix opened the next song's lossless copy at a cue, beat grid
 * and mix-in point measured on the YouTube copy, with no regard for the copy's
 * verified offset. A copy off YouTube's clock now stays YouTube in the standby.
 */
class KoLosslessAheadTest {

    private fun verified(offsetMs: Long, rate: Double = 1.0) = HqVerificationStore.Verified(
        offsetMs = offsetMs,
        rate = rate,
        matchPercent = 90,
        tier = HqTier.LOSSLESS_16,
    )

    @Test
    fun `a copy on YouTube's clock is opened ahead`() {
        assertTrue(KoLosslessAhead.cueSafe(verified(0L)))
        assertTrue(KoLosslessAhead.cueSafe(verified(-30L, 1.0005)))
        assertTrue(KoLosslessAhead.cueSafe(verified(30L, 0.9995)))
    }

    @Test
    fun `a copy with an offset or a different rate is not`() {
        assertFalse(KoLosslessAhead.cueSafe(verified(1_500L)))
        assertFalse(KoLosslessAhead.cueSafe(verified(-31L)))
        assertFalse(KoLosslessAhead.cueSafe(verified(0L, 1.002)))
        assertFalse(KoLosslessAhead.cueSafe(verified(0L, Double.NaN)))
    }

    @Test
    fun `a copy with no verdict has no known clock`() {
        assertFalse(KoLosslessAhead.cueSafe(null))
    }

    @Test
    fun `the lossless-ahead item asks the gate before forcing the copy`() {
        val root = File(".").canonicalFile
        val file = listOf("app/", "")
            .map { File(root, "${it}src/main/java/com/music/bitchord/ko/KoPlaybackLosslessSwap.kt") }
            .first { it.exists() }
        val body = file.readText()
            .substringAfter("internal fun MediaItem.koLosslessAheadItem(context: Context): MediaItem? {")
            .substringBefore("internal fun PlaybackService.koLogHqAhead(")
        val gate = body.indexOf("KoLosslessAhead.cueSafe(")
        assertTrue("gate missing", gate >= 0)
        assertTrue(body.contains("HqVerificationStore.get(context, id, sourceId, choice.trackId)"))
        assertTrue("gate must run before the copy is forced", gate < body.indexOf("QualityUpgrade.force(id, choice)"))
    }
}
