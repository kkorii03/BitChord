package com.music.bitchord.data.lyrics

import java.text.Normalizer

/**
 * Lyrics lined up against words heard with their times (a speech model's
 * transcript of the video) — see [placeLines]. Kept apart from the service
 * that hears them so it can be checked without one.
 */
object LyricsWordAlign {

    /** A word as heard: what, and from when to when, ms from the start. */
    data class Heard(val text: String, val startMs: Long, val endMs: Long)


    /**
     * Letters and digits only, lower case, compatibility forms folded: what
     * two spellings share.
     *
     * Shared with [withHeardWords], which lines a single line's words up
     * against the same transcript by the same letters.
     */
    internal fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }

    private const val MATCH = 3
    private const val MISMATCH = -1
    private const val GAP = -1

    /** Above this many cells the alignment isn't attempted (a very long song and transcript). */
    internal const val MAX_CELLS = 4_000_000L

    /** How far into a line its first heard letter may be and still say when the line starts. */
    private const val LEAD_LETTERS = 4

    /** A letter's typical length when sung, for stepping back from the first heard one to the line's start. */
    private const val LETTER_MS = 180L

    /** Where [placeLinesDetailed] found lines, and whether it could look at all. */
    data class Placement(val starts: List<Long?>, val attempted: Boolean)

    /**
     * When each of [lines] starts, by [words]: the lyrics' letters and the
     * heard letters aligned end to end (match, mismatch and gaps, ends free),
     * then each line started from the first of its opening letters that was
     * heard — stepped back by the letters before it. A line where too little
     * of its opening was heard has no answer (null); TimingFit draws the
     * line through the rest.
     */
    fun placeLines(lines: List<String>, words: List<Heard>): List<Long?> =
        placeLinesDetailed(lines, words).starts

    /**
     * [placeLines], plus whether the alignment actually ran. Too long a song
     * and transcript together exceed [MAX_CELLS] and are not attempted; that
     * is not the same answer as "none of the lines were heard", and callers
     * judging a wrong song must not read it as one.
     */
    fun placeLinesDetailed(lines: List<String>, words: List<Heard>): Placement {
        val aligned = align(lines, words)
            ?: return Placement(List(lines.size) { null }, attempted = false)
        val starts = arrayOfNulls<Long>(lines.size)
        var k = 0
        while (k < aligned.lyricChars.size) {
            val line = aligned.lyricLine[k]
            var end = k
            while (end < aligned.lyricChars.size && aligned.lyricLine[end] == line) end++
            // The opening letters of the line: two of the first few heard,
            // the first of them giving the start.
            val opening = (k until minOf(end, k + LEAD_LETTERS + 2)).filter { aligned.heardFor[it] >= 0 }
            if (opening.size >= 2 && aligned.lyricPos[opening.first()] <= LEAD_LETTERS) {
                val first = opening.first()
                starts[line] = (aligned.heardMs[aligned.heardFor[first]] -
                    aligned.lyricPos[first] * LETTER_MS).coerceAtLeast(0L)
            }
            k = end
        }
        return Placement(starts.toList(), attempted = true)
    }

    /** A heard word matched to a line, with how far into that line its first matched letter sits. */
    data class MatchedWord(val word: Heard, val offsetMs: Long)

    /**
     * The heard words matched to each line by the same monotonic letter
     * alignment [placeLines] uses, with the time before each word inside its
     * line (from its first matched lyric letter). Empty lines have no words.
     *
     * A line with at least two of these was heard as that line, so the per-line
     * residual layer can move just it instead of the whole song. Words are in
     * the order they were matched, so the first is the line's opening.
     */
    fun matchedWords(lines: List<String>, words: List<Heard>): List<List<MatchedWord>> {
        val aligned = align(lines, words) ?: return List(lines.size) { emptyList() }
        val perLine = List(lines.size) { LinkedHashMap<Int, Int>() }
        for (i in aligned.lyricChars.indices) {
            val heardAt = aligned.heardFor[i]
            if (heardAt < 0) continue
            val line = aligned.lyricLine[i]
            val word = aligned.heardWord[heardAt]
            val firstPositions = perLine[line]
            if (!firstPositions.containsKey(word)) firstPositions[word] = aligned.lyricPos[i]
        }
        return perLine.map { firstPositions ->
            firstPositions.map { (word, position) ->
                MatchedWord(words[word], position * LETTER_MS)
            }
        }
    }

    /** The lyrics' and transcript's letters aligned end to end, or null when too large to try. */
    private class Alignment(
        val lyricChars: CharArray,
        val lyricLine: IntArray,
        val lyricPos: IntArray,
        val heardMs: LongArray,
        val heardWord: IntArray,
        val heardFor: IntArray,
    )

    private fun align(lines: List<String>, words: List<Heard>): Alignment? {
        val lyricChars = StringBuilder()
        val lyricLine = ArrayList<Int>()
        val lyricPos = ArrayList<Int>()
        lines.forEachIndexed { index, line ->
            fold(line).forEachIndexed { pos, c ->
                lyricChars.append(c)
                lyricLine += index
                lyricPos += pos
            }
        }
        val heardChars = StringBuilder()
        val heardMs = ArrayList<Long>()
        val heardWord = ArrayList<Int>()
        words.forEachIndexed { index, word ->
            val letters = fold(word.text)
            if (letters.isEmpty()) return@forEachIndexed
            val span = (word.endMs - word.startMs).coerceAtLeast(0L)
            letters.forEachIndexed { k, c ->
                heardChars.append(c)
                heardMs += word.startMs + span * k / letters.length
                heardWord += index
            }
        }
        val n = lyricChars.length
        val m = heardChars.length
        if (n == 0 || m == 0 || n.toLong() * m > MAX_CELLS) return null

        // Semi-global: leading and trailing gaps on either side cost nothing —
        // the video may sing only part of the lyrics, and say things besides.
        val width = m + 1
        val score = IntArray((n + 1) * width)
        val from = ByteArray((n + 1) * width) // 1 diagonal, 2 up (skip lyric), 3 left (skip heard)
        for (i in 1..n) {
            from[i * width] = 2
            val a = lyricChars[i - 1]
            for (j in 1..m) {
                val diagonal = score[(i - 1) * width + j - 1] + if (a == heardChars[j - 1]) MATCH else MISMATCH
                val up = score[(i - 1) * width + j] + if (j == m) 0 else GAP
                val left = score[i * width + j - 1] + if (i == n) 0 else GAP
                val best = maxOf(diagonal, up, left)
                score[i * width + j] = best
                from[i * width + j] = when (best) {
                    diagonal -> 1
                    up -> 2
                    else -> 3
                }
            }
        }
        for (j in 1..m) from[j] = 3

        // Back from the end: the letters that line up and are the same.
        val heardFor = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when (from[i * width + j].toInt()) {
                1 -> {
                    if (lyricChars[i - 1] == heardChars[j - 1]) heardFor[i - 1] = j - 1
                    i--
                    j--
                }
                2 -> i--
                else -> j--
            }
        }
        return Alignment(
            lyricChars = lyricChars.toString().toCharArray(),
            lyricLine = lyricLine.toIntArray(),
            lyricPos = lyricPos.toIntArray(),
            heardMs = heardMs.toLongArray(),
            heardWord = heardWord.toIntArray(),
            heardFor = heardFor,
        )
    }
}
