package com.music.bitchord

import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.QualityUpgrade.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reason a manual upgrade is turned down.
 *
 * 10-31 on the phone: '(It Goes Like) Nanana' played as its music-video row
 * (m=1) and every tap answered "Quality can't be changed right now", with no
 * log line after "upgrade asked for by hand". The listener could not tell a
 * music video from a switched-off source from a temporary failure.
 */
class QualityUpgradeRefusalTest {

    @Test
    fun `a music video row says so`() {
        assertEquals(
            Refusal.MUSIC_VIDEO,
            QualityUpgrade.refusalOf(hasStream = true, musicVideo = true, canSubstitute = true),
        )
    }

    @Test
    fun `no higher source says the sources are off`() {
        assertEquals(
            Refusal.NO_SOURCE,
            QualityUpgrade.refusalOf(hasStream = true, musicVideo = false, canSubstitute = false),
        )
    }

    @Test
    fun `a manual tap re-opens the look after the automatic one answered`() {
        val id = "qsqvJpOgrWw"
        val target = TrackMatcher.Target(title = "체포하겠어", artist = "ILLIT", durationSec = 170)
        QualityUpgrade.forget(id)
        // Not asked for by hand: the automatic answer stands.
        assertFalse(QualityUpgrade.adoptByHand(id, target, StreamFormat(kbps = 160)))
        assertFalse(QualityUpgrade.isPending(id))

        QualityUpgrade.askByHand(id)
        assertTrue(QualityUpgrade.adoptByHand(id, target, StreamFormat(kbps = 160)))
        assertTrue("the manual pass has something to search with", QualityUpgrade.isPending(id))
        QualityUpgrade.forget(id)
    }

    @Test
    fun `a row without a YouTube stream is the only generic refusal`() {
        assertEquals(
            Refusal.NO_STREAM,
            QualityUpgrade.refusalOf(hasStream = false, musicVideo = false, canSubstitute = true),
        )
    }
}
