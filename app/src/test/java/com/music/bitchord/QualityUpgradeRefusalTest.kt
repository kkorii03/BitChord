package com.music.bitchord

import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.ko.KoQualityUpgrade
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.QualityUpgrade.Refusal
import com.music.bitchord.playback.StreamChoice
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

    /**
     * The retry window an inconclusive answer earns has to outlive the caller
     * giving up on what it found.
     *
     * The automatic pass calls [QualityUpgrade.forget] once it has decided what
     * to do with the copy the search returned, which is one statement after
     * `recordAnswer` armed the window — and forget used to delete exactly that
     * window, so the `will retry` line it had just logged was never true.
     * 11-04, the only completed automatic search of the day: a lossless
     * request found nothing but `MP4 · 320 kbps` while the lossless sources
     * were timing out in the same race, and nothing asked them again.
     */
    @Test
    fun `an inconclusive answer keeps its retry window through forget`() = runBlocking {
        val id = "inconclusive-keeps-retry"
        val target = TrackMatcher.Target(title = "Some Song", artist = "Some Artist", durationSec = 200)
        QualityUpgrade.markPendingForTest(id, target)
        val report = SourceResolver.SearchReport()
        report.record(SourceResolver.SearchOutcome(verdict = "timeout", conclusive = false))

        assertNull(QualityUpgrade.lookAgain(id, playingDurationSec = 200, report = report))
        assertTrue("the inconclusive answer arms a bounded retry", KoQualityUpgrade.suppressesRetry(id))

        QualityUpgrade.forget(id)

        assertTrue(
            "forgetting the found answer must not delete the retry the log promised",
            KoQualityUpgrade.suppressesRetry(id),
        )
        QualityUpgrade.forgetLastSession()
    }

    /**
     * Which quality row the player menu leads with, from the evidence each kind
     * of play actually leaves behind.
     *
     * Phone 11-05: NEON (rlYOsUfpi3M) played 30 seconds of `audio/mpeg`
     * 128kbps out of the cache — a substitute, and the wrong recording at that
     * — and the menu offered "Upgrade quality", which answered "no better copy
     * found", instead of the "Revert to original" row. The verdict was read off
     * `NerdStats`' display name, and `currentSourceName` ends with "an
     * eleven-character id is YouTube": a cached stream is never resolved, so it
     * has no config id and no [StreamChoice], and the guessed label said YouTube
     * over whatever bytes were on disk.
     */
    @Test
    fun `a substitute stream out of the cache is offered the revert row`() {
        val id = "rlYOsUfpi3M"
        StreamChoice.forget(id)

        assertFalse(
            "an addon's or a module's file is not YouTube's own copy",
            QualityUpgrade.isKnownToBePlayingYouTubesOwn(
                id,
                rendition = null,
                cachedOrigin = AudioCache.Origin.OTHER,
                decodedKind = SourceKind.ADDON,
            ),
        )
    }

    /** The same track in the same process, read off the cache tag alone. */
    @Test
    fun `an addon-filled cache entry is offered the revert row without a decoded kind`() {
        val id = "addon-cache-entry"
        StreamChoice.forget(id)

        assertFalse(
            QualityUpgrade.isKnownToBePlayingYouTubesOwn(
                id,
                rendition = null,
                cachedOrigin = AudioCache.Origin.OTHER,
            ),
        )
    }

    /** A genuine YouTube stream keeps the upgrade row, decoded or from the cache. */
    @Test
    fun `YouTube's own stream is offered the upgrade row`() {
        val id = "youtube-own-stream"
        StreamChoice.forget(id)

        assertTrue(
            QualityUpgrade.isKnownToBePlayingYouTubesOwn(
                id,
                rendition = null,
                cachedOrigin = AudioCache.Origin.YOUTUBE,
                decodedKind = SourceKind.YOUTUBE,
            ),
        )
    }

    /**
     * An entry nothing tagged is genuinely unknown, and the revert stays on
     * offer there — it is the escape hatch for a substitution nothing
     * announced. Not a lean either way: it is what the menu already did.
     */
    @Test
    fun `an untagged cache entry keeps the revert row`() {
        val id = "untagged-cache-entry"
        StreamChoice.forget(id)

        assertFalse(
            QualityUpgrade.isKnownToBePlayingYouTubesOwn(id, rendition = null, cachedOrigin = null),
        )
    }

    /** A rendition of its own is never YouTube's copy, whatever filled it. */
    @Test
    fun `an upgraded rendition is offered the revert row`() {
        val id = "upgraded-rendition"
        StreamChoice.forget(id)

        assertFalse(
            QualityUpgrade.isKnownToBePlayingYouTubesOwn(
                id,
                rendition = "hifi",
                cachedOrigin = AudioCache.Origin.YOUTUBE,
                decodedKind = SourceKind.YOUTUBE,
            ),
        )
    }
}
