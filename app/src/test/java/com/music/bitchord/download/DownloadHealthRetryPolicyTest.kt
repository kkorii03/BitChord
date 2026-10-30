package com.music.bitchord.download

import com.music.bitchord.data.sources.DownloadHealthPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pending-upgrade marker and the condemned-file repair both use the same
 * request cooldown. A search that was only started must count as a request, and
 * a file with no better source must stop asking after a few tries instead of
 * searching on every playback.
 */
class DownloadHealthRetryPolicyTest {

    private val cooldown = DownloadHealthPolicy.REPAIR_COOLDOWN_MS

    @Test
    fun `a pending upgrade asks once then waits for the cooldown`() {
        assertTrue(downloadHealthMayRetryPending(lastRequestMs = null, attempts = 0, nowMs = 1_000L))
        assertFalse(
            downloadHealthMayRetryPending(
                lastRequestMs = 1_000L,
                attempts = 1,
                nowMs = 1_000L + cooldown - 1,
            ),
        )
        assertTrue(
            downloadHealthMayRetryPending(
                lastRequestMs = 1_000L,
                attempts = 1,
                nowMs = 1_000L + cooldown,
            ),
        )
    }

    @Test
    fun `a pending upgrade is retired after the attempt cap`() {
        assertTrue(
            downloadHealthMayRetryPending(
                lastRequestMs = null,
                attempts = PENDING_UPGRADE_MAX_ATTEMPTS - 1,
                nowMs = 1_000L,
            ),
        )
        assertFalse(
            downloadHealthMayRetryPending(
                lastRequestMs = null,
                attempts = PENDING_UPGRADE_MAX_ATTEMPTS,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun `a started search counts as a request but a refusal does not`() {
        assertTrue(downloadHealthCountsAsRequested(HqLibraryUpgrade.UpgradeRequest.QUEUED))
        assertTrue(downloadHealthCountsAsRequested(HqLibraryUpgrade.UpgradeRequest.SEARCHING))
        assertFalse(downloadHealthCountsAsRequested(HqLibraryUpgrade.UpgradeRequest.REFUSED))
    }
}
