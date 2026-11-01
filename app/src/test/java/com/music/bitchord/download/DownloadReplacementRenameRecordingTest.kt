package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A committed replacement must record the name the store actually kept.
 *
 * MediaStore can append `(1)` when another row still owns the requested name
 * and still report the update as successful. Recording only [finalName] then
 * left the next lookup asking for a name the file does not carry, so the track
 * was downloaded again as a duplicate. The recording path therefore reads the
 * display name back after every attempt, not only after a failed one.
 */
class DownloadReplacementRenameRecordingTest {

    private val finalName = "Artist - Title.flac"
    private val stagedName = "Artist - Title (video123).flac"

    @Test
    fun `a successful update that the store disambiguates records the kept name`() {
        var reads = 0

        val recorded = DownloadStore.reconcilePublishedName(
            finalName = finalName,
            stagedName = stagedName,
            renamed = true,
        ) {
            reads++
            "Artist - Title (1).flac"
        }

        assertEquals("Artist - Title (1).flac", recorded)
        assertEquals("the committed name must be read even when update reports success", 1, reads)
    }

    @Test
    fun `a disambiguated name wins over the requested one even when update succeeded`() {
        val recorded = DownloadStore.settledFileName(
            finalName = finalName,
            stagedName = stagedName,
            renamed = true,
            actualName = "Artist - Title (1).flac",
        )

        assertEquals("Artist - Title (1).flac", recorded)
    }

    @Test
    fun `a successful update that kept the requested name records it`() {
        val recorded = DownloadStore.reconcilePublishedName(
            finalName = finalName,
            stagedName = stagedName,
            renamed = true,
        ) { finalName }

        assertEquals(finalName, recorded)
    }

    @Test
    fun `an unreadable name after a failed rename falls back to the staging name`() {
        val recorded = DownloadStore.reconcilePublishedName(
            finalName = finalName,
            stagedName = stagedName,
            renamed = false,
        ) { null }

        assertEquals(stagedName, recorded)
    }
}
