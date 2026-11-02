package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ko.coverSeedFallback
import com.music.bitchord.ko.coverSeedOriginal
import com.music.bitchord.ko.loadAutoplayTracks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cover upload's own music radio is often two or three rows that the
 * AutoPlay filter drops. The fallback asks the station of the song it covers
 * first, then the upload's video mix, instead of leaving the queue empty.
 */
class AutoplayCoverFallbackTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        isVideo: Boolean = false,
        duration: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        isVideo = isVideo,
    )

    private val cover = Song(
        videoId = "cover-video",
        title = "밤편지",
        artist = "아이유 · 싱어 커버",
        thumbnailUrl = null,
        isVideo = true,
        originalTitle = "밤편지 - 싱어",
        originalArtist = "싱어",
        coverSinger = "싱어",
        coverOriginalArtist = "아이유",
    )

    private val musicCover = Song(
        videoId = "cover-video",
        title = "밤편지",
        artist = "싱어",
        thumbnailUrl = null,
        originalTitle = "밤편지 - 싱어",
        originalArtist = "싱어",
    )

    private fun <T> withPlainAutoplay(block: () -> T): T {
        val personalised = AppSettings.personalisedAutoplay.value
        val musicOnly = AppSettings.preferMusicOnly.value
        AppSettings.personalisedAutoplay.value = false
        AppSettings.preferMusicOnly.value = false
        return try {
            block()
        } finally {
            AppSettings.personalisedAutoplay.value = personalised
            AppSettings.preferMusicOnly.value = musicOnly
        }
    }

    private fun loadCover(
        videoRadio: Boolean,
        inParty: Boolean,
        seedSong: Song = cover,
        seedFor: suspend (Song) -> String? = { "original-id" },
        radio: suspend (String, Boolean) -> Result<List<Song>>,
    ): List<Song> {
        val personalised = AppSettings.personalisedAutoplay.value
        val musicOnly = AppSettings.preferMusicOnly.value
        val video = AppSettings.videoRadio.value
        val metered = AppSettings.meteredConnection.value
        AppSettings.personalisedAutoplay.value = false
        AppSettings.preferMusicOnly.value = false
        AppSettings.videoRadio.value = videoRadio
        AppSettings.meteredConnection.value = false
        return try {
            runBlocking {
                loadAutoplayTracks(
                    existing = listOf(seedSong),
                    seedSong = seedSong,
                    limit = 10,
                    sessionSong = null,
                    sessionIds = emptySet(),
                    radio = radio,
                    inParty = inParty,
                    seedFor = seedFor,
                )
            }.getOrThrow()
        } finally {
            AppSettings.personalisedAutoplay.value = personalised
            AppSettings.preferMusicOnly.value = musicOnly
            AppSettings.videoRadio.value = video
            AppSettings.meteredConnection.value = metered
        }
    }

    @Test
    fun `the original song is what a cover seed falls back to`() {
        val original = coverSeedOriginal(cover)
        assertEquals("밤편지", original?.title)
        assertEquals("아이유", original?.artist)
        assertEquals("", original?.videoId)
        assertNull(coverSeedOriginal(song("a", "밤편지", "아이유")))
    }

    @Test
    fun `the original song's radio fills the queue before the video mix is asked`() {
        val related = song("original-related", "봄날", "아이유")
        val asked = mutableListOf<String>()
        val rows = withPlainAutoplay {
            runBlocking {
                coverSeedFallback(
                    existing = listOf(cover),
                    seedSong = cover,
                    videoSeed = cover.videoId,
                    limit = 10,
                    sessionSong = null,
                    sessionIds = emptySet(),
                    seedFor = { "original-id" },
                    radio = { id, preferVideo ->
                        asked += "$id:$preferVideo"
                        if (id == "original-id" && !preferVideo) {
                            Result.success(listOf(related))
                        } else {
                            Result.success(emptyList())
                        }
                    },
                )
            }
        }

        assertEquals(listOf("original-id:false"), asked)
        assertEquals(listOf("original-related"), rows.map { it.videoId })
    }

    @Test
    fun `the video mix fills the queue when the original has no radio`() {
        val videoRows = listOf(
            song("v1", "첫 곡 (Cover)", "가수 A", isVideo = true, duration = "3:20"),
            song("v2", "둘째 곡 (Cover)", "가수 B", isVideo = true, duration = "3:10"),
            song("v3", "셋째 곡 (Cover)", "가수 C", isVideo = true, duration = "3:30"),
        )
        val asked = mutableListOf<String>()
        val rows = withPlainAutoplay {
            runBlocking {
                coverSeedFallback(
                    existing = listOf(cover),
                    seedSong = cover,
                    videoSeed = cover.videoId,
                    limit = 10,
                    sessionSong = null,
                    sessionIds = emptySet(),
                    seedFor = { "original-id" },
                    radio = { id, preferVideo ->
                        asked += "$id:$preferVideo"
                        if (id == cover.videoId && preferVideo) {
                            Result.success(videoRows)
                        } else {
                            Result.success(emptyList())
                        }
                    },
                )
            }
        }

        assertEquals(listOf("original-id:false", "cover-video:true"), asked)
        assertEquals(listOf("v1", "v2", "v3"), rows.map { it.videoId })
        assertTrue(rows.all { it.videoRadio })
    }

    @Test
    fun `the video mix is not asked when the video radio setting is off`() {
        val asked = mutableListOf<String>()
        val rows = loadCover(
            videoRadio = false,
            inParty = false,
            radio = { id, preferVideo ->
                asked += "$id:$preferVideo"
                Result.success(emptyList())
            },
        )

        assertTrue(rows.isEmpty())
        assertFalse(asked.any { it.endsWith(":true") })
    }

    @Test
    fun `the video mix is not asked in a party`() {
        val asked = mutableListOf<String>()
        val rows = loadCover(
            videoRadio = true,
            inParty = true,
            radio = { id, preferVideo ->
                asked += "$id:$preferVideo"
                Result.success(emptyList())
            },
        )

        assertTrue(rows.isEmpty())
        assertFalse(asked.any { it.endsWith(":true") })
    }

    @Test
    fun `a cover seed asks the resolved seed for the video mix, not its own id`() {
        val videoRows = listOf(
            song("v1", "첫 곡 (Cover)", "가수 A", isVideo = true, duration = "3:20"),
        )
        val asked = mutableListOf<String>()
        val rows = loadCover(
            videoRadio = true,
            inParty = false,
            seedSong = musicCover,
            seedFor = { if (it.videoId == musicCover.videoId) "resolved-seed" else null },
            radio = { id, preferVideo ->
                asked += "$id:$preferVideo"
                if (id == "resolved-seed" && preferVideo) {
                    Result.success(videoRows)
                } else {
                    Result.success(emptyList())
                }
            },
        )

        assertTrue(asked.contains("resolved-seed:true"))
        assertEquals(listOf("v1"), rows.map { it.videoId })
    }
}
