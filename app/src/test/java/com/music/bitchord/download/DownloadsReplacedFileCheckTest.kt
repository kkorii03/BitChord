package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.HqTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows a download invalidates. A re-download can overwrite the same path
 * (private/legacy files, or an HQ upgrade of the same extension), and an alias
 * row can be repointed at the new file; both cases used to keep the old check
 * version and its re-check override.
 */
class DownloadsReplacedFileCheckTest {

    private fun song(id: String, video: Boolean = false) = Song(
        videoId = id,
        title = id,
        artist = "Artist",
        thumbnailUrl = null,
        isVideo = video,
    )

    private val hq = HqDownloadSource(
        sourceConfigId = "source",
        trackId = "track",
        tier = HqTier.NORMAL,
    )

    @Test
    fun `a same-URI rewrite invalidates the song rows`() {
        val ids = Downloads.replacedCheckIds(
            asked = song("song"),
            fetched = song("song"),
            previousSuspects = emptyList(),
            aliasIds = emptyList(),
            fileReplaced = false,
            rewritten = true,
            hadPreviousFile = true,
            hq = null,
        )

        assertEquals(setOf("song"), ids)
    }

    @Test
    fun `adopting a file that was already there invalidates nothing`() {
        val ids = Downloads.replacedCheckIds(
            asked = song("song"),
            fetched = song("song"),
            previousSuspects = emptyList(),
            aliasIds = emptyList(),
            fileReplaced = false,
            rewritten = false,
            hadPreviousFile = false,
            hq = null,
        )

        assertTrue(ids.isEmpty())
    }

    @Test
    fun `an HQ upgrade invalidates even when the path is unchanged`() {
        val ids = Downloads.replacedCheckIds(
            asked = song("song"),
            fetched = song("song"),
            previousSuspects = emptyList(),
            aliasIds = emptyList(),
            fileReplaced = false,
            rewritten = false,
            hadPreviousFile = true,
            hq = hq,
        )

        assertEquals(setOf("song"), ids)
    }

    @Test
    fun `alias rows repointed at the new file are invalidated too`() {
        val ids = Downloads.replacedCheckIds(
            asked = song("song"),
            fetched = song("song"),
            previousSuspects = emptyList(),
            aliasIds = listOf("video"),
            fileReplaced = true,
            rewritten = false,
            hadPreviousFile = true,
            hq = null,
        )

        assertEquals(setOf("song", "video"), ids)
    }

    @Test
    fun `a same-URI rewrite invalidates alias rows too`() {
        val ids = Downloads.replacedCheckIds(
            asked = song("song"),
            fetched = song("song"),
            previousSuspects = emptyList(),
            aliasIds = listOf("video"),
            fileReplaced = false,
            rewritten = true,
            hadPreviousFile = true,
            hq = null,
        )

        assertEquals(setOf("song", "video"), ids)
    }

    @Test
    fun `a first download invalidates nothing even though it wrote bytes`() {
        val ids = Downloads.replacedCheckIds(
            asked = song("song"),
            fetched = song("song"),
            previousSuspects = emptyList(),
            aliasIds = emptyList(),
            fileReplaced = false,
            rewritten = true,
            hadPreviousFile = false,
            hq = null,
        )

        assertTrue(ids.isEmpty())
    }

    @Test
    fun `video rows keep their own timing as before`() {
        val ids = Downloads.replacedCheckIds(
            asked = song("video", video = true),
            fetched = song("video", video = true),
            previousSuspects = emptyList(),
            aliasIds = emptyList(),
            fileReplaced = false,
            rewritten = true,
            hadPreviousFile = true,
            hq = null,
        )

        assertTrue(ids.isEmpty())
    }
}
