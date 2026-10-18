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
 *
 * The beats are guessed from the words themselves: a Hangul, Han or kana
 * syllable is one, and a Latin word contributes one per vowel group rather
 * than one per letter ("everything" is four beats, not ten). The line stops a
 * breath ([BREATH_MS]) before the next stamp, and a long word at the end keeps
 * enough of the slot to be read as held rather than rattled off.
 */
fun LyricLine.withEstimatedWords(nextLineMs: Long?): LyricLine {
    if (isGap || isWordSynced || timingSource != null || text.isBlank()) return this

    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isEmpty()) return
        tokens += current.toString()
        current.setLength(0)
    }
    for (char in text) {
        if (char.isWhitespace()) flush() else current.append(char)
    }
    flush()

    val weights = tokens.map { it.sweepWeight() }
    val totalWeight = weights.sum() + GAP_WEIGHT * (tokens.size - 1)
    if (totalWeight <= 0.0) return this

    val durationMs = if (sungUntilMs != null && sungUntilMs > timeMs) {
        sungUntilMs - timeMs
    } else {
        val cap = (totalWeight * MAX_MS_PER_UNIT).toLong()
        val room = nextLineMs?.let { it - timeMs - BREATH_MS }
        var duration = if (room == null) cap else minOf(room, cap)
        if (duration < MIN_SWEEP_MS) {
            duration = minOf(MIN_SWEEP_MS, nextLineMs?.let { it - timeMs } ?: MIN_SWEEP_MS)
        }
        duration
    }.coerceAtLeast(1L)

    return copy(words = wordsWithin(tokens, weights, totalWeight, timeMs, durationMs), wordsEstimated = true)
}

/**
 * [tokens] laid end to end over [durationMs] from [fromMs], each for as long
 * as its own [weights] say, with a [GAP_WEIGHT] pause between two.
 *
 * The last word is given at least [MIN_FINAL_WORD_MS] — less only when the
 * slot cannot spare it — by taking the time off the words before it rather
 * than letting a long closing word be squeezed into the tail.
 */
private fun wordsWithin(
    tokens: List<String>,
    weights: List<Double>,
    totalWeight: Double,
    fromMs: Long,
    durationMs: Long,
): List<LyricWord> {
    val limit = fromMs + durationMs
    if (tokens.size == 1) return listOf(LyricWord(startMs = fromMs, endMs = limit, text = tokens.first()))

    val lastWeight = weights.last()
    val evenFinal = (lastWeight * durationMs / totalWeight).roundToLong()
    val finalShare = if (lastWeight >= LONG_FINAL_WEIGHT) {
        maxOf(evenFinal, minOf(MIN_FINAL_WORD_MS, durationMs / 2))
    } else {
        evenFinal
    }.coerceIn(0L, durationMs)
    val finalAt = fromMs + durationMs - finalShare
    val prefixWeight = totalWeight - lastWeight
    val perUnit = if (prefixWeight > 0.0) (durationMs - finalShare).toDouble() / prefixWeight else 0.0

    var cursor = fromMs.toDouble()
    val words = ArrayList<LyricWord>(tokens.size)
    for (index in 0 until tokens.size - 1) {
        val weight = weights[index]
        val start = cursor.roundToLong().coerceIn(fromMs, finalAt)
        val end = (cursor + weight * perUnit).roundToLong().coerceIn(start, finalAt)
        words += LyricWord(startMs = start, endMs = end, text = tokens[index])
        cursor += (weight + GAP_WEIGHT) * perUnit
    }
    words += LyricWord(startMs = finalAt, endMs = limit, text = tokens.last())
    return words
}

/**
 * How much time a word of a guessed sweep is worth, in syllables.
 *
 * A syllable in Hangul, Han or kana is sung as one beat, so it counts whole.
 * A Latin word counts its vowel groups — "everything" is four syllables, and
 * weighting each of its ten letters the same as a Hangul syllable dragged the
 * sweep behind the singer by the end of every long English word. A word with
 * no vowel at all ("psst") is one beat, a digit half of one, and punctuation
 * is no beat at all, which keeps a trailing comma from eating into the word it
 * follows.
 */
private fun String.sweepWeight(): Double {
    var weight = 0.0
    var beat = false
    var inVowelRun = false
    for (char in this) {
        when {
            char.isSyllableBlock() -> {
                weight += 1.0
                beat = true
                inVowelRun = false
            }
            char.isDigit() -> {
                weight += DIGIT_WEIGHT
                beat = true
                inVowelRun = false
            }
            char.isLetter() -> {
                beat = true
                val vowel = char.lowercaseChar() in LATIN_VOWELS
                if (vowel && !inVowelRun) weight += 1.0
                inVowelRun = vowel
            }
            else -> inVowelRun = false
        }
    }
    return if (beat && weight <= 0.0) 1.0 else weight
}

/** Scripts whose characters are syllables already: one beat each. */
private fun Char.isSyllableBlock(): Boolean =
    this in '가'..'힯' || this in 'ᄀ'..'ᇿ' || this in '㄰'..'㆏' ||
        this in '一'..'鿿' || this in '぀'..'ゟ' || this in '゠'..'ヿ'

private const val LATIN_VOWELS = "aeiouy"

/** A digit is said, but in a fraction of the time a syllable is. */
private const val DIGIT_WEIGHT = 0.5

/** The pause between two guessed words, in the same unit as one syllable. */
private const val GAP_WEIGHT = 0.25

/** Slowest plausible singing per unit, so a line before an instrumental does not crawl through the break. */
private const val MAX_MS_PER_UNIT = 550.0

/** How long before the next line the sweep is made to finish: a breath between the two. */
private const val BREATH_MS = 300L

/** Shortest sweep worth drawing, unless the slot itself is shorter. */
private const val MIN_SWEEP_MS = 200L

/** A final word worth this many syllables is held at the end rather than squeezed into it. */
private const val LONG_FINAL_WEIGHT = 1.5

/** How much of the slot a long final word is given, where the slot can spare it. */
private const val MIN_FINAL_WORD_MS = 300L
