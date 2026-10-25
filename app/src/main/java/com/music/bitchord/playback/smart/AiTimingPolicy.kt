package com.music.bitchord.playback.smart

/**
 * What the kept timing entry says about AI timing for one video, copied out of
 * [LyricsTiming.Entry] so the "ask again?" question can be answered — and
 * tested — without Android, the disk store or a coroutine.
 *
 * The rule it exists for: an answer that was paid for and agreed with most of
 * the song is the answer for these lyrics. Every automatic caller used to
 * decide for itself whether to ask (the screen, the next-song preparation,
 * the calibration sample), and each missed a case — a later measurement
 * giving up, a lyrics list arriving again, a quicker engine's answer being
 * "improved" by Whisper — so a single playback could decode the audio and pay
 * Gemini, Deepgram and Whisper several times over for the same track.
 */
internal data class AiTimingKnowledge(
    /** The lyrics (text hash) the kept answer was made for, if any. */
    val answerKey: Int? = null,
    /** Which engine gave it — [AiTimingPolicy.DEEPGRAM], [AiTimingPolicy.WHISPER] or [AiTimingPolicy.GEMINI]. */
    val answerEngine: String? = null,
    /** How much of the song agreed with it, 0–100; null on answers kept before it was recorded. */
    val answerMatch: Int? = null,
    /** The lyrics an automatic request last reported nothing usable for. */
    val failedKey: Int? = null,
    /** The lyrics the last request was for, and which engines it already spent on them. */
    val attemptedKey: Int? = null,
    val attemptedEngines: Set<String> = emptySet(),
    /** Older entries kept only this: Whisper was asked about these lyrics, answered or not. */
    val whisperKey: Int? = null,
)

internal object AiTimingPolicy {

    const val DEEPGRAM = "Deepgram"
    const val WHISPER = "Whisper"
    const val GEMINI = "Gemini"

    /** All engines, cheapest first: what a request may spend while nothing usable is kept. */
    val ALL_ENGINES = listOf(DEEPGRAM, WHISPER, GEMINI)

    /**
     * The quick engines asked alongside the measurement. Whisper is paid for
     * by the minute, so it waits until the measurement has given up.
     */
    val AHEAD_ENGINES = listOf(DEEPGRAM, GEMINI)

    /**
     * What a song check may spend. There is no video edit to tell apart from
     * the record, so the only thing worth asking for is a word timing — and
     * Whisper is a paid-per-minute upgrade the song path does not take.
     */
    val SONG_ENGINES = listOf(DEEPGRAM)

    /**
     * The agreement an answer needs to count as the answer, and to be applied
     * to the lyrics at all. Logs showed 50–67% answers pushing Korean lines
     * seconds off time, so the floor is the same 70% used by
     * [com.music.bitchord.data.lyrics.LyricsAiGuard]. Below it the answer is
     * kept (another engine's may be better) but not shown, and the next engine
     * may be tried once.
     */
    const val MIN_ACCEPTED_MATCH = com.music.bitchord.data.lyrics.LyricsAiGuard.MIN_MATCH_PERCENT

    /** Whether the kept answer was made for exactly these lyrics and agreed enough to stand. */
    fun isAccepted(knowledge: AiTimingKnowledge, lyricsKey: Int): Boolean =
        knowledge.answerKey == lyricsKey &&
            (knowledge.answerMatch == null || knowledge.answerMatch >= MIN_ACCEPTED_MATCH)

    /**
     * The engines one request may still spend on [lyricsKey], in order; empty
     * means don't ask at all — the answer is kept and paid for.
     *
     * [manual] is the AI button: it always forces a fresh measurement, even
     * over an accepted answer or one already attempted for these lyrics.
     * Automatic asks only spend an engine once per lyrics text; a failed or
     * low-agreement answer therefore gets the next engine once, never the
     * same one again. [measurementGaveUp] widens the pool to Whisper, whose
     * cost is only worth paying once the free measurement has nothing.
     *
     * [songOnly] is the record-timed song check: its pool is [SONG_ENGINES],
     * so Whisper and Gemini are never spent there — not even as the
     * post-measurement upgrade. The manual button is unchanged.
     */
    fun nextEngines(
        knowledge: AiTimingKnowledge,
        lyricsKey: Int,
        manual: Boolean,
        measurementGaveUp: Boolean,
        songOnly: Boolean = false,
    ): List<String> {
        if (manual) return ALL_ENGINES
        val spent = spentOn(knowledge, lyricsKey)
        if (isAccepted(knowledge, lyricsKey)) {
            // Once the free measurement has given up, the answer is all the
            // timing this song has, and a quicker engine's is coarser than
            // Whisper's word stamps: Whisper may still upgrade it — once.
            val upgrade = !songOnly && measurementGaveUp &&
                knowledge.answerEngine != WHISPER && WHISPER !in spent
            return if (upgrade) listOf(WHISPER) else emptyList()
        }
        val pool = when {
            songOnly -> SONG_ENGINES
            measurementGaveUp -> ALL_ENGINES
            else -> AHEAD_ENGINES
        }
        return pool.filter { it !in spent }
    }

    /** The engines already spent on [lyricsKey], by whatever the entry recorded. */
    private fun spentOn(knowledge: AiTimingKnowledge, lyricsKey: Int): Set<String> =
        buildSet {
            if (knowledge.attemptedKey == lyricsKey) addAll(knowledge.attemptedEngines)
            if (knowledge.whisperKey == lyricsKey) add(WHISPER)
            // Only Gemini's "no usable fit" was ever recorded this way.
            if (knowledge.failedKey == lyricsKey) add(GEMINI)
            // A low-agreement answer means that engine already ran for this text.
            if (knowledge.answerKey == lyricsKey) knowledge.answerEngine?.let(::add)
        }

    /** Whether any automatic request may still run for these lyrics. */
    fun shouldAskAgain(
        knowledge: AiTimingKnowledge,
        lyricsKey: Int,
        manual: Boolean,
        measurementGaveUp: Boolean,
        songOnly: Boolean = false,
    ): Boolean = nextEngines(knowledge, lyricsKey, manual, measurementGaveUp, songOnly).isNotEmpty()
}
