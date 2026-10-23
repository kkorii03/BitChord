package com.music.bitchord.data.lyrics

import kotlin.math.abs

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

    /** An answer whose agreement was never recorded (older entries) is left as it was. */
    fun agreementAllows(matchPercent: Int?): Boolean =
        matchPercent == null || matchPercent >= MIN_MATCH_PERCENT

    /** The first sung line's movement; a video may not be pushed further than [VIDEO_MAX_SHIFT_MS]. */
    fun shiftAllows(isVideo: Boolean, firstLineShiftMs: Long): Boolean =
        !isVideo || abs(firstLineShiftMs) <= VIDEO_MAX_SHIFT_MS

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
