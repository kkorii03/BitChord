package com.music.bitchord.data.lyrics

import com.music.bitchord.data.lyrics.DownloadedSyncCheck.Recheck
import com.music.bitchord.data.lyrics.DownloadedSyncCheck.Winner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rest of the downloaded-sync decision table, including the ties and missing answers. */
class DownloadedSyncCheckDecideTest {

    @Test
    fun theVersionAndMatchFloorsAreUnchanged() {
        assertEquals(1, DownloadedSyncCheck.SYNC_CHECK_VERSION)
        assertEquals(70, DownloadedSyncCheck.MIN_MATCH_PERCENT)
    }

    @Test
    fun everyRecheckReasonHasItsStableToken() {
        assertEquals("none", Recheck.NONE.token)
        assertEquals("unverified", Recheck.UNVERIFIED.token)
        assertEquals("unknown-provider", Recheck.UNKNOWN_PROVIDER.token)
        assertEquals("low-match", Recheck.LOW_MATCH.token)
        assertEquals("done", Recheck.DONE.token)
        assertEquals(5, Recheck.entries.size)
    }

    @Test
    fun aNegativeStoredVersionIsUnverified() {
        assertTrue(DownloadedSyncCheck.isUnverified(-1))
        assertFalse(DownloadedSyncCheck.isUnverified(Int.MAX_VALUE))
    }

    @Test
    fun theCurrentRulesBeatEveryOtherReason() {
        assertEquals(
            Recheck.NONE,
            DownloadedSyncCheck.recheckReason(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                providerUnknown = true,
                matchPercent = 0,
            ),
        )
        assertEquals(
            Recheck.UNKNOWN_PROVIDER,
            DownloadedSyncCheck.recheckReason(
                DownloadedSyncCheck.SYNC_CHECK_VERSION - 1,
                providerUnknown = true,
                matchPercent = 0,
            ),
        )
    }

    @Test
    fun theAgreementBoundaryIsSeventyInclusive() {
        assertEquals(
            Recheck.LOW_MATCH,
            DownloadedSyncCheck.recheckReason(null, providerUnknown = false, matchPercent = 69),
        )
        assertEquals(
            Recheck.UNVERIFIED,
            DownloadedSyncCheck.recheckReason(null, providerUnknown = false, matchPercent = 70),
        )
        assertEquals(
            Recheck.UNVERIFIED,
            DownloadedSyncCheck.recheckReason(null, providerUnknown = false, matchPercent = 100),
        )
    }

    @Test
    fun aNewAnswerMustStrictlyBeatAFailedStoredAnswer() {
        assertFalse(
            DownloadedSyncCheck.shouldReplace(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                storedMatchPercent = 0,
                newMatchPercent = 0,
            ),
        )
        assertTrue(
            DownloadedSyncCheck.shouldReplace(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                storedMatchPercent = 0,
                newMatchPercent = 1,
            ),
        )
        assertTrue(
            DownloadedSyncCheck.shouldReplace(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                storedMatchPercent = null,
                newMatchPercent = 0,
            ),
        )
    }

    @Test
    fun noNewAnswerNeverReplacesEvenAnUnverifiedOne() {
        assertFalse(DownloadedSyncCheck.shouldReplace(null, storedMatchPercent = null, newMatchPercent = null))
        assertFalse(DownloadedSyncCheck.shouldReplace(0, storedMatchPercent = 99, newMatchPercent = null))
    }

