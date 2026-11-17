package com.music.bitchord.playback

import com.music.bitchord.ko.KoNextPrepAttempts
import com.music.bitchord.playback.NextPrepPolicy.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TR2 F3: the next-row bundle marks a step tried before it runs it. A bundle
 * cancelled in the middle of a step (the row stopped being next) left the mark,
 * so when the same sound was next again its lyrics and sync were never prepared.
 * Each case runs the step the way NextTrackPreparation.run does: marked, then
 * its CancellationException handler calls [KoNextPrepAttempts.forgetIfCancelled].
 */
class NextPrepCancelledStepTest {

    private val lock = Any()

    private fun planFor(attempted: Set<Step>) = NextPrepPolicy.plan(
        NextPrepPolicy.Request(
            rowId = "row",
            queueKey = "row|video",
            wanted = setOf(Step.LYRICS, Step.SYNC),
            attempted = attempted,
        ),
    ).steps

    private suspend fun runStep(steps: MutableSet<Step>, step: Step, bundle: Job?, body: suspend () -> Unit) {
        steps += step
        try {
            body()
        } catch (cancelled: CancellationException) {
            KoNextPrepAttempts.forgetIfCancelled(steps, lock, step, bundle)
            throw cancelled
        }
    }

    @Test
    fun `a step the bundle was cancelled in is planned again for the same sound`() = runBlocking {
        val steps = mutableSetOf<Step>()
        val inSync = CompletableDeferred<Unit>()
        val bundle = launch {
            val bundleJob = coroutineContext[Job]
            runStep(steps, Step.LYRICS, bundleJob) {}
            runStep(steps, Step.SYNC, bundleJob) {
                inSync.complete(Unit)
                awaitCancellation()
            }
        }
        inSync.await()
        // The row is replaced while SYNC waits on its audio.
        bundle.cancelAndJoin()

        // Old code kept SYNC marked: the plan for the same sound was empty.
        assertEquals(listOf(Step.SYNC), planFor(steps))
    }

    @Test
    fun `a step cut by a deadline inside a running bundle stays tried`() = runBlocking {
        val steps = mutableSetOf(Step.LYRICS)
        val bundleJob = coroutineContext[Job]
        try {
            runStep(steps, Step.SYNC, bundleJob) { withTimeout(10L) { awaitCancellation() } }
        } catch (ignored: CancellationException) {
            // The step's own timeout, not the bundle's cancel.
        }

        assertTrue(Step.SYNC in steps)
        assertEquals(emptyList<Step>(), planFor(steps))
    }

    @Test
    fun `without a bundle job nothing is taken back`() {
        val steps = mutableSetOf(Step.SYNC)
        KoNextPrepAttempts.forgetIfCancelled(steps, lock, Step.SYNC, null)
        assertTrue(Step.SYNC in steps)
    }
}
