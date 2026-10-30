package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The next-song cache is keyed by the sound it was timed for, not only by the
 * video id: a downloaded file and the stream of the same row are different
 * clocks, and a cached file sheet must not appear on the stream's first frame.
 */
class NextPrepCacheKeyTest {

    private val lines = listOf(LyricLine(1_000L, "one"))

    private fun ready(audioSource: String?, isVideo: Boolean) = NextPrepCache.Ready(
        videoId = "video",
        raw = lines,
        shown = lines,
        source = LyricsSource.LRCLIB,
        searchedAs = null,
        isVideo = isVideo,
        fromFile = audioSource != null,
        audioSource = audioSource,
    )

    @After
    fun tearDown() {
        NextPrepCache.clear()
    }

    @Test
    fun `the file and the stream are different cache entries`() {
        val file = LyricsTimingAudioSource.file
        NextPrepCache.put(ready(file, isVideo = false))

        assertEquals("video", NextPrepCache.get("video", file, isVideo = false)?.videoId)
        assertNull(NextPrepCache.get("video", LyricsTimingAudioSource.stream, isVideo = false))
    }

    @Test
    fun `a video's own clock is not the song clock`() {
        NextPrepCache.put(ready(null, isVideo = true))

        assertEquals("video", NextPrepCache.get("video", null, isVideo = true)?.videoId)
        assertNull(NextPrepCache.get("video", LyricsTimingAudioSource.stream, isVideo = false))
    }

    @Test
    fun `another video is never returned`() {
        NextPrepCache.put(ready(LyricsTimingAudioSource.stream, isVideo = false))

        assertNull(NextPrepCache.get("other", LyricsTimingAudioSource.stream, isVideo = false))
    }

    private object LyricsTimingAudioSource {
        val file = com.music.bitchord.playback.smart.LyricsTiming.audioSourceKey("video", localFile = true)
        val stream = com.music.bitchord.playback.smart.LyricsTiming.audioSourceKey("video", localFile = false)
    }
}
