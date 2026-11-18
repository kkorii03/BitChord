package com.music.bitchord.data

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.playback.NextPrepLog
import com.music.bitchord.playback.smart.LyricsTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LOG-1117 G1: the per-track summary took its lyrics only from the lyrics
 * screen, so a track played with the app in the background read `lyrics=none`
 * even when next-prep had found and timed its lyrics (145 of 157 "none" rows).
 */
class TrackDiagnosticsPreparedTest {

    @Test
    fun `a prepared track with no lyrics screen reports what next-prep found`() {
        val timing = LyricsTiming.Timing(base = LyricsTiming.Adjustment.Shift(800L, byAi = true), manualMs = 0L)
        val facts = NextPrepLog.summaryFacts(LyricsSource.LRCLIB, hasLines = true, timing = timing)!!
        TrackDiagnostics.notePreparedLyrics("prepOnly001", facts)

        val summary = TrackDiagnostics.summaryLyricsFor("prepOnly001")
        assertEquals(LyricsSource.LRCLIB.label, summary?.provider)
        assertEquals("ai", summary?.sync)
        // The lyrics engine itself never published this track.
        assertNull(TrackDiagnostics.lyricsFor("prepOnly001"))
    }

    @Test
    fun `the lyrics screen's own fact wins over next-prep's`() {
        TrackDiagnostics.notePreparedLyrics(
            "prepThenUi01",
            NextPrepLog.summaryFacts(LyricsSource.LRCLIB, hasLines = true, timing = null)!!,
        )
        TrackDiagnostics.noteLyricsSync(
            videoId = "prepThenUi01",
            provider = "AMLL TTML",
            base = "measured",
            sync = "measured",
            shiftMs = 120L,
            matchPercent = 90,
            biasMs = 0L,
            manualOffsetMs = 0L,
            video = false,
        )
        TrackDiagnostics.notePreparedLyrics(
            "prepThenUi01",
            NextPrepLog.summaryFacts(LyricsSource.LRCLIB, hasLines = true, timing = null)!!,
        )

        val summary = TrackDiagnostics.summaryLyricsFor("prepThenUi01")
        assertEquals("AMLL TTML", summary?.provider)
        assertEquals("measured", summary?.sync)
    }

    @Test
    fun `next-prep's words match the summary's vocabulary`() {
        assertNull(NextPrepLog.summaryFacts(LyricsSource.LRCLIB, hasLines = false, timing = null))
        assertEquals("none", NextPrepLog.summaryFacts(LyricsSource.LRCLIB, true, null)?.sync)
        assertEquals("file", NextPrepLog.summaryFacts(null, true, null)?.provider)
        val measured = LyricsTiming.Timing(
            base = LyricsTiming.Adjustment.Shift(-300L, byAi = false),
            manualMs = 0L,
        )
        assertEquals("measured", NextPrepLog.summaryFacts(LyricsSource.LRCLIB, true, measured)?.sync)
        val manualOnly = LyricsTiming.Timing(base = null, manualMs = 250L)
        val manual = NextPrepLog.summaryFacts(LyricsSource.LRCLIB, true, manualOnly)
        assertEquals("manual", manual?.sync)
        assertEquals(250L, manual?.manualOffsetMs)
    }

    @Test
    fun `lines the heard words moved with no base are refined, not none`() {
        // log1819: 11-18 DK_0jXPuIr0 `lyrics-sync: ... base=none ... refine=-698ms` was
        // summed up as `sync=none shift=-`; Rolling in the Deep 4ujBQOzs6Lw likewise after
        // base-choice picked the provider's own clock over the AI answer on the whole song.
        val refined = LyricsTiming.Timing(base = null, manualMs = 0L, refineMs = -698L)
        assertEquals("refined", NextPrepLog.summaryFacts(LyricsSource.LRCLIB, true, refined)?.sync)
        val sloped = LyricsTiming.Timing(base = null, manualMs = 0L, refineSlopePerMin = -474.0)
        assertEquals("refined", NextPrepLog.summaryFacts(LyricsSource.LRCLIB, true, sloped)?.sync)
        assertEquals("refined", TrackDiagnostics.syncLabel("none", 0L, refined = true))
        // A base or the listener's own correction still names itself.
        assertEquals("ai", TrackDiagnostics.syncLabel("ai", 0L, refined = true))
        assertEquals("manual", TrackDiagnostics.syncLabel("none", 250L, refined = true))
        assertEquals("none", TrackDiagnostics.syncLabel("none", 0L))
        val screen = source("app/src/main/java/com/music/bitchord/ui/LyricsController.kt")
        assertTrue(screen.contains("TrackDiagnostics.syncLabel(baseLabel, manualMs, refined = refineShift != 0L)"))
    }

    @Test
    fun `the service writes next-prep's fact and the summary reads it`() {
        val prep = source("app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt")
        assertTrue(
            "next-prep must record its lyrics for the summary",
            prep.contains("TrackDiagnostics.notePreparedLyrics(song.videoId"),
        )
        val service = source("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        val summary = service.substringAfter("private fun logTrackSummary(").substringBefore("\n    }")
        assertTrue(
            "the track summary must fall back to next-prep's fact",
            summary.contains("TrackDiagnostics.summaryLyricsFor(song.videoId)"),
        )
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        val file = if (File(root, "app/src/main").exists()) File(root, path) else File(root, path.removePrefix("app/"))
        return file.readText()
    }
}
