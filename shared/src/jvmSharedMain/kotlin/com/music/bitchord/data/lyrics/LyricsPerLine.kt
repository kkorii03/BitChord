package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * A residual correction for a single line, learned from the words the app
 * already heard for this song. The whole-song refine moves a constant offset
 * or a drift; this layer moves only the lines the transcript clearly heard in
 * a different place, and lets the lines between them follow.
 *
 * Pure, so the rule can be checked without Android, a transcript or the disk.
 * A line is an anchor only when at least [MIN_ANCHOR_WORDS] heard words matched
 * it in order and those words agree on its start to within
 * [MAX_ANCHOR_MAD_MS]. Its residual is the median of its words' times (each
 * stepped back by its offset inside the line) less the line's time after the
 * whole-song map, so one misheard word cannot set the move.
 */
object LyricsPerLine {

    /** A line needs this many heard words in order before it may move on its own. */
    const val MIN_ANCHOR_WORDS = 2

    /** Words that scatter more than this around their line are not one placement. */
    const val MAX_ANCHOR_MAD_MS = 120L

    /** A residual this small is not worth moving the line for. */
    const val MIN_APPLY_MS = 40L

    /** A line heard through three or more words can be an arrangement's worth out. */
    const val MAX_SHIFT_MANY_WORDS_MS = 4_000L

    /** With only two words, a large move is more likely a mishearing than a real one. */
    const val MAX_SHIFT_TWO_WORDS_MS = 600L

    /** A block of anchors this short that disagrees with both neighbours is a mishearing, not a move. */
    const val MAX_LONE_BLOCK = 3
    const val MIN_LONE_BLOCK_MS = 1_200L
    const val MAX_LONE_BLOCK_MS = 4_000L

    /** This share of a song's anchors within this of their middle says the sheet is right; see [withoutStrays]. */
    const val GOOD_SHEET_SHARE = 0.9
    const val GOOD_SHEET_WITHIN_MS = 250L
    const val MIN_GOOD_SHEET_ANCHORS = 8

    /** A sheet heard this close to where it is shown, on this share of its lines, is right. */
    const val GOOD_SHEET_MOVE_MS = 150L
    const val GOOD_SHEET_MOVE_SHARE = 0.8
    /** Fewer lines than this is not a song-wide answer. */
    const val MIN_GOOD_SHEET_LINES = 8

    /**
     * A sheet that is a transcript of this very recording (Gemini's) has the sung words
     * as its text, so a line whose letters were mostly heard (this share) may be placed
     * by where its opening was heard even when the transcript split it into fewer than
     * [MIN_ANCHOR_WORDS] words or spread them over [MAX_ANCHOR_MAD_MS].
     *
     * Gemini stamps each line on its own, so its errors are a line's or a few lines' worth,
     * 1-3 s, in no pattern (11-08 n3 4Jo__IP-Vdk: 5 lines in a row 1.2-3.0 s late, single
     * lines 1.9-2.4 s off between right ones). Where the whole-song alignment placed such a
     * line is where it was sung (within 70 ms on 27 of its 30 scored lines), so the move
     * may reach [MAX_OWN_TRANSCRIPT_MOVE_MS], and a placed line is not taken for a mishearing
     * because its neighbours moved less ([withoutLoneBlocks], [loneAnchorDisagrees]).
     */
    const val MIN_OWN_TRANSCRIPT_SHARE = 0.6

    /** How far a placed transcript line may move; past it the line keeps its word anchor and every guard (11-02 RrfF9cuJuQU: three lines heard 3.5 s early, sung on time). */
    const val MAX_OWN_TRANSCRIPT_MOVE_MS = 3_000L

    /** See [firstWordSnaps]: what a line's opening must show before the line starts on its first heard word. */
    const val MIN_FIRST_WORD_SHARE = 0.6
    const val MIN_FIRST_WORD_COVER = 0.5
    /** Past this from the fitted line, a first word is another line's or a mishearing (replay: 1.5 s keeps every true jitter). */
    const val MAX_FIRST_WORD_MOVE_MS = 1_500L
    /** A repeated line's opening may be the other copy's (replay 11-09: w8P-BIQU1qI, eqKhhG4FVoQ, l4Z9kkYAggc moved 0.7-1.2 s wrong). */
    const val MAX_REPEATED_FIRST_WORD_MOVE_MS = 500L
    /** A snap this far from both agreeing neighbours is an opening heard elsewhere (replay: lga4m0pWicc line 11, +1.1 s). */
    const val MAX_LONE_FIRST_WORD_DIFF_MS = 800L

