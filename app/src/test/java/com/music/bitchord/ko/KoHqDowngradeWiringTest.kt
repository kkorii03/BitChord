package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KO] HQ-DOWN, 11-18 16:31 Bones: the automatic look logged
 * `hq-tier: Hi-Res 24-bit -> Lossless 16-bit` while a saved 24/48 FLAC played,
 * then prefetched the addon's 16/44.1 copy for the next play. Both branches of
 * the look and the next-play pin ask the shared rule before any proof; they sit
 * inside the player service, so the wiring is pinned on the source.
 */
class KoHqDowngradeWiringTest {

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
    }

    @Test
    fun `both branches of the look refuse a downgrade before proving it`() {
        val service = source("playback/PlaybackService.kt")
        val shelved = "KoQualityUpgrade.refusesDowngrade(mediaId, target.title, currentFormatForNotice(mediaId), shelved.format)"
        val fresh = "KoQualityUpgrade.refusesDowngrade(mediaId, target.title, currentFormatForNotice(mediaId), better.format)"
        assertEquals(1, Regex(Regex.escape(shelved)).findAll(service).count())
        assertEquals(1, Regex(Regex.escape(fresh)).findAll(service).count())
        assertTrue(service.indexOf(shelved) < service.indexOf("HqAudioVerify.verify(this@PlaybackService, mediaId, target, shelved)"))
        assertTrue(service.indexOf(fresh) < service.indexOf("koRaceProofs[better] ?: HqAudioVerify.verify("))
    }

    @Test
    fun `the refusal ends this play's look only`() {
        val upgrade = source("ko/KoQualityUpgrade.kt")
            .substringAfter("fun refusesDowngrade(")
            .substringBefore("return true")
        assertTrue(upgrade.contains("HqQuality.isDowngradeFrom(playing, candidate)"))
        assertTrue(upgrade.contains("QualityUpgrade.asked += mediaId"))
        assertTrue("not the session-wide refusal", !upgrade.contains("refuseUpgrades") && !upgrade.contains("QualityUpgrade.refused"))
    }

    @Test
    fun `the next-play pin is not lowered below the copy it holds`() {
        val queue = source("ko/KoPrefetchQueue.kt")
        assertTrue(queue.contains("val down = HqQuality.isDowngradeFrom(held, candidate.format)"))
        assertTrue(queue.contains("!down && koMayPinAhead(context, next, candidate)"))
    }
}
