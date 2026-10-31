package com.music.bitchord

import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.QualityUpgrade.Refusal
import org.junit.Assert.assertEquals
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
    fun `a row without a YouTube stream is the only generic refusal`() {
        assertEquals(
            Refusal.NO_STREAM,
            QualityUpgrade.refusalOf(hasStream = false, musicVideo = false, canSubstitute = true),
        )
    }
}