    /** Two anchors further apart than this do not interpolate between themselves. */
    const val MAX_INTERPOLATE_SPAN_MS = 30_000L

    /** Anchors whose residuals differ by more than this do not interpolate either. */
    const val MAX_INTERPOLATE_DIFF_MS = 400L

    /** Otherwise a line takes the nearest anchor's residual when it is this close. */
    const val HOLD_RANGE_MS = 3_000L

    /** The neighbours a lone anchor is judged against may be this far away. */
    const val LONE_ANCHOR_NEAR_MS = 8_000L

    /** No move outside this much beyond the heard words. */
    const val OUTSIDE_HEARD_RANGE_MS = 8_000L

    /** Lines never start less than this after the one before them. */
    const val MIN_LINE_GAP_MS = 50L

    /** A line's sung pace against the nominal 180 ms a letter: 45 ms to 360 ms a letter. */
    const val MIN_PACE = 0.25
    const val MAX_PACE = 2.0

    /**
     * How fast [words] were sung against their nominal offsets: the median slope
     * over every pair (Theil–Sen), so one misheard word cannot set the pace.
     * 1.0 when no two words sit at different offsets.
     */
    private fun pace(words: List<LyricsWordAlign.MatchedWord>): Double {
        val slopes = ArrayList<Double>()
        for (a in words.indices) for (b in a + 1 until words.size) {
            val dOffset = words[b].offsetMs - words[a].offsetMs
            if (dOffset != 0L) slopes += (words[b].word.startMs - words[a].word.startMs).toDouble() / dOffset
        }
        if (slopes.isEmpty()) return 1.0
        slopes.sort()
        val mid = slopes.size / 2
        return if (slopes.size % 2 == 1) slopes[mid] else (slopes[mid - 1] + slopes[mid]) / 2
    }

    /** What [compute] decided for one song: each line's move and the figures for the log. */
    data class Layer(
        val shiftsMs: List<Long>,
        val anchors: Int,
        val applied: Int,
        val maxAbsMs: Long,
        /** Lines started on their first heard word ([firstWordSnaps]). */
        val snapped: Int = 0,
    ) {
        companion object {
            fun identity(lines: Int): Layer = Layer(List(lines) { 0L }, 0, 0, 0L)
        }
    }

    /**
     * [placed]: from where a transcript sheet's line was heard ([addPlacedLines]), not from its words.
     * [opening]: the heard word a first-word snap ([firstWordSnaps]) starts the line on, else -1.
     */
    private class Anchor(val line: Int, val timeMs: Long, val residualMs: Long, val placed: Boolean = false, val opening: Int = -1)

    /**
     * The per-line moves for [lines] (already after base, HQ and refine) from
     * [heard] (on the same clock). [heardAudioKey] is the rendition those words
     * were heard on; when it is not [currentAudioKey] the layer does nothing.
     */
    /** Whether the provider itself states when each word falls, on most of the sheet's sung lines. */
    fun statesItsOwnWords(lines: List<LyricLine>): Boolean {
        val sung = lines.filter { it.text.isNotBlank() }
        return sung.isNotEmpty() && sung.count { it.words.isNotEmpty() && !it.wordsEstimated } * 2 >= sung.size
    }

