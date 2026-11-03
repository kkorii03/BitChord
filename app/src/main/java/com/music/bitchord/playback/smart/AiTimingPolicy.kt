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
    /**
     * Which engine gave it — [AiTimingPolicy.DEEPGRAM], [AiTimingPolicy.WHISPER],
     * [AiTimingPolicy.GEMINI], or [AiTimingPolicy.DEEPGRAM_WHISPER] for a song check.
     */
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

    /**
     * Whisper through Deepgram's key, as the song check names it. Kept apart
     * from [WHISPER] (and from [DEEPGRAM]) so a song already tried with
     * nova-3, which hears almost no singing, is asked again once with the
     * model that does.
     */
    const val DEEPGRAM_WHISPER = "Deepgram Whisper"

    /**
     * All engines a video request may spend. Deepgram's Whisper is used here
     * instead of nova-3, which hears almost no singing over a band; it is the
     * same request and key the song check uses.
     */
    val ALL_ENGINES = listOf(DEEPGRAM_WHISPER, GEMINI)

    /**
     * The engines asked alongside a video's measurement. nova-3 was cheap but
     * deaf to songs seen on the phone (37 words, 5 of 44 lines placed), while
     * Whisper through the same key heard hundreds. Gemini stays as before.
     */
    val AHEAD_ENGINES = listOf(DEEPGRAM_WHISPER, GEMINI)

    /**
     * What a song check may spend. There is no video edit to tell apart from
     * the record, so the only thing worth asking for is a word timing. For a
     * song that is Whisper (through Deepgram's key): nova-3 hears almost no
     * singing over a band, and a 90-second opening gave it only a few words.
     * Its own engine id means songs tried with nova-3 before are checked once
     * more, while a Whisper answer or attempt still stops a second request.
     */
    val SONG_ENGINES = listOf(DEEPGRAM_WHISPER)

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
     * same one again. [measurementGaveUp] moves the pool from the ahead set to
     * every engine still unspent; both hold Deepgram's Whisper now.
     *
     * [songOnly] is the record-timed song check: its pool is [SONG_ENGINES]
     * (Deepgram's Whisper only), so the video path's Gemini and its one
     * Whisper upgrade are never spent there. The manual button is unchanged.
     *
     * [unsettled] means the heard words put the accepted timing beyond any
     * narrow refine. The answer is then not the answer, and the engines not
     * yet spent on these lyrics may be asked once, as if the measurement had
     * given up.
     */
    fun nextEngines(
        knowledge: AiTimingKnowledge,
        lyricsKey: Int,
        manual: Boolean,
        measurementGaveUp: Boolean,
        songOnly: Boolean = false,
        unsettled: Boolean = false,
    ): List<String> {
        if (manual) return ALL_ENGINES
        val spent = spentOn(knowledge, lyricsKey)
        if (isAccepted(knowledge, lyricsKey) && !unsettled) {
            // Once the free measurement has given up, the answer is all the
            // timing this song has, and a quicker engine's is coarser than
            // Whisper's word stamps: Whisper may still upgrade it — once.
            val upgrade = !songOnly && measurementGaveUp &&
                knowledge.answerEngine != WHISPER &&
                knowledge.answerEngine != DEEPGRAM_WHISPER &&
                WHISPER !in spent
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
            // A legacy Whisper attempt was the same Deepgram Whisper request
            // the pools ask for now, so it must spend both ids: otherwise the
            // song is billed for the identical request a second time.
            if (knowledge.whisperKey == lyricsKey) {
                add(WHISPER)
                add(DEEPGRAM_WHISPER)
            }
            // Only Gemini's "no usable fit" was ever recorded this way.
            if (knowledge.failedKey == lyricsKey) add(GEMINI)
            // A low-agreement answer means that engine already ran for this text.
            if (knowledge.answerKey == lyricsKey) {
                knowledge.answerEngine?.let(::add)
                // Whisper and Deepgram Whisper are the same request; an
                // unsettled answer must not buy that request again.
                if (knowledge.answerEngine == WHISPER) add(DEEPGRAM_WHISPER)
            }
        }

    /** Whether any automatic request may still run for these lyrics. */
    fun shouldAskAgain(
        knowledge: AiTimingKnowledge,
        lyricsKey: Int,
        manual: Boolean,
        measurementGaveUp: Boolean,
        songOnly: Boolean = false,
        unsettled: Boolean = false,
    ): Boolean = nextEngines(knowledge, lyricsKey, manual, measurementGaveUp, songOnly, unsettled).isNotEmpty()
}
