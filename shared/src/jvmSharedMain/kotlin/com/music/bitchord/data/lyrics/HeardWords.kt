package com.music.bitchord.data.lyrics

import kotlin.math.roundToLong

/**
 * This line with the words a speech model actually heard, at their own times.
 *
 * A line-synced provider — LRCLIB, Bugs, Melon — only stamps where the line
 * starts, so the panel guesses the words from the slot ([withEstimatedWords]).
 * When AI timing has already listened to the video, though, it knows where each
 * sung word fell; [LyricsWordAlign.placeLines] used those times only to place
 * line starts and then threw the words away. This puts them back: the line's
 * tokens take the heard word's own start and end, and only the tokens the
 * model missed are interpolated.
 *
 * The match is by letters, not by string equality, because ASR routinely splits
 * or merges a word: "사랑해" comes back as "사랑" and "해", an English phrase as
 * one token or three. A token is therefore matched when most of its letters
 * line up with one heard word or a run of consecutive heard words. A line where
 * less than [MIN_LINE_MATCH] of its letters were heard has no answer — null —
 * and the caller keeps the estimate rather than inventing sync from noise.
 *
 * Only line-synced lines are candidates, by the same rules as
 * [withEstimatedWords]: a gap, a line the provider already timed word by word,
 * and a translation borrowing another line's clock are all returned as null.
 * The heard words are expected on the displayed lyric timeline (the caller
 * shifts them with the same correction it applies to line times), and
 * [nextLineMs] is the next stanza's stamp, which bounds the slot.
 *
 * The result is display-only like the estimate, but it is not marked
 * [LyricLine.wordsEstimated]: these are real times. It must still never be
 * written back — the provider never claimed word sync, and [LrcWriter] would
 * export a transcript's timings as if they were the lyrics'.
 */
fun LyricLine.withHeardWords(
    heard: List<LyricsWordAlign.Heard>,
    nextLineMs: Long?,
): LyricLine? {
    if (isGap || isWordSynced || timingSource != null || text.isBlank()) return null

    val tokens = text.split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null

    val slotStart = timeMs
    val slotEnd = (nextLineMs ?: (timeMs + LAST_SLOT_MS)).coerceAtLeast(slotStart)
    // A word's midpoint just outside the stanza still belongs to the breath
    // either side of it; far enough out, it is the neighbouring line's.
    val inSlot = heard.filter { word ->
        val middle = (word.startMs + word.endMs) / 2
        middle >= slotStart - LEAD_MS && middle <= slotEnd + TAIL_MS
    }
    if (inSlot.isEmpty()) return null

    val lyricLetters = StringBuilder()
    val lyricToken = ArrayList<Int>()
    tokens.forEachIndexed { index, token ->
        LyricsWordAlign.fold(token).forEach { letter ->
            lyricLetters.append(letter)
            lyricToken += index
        }
    }
    val lyricLength = lyricLetters.length
    if (lyricLength == 0) return null

    val heardLetters = StringBuilder()
    val heardWord = ArrayList<Int>()
    inSlot.forEachIndexed { index, word ->
        LyricsWordAlign.fold(word.text).forEach { letter ->
            heardLetters.append(letter)
            heardWord += index
        }
    }
    val heardLength = heardLetters.length
    if (heardLength == 0) return null
    if (lyricLength.toLong() * heardLength > MAX_CELLS) return null

    val heardFor = alignLetters(lyricLetters, heardLetters).map { letter ->
        if (letter >= 0) heardWord[letter] else -1
    }
    val matchedLetters = heardFor.count { it >= 0 }
    if (matchedLetters < lyricLength * MIN_LINE_MATCH) return null

    val tokenLetters = IntArray(tokens.size)
    val tokenMatched = IntArray(tokens.size)
    val firstHeard = IntArray(tokens.size) { -1 }
    val lastHeard = IntArray(tokens.size) { -1 }
    for (i in 0 until lyricLength) {
        val token = lyricToken[i]
        tokenLetters[token]++
        val heardIndex = heardFor[i]
        if (heardIndex < 0) continue
        tokenMatched[token]++
        if (firstHeard[token] < 0) firstHeard[token] = heardIndex
        lastHeard[token] = heardIndex
    }

    val starts = arrayOfNulls<Long>(tokens.size)
    val ends = arrayOfNulls<Long>(tokens.size)
    for (index in tokens.indices) {
        if (tokenLetters[index] == 0 || tokenMatched[index] * 2 < tokenLetters[index]) continue
        val first = inSlot[firstHeard[index]]
        val last = inSlot[lastHeard[index]]
        val start = first.startMs.coerceIn(slotStart, slotEnd)
        starts[index] = start
        ends[index] = last.endMs.coerceIn(start, slotEnd)
    }

    val weights = sweepWeights(tokens)
    val words = ArrayList<LyricWord>(tokens.size)
    var cursor = slotStart
    var index = 0
    while (index < tokens.size) {
        val start = starts[index]
        if (start != null) {
            val from = start.coerceAtLeast(cursor).coerceIn(slotStart, slotEnd)
            val to = maxOf(from, ends[index] ?: from).coerceIn(from, slotEnd)
            words += LyricWord(startMs = from, endMs = to, text = tokens[index])
            cursor = to
            index++
            continue
        }
        // A run nobody heard, with a heard word on either side: share the room
        // between them in the same beats the estimate would have used.
        var runEnd = index
        while (runEnd < tokens.size && starts[runEnd] == null) runEnd++
        val until = if (runEnd < tokens.size) {
            (starts[runEnd] ?: slotEnd).coerceIn(cursor, slotEnd)
        } else {
            slotEnd
        }
        val count = runEnd - index
        val total = (index until runEnd).sumOf { weights[it] } + SWEEP_GAP_WEIGHT * (count - 1)
        val unit = if (total > 0.0) (until - cursor) / total else (until - cursor).toDouble() / count
        var at = cursor.toDouble()
        for (i in index until runEnd) {
            val weight = if (total > 0.0) weights[i] else 1.0
            val from = at.roundToLong().coerceIn(cursor, until)
            at += (weight + SWEEP_GAP_WEIGHT) * unit
            val to = at.roundToLong().coerceIn(from, until)
            words += LyricWord(startMs = from, endMs = to, text = tokens[i])
        }
        cursor = words.lastOrNull()?.endMs ?: cursor
        index = runEnd
    }

    return copy(words = words, wordsEstimated = false)
}

