package com.music.bitchord.data.lyrics

/**
 * The lines sync-test mode writes to logcat: one block per song, the figures
 * that decided its timing and the start every displayed line ended up with.
 *
 * Formatting only — no Android, no logging, no file — so the shape the
 * scoring script reads is tested on the JVM.
 */
object SyncDump {

    const val PREFIX = "sync-dump:"

    /** How much of a line's text is written; enough to identify it, not the whole song. */
    const val TEXT_CHARS = 60

    /**
     * `sync-dump: begin <videoId> playing=<id> source=<provider>
     * base=<none|measured|ai> shift=<ms> match=<percent> lines=<count>`
     *
     * A missing [matchPercent] is written as 0, as the diagnostics line does.
     */
    fun begin(
        videoId: String,
        playing: String,
        source: String,
        base: String,
        shiftMs: Long,
        matchPercent: Int?,
        lines: Int,
    ): String = "$PREFIX begin $videoId playing=$playing source=$source base=$base " +
        "shift=$shiftMs match=${matchPercent ?: 0} lines=$lines"

    /**
     * `sync-dump: line <videoId> <number> <start ms> rep=<k>/<n> words=<w> <first 60 chars>`;
     * [number] is 1-based.
     *
     * [repeatIndex] is which time this exact text is sung ([repeatCount] in
     * total) and [words] is how many words it holds. Both exist because the
     * scoring cannot tell a real error from a measurement one without them: a
     * one-word line and a chorus line that comes round four times are exactly
     * the lines a forced aligner cannot place, and the app's own timing for
     * them is what a listener complains about (phone 11-04: the only lines
     * scored >1.5 s off were of these two shapes).
     */
    fun line(
        videoId: String,
        number: Int,
        startMs: Long,
        text: String,
        repeatIndex: Int = 1,
        repeatCount: Int = 1,
        words: Int = 0,
    ): String {
        // One record per line: a break inside the text would split the block.
        val shown = text.take(TEXT_CHARS).replace('\n', ' ').replace('\r', ' ')
        return "$PREFIX line $videoId $number $startMs " +
            "rep=$repeatIndex/$repeatCount words=$words $shown"
    }

    fun end(videoId: String): String = "$PREFIX end $videoId"

    /** The whole block: [begin], one [line] for every displayed line, then [end]. */
    fun dump(
        videoId: String,
        playing: String,
        source: String,
        base: String,
        shiftMs: Long,
        matchPercent: Int?,
        lines: List<LyricLine>,
    ): List<String> = buildList {
        add(begin(videoId, playing, source, base, shiftMs, matchPercent, lines.size))
        // How often each line's own words come round, so a scored error on a
        // chorus line can be read as "the wrong repetition" rather than drift.
        val totals = HashMap<String, Int>()
        val keys = lines.map { keyOf(it.text) }
        keys.forEach { key -> totals[key] = (totals[key] ?: 0) + 1 }
        val seen = HashMap<String, Int>()
        lines.forEachIndexed { index, lyric ->
            val key = keys[index]
            val round = (seen[key] ?: 0) + 1
            seen[key] = round
            add(
                line(
                    videoId = videoId,
                    number = index + 1,
                    startMs = lyric.timeMs,
                    text = lyric.text,
                    repeatIndex = round,
                    repeatCount = totals[key] ?: 1,
                    words = wordCount(lyric.text),
                ),
            )
        }
        add(end(videoId))
    }

    /** The words a repeat is counted on: case, spacing and punctuation are not part of it. */
    private fun keyOf(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun wordCount(text: String): Int =
        text.split(' ', '\n', '\r', '\t').count { it.any(Char::isLetterOrDigit) }
}
