package com.music.bitchord.playback.smart

import com.music.bitchord.ko.KoUnverifiedRows
import java.io.File
import org.junit.Assert.assertFalse
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

    /**
     * OPT2 unverified-refusal. Phone 11-16..17: all 15 unmeasured check downloads
     * (189 MB) were rows offered on a doubted name (artist-unverified 11, 124 MB;
     * title-unverified 4, 65 MB) that were other songs. Nothing was stored, so
     * each new run fetched the same head again. Only the aligner's figures
     * refusal of two full openings, on a doubted row, may stand as a refusal.
     */
    @Test
    fun `only a figures refusal of two full openings on a doubted row stands`() {
        val figures = "refused: matched 12% straight 30% cost 0.91 vs chance 0.99 key +3"
        assertTrue(KoUnverifiedRows.refusalStands(true, true, true, true, figures))
        assertFalse("a credited row's null stays unmeasured", KoUnverifiedRows.refusalStands(false, true, true, true, figures))
        assertFalse("a partial reference may still fill in", KoUnverifiedRows.refusalStands(true, false, true, true, figures))
        assertFalse("a short head is not an answer", KoUnverifiedRows.refusalStands(true, true, false, true, figures))
        assertFalse("a thrown aligner leaves an old verdict behind", KoUnverifiedRows.refusalStands(true, true, true, false, figures))
        for (other in listOf("refused: no coarse path", "refused: no fine path", "refused: record nearly silent",
            "refused: no contrast (baseline 0.003)", "refused: no map", "", null)) {
            assertFalse("'$other' says nothing about the row", KoUnverifiedRows.refusalStands(true, true, true, true, other))
        }
    }

    @Test
    fun `doubted rows are kept by source and track`() {
        KoUnverifiedRows.note("opt2-src", listOf("row-a", "", "row-b")) { it }
        assertTrue(KoUnverifiedRows.contains("opt2-src", "row-a"))
        assertTrue(KoUnverifiedRows.contains("opt2-src", "row-b"))
        assertFalse(KoUnverifiedRows.contains("opt2-other", "row-a"))
        assertFalse(KoUnverifiedRows.contains("opt2-src", "row-c"))
        assertFalse(KoUnverifiedRows.contains(null, "row-a"))
        assertFalse(KoUnverifiedRows.contains("opt2-src", null))
    }

    @Test
    fun `a doubted row refused by sound is stored before the unmeasured note`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        val noAlignment = verify.substringAfter("if (result == null) {")
            .substringBefore("return@coroutineScope Outcome.Unavailable(transient = true)")
        val stands = noAlignment.substringAfter("KoUnverifiedRows.refusalStands(", "")
            .substringBefore("UnmeasuredMemory.note(", "")
        for (part in listOf(
            "unverified = KoUnverifiedRows.contains(sourceId, trackId)",
            "referenceComplete = referenceComplete",
            "candidateComplete = candidateComplete",
            "alignerRan = aligned.isSuccess",
            "alignerVerdict = ChromaAlignment.lastVerdict",
            "reject(context, youtubeId, sourceId, trackId)",
            "return@coroutineScope Outcome.Mismatch",
        )) {
            assertTrue("the doubted-row refusal must carry $part", stands.contains(part))
        }
        val resolver = source("data/sources/SourceResolver.kt")
        assertTrue(
            "the search must mark the rows it admits on a doubted name",
            resolver.substringAfter("unverified += localised").substringBefore("verified + localised")
                .contains("KoUnverifiedRows.note(source.configId, localised)"),
        )
    }

    /**
     * U1121 VERIFY-1/2 (review 11-20 UPG-verify): the reference read took an
     * abandoned read's partial (an acceptance on 15-80 s stored for good; a
     * 10-15 s one made every attempt download the candidate head to end "too
     * short") and any copy's bytes as YouTube's (a blocked start measured its
     * addon stream with itself). Pinned on the source: the read needs a decoder.
     */
    @Test
    fun `the reference is the whole window of YouTube's own sound`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        val read = verify.substringAfter("private suspend fun referenceFeaturesUncached(")
            .substringBefore("private fun featuresOfWav(")
        assertTrue(
            "the read must ask for the whole window",
            read.contains("VideoAudio.songWavCopy(context, song, COMPARE_SECONDS, acceptPartial = false)"),
        )
        assertFalse("no partial may answer the reference", read.contains("acceptPartial = true"))
        val refusal = read.indexOf("if (!VideoAudio.isYouTubeOpening(song, opening)) {")
        assertTrue("the opening's clock must be tested", refusal >= 0)
        val refused = read.substring(refusal).substringBefore("val features = featuresOfWav(")
        assertTrue(refused.contains("noteNoReference(youtubeId, NoReferenceReason.NOT_YOUTUBE)"))
        assertTrue(refused.contains("return Reference.Missing(NoReferenceReason.NOT_YOUTUBE)"))
        for (kept in listOf("featuresOfWav(", "referenceCache.put(", "storeReference(")) {
            assertTrue("$kept must come after the clock test", read.indexOf(kept) > refusal)
        }
        val first = verify.substringAfter("val referenceFirst = ").substringBefore("val candidateDeferred")
        assertTrue(
            "a remembered missing reference must answer before the candidate fetch",
            first.replace(Regex("\\s+"), " ").contains("|| noReferenceRemembered(youtubeId, manual)"),
        )
        assertTrue("profiles written from a substitute are rebuilt", verify.contains("REFERENCE_DIR = \"hq_reference2\""))
        val ready = source("playback/smart/VideoAudio.kt")
            .substringAfter("internal fun referenceReady(song: Song, untilSeconds: Double): Boolean {")
            .substringBefore("/** [referenceReady]'s rule")
        assertFalse("a partial must not count as a reference in hand", ready.contains("partialKey("))
        assertTrue(ready.contains("return isYouTubeOpening(song, it)"))
        assertTrue(ready.contains("isYouTubeCopy(song.videoId, it)"))
    }
}
