package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ko.loadAutoplayTracks
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v1.9 merge kept [com.music.bitchord.data.YtMusicRepository.radio]'s
 * `preferVideo` flag but the Korean AutoPlay path stopped passing it, so a
 * video-song station was paired back to audio rows and the continue toggle did
 * nothing. These pin the flag through every AutoPlay path, and pin the toggle
 * off to the audio rows 1.8 played.
 */
class AutoplayVideoRadioWiringTest {

    private fun song(id: String, isVideo: Boolean, albumId: String? = null) = Song(
        videoId = id,
        title = "Song $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        albumId = albumId,
        isVideo = isVideo,
    )

    /** Runs the normal (non video-station) AutoPlay path with every network source off. */
    private fun load(
        seed: Song,
        videoRadio: Boolean,
        radio: suspend (String, Boolean) -> Result<List<Song>>,
    ): Pair<List<Boolean>, List<Song>> {
        val personalised = AppSettings.personalisedAutoplay.value
        val musicOnly = AppSettings.preferMusicOnly.value
        val video = AppSettings.videoRadio.value
        val metered = AppSettings.meteredConnection.value
        AppSettings.personalisedAutoplay.value = false
        AppSettings.preferMusicOnly.value = false
        AppSettings.videoRadio.value = videoRadio
        AppSettings.meteredConnection.value = false
        val asked = mutableListOf<Boolean>()
        try {
            val rows = runBlocking {
                loadAutoplayTracks(
                    existing = emptyList(),
                    seedSong = seed,
                    limit = 10,
                    sessionSong = null,
                    sessionIds = emptySet(),
                    radio = { id, preferVideo ->
                        asked += preferVideo
                        radio(id, preferVideo)
                    },
                    inParty = false,
                    seedFor = { seed.videoId },
                )
            }.getOrThrow()
            return asked to rows
        } finally {
            AppSettings.personalisedAutoplay.value = personalised
            AppSettings.preferMusicOnly.value = musicOnly
            AppSettings.videoRadio.value = video
            AppSettings.meteredConnection.value = metered
        }
    }

    @Test
    fun `a video seed keeps video rows while the continue toggle is on`() {
        // albumId makes this an official-artist row, so the station takes the
        // normal path instead of the separate video-mix branch.
        val seed = song("seed-id", isVideo = true, albumId = "album-1")
        val videoRows = listOf(song("v1", true), song("v2", true), song("v3", true))
        val audioRow = song("a1", false)

        val (asked, rows) = load(seed, videoRadio = true) { _, _ ->
            Result.success(videoRows + audioRow)
        }

        assertEquals(listOf(true), asked)
        assertEquals(videoRows.map { it.videoId }.toSet(), rows.map { it.videoId }.toSet())
    }

    @Test
    fun `turning the continue toggle off plays the audio rows`() {
        val seed = song("seed-id", isVideo = true, albumId = "album-1")
        val videoRows = listOf(song("v1", true), song("v2", true), song("v3", true))
        val audioRow = song("a1", false)

        val (asked, rows) = load(seed, videoRadio = false) { _, _ ->
            Result.success(videoRows + audioRow)
        }

        assertEquals(listOf(false), asked)
        assertEquals(listOf(audioRow.videoId), rows.map { it.videoId })
    }

    @Test
    fun `the video station and its session radio ask for video rows`() {
        val source = listOf(
            "app/src/main/java/com/music/bitchord/ko/KoAutoplayTopUp.kt",
            "../app/src/main/java/com/music/bitchord/ko/KoAutoplayTopUp.kt",
        ).map(::File).first { it.isFile }.readText()
        assertTrue(
            "video station lost preferVideo = true",
            "radioResult = radio(seed, true)" in source,
        )
        assertTrue(
            "video station's session radio lost preferVideo",
            "sessionRadioFor(seedSong, sessionSong, preferVideo = true)" in source,
        )
        assertTrue(
            "normal autoplay lost the video seed; see upstream loadAutoplayTracks",
            "val videos = AppSettings.videoRadio.value &&" in source,
        )
        assertTrue(
            "normal autoplay lost preferVideo",
            "radio(seed, videos)" in source,
        )
        assertTrue(
            "queue build lost preferVideos",
            "preferVideos = videos" in source,
        )
    }
}
