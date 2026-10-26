package com.music.bitchord.data.lyrics

import com.music.bitchord.data.lyrics.DownloadedSyncCheck.Recheck
import com.music.bitchord.data.lyrics.DownloadedSyncCheck.Winner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSyncCheckTest {

    @Test
    fun `no version and older versions are unverified`() {
        assertTrue(DownloadedSyncCheck.isUnverified(null))
        assertTrue(DownloadedSyncCheck.isUnverified(0))
        assertTrue(DownloadedSyncCheck.isUnverified(DownloadedSyncCheck.SYNC_CHECK_VERSION - 1))
        assertFalse(DownloadedSyncCheck.isUnverified(DownloadedSyncCheck.SYNC_CHECK_VERSION))
        assertFalse(DownloadedSyncCheck.isUnverified(DownloadedSyncCheck.SYNC_CHECK_VERSION + 1))
    }

    @Test
    fun `the reason names the most specific problem first`() {
        assertEquals(
            Recheck.UNKNOWN_PROVIDER,
            DownloadedSyncCheck.recheckReason(null, providerUnknown = true, matchPercent = 20),
        )
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
            DownloadedSyncCheck.recheckReason(null, providerUnknown = false, matchPercent = null),
        )
    }

    @Test
    fun `a version from these rules is not checked again`() {
        assertEquals(
            Recheck.NONE,
            DownloadedSyncCheck.recheckReason(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                providerUnknown = true,
                matchPercent = 0,
            ),
        )
    }

    @Test
    fun `a fresh provider lookup is wanted for unknown providers and low agreement`() {
        assertTrue(DownloadedSyncCheck.shouldFetchNewLyrics(providerUnknown = true, matchPercent = 99))
        assertTrue(DownloadedSyncCheck.shouldFetchNewLyrics(providerUnknown = false, matchPercent = 69))
        assertFalse(DownloadedSyncCheck.shouldFetchNewLyrics(providerUnknown = false, matchPercent = 70))
        assertFalse(DownloadedSyncCheck.shouldFetchNewLyrics(providerUnknown = false, matchPercent = null))
    }

    @Test
    fun `a new answer replaces an unverified or failed one`() {
        assertTrue(DownloadedSyncCheck.shouldReplace(null, storedMatchPercent = 95, newMatchPercent = 40))
        assertTrue(
            DownloadedSyncCheck.shouldReplace(
                DownloadedSyncCheck.SYNC_CHECK_VERSION - 1,
                storedMatchPercent = 95,
                newMatchPercent = 40,
            ),
        )
        assertTrue(
            DownloadedSyncCheck.shouldReplace(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                storedMatchPercent = null,
                newMatchPercent = 40,
            ),
        )
    }

    @Test
    fun `a new answer must agree more than a checked one`() {
        assertTrue(
            DownloadedSyncCheck.shouldReplace(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                storedMatchPercent = 70,
                newMatchPercent = 71,
            ),
        )
        assertFalse(
            DownloadedSyncCheck.shouldReplace(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                storedMatchPercent = 70,
                newMatchPercent = 70,
            ),
        )
        assertFalse(
            DownloadedSyncCheck.shouldReplace(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                storedMatchPercent = 70,
                newMatchPercent = 69,
            ),
        )
    }

    @Test
    fun `no new answer never replaces the stored one`() {
        assertFalse(DownloadedSyncCheck.shouldReplace(null, storedMatchPercent = 10, newMatchPercent = null))
        assertFalse(
            DownloadedSyncCheck.shouldReplace(
                DownloadedSyncCheck.SYNC_CHECK_VERSION,
                storedMatchPercent = 90,
                newMatchPercent = null,
            ),
        )
    }

    @Test
    fun `the file's own lines keep their key when they fit best`() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = 90,
            freshMatchPercent = 80,
            freshKey = 20,
        )
        assertTrue(decision.replace)
        assertEquals(Winner.STORED, decision.winner)
        assertEquals(10, decision.key)
        assertTrue(decision.recordVersion)
    }

    @Test
    fun `a better fresh sheet is stored under the fresh key`() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = 80,
            freshMatchPercent = 90,
            freshKey = 20,
        )
        assertTrue(decision.replace)
        assertEquals(Winner.FRESH, decision.winner)
        assertEquals(20, decision.key)
        assertTrue(decision.recordVersion)
    }

    @Test
    fun `a stored answer that still fits better is not replaced`() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = DownloadedSyncCheck.SYNC_CHECK_VERSION,
            existingMatchPercent = 95,
            existingKey = 10,
            ownMatchPercent = 80,
            freshMatchPercent = 90,
            freshKey = 20,
        )
        assertFalse(decision.replace)
        assertEquals(null, decision.winner)
        assertEquals(null, decision.key)
        assertTrue(decision.recordVersion)
    }

    @Test
    fun `a tie keeps the file's own lines`() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = 90,
            freshMatchPercent = 90,
            freshKey = 20,
        )
        assertEquals(Winner.STORED, decision.winner)
        assertEquals(10, decision.key)
    }

    @Test
    fun `no fresh answer means the file's own lines are the candidate`() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = null,
            existingMatchPercent = null,
            existingKey = 10,
            ownMatchPercent = 90,
            freshMatchPercent = null,
            freshKey = 20,
        )
        assertTrue(decision.replace)
        assertEquals(Winner.STORED, decision.winner)
        assertEquals(10, decision.key)
    }

    @Test
    fun `nothing to replace records no key`() {
        val decision = DownloadedSyncCheck.decide(
            storedVersion = DownloadedSyncCheck.SYNC_CHECK_VERSION,
            existingMatchPercent = 90,
            existingKey = 10,
            ownMatchPercent = 50,
            freshMatchPercent = null,
            freshKey = 20,
        )
        assertFalse(decision.replace)
        assertEquals(null, decision.key)
        assertTrue(decision.recordVersion)
    }
}
