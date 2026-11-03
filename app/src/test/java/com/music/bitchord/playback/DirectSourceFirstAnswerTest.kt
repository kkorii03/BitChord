package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct Source Stream for a track that is already playing: the addon window
 * must not be spent in silence. Whichever source answers first is the one the
 * listener hears, and an addon that is still looking stays available to the
 * second look.
 */
class DirectSourceFirstAnswerTest {

    @Test
    fun `youtube starts while the addon is still looking`() = runTest {
        val addon = CompletableDeferred<SourceStream?>()
        val youtube = CompletableDeferred<Result<String>>()
        youtube.complete(Result.success("https://youtube.test/audio"))

        val answer = DirectSourceGate.firstAnswer(addon, youtube)

        assertEquals(DirectSourceGate.FirstAnswer.YouTube("https://youtube.test/audio"), answer)
        assertFalse("the addon lookup must not be awaited", addon.isCompleted)
    }

    @Test
    fun `a quick addon still wins the first note`() = runTest {
        val addon = CompletableDeferred<SourceStream?>()
        val youtube = CompletableDeferred<Result<String>>()
        addon.complete(SourceStream("https://addon.test/flac"))

        val answer = DirectSourceGate.firstAnswer(addon, youtube)

        assertEquals(
            DirectSourceGate.FirstAnswer.Addon(SourceStream("https://addon.test/flac")),
            answer,
        )
        assertFalse("youtube was never asked", youtube.isCompleted)
    }

    @Test
    fun `a failed youtube walk waits for the addon rather than failing the track`() = runTest {
        val addon = CompletableDeferred<SourceStream?>()
        val youtube = CompletableDeferred<Result<String>>()
        youtube.complete(Result.failure(java.io.IOException("refused")))
        addon.complete(SourceStream("https://addon.test/flac"))

        val answer = DirectSourceGate.firstAnswer(addon, youtube)

        assertEquals(
            DirectSourceGate.FirstAnswer.Addon(SourceStream("https://addon.test/flac")),
            answer,
        )
    }

    @Test
    fun `the next row is not playing`() = runTest {
        DirectSourceGate.onPlaying(setOf("playing"))
        try {
            assertTrue(DirectSourceGate.isPlaying("playing"))
            DirectSourceGate.openNext("next")
            assertFalse("a warmed next row must still wait for its window", DirectSourceGate.isPlaying("next"))
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }
}