/**
 * For each letter of [lyrics], the index of the heard letter it lines up with,
 * or -1 — an end-to-end alignment with the ends free on both sides, so a
 * transcript with extra words around the stanza and a lyric with letters the
 * model missed still meet in the middle.
 *
 * The same scoring and semi-global shape as [LyricsWordAlign.placeLines], but
 * kept here because only the correspondence is wanted, not line starts.
 */
private fun alignLetters(lyrics: CharSequence, heard: CharSequence): IntArray {
    val n = lyrics.length
    val m = heard.length
    val width = m + 1
    val score = IntArray((n + 1) * width)
    val from = ByteArray((n + 1) * width) // 1 diagonal, 2 up (skip lyric), 3 left (skip heard)
    for (i in 1..n) {
        from[i * width] = 2
        val lyric = lyrics[i - 1]
        for (j in 1..m) {
            val diagonal = score[(i - 1) * width + j - 1] + if (lyric == heard[j - 1]) MATCH else MISMATCH
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

    val heardFor = IntArray(n) { -1 }
    var i = n
    var j = m
    while (i > 0 && j > 0) {
        when (from[i * width + j].toInt()) {
            1 -> {
                if (lyrics[i - 1] == heard[j - 1]) heardFor[i - 1] = j - 1
                i--
                j--
            }
            2 -> i--
            else -> j--
        }
    }
    return heardFor
}

private val WHITESPACE = Regex("\\s+")

/** How far outside the stanza a heard word's midpoint may sit and still belong to it. */
private const val LEAD_MS = 400L
private const val TAIL_MS = 200L

/** The room a last line is allowed to have when the next stamp is unknown. */
private const val LAST_SLOT_MS = 15_000L

/** The share of a line's letters that must have been heard before its words are trusted. */
private const val MIN_LINE_MATCH = 0.6

private const val MATCH = 3
private const val MISMATCH = -1
private const val GAP = -1

/** Beyond this many cells one line's alignment is not attempted. */
private const val MAX_CELLS = 400_000L
