package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsPerLine
import com.music.bitchord.data.lyrics.LyricsWordAlign
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Offline experiment: should a line move only when two independent hearings agree?
 *
 * The corpus is built outside the repository (`SYNC_REPLAY_DIR`). Without that
 * variable the test does nothing. With it, each song's sheet is moved by the
 * whole-song fit ([Song.expectedOffsetMs]) and then by five per-line strategies:
 *
 * A. the whole-song fit alone;
 * B. A plus [LyricsPerLine] on `heard` (the app's current layer);
 * C. A plus [LyricsPerLine] on `heard2` (the second hearing, first 90 s only);
 * D. A plus a move only where B and C agree within 150/200/300 ms, using their
 *    mean; other lines take their agreeing neighbours or stay put;
 * E. A plus the shift from whichever hearing matched more of the line's words.
 *
 * `heard2` covers only the first 90 s, so the A-E table scores lines inside that
 * range and the remainder is reported separately for A and B. Songs are split
 * deterministically by id into development and confirmation halves. Only ids and
 * numbers are printed: the corpus holds lyrics.
 */
class SyncReplayTwoEarsTest {

    @Serializable
    private data class Word(val t: String, val s: Long, val e: Long)

    @Serializable
    private data class SheetLine(val n: Int, val t: Long, val text: String)

    @Serializable
    private data class Song(
        val id: String,
        val language: String,
        val sheet: List<SheetLine>,
        val heard: List<Word>? = null,
        val heard2: List<Word>? = null,
        val truth: List<List<Long>>? = null,
        val expectedOffsetMs: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val strategies = listOf("A", "B", "C", "D150", "D200", "D300", "E")

    @Test
    fun `two ears agree before a line moves`() {
        val dir = System.getenv("SYNC_REPLAY_DIR") ?: return
        val corpus = File(dir)
        assertTrue("SYNC_REPLAY_DIR is not a directory: $dir", corpus.isDirectory)
        val songs = corpus.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .map { file ->
                // Never let a decode message echo a corpus line: the file holds lyrics.
                try {
                    json.decodeFromString<Song>(file.readText())
                } catch (e: Exception) {
                    throw AssertionError("corpus file not readable: ${file.name} (${e.javaClass.simpleName})")
                }
            }
        val withSecond = songs.filter { !it.heard2.isNullOrEmpty() }
        val scores = withSecond.map(::evaluate)
        val out = StringBuilder()
        out.appendLine("two-ears: songs=${songs.size} with-heard2=${withSecond.size}")

        // Deterministic split: sorted by id, alternating halves.
        val sorted = scores.sortedBy { it.id }
        val dev = sorted.filterIndexed { i, _ -> i % 2 == 0 }
        val conf = sorted.filterIndexed { i, _ -> i % 2 == 1 }
        val languages = scores.map { it.language }.distinct().sorted()
        for (language in languages) {
            for ((halfName, half) in listOf("dev" to dev, "conf" to conf, "all" to sorted)) {
                val langHalf = half.filter { it.language == language }
                if (langHalf.isEmpty()) continue
                for (strategy in strategies) {
                    val m = metrics(langHalf, strategy, inside = true)
                    out.appendLine(
                        "$strategy $language $halfName lines=${m.lines} med=${m.medianAbs} " +
                            "in200=${pct(m.in200)} in300=${pct(m.in300)} in500=${pct(m.in500)} " +
                            "worst=${m.worst}@${m.worstAt} strict=${m.strict}/${m.songs}",
                    )
                }
            }
        }

        // The remainder past heard2's range: only A and B have words there.
        for (language in languages) {
            val langScores = scores.filter { it.language == language }
            for (strategy in listOf("A", "B")) {
                val m = metrics(langScores, strategy, inside = false)
                out.appendLine(
                    "rest $strategy $language lines=${m.lines} med=${m.medianAbs} " +
                        "in200=${pct(m.in200)} in300=${pct(m.in300)} in500=${pct(m.in500)} " +
                        "worst=${m.worst}@${m.worstAt}",
                )
            }
        }

        // Songs a strategy makes noticeably worse than B on the same lines.
        val worse = ArrayList<String>()
        for (score in scores) {
            val b = median(score.inside["B"].orEmpty())
            for (strategy in listOf("C", "D150", "D200", "D300", "E")) {
                val s = median(score.inside[strategy].orEmpty())
                if (s - b >= 100L) worse += "$strategy ${score.id} +${s - b}"
            }
        }
        out.appendLine("worse: ${worse.joinToString(",")}")
        println(out.toString())
    }

    /** A plain-number check of the two helpers the experiment depends on. */
    @Test
    fun `strategy helpers use plain numbers`() {
        val lines = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L).map { LyricLine(it, "x") }
        val b = listOf(0L, 100L, 0L, 100L, 0L)
        val c = listOf(0L, 120L, 0L, 500L, 0L)
        val d = strategyD(b, c, 150L, lines)
        // Lines 0, 1, 2, 4 agree; line 3 disagrees and interpolates between 2 and 4.
        assertTrue(d == listOf(0L, 110L, 0L, 0L, 0L))
        val good = listOf(0L to 0L, 1_000L to 50L, 2_000L to 50L, 3_000L to 50L,
            4_000L to 50L, 5_000L to 50L, 6_000L to 50L, 7_000L to 50L)
        assertTrue(strictPass(good, 8))
        assertTrue(!strictPass(good, 100)) // coverage 8/100
    }

    private class SongScore(
        val id: String,
        val language: String,
        val inside: Map<String, List<Long>>,
        val remainder: Map<String, List<Long>>,
        val strict: Map<String, Boolean>,
        val worst: Map<String, Long>,
        val worstAt: Map<String, String>,
        val restWorst: Map<String, Long>,
        val restWorstAt: Map<String, String>,
    )

    private fun evaluate(song: Song): SongScore {
        val lines = song.sheet.map { LyricLine(timeMs = it.t + song.expectedOffsetMs, text = it.text) }
        val heard = song.heard.orEmpty().map { LyricsWordAlign.Heard(it.t, it.s, it.e) }
        val heard2 = song.heard2.orEmpty().map { LyricsWordAlign.Heard(it.t, it.s, it.e) }
        val b = LyricsPerLine.compute(lines, heard, null, null).shiftsMs
        val c = LyricsPerLine.compute(lines, heard2, null, null).shiftsMs
        val shifts = linkedMapOf(
            "A" to List(lines.size) { 0L },
            "B" to b,
            "C" to c,
            "D150" to strategyD(b, c, 150L, lines),
            "D200" to strategyD(b, c, 200L, lines),
            "D300" to strategyD(b, c, 300L, lines),
            "E" to strategyE(b, c, lines, heard, heard2),
        )
        val rangeEnd = minOf(90_000L, heard2.maxOfOrNull { it.endMs } ?: 0L)
        val indexByN = HashMap<Int, Int>(song.sheet.size)
        song.sheet.forEachIndexed { i, line -> indexByN[line.n] = i }
        val inside = shifts.keys.associateWith { ArrayList<Long>() }
        val insidePoints = shifts.keys.associateWith { ArrayList<Pair<Long, Long>>() }
        val remainder = shifts.keys.associateWith { ArrayList<Long>() }
        val worst = shifts.keys.associateWithTo(HashMap()) { 0L }
        val worstAt = shifts.keys.associateWithTo(HashMap()) { "" }
        val restWorst = shifts.keys.associateWithTo(HashMap()) { 0L }
        val restWorstAt = shifts.keys.associateWithTo(HashMap()) { "" }
        for (entry in song.truth.orEmpty()) {
            if (entry.size < 2) continue
            val n = entry[0].toInt()
            val sung = entry[1]
            val index = indexByN[n] ?: continue
            val base = lines[index].timeMs
            val isInside = sung <= rangeEnd
            for ((name, shift) in shifts) {
                val err = abs(base + shift[index] - sung)
                if (isInside) {
                    inside[name]!!.add(err)
                    insidePoints[name]!!.add(sung to err)
                    if (err > worst[name]!!) {
                        worst[name] = err
                        worstAt[name] = "${song.id}:$n"
                    }
                } else {
                    remainder[name]!!.add(err)
                    if (err > restWorst[name]!!) {
                        restWorst[name] = err
                        restWorstAt[name] = "${song.id}:$n"
                    }
                }
            }
        }
        val eligible = lines.count { it.text.isNotBlank() && it.timeMs in 0..rangeEnd }
        val strict = shifts.keys.associateWith { name ->
            strictPass(insidePoints[name]!!, eligible)
        }
        return SongScore(
            id = song.id,
            language = song.language,
            inside = inside,
            remainder = remainder,
            strict = strict,
            worst = worst,
            worstAt = worstAt,
            restWorst = restWorst,
            restWorstAt = restWorstAt,
        )
    }

    /**
     * D: a line moves by the mean of B and C only when they agree within
     * [threshold]; the rest take their agreeing neighbours (interpolated when
     * both sides are close, else the nearest within [LyricsPerLine.HOLD_RANGE_MS])
     * or stay put.
     */
    private fun strategyD(b: List<Long>, c: List<Long>, threshold: Long, lines: List<LyricLine>): List<Long> {
        val n = b.size
        val agree = BooleanArray(n) { abs(b[it] - c[it]) <= threshold }
        val out = LongArray(n)
        for (i in 0 until n) if (agree[i]) out[i] = (b[i] + c[i]) / 2
        for (i in 0 until n) {
            if (agree[i]) continue
            var before = -1
            for (j in i - 1 downTo 0) if (agree[j]) { before = j; break }
            var after = -1
            for (j in i + 1 until n) if (agree[j]) { after = j; break }
            out[i] = when {
                before >= 0 && after >= 0 -> {
                    val span = lines[after].timeMs - lines[before].timeMs
                    if (span in 0..LyricsPerLine.MAX_INTERPOLATE_SPAN_MS &&
                        abs(out[after] - out[before]) < LyricsPerLine.MAX_INTERPOLATE_DIFF_MS
                    ) {
                        if (span <= 0L) {
                            out[before]
                        } else {
                            val fraction = (lines[i].timeMs - lines[before].timeMs).toDouble() / span
                            (out[before] + (out[after] - out[before]) * fraction).roundToLong()
                        }
                    } else {
                        nearest(out, before, after, i, lines)
                    }
                }
                before >= 0 -> if (abs(lines[before].timeMs - lines[i].timeMs) <= LyricsPerLine.HOLD_RANGE_MS) out[before] else 0L
                after >= 0 -> if (abs(lines[after].timeMs - lines[i].timeMs) <= LyricsPerLine.HOLD_RANGE_MS) out[after] else 0L
                else -> 0L
            }
        }
        return out.toList()
    }

    private fun nearest(out: LongArray, before: Int, after: Int, at: Int, lines: List<LyricLine>): Long {
        val toBefore = abs(lines[at].timeMs - lines[before].timeMs)
        val toAfter = abs(lines[at].timeMs - lines[after].timeMs)
        val pick = if (toBefore <= toAfter) before else after
        return if (minOf(toBefore, toAfter) <= LyricsPerLine.HOLD_RANGE_MS) out[pick] else 0L
    }

    /** E: the shift from whichever hearing matched more of the line's words. */
    private fun strategyE(
        b: List<Long>,
        c: List<Long>,
        lines: List<LyricLine>,
        heard: List<LyricsWordAlign.Heard>,
        heard2: List<LyricsWordAlign.Heard>,
    ): List<Long> {
        val texts = lines.map { it.text }
        val matchedB = LyricsWordAlign.matchedWords(texts, heard)
        val matchedC = LyricsWordAlign.matchedWords(texts, heard2)
        return lines.indices.map { i ->
            if (matchedC[i].size > matchedB[i].size) c[i] else b[i]
        }
    }

    private data class Metrics(
        val lines: Int,
        val medianAbs: Long,
        val in200: Double,
        val in300: Double,
        val in500: Double,
        val worst: Long,
        val worstAt: String,
        val strict: Int,
        val songs: Int,
    )

    private fun metrics(scores: List<SongScore>, strategy: String, inside: Boolean): Metrics {
        val errors = ArrayList<Long>()
        var worst = 0L
        var worstAt = ""
        var strict = 0
        for (score in scores) {
            val list = if (inside) score.inside[strategy].orEmpty() else score.remainder[strategy].orEmpty()
            errors += list
            if (inside) {
                val w = score.worst[strategy] ?: 0L
                if (w > worst) {
                    worst = w
                    worstAt = score.worstAt[strategy].orEmpty()
                }
                if (score.strict[strategy] == true) strict++
            } else {
                val w = score.restWorst[strategy] ?: 0L
                if (w > worst) {
                    worst = w
                    worstAt = score.restWorstAt[strategy].orEmpty()
                }
            }
        }
        if (errors.isEmpty()) return Metrics(0, 0, 0.0, 0.0, 0.0, 0, "", 0, scores.size)
        val absErrs = errors.map { abs(it) }
        return Metrics(
            lines = errors.size,
            medianAbs = median(absErrs),
            in200 = absErrs.count { it <= 200L }.toDouble() / absErrs.size,
            in300 = absErrs.count { it <= 300L }.toDouble() / absErrs.size,
            in500 = absErrs.count { it <= 500L }.toDouble() / absErrs.size,
            worst = worst,
            worstAt = worstAt,
            strict = strict,
            songs = scores.size,
        )
    }

    /**
     * synctest's strict rules on the scored lines: at least 8 lines, coverage
     * 40%, no drift past 60 ms/min over 90 s, median |err| <= 120 ms, |bias| <=
     * 80 ms, 95% within 300 ms, 85% within 200 ms, no line past 800 ms.
     */
    private fun strictPass(points: List<Pair<Long, Long>>, eligible: Int): Boolean {
        if (points.size < 8) return false
        val errs = points.map { it.second }
        val absErrs = errs.map { abs(it) }
        val medAbs = median(absErrs)
        val medSigned = median(errs)
        val in02 = absErrs.count { it <= 200L }.toDouble() / absErrs.size
        val in03 = absErrs.count { it <= 300L }.toDouble() / absErrs.size
        val maxAbs = absErrs.maxOrNull() ?: 0L
        val coverage = if (eligible > 0) points.size.toDouble() / eligible else 1.0
        val (slope, span) = driftSlope(points)
        return medAbs <= 120L &&
            abs(medSigned) <= 80L &&
            in03 >= 0.95 &&
            in02 >= 0.85 &&
            maxAbs <= 800L &&
            coverage >= 0.4 &&
            !(span >= 90_000L && abs(slope * 60_000.0) > 60.0)
    }

    /** Error change per millisecond (least squares, lines > 500 ms off the median left out). */
    private fun driftSlope(points: List<Pair<Long, Long>>): Pair<Double, Long> {
        if (points.size < 4) return 0.0 to 0L
        val med = median(points.map { it.second })
        val kept = points.filter { abs(it.second - med) <= 500L }.ifEmpty { points }
        val mt = kept.map { it.first }.average()
        val me = kept.map { it.second }.average()
        var num = 0.0
        var den = 0.0
        for ((t, e) in kept) {
            num += (t - mt) * (e - me)
            den += (t - mt) * (t - mt)
        }
        val slope = if (den == 0.0) 0.0 else num / den
        val span = kept.maxOf { it.first } - kept.minOf { it.first }
        return slope to span
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun pct(value: Double): String = "${(value * 100).roundToLong()}%"
}
