package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which picture a cover upload, a normal song, or a downloaded copy is drawn
 * with — and the one rule the media session must never break.
 *
 * The ids and sidecar shape are the ones from the 10-26 phone report: the
 * dance-hall cover (`etyHAg3sADA`), the night-flower cover (`vqskFsBLeUs`),
 * the Ponyo cover (`v59g4HRNIkc`) and a normal catalogue track.
 */
class ArtworkSelectionTest {

    private val danceHall = "etyHAg3sADA"
    private val nightFlower = "vqskFsBLeUs"
    private val ponyo = "v59g4HRNIkc"
    private val normal = "dQw4w9WgXcQ"

    private val sidecar =
        "file:///data/user/0/com.music.bitchord/files/download-artwork/6dcbdbb0.jpg"
    private val youtubeStill = "https://i.ytimg.com/vi/$danceHall/hqdefault.jpg"

    private fun song(
        videoId: String,
        thumbnailUrl: String?,
        localUri: String? = null,
        artist: String = "오화요 커버",
    ) = Song(
        videoId = videoId,
        title = "ダンスホール - Dance Hall",
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        localUri = localUri,
    )

    @Test
    fun `a streaming cover keeps its https thumbnail`() {
        val cover = song(danceHall, youtubeStill)

        assertEquals(
            ArtworkSelection(ArtworkSource.REMOTE, youtubeStill),
            cover.artworkSelection(localArtworkExists = false),
        )
    }

    @Test
    fun `a downloaded cover with its sidecar draws the local file`() {
        val cover = song(danceHall, sidecar, localUri = "content://media/external/audio/media/1")

        assertEquals(
            ArtworkSelection(ArtworkSource.LOCAL, sidecar),
            cover.artworkSelection(localArtworkExists = true),
        )
    }

    @Test
    fun `a downloaded cover whose sidecar is gone falls back to YouTube`() {
        val cover = song(danceHall, sidecar, localUri = "content://media/external/audio/media/1")

        val chosen = cover.artworkSelection(localArtworkExists = false)

        assertEquals(ArtworkSource.REMOTE, chosen.source)
        assertEquals("https://i.ytimg.com/vi/$danceHall/hqdefault.jpg", chosen.value)
    }

    @Test
    fun `every reported cover id falls back to its own still`() {
        mapOf(
            nightFlower to "https://i.ytimg.com/vi/$nightFlower/hqdefault.jpg",
            ponyo to "https://i.ytimg.com/vi/$ponyo/hqdefault.jpg",
        ).forEach { (id, still) ->
            val cover = song(id, sidecar, localUri = "file:///data/user/0/app/files/downloads/x.m4a")
            assertEquals(
                ArtworkSelection(ArtworkSource.REMOTE, still),
                cover.artworkSelection(localArtworkExists = false),
            )
        }
    }

    @Test
    fun `a normal downloaded song with no sidecar also uses its still`() {
        val track = song(normal, thumbnailUrl = null, localUri = "content://media/external/audio/media/2")

        assertEquals(
            ArtworkSelection(
                ArtworkSource.REMOTE,
                "https://i.ytimg.com/vi/$normal/hqdefault.jpg",
            ),
            track.artworkSelection(localArtworkExists = false),
        )
    }

    @Test
    fun `a remote square cover is kept instead of replacing it with a still`() {
        val square = "https://is1-ssl.mzstatic.com/image/thumb/600x600bb.jpg"
        val track = song(normal, square, localUri = "content://media/external/audio/media/2")

        assertEquals(
            ArtworkSelection(ArtworkSource.REMOTE, square),
            track.artworkSelection(localArtworkExists = false),
        )
    }

    @Test
    fun `a plain local file with no YouTube id has no still to fall back to`() {
        val local = song(
            videoId = "content://media/external/audio/media/9",
            thumbnailUrl = sidecar,
            localUri = "content://media/external/audio/media/9",
        )

        assertEquals(ArtworkSelection.None, local.artworkSelection(localArtworkExists = false))
    }

    @Test
    fun `the media session is never handed a private file uri`() {
        val cover = song(danceHall, sidecar, localUri = "content://media/external/audio/media/1")

        val exists = cover.artworkSelection(localArtworkExists = true)
        val missing = cover.artworkSelection(localArtworkExists = false)

        // The caller converts LOCAL to a content:// URI or artworkData; the
        // fallback path itself must already be cross-process readable.
        assertEquals(ArtworkSource.LOCAL, exists.source)
        assertFalse(exists.value.orEmpty().startsWith("https://"))
        assertEquals(ArtworkSource.REMOTE, missing.source)
        assertFalse(missing.value.orEmpty().startsWith("file://"))
    }

    @Test
    fun `no thumbnail and no id chooses nothing`() {
        val empty = song(videoId = "", thumbnailUrl = null)
        assertEquals(ArtworkSelection.None, empty.artworkSelection(localArtworkExists = false))
        assertNull(empty.artworkSelection(localArtworkExists = false).value)
    }
}
