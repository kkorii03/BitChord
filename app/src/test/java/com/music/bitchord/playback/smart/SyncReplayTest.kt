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

/** Strict rules from `scripts/synctest.py` `score()`: every rule must hold for `pass`. */
private const val STRICT_MIN_LINES = 8
private const val STRICT_MEDIAN_ABS_MS = 120L
private const val STRICT_BIAS_MS = 80L
private const val STRICT_IN03_PCT = 95
private const val STRICT_IN02_PCT = 85
private const val STRICT_MAX_ABS_MS = 800L
private const val STRICT_COVERAGE = 0.4
private const val STRICT_DRIFT_MS_PER_MIN = 60.0
private const val STRICT_DRIFT_SPAN_MS = 90_000L

/** The loose `old` rule kept by `score()`: median <= 200 ms and 90% within 300 ms. */
private const val OLD_MEDIAN_MS = 200L
private const val OLD_IN03_PCT = 90

/** The loose rule behind `warn`: median absolute error at or below 300 ms. */
private const val WARN_MEDIAN_MS = 300L

/** A line counts as heard when this share of its letters was matched. */
private const val HEARD_SHARE = 0.5

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
        assertEquals(-225L, scores.biasMs)
        assertEquals(1_000L, scores.maxAbsMs)
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
     * A line-accuracy report over the same corpus, one stage per row: the
     * provider's sheet, the heard-words fit (`LyricsTiming.apply`), [RefinePolicy]
     * on top, and the `LyricsPerLine` layer on top of that. The same stages run
     * for the first ninety seconds of heard words, and a base fit on every other
     * anchor is scored on the anchors held out from it. Diagnostic only — no
     * assertion decides on these numbers, and a bug here must not fail the
     * baseline run.
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
    private data class LineError(
        val song: String,
        val line: Int,
        val errorMs: Long,
        val signedMs: Long = errorMs,
        val sungMs: Long = 0L,
    )

    private data class Score(
        val lines: Int,
        val medianMs: Long,
        val w200: Int,
        val w300: Int,
        val w500: Int,
        val worst: LineError?,
        val biasMs: Long = 0L,
        val maxAbsMs: Long = 0L,
        val coverage: Double? = null,
        val driftMsPerMin: Double = 0.0,
        val spanMs: Long = 0L,
    ) {
        // Strict rules copied from scripts/synctest.py score(): every rule must
        // hold for `pass`; the verdict is `warn` up to a 300 ms median.
        val strict: Boolean
            get() = lines >= STRICT_MIN_LINES &&
                medianMs <= STRICT_MEDIAN_ABS_MS &&
                abs(biasMs) <= STRICT_BIAS_MS &&
                w300 * 100 >= STRICT_IN03_PCT * lines &&
                w200 * 100 >= STRICT_IN02_PCT * lines &&
                maxAbsMs <= STRICT_MAX_ABS_MS &&
                (coverage == null || coverage >= STRICT_COVERAGE) &&
                !(spanMs >= STRICT_DRIFT_SPAN_MS && abs(driftMsPerMin) > STRICT_DRIFT_MS_PER_MIN)

        val verdict: String
            get() = when {
                lines == 0 -> "n/a"
                strict -> "pass"
                medianMs <= WARN_MEDIAN_MS -> "warn"
                else -> "fail"
            }

        /** The legacy `old_verdict` in `synctest.py`, for comparing past runs. */
        val oldVerdict: String
            get() = when {
                lines == 0 -> "n/a"
                medianMs <= OLD_MEDIAN_MS && w300 * 100 >= OLD_IN03_PCT * lines -> "pass"
                medianMs <= WARN_MEDIAN_MS -> "warn"
                else -> "fail"
            }
    }

    /** Lines heard near their shown time over lines that should have been heard. */
    private data class Coverage(val found: Int, val eligible: Int) {
        val share: Double? get() = if (eligible == 0) null else found.toDouble() / eligible
    }

    private class Tally {
        val errors = ArrayList<LineError>()
        var found = 0
        var eligible = 0

        fun add(more: List<LineError>, coverage: Coverage?) {
            errors += more
            if (coverage != null) {
                found += coverage.found
                eligible += coverage.eligible
            }
        }
    }

    private fun Tally.score(): Score =
        stat(errors, if (eligible == 0) null else found.toDouble() / eligible)

    private val fullStages = listOf("provider", "base", "refine", "perline")
    private val heldStages = listOf("provider", "base")

    private fun lineAccuracyReport(corpus: File) {
        val files = corpus.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
        val fullTallies = linkedMapOf<String, Tally>()
        val openingTallies = linkedMapOf<String, Tally>()
        val heldTallies = linkedMapOf<String, Tally>()
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
            println("${song.id} lang=${song.language}")
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

            val fullCoverage = coverageOf(indices, lines, song.truth, numbers, placement, heard, null)
            val full = snapshots(song.id, lines, heard, indices, placement, heard, spellable)
            report("full", full, fullStages, fullTallies, song, numbers, fullCoverage, null)

            // What an opening-only listen can fit, scored against the whole song.
            val opening = heard.filter { it.startMs <= OPENING_MS }
            if (opening.isNotEmpty()) {
                val openingPlacement = LyricsWordAlign.placeLinesDetailed(texts, opening)
                val openingCoverage = coverageOf(
                    indices, lines, song.truth, numbers, openingPlacement, opening, null,
                )
                val openingStages = snapshots(
                    song.id, lines, opening, indices, openingPlacement, opening, spellable,
                )
                report(
                    "first90", openingStages, fullStages, openingTallies,
                    song, numbers, openingCoverage, null,
                )
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
                val heldCoverage = coverageOf(
                    indices, lines, song.truth, numbers, placement, heard, heldHalf,
                )
                val held = snapshots(
                    song.id, lines, heard, fitHalf, halfPlacement, emptyList(), spellable,
                )
                report(
                    "held", held, heldStages, heldTallies,
                    song, numbers, heldCoverage, heldHalf,
                )
            }
        }
        println("corpus songs=$songs truth=$withTruth")
        for ((label, tally) in fullTallies) {
            println(line("totals full $label", tally.score(), withSong = true))
        }
        for ((label, tally) in openingTallies) {
            println(line("totals first90 $label", tally.score(), withSong = true))
        }
        for ((label, tally) in heldTallies) {
            println(line("totals held $label", tally.score(), withSong = true))
        }
    }

    /** One row per stage, accumulated into the variant's totals. */
    private fun report(
        tag: String,
        stages: Map<String, List<LyricLine>>,
        labels: List<String>,
        tallies: MutableMap<String, Tally>,
        song: Song,
        numbers: List<Int?>,
        coverage: Coverage?,
        only: Set<Int>?,
    ) {
        for (label in labels) {
            val errors = scoreLines(song.id, stages.getValue(label), song.truth, numbers, only)
            tallies.getOrPut(label) { Tally() }.add(errors, coverage)
            println(line("${song.id} $tag $label", stat(errors, coverage?.share)))
        }
    }

    /**
     * The app's own order as separate snapshots: provider times, the heard-words
     * fit through [LyricsTiming.apply], [RefinePolicy] on those lines, then the
     * per-line layer through [LyricsTiming.apply]. No hand correction and no HQ
     * clock, as recorded.
     */
    private fun snapshots(
        id: String,
        lines: List<LyricLine>,
        heard: List<LyricsWordAlign.Heard>,
        indices: List<Int>,
        placement: LyricsWordAlign.Placement,
        refineWords: List<LyricsWordAlign.Heard>,
        spellable: Int,
    ): Map<String, List<LyricLine>> {
        val out = LinkedHashMap<String, List<LyricLine>>()
        out["provider"] = lines
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
        val baseTiming = LyricsTiming.Timing(base = base, manualMs = 0L)
        val baseLines = LyricsTiming.apply(lines, baseTiming)
        out["base"] = baseLines
        val refine = if (refineWords.isEmpty()) {
            RefinePolicy.Result.NONE
        } else {
            RefinePolicy.compute(baseLines, refineWords)
        }
        val refineTiming = baseTiming.copy(
            refineMs = if (refine.applied) refine.medianMs else 0L,
            refineSlopePerMin = if (refine.applied) refine.slopePerMin else 0.0,
            refineUntilMs = refineWords.maxOfOrNull { it.endMs } ?: Long.MAX_VALUE,
            refineWide = refine.applied && refine.model.wide,
        )
        val refineLines = LyricsTiming.apply(lines, refineTiming)
        out["refine"] = refineLines
        val perLine = refineWords.takeIf { it.isNotEmpty() }?.let {
            LyricsTiming.PerLineWords(words = it, wordsKey = 0, currentKey = 0)
        }
        out["perline"] = if (perLine == null) {
            refineLines
        } else {
            LyricsTiming.apply(lines, refineTiming.copy(perLine = perLine))
        }
        return out
    }

    /**
     * Lines heard near their shown time over lines that should have been heard:
     * a truth line whose shown time is inside the heard audio, and whose own
     * letters were at least [HEARD_SHARE] matched by the placement.
     */
    private fun coverageOf(
        indices: List<Int>,
        lines: List<LyricLine>,
        truth: List<List<Long>>,
        numbers: List<Int?>,
        placement: LyricsWordAlign.Placement,
        heard: List<LyricsWordAlign.Heard>,
        only: Set<Int>?,
    ): Coverage? {
        if (truth.isEmpty() || heard.isEmpty()) return null
        val from = heard.minOf { it.startMs }
        val to = heard.maxOf { it.endMs }
        val position = HashMap<Int, Int>(indices.size)
        indices.forEachIndexed { p, index -> position[index] = p }
        var eligible = 0
        var found = 0
        for (row in truth) {
            if (row.size < 2) continue
            val index = lines.indices.firstOrNull { idx ->
                (numbers.getOrNull(idx) ?: (idx + 1)).toLong() == row[0]
            } ?: continue
            if (only != null && index !in only) continue
            val shown = lines[index].timeMs
            if (shown < from || shown > to) continue
            val p = position[index] ?: continue
            eligible++
            if (placement.matchedShare.getOrElse(p) { 0.0 } >= HEARD_SHARE) found++
        }
        return if (eligible == 0) null else Coverage(found, eligible)
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
            errors += LineError(song, index + 1, abs(line.timeMs - sung), line.timeMs - sung, sung)
        }
        return errors
    }

    private fun stat(errors: List<LineError>, coverage: Double? = null): Score {
        if (errors.isEmpty()) return Score(0, 0, 0, 0, 0, null, coverage = coverage)
        val values = errors.map { it.errorMs }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        val signed = errors.map { it.signedMs }.sorted()
        val bias = if (signed.size % 2 == 1) signed[middle] else (signed[middle - 1] + signed[middle]) / 2
        // scripts/synctest.py drift_slope: least squares over the signed errors,
        // lines more than 0.5 s off the median left out; fewer than 4 points
        // have no drift.
        var drift = 0.0
        var span = 0L
        if (errors.size >= 4) {
            val points = errors.filter { abs(it.signedMs - bias) <= 500L }.ifEmpty { errors }
            val ts = points.map { it.sungMs.toDouble() }
            val es = points.map { it.signedMs.toDouble() }
            val mt = ts.average()
            val me = es.average()
            val variance = ts.sumOf { (it - mt) * (it - mt) }.takeIf { it > 0.0 } ?: 1e-9
            val slope = ts.indices.sumOf { (ts[it] - mt) * (es[it] - me) } / variance
            drift = slope * 60_000.0
            span = points.maxOf { it.sungMs } - points.minOf { it.sungMs }
        }
        return Score(
            lines = values.size,
            medianMs = median,
            w200 = values.count { it <= 200L },
            w300 = values.count { it <= 300L },
            w500 = values.count { it <= 500L },
            worst = errors.maxByOrNull { it.errorMs },
            biasMs = bias,
            maxAbsMs = values.maxOrNull() ?: 0L,
            coverage = coverage,
            driftMsPerMin = drift,
            spanMs = span,
        )
    }

    private fun line(label: String, score: Score, withSong: Boolean = false): String {
        val worst = score.worst?.let {
            if (withSong) "${it.song}:${it.line}:${it.errorMs}" else "${it.line}:${it.errorMs}"
        } ?: "-"
        val coverage = score.coverage?.let { pct((it * 100).toInt(), 100) } ?: "-"
        return "$label lines=${score.lines} med=${score.medianMs} bias=${score.biasMs} " +
            "in02=${pct(score.w200, score.lines)} in03=${pct(score.w300, score.lines)} " +
            "in05=${pct(score.w500, score.lines)} max=${score.maxAbsMs} cov=$coverage " +
            "drift=${score.driftMsPerMin.toLong()} span=${score.spanMs} worst=$worst " +
            "verdict=${score.verdict} old=${score.oldVerdict}"
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
