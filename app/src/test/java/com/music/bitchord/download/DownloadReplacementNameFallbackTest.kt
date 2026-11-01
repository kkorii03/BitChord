package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A replacement whose rename is refused must still be findable afterwards.
 *
 * When the old row was not removed (an alias, or a failed delete), MediaStore
 * keeps the staging name or appends `(1)`. Ignoring that result recorded only
 * the final name, which no longer matched the committed file — so the next pass
 * downloaded the same bytes again under another copy.
 */
class DownloadReplacementNameFallbackTest {

    private val finalName = "Artist - Title.flac"
    private val stagedName = "Artist - Title (video123).flac"

    @Test
    fun `a refused rename records the name the store actually kept`() {
        val recorded = DownloadStore.settledFileName(
            finalName = finalName,
            stagedName = stagedName,
            renamed = false,
            actualName = "Artist - Title (1).flac",
        )

        assertEquals("Artist - Title (1).flac", recorded)
    }

    @Test
    fun `a successful rename records the final name`() {
        val recorded = DownloadStore.settledFileName(
            finalName = finalName,
            stagedName = stagedName,
            renamed = true,
            actualName = finalName,
        )

        assertEquals(finalName, recorded)
    }

    @Test
    fun `an unreadable actual name falls back to the staging name`() {
        val recorded = DownloadStore.settledFileName(
            finalName = finalName,
            stagedName = stagedName,
            renamed = false,
            actualName = null,
        )

        assertEquals(stagedName, recorded)
    }

    @Test
    fun `the recorded fallback name is part of the next lookup`() {
        val names = DownloadStore.lookupNames(finalName, "Artist - Title (1).flac")

        assertEquals(listOf("Artist - Title (1).flac", finalName), names)
    }

    @Test
    fun `a record for another container does not answer this lookup`() {
        val names = DownloadStore.lookupNames(finalName, "Artist - Title (video123).m4a")

        assertEquals(listOf(finalName), names)
    }

    @Test
    fun `an ordinary record still looks up only its final name`() {
        assertEquals(listOf(finalName), DownloadStore.lookupNames(finalName, null))
        assertEquals(listOf(finalName), DownloadStore.lookupNames(finalName, finalName))
    }
}
