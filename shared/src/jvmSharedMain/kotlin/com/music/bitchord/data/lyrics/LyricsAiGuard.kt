package com.music.bitchord.data.lyrics

/**
 * The rules that decide whether a paid-for AI timing answer may move the
 * lyrics, and which paid engines are worth asking at all.
 *
 * Kept here, apart from the Android timing engine, so they can be tested as
 * plain functions. The timing engine itself still does the measuring; these
 * only say yes or no.
 */
object LyricsAiGuard {

    /**
     * How much of the song must agree with an AI answer before its shift is
     * applied. Logs showed Whisper answers at 50–67% moving Korean lyrics by
     * several seconds: enough lines matched to fit a straight line, not enough
     * to know the answer is about this video. 70% is the floor where the fit
     * follows most of the song, and the answer stops being a guess.
     */
    const val MIN_MATCH_PERCENT = 70

    /**
     * A music video's own edit can start well before the record (a spoken
     * intro, a long establishing shot). Past twenty seconds the AI answer is
     * usually following the video's story rather than the song, so on a video
     * (isVideo) a larger shift is refused. Record-timed audio has no such rule.
     */
    const val VIDEO_MAX_SHIFT_MS = 20_000L

    /**
     * How far a song check may move the words. The song is played from the
     * record itself, so its first sung line is the record's: an answer that
     * starts the words later than this is following something else (a live
     * version, a different edit) and is refused. Deliberately a policy on
     * applying the answer, not a change to the fitting arithmetic.
     */
    const val SONG_MAX_SHIFT_MS = 15_000L

    /** An answer whose agreement was never recorded (older entries) is left as it was. */
    fun agreementAllows(matchPercent: Int?): Boolean =
        matchPercent == null || matchPercent >= MIN_MATCH_PERCENT

    /** The first sung line's movement; a video may not be pushed further than [VIDEO_MAX_SHIFT_MS]. */
    fun shiftAllows(isVideo: Boolean, firstLineShiftMs: Long): Boolean =
        !isVideo || firstLineShiftMs in -VIDEO_MAX_SHIFT_MS..VIDEO_MAX_SHIFT_MS

    /**
     * The same floor for a record-timed song: [SONG_MAX_SHIFT_MS], whichever
     * way the answer wants to move it. Kept apart from [shiftAllows] so the
     * video rule and every existing record-timed path keep behaving as before.
     */
    fun songShiftAllows(firstLineShiftMs: Long): Boolean =
        firstLineShiftMs in -SONG_MAX_SHIFT_MS..SONG_MAX_SHIFT_MS

    /** Whether this AI answer may be applied to these lyrics at all. */
    fun allowsAiTiming(isVideo: Boolean, matchPercent: Int?, firstLineShiftMs: Long): Boolean =
        agreementAllows(matchPercent) && shiftAllows(isVideo, firstLineShiftMs)

    /**
     * Whether Deepgram is worth asking for this song. On Korean songs it
     * answered "not enough" five times out of five in the logs, and on
     * instrumental uploads there is nothing to hear: both spend the upload and
     * the request for an answer that cannot come.
     */
    fun wantsDeepgram(title: String, artist: String, lyricsTexts: List<String>): Boolean =
        !isKoreanSong(title, artist, lyricsTexts) && !isInstrumentalTitle(title)

    /**
     * Whether a record-timed song's own lyrics are worth checking against its
     * audio. A video's timing is measured against the record instead (chroma),
     * so this is only for the song version: timed words, at least three sung
     * lines to fit a line through, and words Deepgram can hear — the same test
     * the video path already uses. Instrumentals and Korean songs are refused
     * there, and the song check pays for nothing else.
     */
    fun songTimingWanted(
        isVideo: Boolean,
        timed: Boolean,
        sungLines: Int,
        title: String,
        artist: String,
        texts: List<String>,
    ): Boolean = !isVideo && timed && sungLines >= 3 && wantsDeepgram(title, artist, texts)

    /**
     * The least number of usable (non-blank) lines an AI timing answer may
     * move: [LyricsTiming.placeWithAi] returns null below this before any
     * engine is asked, so an action offered on fewer lines can only end in
     * `aiFailed`. Kept in step with `LyricsTiming.MIN_AI_LINES`.
     */
    const val MIN_AI_LINES = 3

    /**
     * The window a song's audio must fit to be sent inline to the engines,
     * mirroring [com.music.bitchord.playback.smart.VideoAudio]'s decode:
     * an inline request is capped at seven minutes, and a decode shorter than
     * ten seconds is not enough to hear. An unknown (non-positive) length is
     * not refused here — the player often learns it a beat after the track.
     */
    const val MAX_TIMING_AUDIO_MS = 7L * 60 * 1000
    const val MIN_TIMING_AUDIO_MS = 10_000L

    /** Whether [durationMs] is a length the inline audio path can decode. */
    fun audioFitsInline(durationMs: Long): Boolean =
        durationMs <= 0L || durationMs in MIN_TIMING_AUDIO_MS..MAX_TIMING_AUDIO_MS

    /**
     * Whether a hand-asked timing has an engine that can answer for this song.
     *
     * No engine can move fewer than [MIN_AI_LINES] lines. Deepgram is skipped
     * for Korean and instrumental titles and cannot run without the song's
     * audio, nor for a track too long or too short to decode inline; Gemini can
     * still fetch the video. A Deepgram-only setup therefore has nothing to ask
     * when any of those applies, and offering the action would only end in
     * `aiFailed`.
     */
    fun manualTimingAvailable(
        geminiUsable: Boolean,
        deepgramUsable: Boolean,
        audioAvailable: Boolean,
        durationMs: Long,
        title: String,
        artist: String,
        lyricsTexts: List<String>,
    ): Boolean {
        if (lyricsTexts.count { it.isNotBlank() } < MIN_AI_LINES) return false
        if (geminiUsable) return true
        return deepgramUsable &&
            audioAvailable &&
            audioFitsInline(durationMs) &&
            wantsDeepgram(title, artist, lyricsTexts)
    }

    /** Korean when the name is written in Hangul or the words themselves are. */
    fun isKoreanSong(title: String, artist: String, lyricsTexts: List<String>): Boolean =
        OriginalNames.worthTrying(title, artist) || Deepgram.languageFor(lyricsTexts) == "ko"

    /**
     * An upload labelled as an instrumental: there are no words to transcribe,
     * so the Gemini request is skipped before it is paid for.
     */
    fun isInstrumentalTitle(title: String): Boolean =
        title.isNotBlank() &&
            (INSTRUMENTAL.containsMatchIn(title) || INST.containsMatchIn(title) || MR.containsMatchIn(title))

    private val INSTRUMENTAL = Regex("""\binstrumental\b""", RegexOption.IGNORE_CASE)

    /** "Inst." and "Inst" — a word on its own, never the start of "instrument". */
    private val INST = Regex("""\binst\b\.?""", RegexOption.IGNORE_CASE)

    /**
     * "MR" in capitals (MR, (MR), MR ver., MR제거). Deliberately
     * case-sensitive: "Mr. Brightside" is a song title, not an instrumental.
     */
    private val MR = Regex("""(^|[^A-Za-z])MR([^A-Za-z]|$)""")
}
