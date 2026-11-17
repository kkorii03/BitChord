package com.music.bitchord.playback.smart

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 11-17 HQ6. Phone 11-17 19:5x, 또 하루 qmAjTFSH5SA: two
 * "match=0% offset=0 -> unmeasured" lines with no reason, because the branch
 * where the aligner returns nothing logged only the empty verdict. The aligner
 * already records why it refused (too short, silent, no path, chance level);
 * the branch must put that on the phone log so the next report can tell a
 * broken reference from a different recording. Pinned on the source because
 * the branch needs a real decoder and network.
 */
class HqVerifyNoAlignmentLogTest {

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
    }

    @Test
    fun `an aligner with no answer logs its refusal reason`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        val branch = verify.substringAfter("if (result == null) {")
            .substringBefore("return@coroutineScope Outcome.Unavailable(transient = true)")
        assertTrue(
            "the aligner-null branch must log ChromaAlignment.lastVerdict",
            branch.contains("no alignment (\${ChromaAlignment.lastVerdict})"),
        )
    }

    /**
     * OPT1 next-prep-verify-ref-before-fetch: the read-ahead (waitForOpening=false)
     * launched the candidate fetch before learning it had no reference, then
     * cancelled it. With nothing in hand the reference is asked first; a profile
     * in memory or on disk still counts as in hand, so those comparisons keep
     * running in parallel.
     */
    @Test
    fun `a read-ahead with no reference in hand does not start the candidate fetch`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        assertTrue(
            "the no-wait branch must ask the reference before fetching",
            verify.contains("val referenceFirst = !waitForOpening && !referenceInHand(context, youtubeId, target.title)") &&
                verify.contains("if (referenceFirst) null else async { candidateFetch("),
        )
        val inHand = verify.substringAfter("private fun referenceInHand(").substringBefore("private suspend fun referenceFeatures(")
        for (check in listOf("referenceCache.get(youtubeId)", "referenceFile(context, youtubeId).exists()", "VideoAudio.referenceReady(")) {
            assertTrue("referenceInHand must check $check", inHand.contains(check))
        }
        assertTrue(
            "the skipped fetch must be logged",
            verify.contains("skipped fetch, no reference yet"),
        )
    }

    /**
     * OPT1 battery-unmeasured-memo: 15 "unmeasured match=0%" downloads in 3.5
     * days, 13 of them the same head again ~61 s later (4,368,000 bytes x6). A
     * short head against a full reference, and a null alignment of two full
     * openings, are the row's own; they are remembered for this run like a bent
     * match. A null alignment against a partial reference is not.
     */
    @Test
    fun `an incomplete head or a null alignment of full openings is remembered for the run`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        val incomplete = verify.substringAfter("if (referenceComplete && !candidateComplete) {")
            .substringBefore("return@coroutineScope")
        assertTrue(
            "a short head must be noted without resting the source",
            incomplete.contains("UnmeasuredMemory.note(sourceId, trackId, \"incomplete head\", restSource = false)"),
        )
        val noAlignment = verify.substringAfter("if (result == null) {")
            .substringBefore("return@coroutineScope Outcome.Unavailable(transient = true)")
        val guarded = noAlignment.substringAfter("if (referenceComplete && candidateComplete) {", "")
        assertTrue(
            "a null alignment is noted only when both openings are complete",
            guarded.trimStart().startsWith("UnmeasuredMemory.note(sourceId, trackId, \"no alignment\", restSource = false)"),
        )
        val locked = verify.substringAfter("return@withContext lock.withLock {").substringBefore("val hearer = wordsHearer()")
        assertTrue(
            "a caller queued on the lock must re-read the unmeasured memory",
            locked.contains("UnmeasuredMemory.reason(sourceId, trackId) != null"),
        )
    }
}
