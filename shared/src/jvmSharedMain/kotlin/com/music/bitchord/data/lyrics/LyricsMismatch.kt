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

    fun judgeSheet(lines: List<LyricLine>, heard: List<LyricsWordAlign.Heard>): Verdict {
        val texts = lines.filter { !it.isGap && it.text.isNotBlank() }
        // A sheet with no times cannot be matched to the part of the song the
        // transcript covers: judging all of it against the opening would hide
        // a correct long sheet. Without timings it is not evidence either way.
        if (texts.none { it.timeMs > 0L }) {
            return Verdict(heard.size, 0, texts.size, rejected = false)
        }
        return judgeTexts(withinHeardSpan(lines, heard).map { it.text }, heard)
    }

    fun judge(lines: List<String>, heard: List<LyricsWordAlign.Heard>): Verdict =
        judgeTexts(lines.filter { it.isNotBlank() }, heard)

    /**
     * Lines the transcript could have covered. A song check hears only the
     * opening, so the rest of a correct long song must not be counted against
     * it. An untimed sheet never reaches here: [judgeSheet] leaves it alone.
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
        val cells = texts.sumOf { LyricsWordAlign.fold(it).length.toLong() } *
            heard.sumOf { LyricsWordAlign.fold(it.text).length.toLong() }
        if (cells <= OPENING_CELLS) {
            val placement = LyricsWordAlign.placeLinesDetailed(texts, heard)
            if (placement.attempted) return verdict(texts, placement, heard)
        }
        // A sheet and a transcript too long to align together are judged on
        // the opening lines that do fit, rather than not at all: skipping the
        // verdict would let a wrong sheet through whenever Whisper heard the
        // whole song.
        val window = openingWithinBudget(texts, heard)
        if (window.isEmpty()) return Verdict(heard.size, 0, texts.size, rejected = false)
        val bounded = LyricsWordAlign.placeLinesDetailed(window, heard)
        if (!bounded.attempted) return Verdict(heard.size, 0, window.size, rejected = false)
        return verdict(window, bounded, heard)
    }

    private fun verdict(
        texts: List<String>,
        placement: LyricsWordAlign.Placement,
        heard: List<LyricsWordAlign.Heard>,
    ): Verdict {
        val matched = placement.starts.count { it != null }
        return Verdict(
            heardWords = heard.size,
            matchedLines = matched,
            totalLines = texts.size,
            rejected = matched.toDouble() / texts.size < MIN_AGREEING_FRACTION,
        )
    }

    /**
     * As many opening lines as the alignment's cell budget can hold beside
     * the whole transcript. The opening is where a correct sheet and the
     * heard sound meet; a wrong sheet has nothing there to match either way.
     */
    private fun openingWithinBudget(
        texts: List<String>,
        heard: List<LyricsWordAlign.Heard>,
    ): List<String> {
        val heardLetters = heard.sumOf { LyricsWordAlign.fold(it.text).length }
        if (heardLetters <= 0) return emptyList()
        val budget = OPENING_CELLS / heardLetters
        if (budget <= 0) return emptyList()
        val window = ArrayList<String>()
        var letters = 0L
        for (text in texts) {
            val line = LyricsWordAlign.fold(text).length
            if (line > budget && window.isEmpty()) return emptyList()
            if (window.isNotEmpty() && letters + line > budget) break
            window += text
            letters += line
            if (letters >= budget) break
        }
        return window
    }

    /** The opening of a sheet says what it needs to; the aligner's own limit is for whole songs. */
    private const val OPENING_CELLS = 4_000_000L

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

    /**
     * How many of a video's sheets must be thrown away before the *sound* is
     * the one at fault. One provider's wrong sheet is a wrong sheet; two
     * catalogues independently failing to describe what is being sung is the
     * recording. The device log that prompted this (11-06) had three.
     */
    const val MIN_REJECTED_SHEETS = 2

    private val rejectedSheets = object : LinkedHashMap<String, Int>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Int>) = size > REMEMBERED
    }
    private val lastVerdict = object : LinkedHashMap<String, Verdict>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Verdict>) = size > REMEMBERED
    }

    private val _wrongSounds = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * Video ids that have just become [wrongSound], so a player that swapped
     * to one of them can undo it. One emission per video: the second rejected
     * sheet is what makes the sound the culprit.
     */
    val wrongSounds: kotlinx.coroutines.flow.SharedFlow<String> = _wrongSounds

    /**
     * The verdict that judged [videoId]'s sound as singing something else than
     * the sheets offered for it, or null. Read, never measured: a caller acts
     * on what the lyrics lookup has already heard, and asks for no decode of
     * its own.
     */
    fun wrongSound(videoId: String): Verdict? {
        synchronized(rejectedSheets) {
            if ((rejectedSheets[videoId] ?: 0) >= MIN_REJECTED_SHEETS) {
                return lastVerdict[videoId]
            }
        }
        return null
    }

    /**
     * The audio switch's own line for undoing a swap to a sound the sheets
     * proved is another song, or null while it may play — see
     * `KoAudioSwitch.resolveAudioUncached` and the player's revert effect.
     */
    fun wrongSoundNote(videoId: String): String? = wrongSound(videoId)?.let {
        "audio switch: revert $videoId (lyrics mismatch ${it.matchedLines}/${it.totalLines})"
    }

    private fun key(videoId: String, source: LyricsSource, lines: List<LyricLine>): String =
        buildString {
            append(videoId).append('\u0000').append(source.name).append('\u0000')
            lines.forEach { line -> append(line.timeMs).append('\u0001').append(line.text).append('\u0002') }
        }

    fun isRejected(videoId: String, source: LyricsSource, lines: List<LyricLine>): Boolean =
        synchronized(rejected) { rejected.containsKey(key(videoId, source, lines)) }

    fun rememberRejected(videoId: String, source: LyricsSource, lines: List<LyricLine>, verdict: Verdict) {
        synchronized(rejected) { rejected[key(videoId, source, lines)] = true }
        val sheets = synchronized(rejectedSheets) {
            val count = (rejectedSheets[videoId] ?: 0) + 1
            rejectedSheets[videoId] = count
            lastVerdict[videoId] = verdict
            count
        }
        // Only the sheet that makes the count say the *sound* is wrong is an
        // event; the first one is still just a wrong sheet.
        if (sheets == MIN_REJECTED_SHEETS) _wrongSounds.tryEmit(videoId)
    }
}
