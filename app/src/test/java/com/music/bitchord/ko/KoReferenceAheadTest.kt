package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPG-speed item 4: the next row's YouTube reference profile is made at
 * background decode priority from bytes already on disk, so the verify at the
 * row's first sound does not decode it at the gate first.
 */
class KoReferenceAheadTest {

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `only a row the lossless step serves, with time left`() {
        assertTrue(KoReferenceAhead.shouldPrepare(wanted = true, remainingMs = 30_000L))
        assertTrue(KoReferenceAhead.shouldPrepare(wanted = true, remainingMs = Long.MAX_VALUE))
        assertFalse("video, local, source or already lossless rows", KoReferenceAhead.shouldPrepare(false, 30_000L))
        assertFalse("inside the handoff margin", KoReferenceAhead.shouldPrepare(true, 0L))
    }

    @Test
    fun `the reference is the verify's own, from bytes in hand, at background priority`() {
        val ahead = source("playback/smart/HqAudioVerify.kt")
            .substringAfter("suspend fun prepareReferenceAhead(")
            .substringBefore("private suspend fun referenceFeatures(")
        val inHand = ahead.indexOf("if (!referenceInHand(context, youtubeId, title)) return false")
        val made = ahead.indexOf("referenceFeatures(context, youtubeId, title, manual = false, waitForOpening = false)")
        assertTrue("nothing is decoded without an opening on disk", inHand in 0 until made)
        assertTrue(ahead.contains("AudioDecoder.aheadOfTime.asContextElement(true)"))
        // No network: never the head request the song check uses.
        assertFalse(ahead.contains("awaitAnalysisHead") || ahead.contains("requestAnalysisHead"))
    }

    @Test
    fun `the bundle asks after its mix step, for a row with no lossless answer yet`() {
        val prep = source("playback/NextTrackPreparation.kt")
        val hook = prep.indexOf(
            "if (!deadlineHit && stillCurrent()) com.music.bitchord.ko.KoReferenceAhead.prepare(context, song, " +
                "hqWanted(song) && existingHq(song) == null, currentPlayer()?.let { remainingMs(it) } ?: Long.MAX_VALUE)",
        )
        assertTrue(hook > prep.indexOf("if (Step.MIX in plan.steps) {"))
        assertTrue("before the summary line", hook in 0 until prep.indexOf("val completedKey = NextPrepPolicy.completedQueueKeyAfterRun("))
    }
}
