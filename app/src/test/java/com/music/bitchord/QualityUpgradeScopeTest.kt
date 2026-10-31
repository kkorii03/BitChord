package com.music.bitchord

import com.music.bitchord.playback.QualityUpgrade
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long an upgrade verdict is allowed to stand.
 *
 * `asked` records that a track has had its second look, so the every-five-
 * seconds offer from the cached-track path stops pestering a track already
 * dealt with. That is the right scope *within one playback* and the wrong
 * scope across two: the upgraded bytes live under their own rendition key and
 * the marked item URI goes with the queue entry that carried it, so the next
 * play of the same song reads the base cache entry — the YouTube copy — while
 * the verdict from the previous play says the question is settled.
 *
 * Measured on a device, same process, one session:
 *
 * ```
 *   23:38:28  substituted: 'Gehra Hua' … over YouTube at Dolby Atmos
 *   23:44:07  TIMING track selected: i1o1p_DD6TU (reason=3)   ← Opus, and no lookup at all
 * ```
 */
class QualityUpgradeScopeTest {

    private val mediaId = "i1o1p_DD6TU"

    @Test
    fun `a fresh play re-opens a verdict reached on the previous one`() {
        QualityUpgrade.markAnsweredForTest(mediaId)
        assertTrue(QualityUpgrade.hasAnsweredFor(mediaId))

        // The item a listener gets by queueing the song again: no marker on it,
        // because the marked URI belonged to the entry that was swapped.
        QualityUpgrade.onPlaybackStarted(mediaId, rendition = null)

        assertFalse(
            "a second play reads the base cache entry, so last play's yes does not describe it",
            QualityUpgrade.hasAnsweredFor(mediaId),
        )
    }

    /**
     * The upgraded item itself must not re-open the question — that would have
     * the swap that just happened searched for all over again. It is refused on
     * its own terms by the marker rule in `couldStillUpgrade`; this only checks
     * that the verdict is left standing for it.
     */
    @Test
    fun `the upgraded item does not re-open its own verdict`() {
        QualityUpgrade.markAnsweredForTest(mediaId)

        QualityUpgrade.onPlaybackStarted(mediaId, rendition = "hifi")

        assertTrue(QualityUpgrade.hasAnsweredFor(mediaId))
    }

    /** A hand-reverted track is playing `q=original`, which is equally not a fresh copy. */
    @Test
    fun `a reverted item does not re-open its own verdict either`() {
        QualityUpgrade.markAnsweredForTest(mediaId)

        QualityUpgrade.onPlaybackStarted(mediaId, rendition = "original")

        assertTrue(QualityUpgrade.hasAnsweredFor(mediaId))
    }


    /**
     * A hand-made request overrules a previous "nothing better" verdict, even
     * when that verdict was reached while a source was down. See
     * [QualityUpgrade.askByHand].
     */
    @Test
    fun `a manual request reopens an answered track`() {
        val manualId = "manual-reopen"
        QualityUpgrade.markAnsweredForTest(manualId)
        assertTrue(QualityUpgrade.hasAnsweredFor(manualId))

        QualityUpgrade.askByHand(manualId)

        assertFalse(
            "the listener contradicting a cached none must start the search again",
            QualityUpgrade.hasAnsweredFor(manualId),
        )
        QualityUpgrade.forget(manualId)
    }

    /** One track's replay says nothing about another's. */
    @Test
    fun `re-opening one track leaves the rest alone`() {
        QualityUpgrade.markAnsweredForTest(mediaId)
        QualityUpgrade.markAnsweredForTest("other")

        QualityUpgrade.onPlaybackStarted(mediaId, rendition = null)

        assertFalse(QualityUpgrade.hasAnsweredFor(mediaId))
        assertTrue(QualityUpgrade.hasAnsweredFor("other"))
    }

    /**
     * A manual request that joins an automatic pass must not be answered by
     * that pass. Its search started before the press, so it used the automatic
     * rules (a memoised empty answer, resting sources skipped); only the
     * waiter's manual search may report.
     */
    @Test
    fun `an automatic pass defers a joined manual request to the manual search`() {
        assertTrue(
            QualityUpgrade.defersAutomaticAnswerToManual(
                startedManual = false,
                manualPending = true,
                manualJoined = true,
            ),
        )
        assertFalse(
            "a pass that started manual may answer it",
            QualityUpgrade.defersAutomaticAnswerToManual(
                startedManual = true,
                manualPending = true,
                manualJoined = true,
            ),
        )
        assertFalse(
            "nothing is deferred when no manual request is waiting",
            QualityUpgrade.defersAutomaticAnswerToManual(
                startedManual = false,
                manualPending = false,
                manualJoined = true,
            ),
        )
        assertFalse(
            "nothing is deferred when the request did not join",
            QualityUpgrade.defersAutomaticAnswerToManual(
                startedManual = false,
                manualPending = true,
                manualJoined = false,
            ),
        )
    }

}
