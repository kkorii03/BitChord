package com.music.bitchord.ko.downloads

import com.music.bitchord.data.net.DownloadedMvPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review D5: a legacy file filed under a music video V and its song S (no
 * provenance) is queued for the MV-sound repair as S. The song-version lookup
 * answers S itself, and the old rule ("fetched must differ from asked")
 * refused it forever, although S's own stream is exactly the fix.
 */
class KoMvRepairAliasedFileTest {

    private val video = "VVVVVVVVVVV"
    private val song = "SSSSSSSSSSS"

    private val legacyAliased = KoDownloadPipeline.FileOrigin(
        soundSourceId = null,
        fileIds = setOf(video, song),
        songIdOfFile = song,
    )

    @Test
    fun `old rule refuses the aliased repair`() {
        // Documents what the code used before: asked == fetched is refused.
        assertFalse(DownloadedMvPolicy.repairFetchedSongVersion(song, song))
    }

    @Test
    fun `song id of an aliased legacy file may fetch its own stream`() {
        assertTrue(KoDownloadPipeline.repairReplacesUpload(song, song, legacyAliased))
    }

    @Test
    fun `recorded MV provenance lets the song id replace it`() {
        val fromVideo = legacyAliased.copy(soundSourceId = video)
        assertTrue(KoDownloadPipeline.repairReplacesUpload(song, song, fromVideo))
        assertFalse(
            "re-fetching the upload the file came from writes the same bytes",
            KoDownloadPipeline.repairReplacesUpload(song, video, fromVideo),
        )
    }

    @Test
    fun `own-id-only file still needs a different upload`() {
        val ownOnly = KoDownloadPipeline.FileOrigin(null, setOf(video), video)
        assertFalse(KoDownloadPipeline.repairReplacesUpload(video, video, ownOnly))
        assertFalse(KoDownloadPipeline.repairReplacesUpload(video, video, null))
        assertTrue(KoDownloadPipeline.repairReplacesUpload(video, song, ownOnly))
        assertTrue(KoDownloadPipeline.repairReplacesUpload(video, song, null))
    }

    @Test
    fun `the video alias fetching itself again is still refused`() {
        assertFalse(KoDownloadPipeline.repairReplacesUpload(video, video, legacyAliased))
        // An ordinary download of the video row resolved to the song still
        // clears the verdict, as it did before.
        assertTrue(KoDownloadPipeline.repairReplacesUpload(video, song, legacyAliased))
    }

    @Test
    fun `blank ids never count as a replacement`() {
        assertFalse(KoDownloadPipeline.repairReplacesUpload("", song, legacyAliased))
        assertFalse(KoDownloadPipeline.repairReplacesUpload(song, "", legacyAliased))
    }
}
