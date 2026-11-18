package com.music.bitchord.ko

import com.music.bitchord.playback.smart.AnalysisWindow
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPG-speed item 6: the next row's opening pass waits behind foreground decodes
 * (the current track's HQ verification) instead of taking the slot first.
 */
class KoAnalysisPriorityTest {

    @Test
    fun `only the next row's opening is ahead of time`() {
        assertTrue(KoAnalysisPriority.aheadOfTime(AnalysisWindow.HEAD))
        assertFalse(KoAnalysisPriority.aheadOfTime(AnalysisWindow.TAIL))
    }

    @Test
    fun `the analysis thread marks the pass and clears the mark when it ends`() {
        val root = File(".").canonicalFile
        val run = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt") }
            .first { it.exists() }
            .readText()
            .replace("\r\n", "\n")
            .substringAfter("private fun runWindow(")
            .substringBefore("private fun publishWindow(")
        val set = run.indexOf("AudioDecoder.aheadOfTime.set(com.music.bitchord.ko.KoAnalysisPriority.aheadOfTime(window))")
        val decode = run.indexOf("analyzeHead(trackId, uri, durationSeconds, headRendition)")
        assertTrue("the mark is set before the pass decodes", set in 0 until decode)
        val finally = run.substringAfter("} finally {")
        // A pool thread reused for the current track's tail must not keep the mark.
        assertTrue(finally.contains("AudioDecoder.aheadOfTime.set(false)"))
    }
}
