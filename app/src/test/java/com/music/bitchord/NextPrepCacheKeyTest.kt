package com.music.bitchord

import com.music.bitchord.playback.NextPrepCache
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The first-frame cache belongs to one sound, not to a video id alone. */
class NextPrepCacheKeyTest {

    private fun ready(videoId: String = "video", localFile: Boolean = true, isVideo: Boolean = false) =
        NextPrepCache.Ready(
            videoId = videoId,
            raw = emptyList(),
            shown = emptyList(),
            source = null,
            searchedAs = null,
            isVideo = isVideo,
            fromFile = localFile,
            localFile = localFile,
        )

    @Test
    fun theSameTrackFromTheSameSoundMatches() {
        NextPrepCache.put(ready())
        assertNotNull(NextPrepCache.get("video", localFile = true, isVideo = false))
    }

    @Test
    fun aStreamDoesNotUseAFilePreparedCache() {
        NextPrepCache.put(ready(localFile = true))
        assertNull(NextPrepCache.get("video", localFile = false, isVideo = false))
    }

    @Test
    fun aSongDoesNotUseAVideoPreparedCache() {
        NextPrepCache.put(ready(isVideo = true))
        assertNull(NextPrepCache.get("video", localFile = true, isVideo = false))
    }

    @Test
    fun anotherTrackNeverMatches() {
        NextPrepCache.put(ready(videoId = "other"))
        assertNull(NextPrepCache.get("video", localFile = true, isVideo = false))
    }
}
