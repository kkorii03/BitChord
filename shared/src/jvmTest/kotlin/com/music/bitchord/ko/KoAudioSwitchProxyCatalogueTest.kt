package com.music.bitchord.ko

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * WDYM 11-18: DK_0jXPuIr0 ("What Do You Mean?", the MV) played its own sound
 * because Korea's catalogue hides the region-blocked official audio
 * FCGb0pw_wPU, which the US catalogue lists and the proxy plays. The audio
 * switch now asks the US Songs page once, only with the proxy configured,
 * and adopts only through the strict same-artist / official-audio gates.
 */
class KoAudioSwitchProxyCatalogueTest {

    private val mv = Song(
        videoId = "DK_0jXPuIr0",
        title = "What Do You Mean?",
        artist = "Justin Bieber",
        thumbnailUrl = "https://i.ytimg.com/vi/DK_0jXPuIr0/hq720.jpg",
        durationText = "5:06",
        isVideo = true,
    )

    private fun track(id: String, title: String, artist: String, duration: String, album: String? = null) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = "https://lh3.googleusercontent.com/$id=w544-h544",
        durationText = duration,
        albumName = album,
    )

    private val officialAudio = track("FCGb0pw_wPU", "What Do You Mean?", "Justin Bieber", "3:27", "Purpose (Deluxe)")
    private val cover = track("cover000001", "What Do You Mean?", "Madilyn Bailey", "3:30")
    private val otherSong = track("other000001", "Sorry", "Justin Bieber", "3:20", "Purpose (Deluxe)")
    private val mvAsVideoRow = mv.copy(videoId = "mvrow000001")

    private fun target(): TrackMatcher.Target =
        TrackMatcher.audioSwitchTargetOf(KoAudioSwitch.audioSwitchRow(mv)).copy(isVideo = true)

    @Test
    fun `with the proxy the US official audio is adopted and cached`() = runBlocking {
        YtMusicRepository.audioVersionCache.remove(mv.videoId)
        val asked = mutableListOf<String>()
        val adopted = KoAudioSwitch.proxyCatalogueAudio("what do you mean justin bieber", target(), mv, proxyReady = true) {
            asked += it
            listOf(cover, mvAsVideoRow, otherSong, officialAudio)
        }
        assertEquals("FCGb0pw_wPU", adopted?.videoId)
        assertEquals(listOf("what do you mean justin bieber"), asked)
        assertEquals("FCGb0pw_wPU", YtMusicRepository.audioVersionCache[mv.videoId]?.videoId)
        YtMusicRepository.audioVersionCache.remove(mv.videoId)
    }

    @Test
    fun `without the proxy nothing is searched`() = runBlocking {
        var calls = 0
        val adopted = KoAudioSwitch.proxyCatalogueAudio("what do you mean justin bieber", target(), mv, proxyReady = false) {
            calls++
            listOf(officialAudio)
        }
        assertNull(adopted)
        assertEquals(0, calls)
    }

    @Test
    fun `a cover, a video row or another song is never adopted`() = runBlocking {
        YtMusicRepository.audioVersionCache.remove(mv.videoId)
        val adopted = KoAudioSwitch.proxyCatalogueAudio("what do you mean justin bieber", target(), mv, proxyReady = true) {
            listOf(cover, mvAsVideoRow, otherSong, mv.copy(isVideo = false))
        }
        assertNull(adopted)
        assertNull(YtMusicRepository.audioVersionCache[mv.videoId])
    }

    @Test
    fun `a failed search is thrown so the miss stays retryable`() {
        assertFailsWith<java.io.IOException> {
            runBlocking {
                KoAudioSwitch.proxyCatalogueAudio("what do you mean justin bieber", target(), mv, proxyReady = true) {
                    throw java.io.IOException("offline")
                }
            }
        }
    }
}
