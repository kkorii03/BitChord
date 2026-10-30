package com.music.bitchord.download

import com.music.bitchord.data.sources.HqLibraryAction
import com.music.bitchord.data.sources.HqLibraryTarget
import com.music.bitchord.data.sources.HqTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A successful download is not proof that the high-quality pass's replacement
 * landed: the queue can refuse the pass's request because the listener already
 * queued the same id at a different rung. Only the recorded verified pair
 * answers that question.
 */
class HqReplacementLandedTest {

    private val target = HqLibraryTarget(
        videoId = "video",
        action = HqLibraryAction.REPLACE,
        verifiedTier = HqTier.LOSSLESS_16,
        sourceConfigId = "source-1",
        trackId = "track-1",
    )

    @Test
    fun `a successful download with the verified pair landed`() {
        assertTrue(hqReplacementLanded(downloadSucceeded = true, applied = true))
    }

    @Test
    fun `a successful download without the verified pair did not land`() {
        assertFalse(hqReplacementLanded(downloadSucceeded = true, applied = false))
    }

    @Test
    fun `a failed download never landed`() {
        assertFalse(hqReplacementLanded(downloadSucceeded = false, applied = true))
        assertFalse(hqReplacementLanded(downloadSucceeded = false, applied = false))
    }

    @Test
    fun `a successful download already at least as good as the target landed`() {
        assertTrue(
            hqReplacementLanded(
                downloadSucceeded = true,
                applied = false,
                atLeastAsGood = true,
            ),
        )
    }

    @Test
    fun `a successful download below the target and without its pair did not land`() {
        assertFalse(
            hqReplacementLanded(
                downloadSucceeded = true,
                applied = false,
                atLeastAsGood = false,
            ),
        )
    }

    @Test
    fun `the same verified recording on a higher rung answers the target`() {
        assertTrue(
            hqSavedCopyAnswersTarget(
                savedMeta(
                    format = "FLAC",
                    hqTier = HqTier.HI_RES_24_88.name,
                ),
                target,
            ),
        )
    }

    @Test
    fun `a wrong recording at the target rung does not answer it`() {
        assertFalse(
            hqSavedCopyAnswersTarget(
                savedMeta(
                    format = "FLAC",
                    hqTier = HqTier.LOSSLESS_16.name,
                    wrongRecording = true,
                ),
                target,
            ),
        )
    }

    @Test
    fun `an ordinary download without provenance does not answer the target`() {
        assertFalse(
            hqSavedCopyAnswersTarget(
                savedMeta(
                    format = "FLAC",
                    hqTier = HqTier.LOSSLESS_16.name,
                    hqSourceConfigId = null,
                    hqTrackId = null,
                ),
                target,
            ),
        )
    }

    @Test
    fun `a different verified source does not answer the target`() {
        assertFalse(
            hqSavedCopyAnswersTarget(
                savedMeta(
                    format = "FLAC",
                    hqTier = HqTier.LOSSLESS_16.name,
                    hqSourceConfigId = "other-source",
                ),
                target,
            ),
        )
    }

    @Test
    fun `a cache only copy without saved metadata does not answer the target`() {
        assertFalse(hqSavedCopyAnswersTarget(null, target))
    }

    private fun savedMeta(
        format: String?,
        hqTier: String?,
        hqSourceConfigId: String? = "source-1",
        hqTrackId: String? = "track-1",
        wrongRecording: Boolean = false,
    ) = SavedSongMetadata(
        videoId = "video",
        title = "Title",
        artist = "Artist",
        uri = "content://downloads/video",
        downloadFormat = format,
        hqSourceConfigId = hqSourceConfigId,
        hqTrackId = hqTrackId,
        hqTier = hqTier,
        wrongRecording = wrongRecording,
    )
}
