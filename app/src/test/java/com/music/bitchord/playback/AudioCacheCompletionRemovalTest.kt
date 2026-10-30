package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A media id can have several complete cache keys. Losing one of them must not
 * erase the media's completion state while another key still holds the file,
 * or the AI timing action stays hidden on every later play and nothing
 * re-adds it.
 */
class AudioCacheCompletionRemovalTest {

    @Test
    fun `completion survives when another rendition of the media is whole`() {
        val remaining = listOf("video-1", "video-1#alt")

        val survives = AudioCache.completionSurvivesSpanRemoval(
            removedKey = "video-1",
            remainingKeys = remaining,
        ) { key -> key == "video-1#alt" }

        assertTrue(survives)
    }

    @Test
    fun `completion is dropped when no other rendition is whole`() {
        val remaining = listOf("video-1", "video-1#alt")

        val survives = AudioCache.completionSurvivesSpanRemoval(
            removedKey = "video-1",
            remainingKeys = remaining,
        ) { key -> key == "video-1" }

        assertFalse(survives)
    }

    @Test
    fun `the removed key itself never counts as still complete`() {
        val survives = AudioCache.completionSurvivesSpanRemoval(
            removedKey = "video-1",
            remainingKeys = listOf("video-1"),
        ) { true }

        assertFalse(survives)
    }
}
