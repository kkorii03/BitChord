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

    /** `sync-dump: line <videoId> <number> <start ms> <first 60 chars>`; [number] is 1-based. */
    fun line(videoId: String, number: Int, startMs: Long, text: String): String {
        // One record per line: a break inside the text would split the block.
        val shown = text.take(TEXT_CHARS).replace('\n', ' ').replace('\r', ' ')
        return "$PREFIX line $videoId $number $startMs $shown"
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
        lines.forEachIndexed { index, lyric ->
            add(line(videoId, index + 1, lyric.timeMs, lyric.text))
        }
        add(end(videoId))
    }
}
