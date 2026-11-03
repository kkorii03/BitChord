package com.music.bitchord.ko

import org.junit.Assert.assertTrue
import org.junit.Test

/** A video row stays a video through a backup's local playlist rows. */
class BackupSongVideoFlagTest {

    @Test
    fun `a video row stays a video after the backup round trip`() {
        val song = KoBackup.BackupSong(
            videoId = "video-1",
            title = "MV",
            artist = "Artist",
            isVideo = true,
        ).toSong()

        assertTrue(song.isVideo)
    }

    @Test
    fun `an old cover row without the flag is still a video`() {
        val song = KoBackup.BackupSong(
            videoId = "cover-1",
            title = "아이묭 - Marigold / cover by 오화요",
            artist = "Ohwayo",
        ).toSong()

        assertTrue(song.isVideo)
    }
}