    fun compute(
        lines: List<LyricLine>,
        heard: List<LyricsWordAlign.Heard>,
        heardAudioKey: Int?,
        currentAudioKey: Int?,
        /** [lines] are a transcript of the recording heard; see [MIN_OWN_TRANSCRIPT_SHARE]. */
        ownTranscript: Boolean = false,
    ): Layer {
        if (heardAudioKey != currentAudioKey) return Layer.identity(lines.size)
        if (lines.isEmpty() || heard.isEmpty()) return Layer.identity(lines.size)
        // The words are expected on the same clock as the lines: LyricsTiming
        // converts them from the rendition they were heard on, and drops them
        // when no verified map can carry them onto the playing clock.
        val matched = LyricsWordAlign.matchedWords(lines.map { it.text }, heard)
        val found = ArrayList<Anchor>(lines.size)
        for (index in lines.indices) {
            val words = matched[index]
            if (words.size < MIN_ANCHOR_WORDS) continue
            // The offsets assume 180 ms a letter; English is often sung at half
            // that, which scattered every English line by seconds (11-03 Grenade:
            // 'perline anchors=0'). The line's own pace, read from its words,
            // scales them instead. Two words always fit their own pace, so an
            // implausible one means a mishearing rather than a fast singer.
            val rawPace = pace(words)
            if (words.size < 3 && rawPace !in MIN_PACE..MAX_PACE) continue
            val pace = rawPace.coerceIn(MIN_PACE, MAX_PACE)
            // The heard words are the lead's, so they are held against the lead's first
            // word: a stamp set by a backing vocal ahead of it is not the line being early
            // (11-06 w58uXpyHWes: a line 884 ms ahead moved its lead 884 ms late).
            val leadStart = lines[index].words.firstOrNull()?.startMs ?: lines[index].timeMs
            val residuals = words.map { word ->
                word.word.startMs - (word.offsetMs * pace).roundToLong() - leadStart
            }
            val median = median(residuals)
            val mad = median(residuals.map { abs(it - median) })
            if (mad > MAX_ANCHOR_MAD_MS) continue
            val cap = if (words.size >= 3) MAX_SHIFT_MANY_WORDS_MS else MAX_SHIFT_TWO_WORDS_MS
            // Beyond the cap the residual is not a line that moved: three words
            // agreeing that far out are almost always the same words heard at a
            // repeated chorus, and moving the line by the cap would be a wrong
            // move rather than a partial one (Opus review, 11-04). Drop the
            // anchor and let its neighbours interpolate.
            val residual = median(residuals)
            if (abs(residual) > cap) continue
            found += Anchor(index, lines[index].timeMs, residual)
        }
        if (ownTranscript) addPlacedLines(lines, heard, found)
        val letters = lines.map { LyricsWordAlign.fold(it.text).length }
        val anchors = withoutStrays(withoutLoneBlocks(sungInOrder(found, letters)))
        // A transcript sheet's lines are placed by addPlacedLines, from the same openings.
        val snapped = if (ownTranscript) emptyList() else firstWordSnaps(lines, heard, letters)
        if (anchors.isEmpty() && snapped.isEmpty()) return Layer.identity(lines.size)

        val heardFrom = heard.minOf { it.startMs }
        val heardTo = heard.maxOf { it.endMs }
        val anchorAt = HashMap<Int, Anchor>(anchors.size)
        for (anchor in anchors) anchorAt[anchor.line] = anchor
        val shifts = LongArray(lines.size)
        var previousAnchor = 0
        for (index in lines.indices) {
            while (previousAnchor < anchors.size && anchors[previousAnchor].line < index) {
                previousAnchor++
            }
            val own = anchorAt[index]
            val before = anchors.getOrNull(previousAnchor - 1)?.takeIf { it.line < index }
            val after = if (own != null) {
                anchors.getOrNull(previousAnchor + 1)?.takeIf { it.line > index }
            } else {
                anchors.getOrNull(previousAnchor)?.takeIf { it.line > index }
            }
            val lineTime = lines[index].timeMs
            val target = when {
                lineTime < heardFrom - OUTSIDE_HEARD_RANGE_MS ||
                    lineTime > heardTo + OUTSIDE_HEARD_RANGE_MS -> 0L
                own != null && !loneAnchorDisagrees(own, before, after, lineTime) -> own.residualMs
                before != null && after != null -> {
                    val span = after.timeMs - before.timeMs
                    if (span in 0..MAX_INTERPOLATE_SPAN_MS &&
                        abs(after.residualMs - before.residualMs) < MAX_INTERPOLATE_DIFF_MS
                    ) {
                        if (span <= 0L) {
                            before.residualMs
                        } else {
                            val fraction = (lineTime - before.timeMs).toDouble() / span
                            (before.residualMs +
                                (after.residualMs - before.residualMs) * fraction).roundToLong()
                        }
                    } else {
                        nearest(before, after, lineTime)
                    }
                }
                else -> {
                    val only = before ?: after
                    if (only != null && abs(only.timeMs - lineTime) <= HOLD_RANGE_MS) {
                        only.residualMs
                    } else {
                        0L
                    }
                }
            }
            shifts[index] = if (abs(target) >= MIN_APPLY_MS) target else 0L
        }
        enforceOrder(lines, shifts)
        // A sheet already heard where it is shown: a move larger than the sheet's
        // own scatter is a mishearing, not the sheet (11-07: 51 lines of a right
        // sheet moved up to 0.7 s by scattered lines; dropping those moves passed
        // 5 songs of the same 52 and lost none).
        // Only lines the words reached say anything: on a sheet with no whole-song fit, most
        // lines have no anchor and no move, and NPZtKA6343o's true 2.25 s moves were dropped.
        val near = anchors.count { abs(shifts[it.line]) <= GOOD_SHEET_MOVE_MS }
        if (anchors.size >= MIN_GOOD_SHEET_LINES && near >= anchors.size * GOOD_SHEET_MOVE_SHARE) {
            for (index in shifts.indices) if (abs(shifts[index]) > GOOD_SHEET_MOVE_MS) shifts[index] = 0L
            enforceOrder(lines, shifts)
        }
        // A line heard from its very first word starts there ([firstWordSnaps]). Put on after
        // the moves above, so a line without such an opening keeps what they gave it.
        if (snapped.isNotEmpty()) {
            for (snap in snapped) shifts[snap.line] = if (abs(snap.residualMs) >= MIN_APPLY_MS) snap.residualMs else 0L
            enforceOrder(lines, shifts)
        }
        val applied = shifts.count { it != 0L }
        val maxAbs = shifts.maxOfOrNull { abs(it) } ?: 0L
        return Layer(shifts.toList(), anchors.size, applied, maxAbs, snapped.size)
    }

