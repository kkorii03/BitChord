package com.music.bitchord.ko.downloads

import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review RG5-3: a legacy file filed under a music video V and its song S,
 * where S's record has no length. The shortest-length pick then names V as
 * the file's "song side", so an MV-sound repair queued as V that re-fetched
 * V's own stream passed as a replacement: the MV sound was saved again and
 * the warning cleared.
 */
class KoMvRepairUnsureSongSideTest {

    private val video = "VVVVVVVVVV2"
    private val song = "SSSSSSSSSS2"
    private val uri = "file:/music/legacy.m4a"

    private var savedBefore: Map<String, String> = emptyMap()
    private var metadataBefore: Map<String, SavedSongMetadata> = emptyMap()

    @Before
    fun setUp() {
        savedBefore = Downloads.savedValue()
        metadataBefore = Downloads.savedMetadataValue()
    }

    @After
    fun tearDown() {
        Downloads.setSaved(savedBefore)
        Downloads.setSavedMetadata(metadataBefore)
    }

    private fun legacyFile(songDuration: String?, videoIsVideo: Boolean?) {
        Downloads.setSaved(mapOf(video to uri, song to uri))
        Downloads.setSavedMetadata(
            mapOf(
                video to SavedSongMetadata(
                    videoId = video,
                    title = "Song (Official Music Video)",
                    artist = "Singer",
                    durationText = "4:30",
                    uri = uri,
                    isVideo = videoIsVideo,
                ),
                song to SavedSongMetadata(
                    videoId = song,
                    title = "Song",
                    artist = "Singer",
                    durationText = songDuration,
                    uri = uri,
                ),
            ),
        )
    }

    @Test
    fun `video picked as song side may not re-fetch itself`() {
        legacyFile(songDuration = null, videoIsVideo = null) // legacy: type unknown, MV title
        val origin = KoDownloadRecords.fileOriginFor(video)
        assertTrue(origin != null && origin.songIdOfFile == video)

        assertFalse(KoDownloadPipeline.repairReplacesUpload(video, video, origin))
        // Fetching the song version still replaces it.
        assertTrue(KoDownloadPipeline.repairReplacesUpload(video, song, origin))
    }

    @Test
    fun `video recorded as video is unsure too`() {
        legacyFile(songDuration = null, videoIsVideo = true)
        assertFalse(KoDownloadPipeline.repairReplacesUpload(video, video, KoDownloadRecords.fileOriginFor(video)))
    }

    @Test
    fun `song with its own length keeps the D5 repair`() {
        legacyFile(songDuration = "3:30", videoIsVideo = null)
        val origin = KoDownloadRecords.fileOriginFor(song)
        assertTrue(origin != null && origin.songIdOfFile == song && !origin.songSideIsVideo)
        assertTrue(KoDownloadPipeline.repairReplacesUpload(song, song, origin))
    }
}
