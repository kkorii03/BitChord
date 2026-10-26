package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The order a downloaded track's cover is tried in.
 *
 * The reported track was '회전목마 (Vocal 손주희) - 에피톤 프로젝트 · 오화요 커버'
 * (`wnRde8OQB6s`), downloaded and played from its file. Its recorded artwork
 * was a local URI that no longer resolved, and the player retried that same
 * dead path every 1.5 seconds instead of trying the file's own picture or
 * YouTube's still.
 */
class DownloadedArtworkCandidatesTest {

    private val videoId = "wnRde8OQB6s"
    private val embedded = "content://media/external/audio/media/77"
    private val sidecar = "file:///data/user/0/com.music.bitchord/files/download-artwork/abc.jpg"

    private fun song(
        thumbnailUrl: String? = sidecar,
        localUri: String? = embedded,
        videoId: String = "wnRde8OQB6s",
    ) = Song(
        videoId = videoId,
        title = "회전목마 (Vocal 손주희) - 에피톤 프로젝트 · 오화요 커버",
        artist = "에피톤 프로젝트",
        thumbnailUrl = thumbnailUrl,
        localUri = localUri,
    )

    @Test
    fun `the reported download tries embedded, sidecar, YouTube, then the default`() {
        val candidates = downloadedArtworkCandidates(song())

        assertEquals(
            listOf(
                ArtworkCandidateKind.EMBEDDED,
                ArtworkCandidateKind.SAVED_THUMBNAIL,
                ArtworkCandidateKind.YOUTUBE,
                ArtworkCandidateKind.DEFAULT,
            ),
            candidates.map { it.kind },
        )
        assertEquals(embedded, candidates[0].value)
        assertEquals(sidecar, candidates[1].value)
        assertEquals(
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            candidates[2].value,
        )
        assertNull(candidates[3].value)
    }

    @Test
    fun `a missing sidecar goes straight to YouTube`() {
        val candidates = downloadedArtworkCandidates(song(thumbnailUrl = null))

        assertEquals(
            listOf(
                ArtworkCandidateKind.EMBEDDED,
                ArtworkCandidateKind.YOUTUBE,
                ArtworkCandidateKind.DEFAULT,
            ),
            candidates.map { it.kind },
        )
    }

    @Test
    fun `a YouTube thumbnail is not also counted as the saved sidecar`() {
        val candidates = downloadedArtworkCandidates(
            song(thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"),
        )

        assertEquals(
            listOf(
                ArtworkCandidateKind.EMBEDDED,
                ArtworkCandidateKind.YOUTUBE,
                ArtworkCandidateKind.DEFAULT,
            ),
            candidates.map { it.kind },
        )
    }


    @Test
    fun `a dead sidecar is skipped and the still is used without a retry`() {
        val candidates = downloadedArtworkCandidates(song(), savedArtworkExists = false)

        assertEquals(
            listOf(
                ArtworkCandidateKind.EMBEDDED,
                ArtworkCandidateKind.YOUTUBE,
                ArtworkCandidateKind.DEFAULT,
            ),
            candidates.map { it.kind },
        )
        assertEquals(
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            candidates[1].value,
        )
    }


    @Test
    fun `a recorded square cover is kept ahead of the video still`() {
        val square = "https://is1-ssl.mzstatic.com/image/thumb/600x600bb.jpg"
        val candidates = downloadedArtworkCandidates(
            song(thumbnailUrl = square),
            savedArtworkExists = false,
        )

        assertEquals(
            listOf(
                ArtworkCandidateKind.EMBEDDED,
                ArtworkCandidateKind.RECORDED,
                ArtworkCandidateKind.DEFAULT,
            ),
            candidates.map { it.kind },
        )
        assertEquals(square, candidates[1].value)
    }

    @Test
    fun `the reported cover ids keep their own still when their sidecar is gone`() {
        listOf("etyHAg3sADA", "vqskFsBLeUs", "v59g4HRNIkc").forEach { id ->
            val candidates = downloadedArtworkCandidates(
                song(videoId = id),
                savedArtworkExists = false,
            )
            assertEquals(
                "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                candidates.first { it.kind == ArtworkCandidateKind.YOUTUBE }.value,
            )
        }
    }

    @Test
    fun `a remote file with no YouTube id has no still to fall back to`() {
        val candidates = downloadedArtworkCandidates(
            song(
                thumbnailUrl = null,
                localUri = "smb://server/Music/Album/track.flac",
                videoId = "smb://server/Music/Album/track.flac",
            ),
        )

        assertEquals(
            listOf(ArtworkCandidateKind.DEFAULT),
            candidates.map { it.kind },
        )
    }
}