    /**
     * Lines that start where their first heard word starts. A hand-tapped sheet is off by
     * its own ±0.3 s on each line (11-09 6rS7OUGXUik: VIBE/LRCLIB 0.5 s early with ±0.3 s per
     * line; the shift reached 74 % of lines within 0.3 s, the word anchors 76 %), and where a
     * line's opening word was heard, that onset is where it was sung: the median of its words
     * stepped back by 180 ms a letter is not, for held notes and fast rap alike.
     *
     * A line qualifies only when the words clearly heard it from its start: [MIN_FIRST_WORD_SHARE]
     * of its letters and [MIN_ANCHOR_WORDS] words matched, its first letter matched to the first
     * letter of a heard word that is mostly this line's ([MIN_FIRST_WORD_COVER] of its letters),
     * not the previous line's last word, in time order with the next word, and starting no earlier
     * than the previous line's last heard word ends (a held note is not cut short). The move is at
     * most [MAX_FIRST_WORD_MOVE_MS] from where the fit put the line, [MAX_REPEATED_FIRST_WORD_MOVE_MS]
     * for a line whose text the sheet repeats (the words may be the other copy's). The snaps then
     * pass the same order, lone-block and stray guards as the word anchors, a lone one far from
     * both agreeing neighbours is dropped ([MAX_LONE_FIRST_WORD_DIFF_MS]), and on a sheet the
     * openings say is right, only the moves within its scatter are kept.
     *
     * Sync replay, 85 recorded songs through the app's fit (heard and scored on whisper-large):
     * within 0.3 s after the per-line layer 88 % -> 91 % of 2253 lines, median 50 -> 30 ms.
     */
    private fun firstWordSnaps(
        lines: List<LyricLine>,
        heard: List<LyricsWordAlign.Heard>,
        letters: List<Int>,
    ): List<Anchor> {
        val hearings = LyricsWordAlign.lineHearings(lines.map { it.text }, heard)
        val folded = lines.map { LyricsWordAlign.fold(it.text) }
        val copies = folded.groupingBy { it }.eachCount()
        val found = ArrayList<Anchor>()
        // Openings heard before the line before them ends. They still take part in the order
        // check, so a pair heard crossed loses the one that moves further, then they go.
        val insideHeldNote = HashSet<Int>()
        var previousLastWord = -1
        var previousLastEnd = Long.MIN_VALUE
        for (index in lines.indices) {
            val hearing = hearings[index]
            val opening = hearing.opening
            val leadStart = lines[index].words.firstOrNull()?.startMs ?: lines[index].timeMs
            if (opening != null &&
                hearing.share >= MIN_FIRST_WORD_SHARE &&
                hearing.words.size >= MIN_ANCHOR_WORDS &&
                opening != previousLastWord &&
                hearing.openingLetters >= maxOf(1, ceil(MIN_FIRST_WORD_COVER * LyricsWordAlign.fold(heard[opening].text).length).toInt()) &&
                heard[opening].startMs <= heard[hearing.words[1]].startMs
            ) {
                val residual = heard[opening].startMs - leadStart
                val cap = if ((copies[folded[index]] ?: 0) > 1) MAX_REPEATED_FIRST_WORD_MOVE_MS else MAX_FIRST_WORD_MOVE_MS
                if (abs(residual) <= cap) {
                    found += Anchor(index, lines[index].timeMs, residual, opening = opening)
                    if (heard[opening].startMs < previousLastEnd) insideHeldNote += index
                }
            }
            hearing.words.lastOrNull()?.let {
                previousLastWord = it
                previousLastEnd = heard[it].endMs
            }
        }
        val ordered = sungInOrder(found, letters).filter { it.line !in insideHeldNote }
        val guarded = withoutLoneFirstWords(withoutStrays(withoutLoneBlocks(ordered)))
        if (guarded.size >= MIN_GOOD_SHEET_LINES &&
            guarded.count { abs(it.residualMs) <= GOOD_SHEET_MOVE_MS } >= guarded.size * GOOD_SHEET_MOVE_SHARE
        ) {
            return guarded.filter { abs(it.residualMs) <= GOOD_SHEET_MOVE_MS }
        }
        return guarded
    }

