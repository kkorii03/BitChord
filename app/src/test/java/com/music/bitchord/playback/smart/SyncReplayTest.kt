package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsAiGuard
import com.music.bitchord.data.lyrics.LyricsMismatch
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.lyrics.RefinePolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Replays recorded songs through the real fitting path, without a device.
 *
 * The corpus is built by `scripts/replay_pack.py` from phone logs and word
 * files and lives outside the repository (`SYNC_REPLAY_DIR`).  Without that
 * variable the test does nothing, so CI stays green until the corpus is wired.
 * With it, each song's sheet and heard words go through
 * [LyricsWordAlign.placeLinesDetailed], [LyricsTiming.fitHeard] (and so
 * [com.music.bitchord.data.lyrics.TimingFit] and
 * [com.music.bitchord.data.lyrics.TimingPieces]), [LyricsMismatch] and
 * [RefinePolicy].  Only ids and numbers are printed; the corpus holds lyrics.
 * A verdict is reported as numbers (`reject`, `keep`, `refine`, `pass`), and a
 * corpus song whose id the baseline does not name fails the test instead of
 * disappearing from the run.
 */
class SyncReplayTest {

    @Serializable
    private data class Word(val t: String, val s: Long, val e: Long)

    @Serializable
    private data class SheetLine(val t: Long, val text: String)

    @Serializable
    private data class Song(
        val id: String,
        val language: String,
        val sheet: List<SheetLine>,
        val heard: List<Word>,
        val expectedOffsetMs: Long,
        val baselineErrorMs: Long,
        val stress: Boolean = false,
        val mismatchRejected: Int? = null,
        val refineApplied: Int? = null,
    )

    @Serializable
    private data class BaselineSong(
        val id: String,
        val expectedOffsetMs: Long,
        val baselineErrorMs: Long,
        val stress: Int = 0,
        val toleranceMs: Long = 300,
        val mismatchRejected: Int? = null,
        val refineApplied: Int? = null,
    )

    @Serializable
    private data class Baseline(val version: Int = 1, val songs: List<BaselineSong>)

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `recorded songs stay at or above the baseline`() {
        val dir = System.getenv("SYNC_REPLAY_DIR") ?: return
        val corpus = File(dir)
        assertTrue("SYNC_REPLAY_DIR is not a directory: $dir", corpus.isDirectory)
        val baselineFile = repoFile("bench/replay/baseline.json")
        assertTrue("baseline missing: ${baselineFile.path}", baselineFile.isFile)
        val baseline = json.decodeFromString<Baseline>(baselineFile.readText())
        val byId = baseline.songs.associateBy { it.id }

        val rows = ArrayList<String>()
        val missingIds = ArrayList<String>()
        var failures = 0
        var rejected = 0
        var kept = 0
        var refined = 0
        var expectedReject = 0
        var expectedKeep = 0
        var verdictPass = 0
        var verdictKnown = 0
        corpus.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                // Never let a decode message echo a corpus line: the file holds lyrics.
                val song = try {
                    json.decodeFromString<Song>(file.readText())
                } catch (e: Exception) {
                    throw AssertionError("corpus file not readable: ${file.name} (${e.javaClass.simpleName})")
                }
                val base = byId[song.id]
                if (base == null) {
                    missingIds += song.id
                    return@forEach
                }
                val lines = song.sheet.map { LyricLine(timeMs = it.t, text = it.text) }
                val heard = song.heard.map { LyricsWordAlign.Heard(it.t, it.s, it.e) }
                val indices = lines.indices.filter { lines[it].text.isNotBlank() }
                val texts = indices.map { lines[it].text }
                val placement = LyricsWordAlign.placeLinesDetailed(texts, heard)
                val spellable = Deepgram.spellableLines(texts, song.language)
                val fitted = LyricsTiming.fitHeard(
                    song.id,
                    lines,
                    indices,
                    placement,
                    heard.maxOfOrNull { it.endMs } ?: 0L,
                    spellable,
                ) { fit, moved ->
                    LyricsAiGuard.agreementAllows(fit.matchPercent) &&
                        LyricsAiGuard.songShiftAllows(moved, fit.matchPercent)
                }
                val fitOffset = fitted?.let { LyricsTiming.shownShiftMs(lines, it) } ?: 0L
                val error = abs(fitOffset - song.expectedOffsetMs)
                val mismatch = LyricsMismatch.judgeSheet(lines, heard)
                val refine = RefinePolicy.compute(lines, heard)
                val match = fitted?.matchPercent ?: -1
                val ok = error <= base.baselineErrorMs + base.toleranceMs
                val reject = if (mismatch.rejected) 1 else 0
                val keep = 1 - reject
                val refineApplied = if (refine.applied) 1 else 0
                val pass = if (ok) 1 else 0
                val expReject = base.mismatchRejected ?: song.mismatchRejected ?: -1
                val expRefine = base.refineApplied ?: song.refineApplied ?: -1
                if (!ok) failures++
                rejected += reject
                kept += keep
                refined += refineApplied
                if (expReject == 1) expectedReject++
                if (expReject == 0) expectedKeep++
                if (expReject >= 0) {
                    verdictKnown++
                    if ((expReject == 1) == mismatch.rejected) verdictPass++
                }
                rows += "${song.id} fit=$fitOffset exp=${song.expectedOffsetMs} err=$error " +
                    "base=${base.baselineErrorMs} match=$match " +
                    "reject=$reject keep=$keep refine=$refineApplied pass=$pass " +
                    "expReject=$expReject expRefine=$expRefine"
            }

        println(
            "sync replay: songs=${rows.size} missing=${missingIds.size} " +
                "pass=${rows.size - failures} fail=$failures",
        )
        println(
            "verdict: reject=$rejected keep=$kept refine=$refined " +
                "expectedReject=$expectedReject expectedKeep=$expectedKeep " +
                "verdictPass=$verdictPass verdictKnown=$verdictKnown",
        )
        if (missingIds.isNotEmpty()) println("missing baseline: ${missingIds.joinToString(",")}")
        rows.forEach { println(it) }
        assertTrue(
            "corpus songs missing from baseline: ${missingIds.joinToString(",")}",
            missingIds.isEmpty(),
        )
        assertTrue("$failures songs below baseline", failures == 0)
    }

    /** The repository root, from the test's working directory (the module or the root). */
    private fun repoFile(path: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        return File(path)
    }
}