    @Test
    fun aFreshSheetMustBeatTheFilesOwnLines() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = 80,
            freshMatchPercent = 80,
            freshKey = 20,
        )
        assertTrue(decision.replace)
        assertEquals(Winner.STORED, decision.winner)
        assertEquals(10, decision.key)

        val better = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = 80,
            freshMatchPercent = 81,
            freshKey = 20,
        )
        assertEquals(Winner.FRESH, better.winner)
        assertEquals(20, better.key)
    }

    @Test
    fun aZeroMatchFreshSheetDoesNotWinOverNoAnswer() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = null,
            freshMatchPercent = 0,
            freshKey = 20,
        )
        assertFalse(decision.replace)
        assertNull(decision.winner)
        assertNull(decision.key)
        assertTrue(decision.recordVersion)
    }

    @Test
    fun aFreshSheetThatOnlyJustFitsIsStillAFreshWinner() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = null,
            freshMatchPercent = 1,
            freshKey = 20,
        )
        assertTrue(decision.replace)
        assertEquals(Winner.FRESH, decision.winner)
        assertEquals(20, decision.key)
    }

    @Test
    fun anAlreadyCheckedFileOnlyReplacesWhenTheNewAnswerIsBetter() {
        val better = DownloadedSyncCheck.decide(
            storedVersion = DownloadedSyncCheck.SYNC_CHECK_VERSION,
            existingMatchPercent = 80,
            existingKey = 10,
            ownMatchPercent = 70,
            freshMatchPercent = 90,
            freshKey = 20,
        )
        assertTrue(better.replace)
        assertEquals(Winner.FRESH, better.winner)
        assertEquals(20, better.key)

        val worse = DownloadedSyncCheck.decide(
            storedVersion = DownloadedSyncCheck.SYNC_CHECK_VERSION,
            existingMatchPercent = 95,
            existingKey = 10,
            ownMatchPercent = 70,
            freshMatchPercent = 90,
            freshKey = 20,
        )
        assertFalse(worse.replace)
        assertNull(worse.winner)
        assertNull(worse.key)
    }

    @Test
    fun theFilesOwnLinesCanStillWinOnACheckedFile() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = DownloadedSyncCheck.SYNC_CHECK_VERSION,
            existingMatchPercent = 80,
            existingKey = 10,
            ownMatchPercent = 90,
            freshMatchPercent = 70,
            freshKey = 20,
        )
        assertTrue(decision.replace)
        assertEquals(Winner.STORED, decision.winner)
        assertEquals(10, decision.key)
    }

    @Test
    fun aCheckedFileAtTheSameAgreementIsNotRewritten() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = DownloadedSyncCheck.SYNC_CHECK_VERSION,
            existingMatchPercent = 90,
            existingKey = 10,
            ownMatchPercent = 90,
            freshMatchPercent = 70,
            freshKey = 20,
        )
        assertFalse(decision.replace)
        assertNull(decision.winner)
        assertNull(decision.key)
        assertTrue(decision.recordVersion)
    }

    @Test
    fun aCheckedFileWithNoStoredAgreementAcceptsTheFilesOwnLines() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = DownloadedSyncCheck.SYNC_CHECK_VERSION,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = 50,
            freshMatchPercent = null,
            freshKey = 20,
        )
        assertTrue(decision.replace)
        assertEquals(Winner.STORED, decision.winner)
        assertEquals(10, decision.key)
    }

    @Test
    fun aCheckedFileWithNothingToCompareDoesNotReplace() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = DownloadedSyncCheck.SYNC_CHECK_VERSION,
            existingMatchPercent = 60,
            existingKey = 10,
            ownMatchPercent = null,
            freshMatchPercent = null,
            freshKey = 20,
        )
        assertFalse(decision.replace)
        assertNull(decision.winner)
        assertNull(decision.key)
        assertTrue(decision.recordVersion)
    }

    @Test
    fun aStructuralMissStillRecordsTheVersionSoItIsNotDecodedAgain() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = null,
            freshMatchPercent = null,
            freshKey = 20,
            ownCheckAnswered = true,
        )
        assertFalse(decision.replace)
        assertTrue(decision.recordVersion)
    }

    @Test
    fun anUnansweredCheckDoesNotRecordTheVersion() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = null,
            freshMatchPercent = null,
            freshKey = 20,
            ownCheckAnswered = false,
        )
        assertFalse(decision.replace)
        assertFalse(
            decision.recordVersion,
            "a no-answer must leave the version unset so the next play retries",
        )
    }
}