    /**
     * The per-line layer for a sheet that states its own word times ([statesItsOwnWords]) on a
     * whole-song fit. Such a sheet was timed by someone listening and is otherwise left as it is
     * (see LyricsTiming.perLineLayer), but its line stamps scatter by their own ±0.35 s (11-09
     * phone: KuGou OxgiiyLp5pk lines 0.32-0.37 s late after a right +350 ms base, SyncLRC
     * 6rS7OUGXUik line 13 0.56 s late). A line moves only by a first-word snap ([firstWordSnaps],
     * with all its guards) that also passes every test here:
     * - the move is [MIN_WORD_SHEET_MOVE_MS]..[MAX_WORD_SHEET_MOVE_MS]: under it is the sheet's
     *   own scatter, over it the opening is more likely another place than a tapping slip;
     * - the sheet does not repeat the line (its opening may be the other copy's);
     * - no other heard place within [WORD_SHEET_ECHO_MS] opens like the line ([echoed]): 6rS7
     *   line 37 was heard twice 1.1 s apart, a whisper copy, and the snap took the second;
     * - the sheet's own later words, where their first letters were heard as word starts, say
     *   the same move to within [WORD_SHEET_AGREE_MS] ([sheetWordsAgree]): the whole line moves,
     *   its words with it, so a slip of the first stamp alone is not a reason to move them all.
     *
     * Offline on 107 phone and emulator dumps of such sheets (65 also on the app's own transcript),
     * without the sheet-words test: every scored line moved came closer to where it was sung, none
     * further; without the echo, repeat or cap tests a line each went 0.35-1.07 s wrong
     * (ship/lanes/opus-wordsheet.md).
     */
    fun wordSheetSnaps(
        lines: List<LyricLine>,
        heard: List<LyricsWordAlign.Heard>,
        heardAudioKey: Int?,
        currentAudioKey: Int?,
    ): Layer {
        if (heardAudioKey != currentAudioKey) return Layer.identity(lines.size)
        if (lines.isEmpty() || heard.isEmpty()) return Layer.identity(lines.size)
        val folded = lines.map { LyricsWordAlign.fold(it.text) }
        val copies = folded.groupingBy { it }.eachCount()
        val snaps = firstWordSnaps(lines, heard, folded.map { it.length })
        if (snaps.isEmpty()) return Layer.identity(lines.size)
        val hearings = LyricsWordAlign.lineHearings(lines.map { it.text }, heard)
        val kept = snaps.filter { snap ->
            val start = lines[snap.line].timeMs + snap.residualMs
            abs(snap.residualMs) in MIN_WORD_SHEET_MOVE_MS..MAX_WORD_SHEET_MOVE_MS &&
                // Inside its neighbours as the sheet has them: a word sheet's line never pushes another.
                (snap.line == 0 || start > lines[snap.line - 1].timeMs + MIN_LINE_GAP_MS) &&
                (snap.line == lines.lastIndex || start < lines[snap.line + 1].timeMs - MIN_LINE_GAP_MS) &&
                (copies[folded[snap.line]] ?: 0) <= 1 &&
                snap.opening >= 0 &&
                !echoed(lines[snap.line].text, heard, snap.opening) &&
                sheetWordsAgree(lines[snap.line], hearings[snap.line], heard, snap.residualMs)
        }
        if (kept.isEmpty()) return Layer.identity(lines.size)
        val shifts = LongArray(lines.size)
        for (snap in kept) shifts[snap.line] = snap.residualMs
        enforceOrder(lines, shifts)
        val applied = shifts.count { it != 0L }
        val maxAbs = shifts.maxOfOrNull { abs(it) } ?: 0L
        return Layer(shifts.toList(), 0, applied, maxAbs, kept.size)
    }

