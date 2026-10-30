package com.music.bitchord.data.stats

import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The schema-2 file has to survive its own round trip, and a schema-1 file
 * written before the library sections existed has to keep importing.
 */
class BackupLibraryRoundTripTest {

    @Test
    fun `schema 2 round trips local playlists, liked rows and search history`() {
        val history = SearchHistoryEntity(
            id = "v1",
            title = "Cover",
            subtitle = "Song · Singer",
            artworkUrl = null,
            entityType = EntityType.TRACK,
            timestamp = 1_700_000_000_000,
        )
        val file = Backup.BackupFile(
            version = 2,
            versionName = "1.2.3",
            exportedAt = "2026-10-19T00:00:00Z",
            settings = mapOf("theme_mode" to Backup.PrefValue("string", "DARK")),
            localPlaylists = listOf(
                Backup.BackupPlaylist(
                    id = "sp_local_1",
                    title = "Covers",
                    songs = listOf(
                        Backup.BackupSong(
                            videoId = "v1",
                            title = "Cover",
                            artist = "Singer",
                            thumbnailUrl = "https://example.test/cover.jpg",
                            albumName = "Album",
                            isVideo = true,
                            isVideoOrigin = true,
                            localPath = "/music/cover.flac",
                            sourceQuality = "LOSSLESS",
                        ),
                    ),
                ),
            ),
            likedVideoIds = listOf("v1", "v2"),
            dislikedVideoIds = listOf("v3"),
            searchHistory = listOf(history),
        )

        val decoded = Backup.decode(Backup.encode(file))

        assertEquals(file, decoded)
        assertEquals("Cover", decoded.localPlaylists.single().songs.single().title)
        assertEquals(history, decoded.searchHistory.single())
        assertTrue(decoded.likedVideoIds.contains("v2"))
    }

    @Test
    fun `a schema 1 file imports without touching the new library sections`() {
        val text = """
            {
              "app": "bitchord",
              "version": 1,
              "versionName": "1.0",
              "exportedAt": "2025-01-01T00:00:00Z",
              "settings": {
                "theme_mode": {"type": "string", "value": "LIGHT"}
              },
              "listening": []
            }
        """.trimIndent()

        val file = Backup.decode(text)
        Backup.validate(file)

        assertEquals(1, file.version)
        assertEquals("LIGHT", file.settings["theme_mode"]?.decoded())
        assertTrue(file.localPlaylists.isEmpty())
        assertTrue(file.likedVideoIds.isEmpty())
        assertTrue(file.searchHistory.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a schema from a newer build is refused`() {
        Backup.validate(Backup.BackupFile(app = "bitchord", version = 4))
    }


    @Test
    fun `schema 3 round trips lyric sheets, sync memory and HQ verdicts`() {
        val overrides = """{"version":1,"songs":{}}"""
        val timing = """{"entries":{"v1":{"syncCheckVersion":1}}}"""
        val file = Backup.BackupFile(
            app = "bitchord",
            version = 3,
            lyricsOverrides = overrides,
            lyricsTiming = timing,
            hqVerified = "v1\u0001source\u0001track\t0\t1.0\t100\tLOSSLESS_16",
            hqRejected = "v2\u0001source\u0001track",
        )

        val decoded = Backup.decode(Backup.encode(file))
        Backup.validate(decoded)

        assertEquals(overrides, decoded.lyricsOverrides)
        assertEquals(timing, decoded.lyricsTiming)
        assertEquals(file.hqVerified, decoded.hqVerified)
        assertEquals(file.hqRejected, decoded.hqRejected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a playlist row without a video id is refused before anything is written`() {
        val file = Backup.BackupFile(
            app = "bitchord",
            version = 2,
            localPlaylists = listOf(
                Backup.BackupPlaylist(
                    id = "sp_local_1",
                    title = "Broken",
                    songs = listOf(Backup.BackupSong(videoId = "")),
                ),
            ),
        )

        Backup.validate(file)
    }

    @Test
    fun `a backup within the cap is read whole`() {
        val text = """{"app":"bitchord","version":2}"""

        val read = Backup.readBackupText(
            ByteArrayInputStream(text.toByteArray()),
            maxBytes = text.length,
        )

        assertEquals(text, read)
    }

    @Test
    fun `a backup larger than the cap is refused before it is decoded`() {
        val input = ByteArrayInputStream(ByteArray(17))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            Backup.readBackupText(input, maxBytes = 16)
        }

        assertEquals("백업 파일이 너무 큽니다", failure.message)
    }

    @Test
    fun `an export within the cap is written whole`() {
        val file = Backup.BackupFile(
            app = "bitchord",
            version = 2,
            localPlaylists = listOf(
                Backup.BackupPlaylist(
                    id = "sp_local_1",
                    title = "Covers",
                    songs = listOf(Backup.BackupSong(videoId = "v1", title = "Cover")),
                ),
            ),
        )

        val bytes = Backup.encodeBackupBytes(file)

        assertEquals(file, Backup.decode(bytes.decodeToString()))
    }

    @Test
    fun `an export larger than the cap is refused before it is written`() {
        val file = Backup.BackupFile(
            app = "bitchord",
            version = 2,
            localPlaylists = listOf(
                Backup.BackupPlaylist(
                    id = "sp_local_1",
                    title = "Covers",
                    songs = listOf(Backup.BackupSong(videoId = "v1", title = "Cover")),
                ),
            ),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            Backup.encodeBackupBytes(file, maxBytes = 16)
        }

        assertEquals("백업 파일이 너무 큽니다", failure.message)
    }
}
