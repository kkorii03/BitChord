package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.playback.NextPrepCache
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The first-frame cache belongs to one sound, not to a video id alone. */
class NextPrepCacheKeyTest {

    private val settings = NextPrepCache.settingsKey(
        sources = setOf(LyricsSource.LRCLIB),
        order = listOf(LyricsSource.LRCLIB),
        prioritizeSyllableSync = false,
    )

    private fun ready(
        videoId: String = "video",
        localFile: Boolean = true,
        isVideo: Boolean = false,
        lyricsSettings: String = settings,
        audioSource: String? = null,
        substituted: Boolean = false,
    ) =
        NextPrepCache.Ready(
            videoId = videoId,
            raw = emptyList(),
            shown = emptyList(),
            source = null,
            searchedAs = null,
            isVideo = isVideo,
            fromFile = localFile,
            localFile = localFile,
            lyricsSettings = lyricsSettings,
            audioSource = audioSource,
            substituted = substituted,
        )

    @Test
    fun theSameTrackFromTheSameSoundMatches() {
        NextPrepCache.put(ready())
        assertNotNull(NextPrepCache.get("video", localFile = true, isVideo = false, lyricsSettings = settings, audioSource = null, substituted = false))
    }

    @Test
    fun aStreamDoesNotUseAFilePreparedCache() {
        NextPrepCache.put(ready(localFile = true))
        assertNull(NextPrepCache.get("video", localFile = false, isVideo = false, lyricsSettings = settings, audioSource = null, substituted = false))
    }

    @Test
    fun aSongDoesNotUseAVideoPreparedCache() {
        NextPrepCache.put(ready(isVideo = true))
        assertNull(NextPrepCache.get("video", localFile = true, isVideo = false, lyricsSettings = settings, audioSource = null, substituted = false))
    }

    @Test
    fun anotherTrackNeverMatches() {
        NextPrepCache.put(ready(videoId = "other"))
        assertNull(NextPrepCache.get("video", localFile = true, isVideo = false, lyricsSettings = settings, audioSource = null, substituted = false))
    }

    @Test
    fun changingTheLyricsSettingsDoesNotReuseTheOldAnswer() {
        NextPrepCache.put(ready())
        val changedOrder = NextPrepCache.settingsKey(
            sources = setOf(LyricsSource.LRCLIB),
            order = listOf(LyricsSource.KUGOU, LyricsSource.LRCLIB),
            prioritizeSyllableSync = false,
        )
        assertNull(NextPrepCache.get("video", localFile = true, isVideo = false, lyricsSettings = changedOrder, audioSource = null, substituted = false))
    }
}