    /** See [wordSheetSnaps]. */
    const val MIN_WORD_SHEET_MOVE_MS = 300L
    const val MAX_WORD_SHEET_MOVE_MS = 800L
    const val WORD_SHEET_ECHO_MS = 4_000L
    const val WORD_SHEET_AGREE_MS = 250L
    /** The opening compared for an echo: the first word, at least this many letters, at most that many. */
    const val MIN_ECHO_LETTERS = 4
    const val MAX_ECHO_LETTERS = 8
    /** Letters in common (twice the longest common run of letters over both lengths) that make two openings one. */
    const val ECHO_SIMILARITY = 0.75

    /**
     * Whether a heard place other than [opening], within [WORD_SHEET_ECHO_MS] of it, opens the way
     * [text] does: the line's first word (at least [MIN_ECHO_LETTERS], at most [MAX_ECHO_LETTERS]
     * letters) against the heard letters from there, [ECHO_SIMILARITY] in common and the same first
     * letter. Too short an opening to tell is taken as echoed.
     */
    private fun echoed(text: String, heard: List<LyricsWordAlign.Heard>, opening: Int): Boolean {
        val folded = LyricsWordAlign.fold(text)
        val first = LyricsWordAlign.fold(text.trim().split(WHITESPACE).firstOrNull().orEmpty())
        val key = folded.take(minOf(folded.length, maxOf(MIN_ECHO_LETTERS, first.length), MAX_ECHO_LETTERS))
        if (key.length < 2) return true
        val at = heard[opening].startMs
        for (j in heard.indices) {
            if (j == opening || abs(heard[j].startMs - at) > WORD_SHEET_ECHO_MS) continue
            val from = StringBuilder()
            var i = j
            while (i < heard.size && from.length < key.length) from.append(LyricsWordAlign.fold(heard[i++].text))
            val other = from.toString().take(key.length)
            if (other.isNotEmpty() && other[0] == key[0] && commonShare(key, other) >= ECHO_SIMILARITY) return true
        }
        return false
    }

