package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.Backup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Importing the same local playlists twice must not grow the list, and a
 * backup row must keep the song metadata the playlist page shows.
 */
class LocalPlaylistBackupTest {

    private fun playlist(id: String, vararg videoIds: String) = LocalPlaylist(
        id = id,
        title = "Playlist $id",
        songs = videoIds.map { Song(videoId = it, title = "Song $it", artist = "Artist", thumbnailUrl = null) },
    )

    @Test
    fun `merging the same import twice does not duplicate rows`() {
        val current = listOf(playlist("a", "v1"))
        val incoming = listOf(playlist("b", "v2"), playlist("c", "v3"))

        val once = mergePlaylists(current, incoming)
        val twice = mergePlaylists(once, incoming)

        assertEquals(listOf("a", "b", "c"), once.map { it.id })
        assertEquals(once, twice)
    }

    @Test
    fun `merging an existing id keeps the device row`() {
        val device = playlist("a", "old")
        val backup = playlist("a", "new")

        val merged = mergePlaylists(listOf(device), listOf(backup))

        assertEquals(listOf("a"), merged.map { it.id })
        assertSame(device, merged.single())
    }

    @Test
    fun `replace collapses duplicate rows from one file`() {
        val incoming = listOf(playlist("a", "v1"), playlist("a", "v2"), playlist("b", "v3"))

        assertEquals(listOf("a", "b"), replacePlaylists(incoming).map { it.id })
    }

    @Test
    fun `extended row fields survive the on-disk playlist format`() {
        val raw = """
            [{"id":"a","title":"A","songs":[{
              "videoId":"v1","title":"Song","artist":"Artist",
              "isVideo":true,"isVideoOrigin":true,"setVideoId":"set-1",
              "localUri":"content://music/1","localPath":"/music/1.flac",
              "downloadFormat":"FLAC","sourceQuality":"LOSSLESS","isExplicit":false
            }]}]
        """.trimIndent()

        val song = parsePlaylists(raw)!!.playlists.single().songs.single()

        assertEquals("set-1", song.setVideoId)
        assertEquals("content://music/1", song.localUri)
        assertEquals("/music/1.flac", song.localPath)
        assertEquals("FLAC", song.downloadFormat)
        assertEquals("LOSSLESS", song.sourceQuality)
        assertEquals(false, song.isExplicit)
        assertEquals(true, song.isVideoOrigin)
    }

    @Test
    fun `a backup playlist keeps the fields the library row carries`() {
        val original = LocalPlaylist(
            id = "sp_local_1",
            title = "Covers",
            songs = listOf(
                Song(
                    videoId = "v1",
                    title = "Cover",
                    artist = "Singer",
                    thumbnailUrl = "https://example.test/cover.jpg",
                    durationText = "3:45",
                    albumId = "album",
                    albumName = "Album",
                    isVideo = true,
                    isVideoOrigin = true,
                    setVideoId = "set-1",
                    localUri = "content://music/1",
                    localPath = "/music/1.flac",
                    downloadFormat = "FLAC",
                    sourceQuality = "LOSSLESS",
                    isExplicit = false,
                ),
            ),
        )

        val restored = Backup.BackupPlaylist.from(original).toLocalPlaylist()

        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.songs.single().videoId, restored.songs.single().videoId)
        assertEquals(original.songs.single().albumName, restored.songs.single().albumName)
        assertEquals(original.songs.single().localPath, restored.songs.single().localPath)
        assertEquals(original.songs.single().sourceQuality, restored.songs.single().sourceQuality)
        assertEquals(original.songs.single().isVideoOrigin, restored.songs.single().isVideoOrigin)
    }
}
