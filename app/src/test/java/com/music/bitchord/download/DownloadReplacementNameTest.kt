package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A media-store replacement cannot take the final name until the old row is
 * gone: the store answers a duplicate `DISPLAY_NAME` with `… (1).ext`, and the
 * next pass then cannot find the file by the name its record expects.
 *
 * The replacement is written under a temporary name first, so the old file can
 * stay in place until the new one is committed.
 */
class DownloadReplacementNameTest {

    private val finalName = "Artist - Title.flac"

    @Test
    fun `a staged replacement does not reuse the name the old row owns`() {
        val staging = DownloadStore.stagingFileName(finalName, "video123")

        assertNotEquals(finalName, staging)
        assertTrue(staging.endsWith(".flac"))
        assertTrue(staging.contains("video123"))
    }

    @Test
    fun `two replacements never stage under the same name`() {
        val first = DownloadStore.stagingFileName(finalName, "videoA")
        val second = DownloadStore.stagingFileName(finalName, "videoB")

        assertNotEquals(first, second)
    }

    @Test
    fun `staging keeps a name without an extension usable`() {
        assertEquals("Title (videoA)", DownloadStore.stagingFileName("Title", "videoA"))
    }
}
