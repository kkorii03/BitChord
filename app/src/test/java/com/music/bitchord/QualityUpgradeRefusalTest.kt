package com.music.bitchord

import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.QualityUpgrade.Refusal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * A second look that ran while a source failed, timed out or was resting
     * says nothing about the catalogue. Recording it as a verdict would keep
     * the track at its lossy copy for the rest of the session; 1.8 recorded
     * only a walk whose every source answered.
     */
    @Test
    fun `an inconclusive second look is not recorded as a verdict`() = runBlocking {
        val id = "inconclusive-second-look"
        val target = TrackMatcher.Target(title = "Some Song", artist = "Some Artist", durationSec = 200)
        QualityUpgrade.markPendingForTest(id, target)
        val report = SourceResolver.SearchReport()
        report.record(SourceResolver.SearchOutcome(verdict = "timeout", conclusive = false))

        assertNull(QualityUpgrade.lookAgain(id, playingDurationSec = 200, report = report))

        assertFalse(
            "a source timed out, so the track must stay open for a later retry",
            QualityUpgrade.hasAnsweredFor(id),
        )
        QualityUpgrade.forget(id)
    }
}
