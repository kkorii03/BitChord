package com.music.bitchord.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Committed searches must not queue behind each other. A slow request used to
 * hold the next query until its network timeout, so changing a filter on a bad
 * connection showed nothing new for up to 30 seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRequestPipelineTest {

    @Test
    fun `a newer request runs while the previous call is still waiting`() = runTest {
        var newest = 1L
        val pipeline = SearchRequestPipeline(this) { newest }
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()

        pipeline.submit(1) {
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
        firstStarted.await()

        newest = 2
        pipeline.submit(2) { secondFinished.complete(Unit) }

        // If requests were serialized this would wait on releaseFirst forever.
        secondFinished.await()
        releaseFirst.complete(Unit)
    }

    @Test
    fun `a request superseded before it starts never reaches the network`() = runTest {
        var newest = 2L
        val pipeline = SearchRequestPipeline(this) { newest }
        var ran = false

        pipeline.submit(1) { ran = true }
        advanceUntilIdle()

        assertFalse(ran)
    }
}
