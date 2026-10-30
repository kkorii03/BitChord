package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A MediaStore row that was removed while its bytes were being written makes
 * update() return zero. Treating that as success recorded a download whose
 * file does not exist; the transfer must fail instead.
 */
class DownloadStorePublishAppliedTest {

    @Test
    fun `a row that was published is a success`() {
        assertTrue(DownloadStore.publishApplied(1))
    }

    @Test
    fun `a row that no longer exists is not a success`() {
        assertFalse(DownloadStore.publishApplied(0))
    }
}
