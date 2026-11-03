package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.HqLibraryAction
import com.music.bitchord.data.sources.HqLibraryTarget
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.download.HqLibraryUpgrade
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.rebuiltFromMediaUri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every stored row that rebuilds a song restores a lost video flag. */
class StoredVideoFlagPathsTest {

    private val coverTitle = "아이묭 - Marigold / cover by 오화요"

    @Test
    fun `an offline playlist row restores an old cover video`() {
        val song = OfflinePlaylists.offlineSongFrom(
            videoId = "cover-1",
            title = coverTitle,
            artist = "Ohwayo",
            thumbnailUrl = null,
            durationText = null,
            artistId = null,
            albumId = null,
            albumName = null,
            isVideo = false,
            isVideoOrigin = false,
            localUri = "content://media/1",
            localPath = "/music/1.mp3",
            added = null,
            modified = null,
            track = null,
        )

        assertTrue(song.isVideo)
        assertTrue(song.isVideoOrigin)
    }

    @Test
    fun `an offline playlist row keeps a converted version audio`() {
        val song = OfflinePlaylists.offlineSongFrom(
            videoId = "cover-1",
            title = coverTitle,
            artist = "Ohwayo",
            thumbnailUrl = null,
            durationText = null,
            artistId = null,
            albumId = null,
            albumName = null,
            isVideo = false,
            isVideoOrigin = true,
            localUri = "content://media/1",
            localPath = "/music/1.mp3",
            added = null,
            modified = null,
            track = null,
        )

        assertFalse(song.isVideo)
        assertTrue(song.isVideoOrigin)
    }

    @Test
    fun `a cached row restores an old cover video`() {
        val song = AudioCache.cachedSongRow(
            mediaId = "cover-1",
            title = coverTitle,
            artist = "Ohwayo",
            thumbnailUrl = null,
            durationText = null,
            albumName = null,
            isVideo = false,
            downloadFormat = "Cache · 1 MB",
            lastUsedMs = 0L,
        )

        assertTrue(song.isVideo)
        assertTrue(song.isVideoOrigin)
    }

    @Test
    fun `a local media row restores an old cover video`() {
        val song = LocalMediaRepository.localSongFromRow(
            rowId = 1L,
            name = "1.mp3",
            title = coverTitle,
            artist = "Ohwayo",
            album = null,
            albumArtUri = null,
            durationMs = 0L,
            contentUri = "content://media/1",
            localPath = "/music/1.mp3",
            dateAdded = 0L,
            dateModified = 0L,
            trackNumber = null,
            videoId = null,
        )

        assertTrue(song.isVideo)
        assertTrue(song.isVideoOrigin)
    }

    @Test
    fun `an hq plan line restores an old cover video`() {
        val pendingClass = Class.forName(
            "com.music.bitchord.download.HqLibraryUpgrade\$PendingTarget",
        )
        val song = Song(
            videoId = "cover-1",
            title = coverTitle,
            artist = "Ohwayo",
            thumbnailUrl = null,
        )
        val target = HqLibraryTarget(
            videoId = "cover-1",
            action = HqLibraryAction.REPLACE,
            verifiedTier = HqTier.LOSSLESS_16,
            sourceConfigId = "source",
            trackId = "track",
        )
        val pending = pendingClass.declaredConstructors
            .first { it.parameterCount == 4 }
            .apply { isAccessible = true }
            .newInstance(target, song, 0, 0L)

        val encode = HqLibraryUpgrade::class.java
            .getDeclaredMethod("encodeTarget", pendingClass)
            .apply { isAccessible = true }
        val line = encode.invoke(HqLibraryUpgrade, pending) as String
        val parse = HqLibraryUpgrade::class.java
            .getDeclaredMethod("parseTarget", String::class.java)
            .apply { isAccessible = true }
        val parsed = parse.invoke(HqLibraryUpgrade, line)!!
        val parsedSong = parsed.javaClass
            .getDeclaredField("song")
            .apply { isAccessible = true }
            .get(parsed) as Song

        assertTrue(parsedSong.isVideo)
        assertTrue(parsedSong.isVideoOrigin)
    }

    @Test
    fun `a playback row rebuilt from a media uri keeps a converted version audio`() {
        val song = Song(
            videoId = "cover-1",
            title = coverTitle,
            artist = "Ohwayo",
            thumbnailUrl = null,
            isVideo = false,
            isVideoOrigin = true,
        )

        val back = song.rebuiltFromMediaUri(
            videoId = "cover-1",
            title = "Marigold",
            artist = "Ohwayo",
        )

        assertFalse(back.isVideo)
        assertTrue(back.isVideoOrigin)
    }
}
