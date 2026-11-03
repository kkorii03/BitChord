package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ko.loadAutoplayTracks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A station row can arrive with its id and title but no artist credit — after
 * the display cleaner removes a soundtrack label, for example. The row is still
 * playable, so AutoPlay keeps it; only a row without a title has no name to
 * show.
 */
class AutoplayMetadataFilterTest {

    private fun song(id: String, title: String, artist: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
    )

    private fun <T> withPlainAutoplay(block: () -> T): T {
        val personalised = AppSettings.personalisedAutoplay.value
        val musicOnly = AppSettings.preferMusicOnly.value
        val video = AppSettings.videoRadio.value
        val metered = AppSettings.meteredConnection.value
        AppSettings.personalisedAutoplay.value = false
        AppSettings.preferMusicOnly.value = false
        AppSettings.videoRadio.value = false
        AppSettings.meteredConnection.value = false
        return try {
            block()
        } finally {
            AppSettings.personalisedAutoplay.value = personalised
            AppSettings.preferMusicOnly.value = musicOnly
            AppSettings.videoRadio.value = video
            AppSettings.meteredConnection.value = metered
        }
    }

    @Test
    fun `a radio row with a title but no artist is queued`() {
        val seed = song("seed", "VILLAIN", "K/DA")
        val rows = withPlainAutoplay {
            runBlocking {
                loadAutoplayTracks(
                    existing = listOf(seed),
                    seedSong = seed,
                    limit = 10,
                    sessionSong = null,
                    sessionIds = emptySet(),
                    radio = { _, _ ->
                        Result.success(
                            listOf(
                                song("bummerland", "Bummerland", ""),
                                song("named", "Firestarter", "K/DA"),
                            ),
                        )
                    },
                    seedFor = { seed.videoId },
                )
            }.getOrThrow()
        }

        assertEquals(listOf("bummerland", "named"), rows.map { it.videoId })
    }

    @Test
    fun `a radio row without a title is still dropped`() {
        val seed = song("seed", "VILLAIN", "K/DA")
        val rows = withPlainAutoplay {
            runBlocking {
                loadAutoplayTracks(
                    existing = listOf(seed),
                    seedSong = seed,
                    limit = 10,
                    sessionSong = null,
                    sessionIds = emptySet(),
                    radio = { _, _ ->
                        Result.success(
                            listOf(
                                song("nameless", "", "AJR"),
                                song("named", "Firestarter", "K/DA"),
                            ),
                        )
                    },
                    seedFor = { seed.videoId },
                )
            }.getOrThrow()
        }

        assertEquals(listOf("named"), rows.map { it.videoId })
    }
}