    /** 2 x the longest common subsequence of [a] and [b] over their lengths together. */
    private fun commonShare(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1)
        for (x in a) {
            val current = IntArray(b.length + 1)
            for (j in b.indices) current[j + 1] = if (x == b[j]) previous[j] + 1 else maxOf(previous[j + 1], current[j])
            previous = current
        }
        return 2.0 * previous[b.length] / (a.length + b.length)
    }

    /**
     * Whether [line]'s own later words, each where its first letter was heard as the start of a
     * heard word, were heard [residualMs] from where the sheet stamps them (their median, within
     * [WORD_SHEET_AGREE_MS]). False when the words do not spell the line or none was heard so.
     */
    private fun sheetWordsAgree(
        line: LyricLine,
        hearing: LyricsWordAlign.LineHearing,
        heard: List<LyricsWordAlign.Heard>,
        residualMs: Long,
    ): Boolean {
        if (line.words.size < 2) return false
        val moves = ArrayList<Long>()
        var position = 0
        line.words.forEachIndexed { k, word ->
            if (k > 0) hearing.onsets[position]?.let { moves += heard[it].startMs - word.startMs }
            position += LyricsWordAlign.fold(word.text).length
        }
        if (position != LyricsWordAlign.fold(line.text).length || moves.isEmpty()) return false
        return abs(median(moves) - residualMs) <= WORD_SHEET_AGREE_MS
    }

    private val WHITESPACE = Regex("\\s+")

    /** [snaps] without one that sits far from both its neighbours while they agree: the opening heard somewhere else. */
    private fun withoutLoneFirstWords(snaps: List<Anchor>): List<Anchor> =
        snaps.filterIndexed { k, snap ->
            val before = snaps.getOrNull(k - 1)
            val after = snaps.getOrNull(k + 1)
            !(before != null && after != null &&
                abs(before.timeMs - snap.timeMs) <= LONE_ANCHOR_NEAR_MS &&
                abs(after.timeMs - snap.timeMs) <= LONE_ANCHOR_NEAR_MS &&
                abs(before.residualMs - after.residualMs) < MAX_INTERPOLATE_DIFF_MS &&
                abs(snap.residualMs - before.residualMs) > MAX_LONE_FIRST_WORD_DIFF_MS &&
                abs(snap.residualMs - after.residualMs) > MAX_LONE_FIRST_WORD_DIFF_MS)
        }

    /**
     * An anchor for each line of a transcript sheet from where [LyricsWordAlign.placeLinesDetailed]
     * heard its opening, in place of its word anchor: the words' median sits a line's own
     * scatter off it, and the placement is where it was sung (see [MIN_OWN_TRANSCRIPT_SHARE]).
     * A line placed on too few of its letters keeps its word anchor. The anchors still pass the
     * order, strays and good-sheet guards below.
     */
    private fun addPlacedLines(lines: List<LyricLine>, heard: List<LyricsWordAlign.Heard>, found: MutableList<Anchor>) {
        val placement = LyricsWordAlign.placeLinesDetailed(lines.map { it.text }, heard)
        val byLine = found.associateByTo(LinkedHashMap()) { it.line }
        for (index in lines.indices) {
            val start = placement.starts[index] ?: continue
            if ((placement.matchedShare.getOrNull(index) ?: 0.0) < MIN_OWN_TRANSCRIPT_SHARE) continue
            val residual = start - lines[index].timeMs
            if (abs(residual) > MAX_OWN_TRANSCRIPT_MOVE_MS) continue
            byLine[index] = Anchor(index, lines[index].timeMs, residual, placed = true)
        }
        found.clear()
        found += byLine.values.sortedBy { it.line }
    }

    /**
     * [anchors] without the odd ones out on a sheet that is right. When nearly every line was
     * heard about where it is shown, the sheet is good, and the few heard far from it are
     * mishearings. Moving them made it worse: 14 of 46 songs lost lines they had right, up to
     * 1 s off from 0.12 s (11-07 BL-aIpCLWnU). Simulated on the 51 scored songs under the
     * strict rules, chosen on half and confirmed on the rest: 22 pass, then 27, none lost.
     * A sheet with many lines to move keeps them all: there the moves are real.
     */
    private fun withoutStrays(anchors: List<Anchor>): List<Anchor> {
        if (anchors.size < MIN_GOOD_SHEET_ANCHORS) return anchors
        val center = median(anchors.map { it.residualMs })
        val near = anchors.filter { abs(it.residualMs - center) <= GOOD_SHEET_WITHIN_MS }
        return if (near.size >= anchors.size * GOOD_SHEET_SHARE) near else anchors
    }

    /**
     * [anchors] without a short block whose move disagrees with the blocks on both
     * sides (11-07: two or three lines heard at a repeated chorus moved 1.2-3.5 s
     * while the rest of the song stayed put). A block of four or more lines is a
     * real stretch, and a move past [MAX_LONE_BLOCK_MS] is already dropped by the cap.
     */
    private fun withoutLoneBlocks(anchors: List<Anchor>): List<Anchor> {
        if (anchors.size < 3) return anchors
        val blocks = ArrayList<MutableList<Anchor>>()
        for (anchor in anchors) {
            val last = blocks.lastOrNull()
            if (last != null && abs(anchor.residualMs - median(last.map { it.residualMs })) <= MAX_INTERPOLATE_DIFF_MS) {
                last += anchor
            } else {
                blocks += mutableListOf(anchor)
            }
        }
        val kept = ArrayList<Anchor>(anchors.size)
        for (k in blocks.indices) {
            val block = blocks[k]
            val move = median(block.map { it.residualMs })
            val before = blocks.getOrNull(k - 1)?.let { median(it.map { a -> a.residualMs }) }
            val after = blocks.getOrNull(k + 1)?.let { median(it.map { a -> a.residualMs }) }
            val lone = block.none { it.placed } &&
                block.size <= MAX_LONE_BLOCK &&
                abs(move) in MIN_LONE_BLOCK_MS..MAX_LONE_BLOCK_MS &&
                before != null && after != null &&
                abs(move - before) > MAX_INTERPOLATE_DIFF_MS &&
                abs(move - after) > MAX_INTERPOLATE_DIFF_MS
            if (!lone) kept += block
        }
        return kept
    }

    /**
     * [anchors] without those whose moves leave the lines between two of them no time
     * to be sung (11-06 w58uXpyHWes: 2690 ms squeezed to 126 ms, a line 2.3 s early).
     * Of the two, the one that moves further goes. A gap the sheet itself has is left.
     */
    private fun sungInOrder(anchors: List<Anchor>, letters: List<Int>): List<Anchor> {
        val kept = ArrayList<Anchor>(anchors.size)
        for (anchor in anchors) {
            var keep = true
            while (kept.isNotEmpty()) {
                val last = kept.last()
                val sheetGap = anchor.timeMs - last.timeMs
                val movedGap = sheetGap + anchor.residualMs - last.residualMs
                val needed = (last.line until anchor.line).sumOf { letters[it] } * LyricsWordAlign.MIN_LETTER_MS
                if (movedGap >= needed || movedGap >= sheetGap) break
                if (abs(last.residualMs) > abs(anchor.residualMs)) {
                    kept.removeAt(kept.lastIndex)
                } else {
                    keep = false
                    break
                }
            }
            if (keep) kept += anchor
        }
        return kept
    }

    private fun loneAnchorDisagrees(own: Anchor, before: Anchor?, after: Anchor?, lineTime: Long): Boolean {
        if (own.placed) return false
        val nearBefore = before?.takeIf { abs(it.timeMs - lineTime) <= LONE_ANCHOR_NEAR_MS } ?: return false
        val nearAfter = after?.takeIf { abs(it.timeMs - lineTime) <= LONE_ANCHOR_NEAR_MS } ?: return false
        if (abs(nearAfter.residualMs - nearBefore.residualMs) >= MAX_INTERPOLATE_DIFF_MS) return false
        return abs(nearBefore.residualMs - own.residualMs) > MAX_INTERPOLATE_DIFF_MS &&
            abs(nearAfter.residualMs - own.residualMs) > MAX_INTERPOLATE_DIFF_MS
    }

    private fun nearest(before: Anchor, after: Anchor, atMs: Long): Long {
        val toBefore = abs(atMs - before.timeMs)
        val toAfter = abs(atMs - after.timeMs)
        val nearest = if (toBefore <= toAfter) before else after
        return if (minOf(toBefore, toAfter) <= HOLD_RANGE_MS) nearest.residualMs else 0L
    }

    /** No line starts within [MIN_LINE_GAP_MS] of the one before it. */
    private fun enforceOrder(lines: List<LyricLine>, shifts: LongArray) {
        var previousStart = Long.MIN_VALUE
        for (index in lines.indices) {
            val start = lines[index].timeMs
            if (index > 0) {
                val minimum = previousStart + MIN_LINE_GAP_MS
                if (start + shifts[index] < minimum) shifts[index] = minimum - start
            }
            previousStart = start + shifts[index]
        }
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
