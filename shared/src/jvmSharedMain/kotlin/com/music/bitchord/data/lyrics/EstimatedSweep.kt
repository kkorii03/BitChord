package com.music.bitchord.data.lyrics

import kotlin.math.roundToLong

/**
 * This line with display-only word timings guessed from its own slot.
 *
 * Line-synced sources — LRCLIB, Bugs, Melon — only stamp where a line starts,
 * so the whole line used to light up at once. Sweeping it letter by letter at a
 * guessed pace reads as following the singer, which is far closer to what the
 * voice is doing than one flat jump. A line that already carries real word
 * timings, or one that borrows another line's timing, is returned untouched.
 *
 * The guess is for drawing only: [LyricLine.wordsEstimated] tells the rest of
 * the code that nobody actually stated these timings. It must never be stored,
 * cached or written back — `LrcWriter` would otherwise export times the
 * provider never claimed, inventing word sync that the next load would trust.
 *
 * [nextLineMs] is the following line's stamp, used to keep the sweep inside
 * this line's slot; null when the line is the last one.
 */
fun LyricLine.withEstimatedWords(nextLineMs: Long?): LyricLine {
    if (isGap || isWordSynced || timingSource != null || text.isBlank()) return this

    val tokens = mutableListOf<Pair<String, Double>>()
    val current = StringBuilder()
    fun flush() {
        if (current.isEmpty()) return
        var weight = 0.0
        for (char in current) weight += char.sweepWeight()
        tokens += current.toString() to weight
        current.setLength(0)
    }
    for (char in text) {
        if (char.isWhitespace()) flush() else current.append(char)
    }
    flush()

    val totalWeight = tokens.sumOf { it.second } + GAP_WEIGHT * (tokens.size - 1)
    if (totalWeight <= 0.0) return this

    val durationMs = if (sungUntilMs != null && sungUntilMs > timeMs) {
        sungUntilMs - timeMs
    } else {
        val cap = (totalWeight * MAX_MS_PER_UNIT).toLong()
        val room = nextLineMs?.let { it - timeMs - TAIL_MS }
        var duration = if (room == null) cap else minOf(room, cap)
        if (duration < MIN_SWEEP_MS) {
            duration = minOf(MIN_SWEEP_MS, nextLineMs?.let { it - timeMs } ?: MIN_SWEEP_MS)
        }
        duration
    }.coerceAtLeast(1L)

    val msPerUnit = durationMs.toDouble() / totalWeight
    val limit = timeMs + durationMs
    var cursor = timeMs.toDouble()
    val estimated = tokens.map { (token, weight) ->
        val start = cursor.roundToLong().coerceIn(timeMs, limit)
        val end = (cursor + weight * msPerUnit).roundToLong().coerceIn(start, limit)
        cursor += (weight + GAP_WEIGHT) * msPerUnit
        LyricWord(startMs = start, endMs = end, text = token)
    }
    return copy(words = estimated, wordsEstimated = true)
}

/**
 * How much time one character of a guessed sweep is worth.
 *
 * A syllable in Hangul, Han or kana is sung as one beat, so it counts whole;
 * a Latin letter or a digit is a fraction of one. Punctuation is no beat at
 * all, which keeps a trailing comma from eating into the word it follows.
 */
private fun Char.sweepWeight(): Double = when {
    this in '가'..'힯' || this in 'ᄀ'..'ᇿ' || this in '㄰'..'㆏' ||
        this in '一'..'鿿' || this in '぀'..'ゟ' || this in '゠'..'ヿ' -> 1.0
    isLetterOrDigit() -> 0.4
    else -> 0.0
}

/** The pause between two guessed words, in the same unit as one syllable. */
private const val GAP_WEIGHT = 0.25

/** Slowest plausible singing per unit, so a line before an instrumental does not crawl through the break. */
private const val MAX_MS_PER_UNIT = 550.0

/** How long before the next line the sweep is made to finish. */
private const val TAIL_MS = 250L

/** Shortest sweep worth drawing, unless the slot itself is shorter. */
private const val MIN_SWEEP_MS = 200L
