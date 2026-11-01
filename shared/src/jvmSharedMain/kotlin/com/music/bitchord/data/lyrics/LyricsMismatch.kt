package com.music.bitchord.data.lyrics

/**
 * Whether a provider's lyrics are the words actually being sung.
 *
 * A search matches names, and a name is ambiguous: a cover channel's title can
 * put the original's artist where a song name belongs, and the catalogue then
 * answers with a different song by that name. The words heard from the sound
 * are the only evidence that settles it. [LyricsWordAlign] already lines a
 * lyric sheet up against a transcript, so a sheet whose line openings were
 * almost never heard is rejected rather than shown.
 *
 * Nothing is judged from too little sound: a short transcript, a failed
 * engine or an instrumental would reject everything. Only a transcript with
 * at least [MIN_HEARD_WORDS] words is evidence, and a sheet keeps unless less
 * than [MIN_AGREEING_FRACTION] of its lines were heard where they open.
 */
object LyricsMismatch {

    /** Below this much heard sound, there is nothing to judge against. */
    const val MIN_HEARD_WORDS = 40

    /** A sheet with fewer of its lines heard than this is the wrong song. */
    const val MIN_AGREEING_FRACTION = 0.15

    /** How the comparison came out; [rejected] is what callers act on. */
    data class Verdict(
        val heardWords: Int,
        val matchedLines: Int,
        val totalLines: Int,
        val rejected: Boolean,
    )

    fun judgeSheet(lines: List<LyricLine>, heard: List<LyricsWordAlign.Heard>): Verdict =
        judgeTexts(withinHeardSpan(lines, heard).map { it.text }, heard)

    fun judge(lines: List<String>, heard: List<LyricsWordAlign.Heard>): Verdict =
        judgeTexts(lines.filter { it.isNotBlank() }, heard)

    /**
     * Lines the transcript could have covered. A song check hears only the
     * opening, so the rest of a correct long song must not be counted against
     * it. An untimed sheet has no span to compare and is judged whole.
     */
    private fun withinHeardSpan(
        lines: List<LyricLine>,
        heard: List<LyricsWordAlign.Heard>,
    ): List<LyricLine> {
        val texts = lines.filter { !it.isGap && it.text.isNotBlank() }
        if (heard.isEmpty() || texts.isEmpty()) return texts
        val first = texts.first().timeMs
        val last = texts.last().timeMs
        if (last <= first || last <= 0L) return texts
        val from = heard.minOf { it.startMs }
        val to = heard.maxOf { it.endMs }
        return texts.filter { it.timeMs in from..to }.ifEmpty { texts }
    }

    private fun judgeTexts(texts: List<String>, heard: List<LyricsWordAlign.Heard>): Verdict {
        if (heard.size < MIN_HEARD_WORDS || texts.isEmpty()) {
            return Verdict(heard.size, 0, texts.size, rejected = false)
        }
        // A sheet and a transcript too long to align were not judged at all;
        // treating that as "no line matched" would throw a correct sheet away
        // (and delete its cached copy).
        val placement = LyricsWordAlign.placeLinesDetailed(texts, heard)
        if (!placement.attempted) {
            return Verdict(heard.size, 0, texts.size, rejected = false)
        }
        val matched = placement.starts.count { it != null }
        return Verdict(
            heardWords = heard.size,
            matchedLines = matched,
            totalLines = texts.size,
            rejected = matched.toDouble() / texts.size < MIN_AGREEING_FRACTION,
        )
    }

    /** The log line the lookup writes when it throws a provider's answer away. */
    fun logLine(source: LyricsSource, verdict: Verdict): String =
        "lyrics-mismatch: ${source.label} heard ${verdict.heardWords} words, " +
            "${verdict.matchedLines}/${verdict.totalLines} lines -> rejected"

    /**
     * Sheets already thrown away for a song, by provider and text. A later
     * lookup of the same song does not have to line the same wrong words up
     * again, and the lookup can skip them before showing them.
     */
    private const val REMEMBERED = 64

    private val rejected = object : LinkedHashMap<String, Boolean>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > REMEMBERED
    }

    private fun key(videoId: String, source: LyricsSource, lines: List<LyricLine>): String =
        buildString {
            append(videoId).append('\u0000').append(source.name).append('\u0000')
            lines.forEach { line -> append(line.timeMs).append('\u0001').append(line.text).append('\u0002') }
        }

    fun isRejected(videoId: String, source: LyricsSource, lines: List<LyricLine>): Boolean =
        synchronized(rejected) { rejected.containsKey(key(videoId, source, lines)) }

    fun rememberRejected(videoId: String, source: LyricsSource, lines: List<LyricLine>) {
        synchronized(rejected) { rejected[key(videoId, source, lines)] = true }
    }
}
