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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** The opening a measured song's first pass hears. */
private const val OPENING_MS = 90_000L

/** Strict synctest: half the lines inside 200 ms, 80% inside 300 ms, 95% inside 500 ms. */
private const val STRICT_MEDIAN_MS = 200L
private const val STRICT_WITHIN_300_PCT = 80
private const val STRICT_WITHIN_500_PCT = 95

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
    private data class SheetLine(val n: Int? = null, val t: Long, val text: String)

    @Serializable
    private data class Song(
        val id: String,
        val language: String,
        val sheet: List<SheetLine>,
        val heard: List<Word>,
        /** `[line, sung ms]` from the independent score; see `scripts/replay_pack.py`. */
        val truth: List<List<Long>> = emptyList(),
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
    fun `line score reports median shares and the worst line`() {
        val lines = listOf(
            LyricLine(timeMs = 1_000, text = "a"),
            LyricLine(timeMs = 2_000, text = "b"),
            LyricLine(timeMs = 3_000, text = "c"),
            LyricLine(timeMs = 4_000, text = "d"),
        )
        val truth = listOf(
            listOf(1L, 1_150L),
            listOf(2L, 2_100L),
            listOf(3L, 3_300L),
            listOf(4L, 5_000L),
        )
        val scores = stat(scoreLines("song", lines, truth, listOf(1, 2, 3, 4)))
        assertEquals(4, scores.lines)
        assertEquals(225L, scores.medianMs)
        assertEquals(2, scores.w200)
        assertEquals(3, scores.w300)
        assertEquals(3, scores.w500)
        assertEquals(4, scores.worst?.line)
        assertEquals(1_000L, scores.worst?.errorMs)
        assertFalse(scores.strict)
    }

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

    /**
     * A line-accuracy report over the same corpus: the whole heard words, the
     * first ninety seconds of them, and a fit on half the lines scored on the
     * other half. Diagnostic only — no assertion decides on these numbers, and
     * a bug here must not fail the baseline run.
     */
    @Test
    fun `line accuracy report is printed`() {
        val dir = System.getenv("SYNC_REPLAY_DIR") ?: return
        try {
            lineAccuracyReport(File(dir))
        } catch (e: Exception) {
            println("line accuracy: skipped (${e.javaClass.simpleName})")
        }
    }

    /** A scored line: which song, which 1-based sheet line, and how far off it ended up. */
    private data class LineError(val song: String, val line: Int, val errorMs: Long)

    private data class Score(
        val lines: Int,
        val medianMs: Long,
        val w200: Int,
        val w300: Int,
        val w500: Int,
        val worst: LineError?,
    ) {
        // Strict synctest: half the lines inside 200 ms, 80% inside 300 ms and
        // 95% inside 500 ms. Named here so the report's verdict has a definition.
        val strict: Boolean
            get() = lines > 0 &&
                medianMs <= STRICT_MEDIAN_MS &&
                w300 * 100 >= STRICT_WITHIN_300_PCT * lines &&
                w500 * 100 >= STRICT_WITHIN_500_PCT * lines
    }

    private fun lineAccuracyReport(corpus: File) {
        val files = corpus.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
        val allFull = ArrayList<LineError>()
        val allOpening = ArrayList<LineError>()
        val allHeld = ArrayList<LineError>()
        var songs = 0
        var withTruth = 0
        for (file in files) {
            // A decode message would echo corpus text, so only the exception's shape is printed.
            val song = try {
                json.decodeFromString<Song>(file.readText())
            } catch (e: Exception) {
                println("${file.nameWithoutExtension} unreadable=${e.javaClass.simpleName}")
                continue
            }
            songs++
            if (song.truth.isEmpty()) {
                println("${song.id} truth=0")
                continue
            }
            withTruth++
            val lines = song.sheet.map { LyricLine(timeMs = it.t, text = it.text) }
            val heard = song.heard.map { LyricsWordAlign.Heard(it.t, it.s, it.e) }
            val numbers = song.sheet.map { it.n }
            val indices = lines.indices.filter { lines[it].text.isNotBlank() }
            val texts = indices.map { lines[it].text }
            val spellable = Deepgram.spellableLines(texts, song.language)
            val placement = LyricsWordAlign.placeLinesDetailed(texts, heard)

            val full = play(song.id, lines, heard, indices, placement, heard, spellable)
            val fullErrors = scoreLines(song.id, full, song.truth, numbers)
            allFull += fullErrors
            println(line("${song.id} full", stat(fullErrors)))

            // What an opening-only listen can fit, scored against the whole song.
            val opening = heard.filter { it.startMs <= OPENING_MS }
            if (opening.isNotEmpty()) {
                val openingPlacement = LyricsWordAlign.placeLinesDetailed(texts, opening)
                val openingLines = play(song.id, lines, opening, indices, openingPlacement, opening, spellable)
                val openingErrors = scoreLines(song.id, openingLines, song.truth, numbers)
                allOpening += openingErrors
                println(line("${song.id} first90", stat(openingErrors)))
            }

            // Fit on every other anchor, score the lines held out from it.
            val position = HashMap<Int, Int>(indices.size)
            indices.forEachIndexed { index, line -> position[line] = index }
            val fitHalf = indices.filterIndexed { index, _ -> index % 2 == 0 }
            val heldHalf = indices.filterIndexed { index, _ -> index % 2 == 1 }.toSet()
            if (fitHalf.size >= 3 && heldHalf.size >= 3) {
                val halfPlacement = LyricsWordAlign.Placement(
                    starts = fitHalf.map { placement.starts[position.getValue(it)] },
                    attempted = placement.attempted,
                    matchedShare = fitHalf.map {
                        placement.matchedShare.getOrElse(position.getValue(it)) { 0.0 }
                    },
                )
                val heldLines = play(song.id, lines, heard, fitHalf, halfPlacement, emptyList(), spellable)
                val heldErrors = scoreLines(song.id, heldLines, song.truth, numbers, heldHalf)
                allHeld += heldErrors
                println(line("${song.id} held", stat(heldErrors)))
            }
        }
        println("corpus songs=$songs truth=$withTruth")
        println(line("totals full", stat(allFull), withSong = true))
        println(line("totals first90", stat(allOpening), withSong = true))
        println(line("totals held", stat(allHeld), withSong = true))
    }

    /**
     * The app's own order: provider times, the heard-words fit, the applied base,
     * [RefinePolicy] on those lines, then LyricsPerLine through
     * [LyricsTiming.apply]. No hand correction and no HQ clock, as recorded.
     */
    private fun play(
        id: String,
        lines: List<LyricLine>,
        heard: List<LyricsWordAlign.Heard>,
        indices: List<Int>,
        placement: LyricsWordAlign.Placement,
        refineWords: List<LyricsWordAlign.Heard>,
        spellable: Int,
    ): List<LyricLine> {
        val base = LyricsTiming.fitHeard(
            id,
            lines,
            indices,
            placement,
            heard.maxOfOrNull { it.endMs } ?: 0L,
            spellable,
        ) { fit, moved ->
            LyricsAiGuard.agreementAllows(fit.matchPercent) &&
                LyricsAiGuard.songShiftAllows(moved, fit.matchPercent)
        }
        val baseOnly = LyricsTiming.Timing(base = base, manualMs = 0L)
        val refine = if (refineWords.isEmpty()) {
            RefinePolicy.Result.NONE
        } else {
            RefinePolicy.compute(LyricsTiming.apply(lines, baseOnly), refineWords)
        }
        val perLine = refineWords.takeIf { it.isNotEmpty() }?.let {
            LyricsTiming.PerLineWords(words = it, wordsKey = 0, currentKey = 0)
        }
        val timing = LyricsTiming.Timing(
            base = base,
            manualMs = 0L,
            refineMs = if (refine.applied) refine.medianMs else 0L,
            refineSlopePerMin = if (refine.applied) refine.slopePerMin else 0.0,
            refineUntilMs = refineWords.maxOfOrNull { it.endMs } ?: Long.MAX_VALUE,
            refineWide = refine.applied && refine.model.wide,
            perLine = perLine,
        )
        return LyricsTiming.apply(lines, timing)
    }

    /** Each shown line's distance from the independent score's sung time, ms. */
    private fun scoreLines(
        song: String,
        adjusted: List<LyricLine>,
        truth: List<List<Long>>,
        numbers: List<Int?>,
        only: Set<Int>? = null,
    ): List<LineError> {
        if (truth.isEmpty()) return emptyList()
        val sungByLine = HashMap<Long, Long>(truth.size)
        for (row in truth) {
            if (row.size >= 2) sungByLine[row[0]] = row[1]
        }
        val errors = ArrayList<LineError>(truth.size)
        adjusted.forEachIndexed { index, line ->
            if (only != null && index !in only) return@forEachIndexed
            if (line.text.isBlank()) return@forEachIndexed
            val number = (numbers.getOrNull(index) ?: (index + 1)).toLong()
            val sung = sungByLine[number] ?: return@forEachIndexed
            errors += LineError(song, index + 1, abs(line.timeMs - sung))
        }
        return errors
    }

    private fun stat(errors: List<LineError>): Score {
        if (errors.isEmpty()) return Score(0, 0, 0, 0, 0, null)
        val values = errors.map { it.errorMs }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        return Score(
            lines = values.size,
            medianMs = median,
            w200 = values.count { it <= 200L },
            w300 = values.count { it <= 300L },
            w500 = values.count { it <= 500L },
            worst = errors.maxByOrNull { it.errorMs },
        )
    }

    private fun line(label: String, score: Score, withSong: Boolean = false): String {
        val worst = score.worst?.let {
            if (withSong) "${it.song}:${it.line}:${it.errorMs}" else "${it.line}:${it.errorMs}"
        } ?: "-"
        val verdict = when {
            score.lines == 0 -> "n/a"
            score.strict -> "pass"
            else -> "fail"
        }
        return "$label lines=${score.lines} med=${score.medianMs} " +
            "w200=${pct(score.w200, score.lines)} w300=${pct(score.w300, score.lines)} " +
            "w500=${pct(score.w500, score.lines)} worst=$worst strict=$verdict"
    }

    private fun pct(part: Int, whole: Int): String =
        if (whole == 0) "0%" else "${(part * 100 + whole / 2) / whole}%"

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
