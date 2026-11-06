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
    /** The lyrics a song check answered with words whose fit was refused (kept as heard-check words). */
    val rejectedKey: Int? = null,
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
        if (songOnly && songRetry(knowledge, lyricsKey)) return SONG_ENGINES
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

    /** How many AI placements may run at once on the app's own initiative. */
    const val MAX_AUTOMATIC_REQUESTS = 2

    /**
     * Whether a placement may start beside [running] others. The cap is for what
     * the app starts by itself: a tap on the button is the listener waiting on
     * an answer, and two background passes must not make it look broken.
     */
    fun mayStartRequest(automatic: Boolean, running: Int): Boolean =
        !automatic || running < MAX_AUTOMATIC_REQUESTS

    /** Marks the second (last) song check on one lyrics text; see [songRetry]. */
    const val SONG_RETRY = "Deepgram Whisper retry"

    /**
     * Whether the song check may ask Whisper once more for these lyrics: its
     * first reply was spent but refused (words heard, fit below the bar), and
     * the one retry has not been spent yet. Two replies per lyrics text, total;
     * a refusal used to leave the song uncorrectable for good (vc147 NEON).
     */
    fun songRetry(knowledge: AiTimingKnowledge, lyricsKey: Int): Boolean {
        if (knowledge.rejectedKey != lyricsKey || isAccepted(knowledge, lyricsKey)) return false
        val spent = spentOn(knowledge, lyricsKey)
        return DEEPGRAM_WHISPER in spent && SONG_RETRY !in spent
    }

    /**
     * What one spent song reply adds to [previous] (the entry's attempted
     * engines for these lyrics): the engine, plus [SONG_RETRY] when the reply
     * belongs to the [retry] check — so a third check is never asked. Marked
     * per reply, not at the start, so a retry that heard nothing (no audio, a
     * server error) is not used up.
     */
    fun songSpent(previous: List<String>, engine: String, retry: Boolean): List<String> =
        (previous + engine + listOfNotNull(SONG_RETRY.takeIf { retry })).distinct()

    /**
     * Why the song check did not start, for its one log line: `deepgram-off`,
     * `accepted` (the kept answer stands), `spent` (Whisper's replies for these
     * lyrics are used up and none was good enough to show — the provider's
     * times stay, nothing else is tried), or `none`.
     */
    fun songSkipReason(knowledge: AiTimingKnowledge, lyricsKey: Int, deepgramUsable: Boolean): String = when {
        !deepgramUsable -> "deepgram-off"
        isAccepted(knowledge, lyricsKey) -> "accepted"
        DEEPGRAM_WHISPER in spentOn(knowledge, lyricsKey) -> "spent"
        else -> "none"
    }

    /**
     * Where the song check stands on [lyricsKey], for the base-apply line when
     * no base exists: `held-429`, `accepted`, `not-run`, `spent 1/2 retry-next`
     * (the one retry is still owed), `spent 1/2 no-retry` (the reply kept no
     * heard words to retry on) or `spent 2/2`.
     */
    fun songCheckState(knowledge: AiTimingKnowledge, lyricsKey: Int, retryHeld: Boolean): String {
        val spent = spentOn(knowledge, lyricsKey)
        return when {
            retryHeld -> "held-429"
            isAccepted(knowledge, lyricsKey) -> "accepted"
            SONG_RETRY in spent -> "spent 2/2"
            DEEPGRAM_WHISPER !in spent -> "not-run"
            songRetry(knowledge, lyricsKey) -> "spent 1/2 retry-next"
            else -> "spent 1/2 no-retry"
        }
    }

    /**
     * Whether a song check's reply spends Whisper on these lyrics. Only a
     * reply the engine actually gave (2xx) does; a server error is not an
     * answer, and spending on it left the song unchecked for good.
     */
    fun songReplySpends(httpCode: Int?): Boolean = httpCode != null && httpCode in 200..299

    /** Whether asking an engine in the AI placement used it up for these lyrics. */
    fun aiAskSpends(httpCode: Int?): Boolean = true

    /** What a song check result does to the sheet on screen, for its log line. */
    fun songSheetEffect(result: String): String = if (result == "applied") "moved" else "kept"

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
