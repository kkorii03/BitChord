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
     * Whether Deepgram is worth asking for this song: not for an instrumental
     * upload, which has nothing to hear.
     *
     * Korean songs were refused here too, on the evidence of nova-3 answering
     * "not enough" five times out of five. Every check now asks Deepgram's
     * whisper-large ([Deepgram.MODEL_WHISPER], language read off the lyrics),
     * which hears Korean singing: the sync scorer, on the same model, places
     * 50-92% of the lines of the Korean covers in the user's test set. With
     * the refusal, every Korean cover — half that set — was never checked at
     * all (11-02 smoke: "song check skipped by its guard" on each one).
     */
    @Suppress("UNUSED_PARAMETER") // kept: callers name the song the same way as before
    fun wantsDeepgram(title: String, artist: String, lyricsTexts: List<String>): Boolean =
        deepgramSkipReason(title) == null

    /**
     * Why Deepgram is not asked for a song, or null when it is — the same
     * answer [wantsDeepgram] gives, named for the log.
     *
     * An instrumental upload is the only reason left. The Korean label that
     * the skip line used to print stayed behind after the refusal went, so the
     * 11-02 diagnostics read "Deepgram skipped (Korean)" for songs
     * whisper-large hears, and the sync investigation chased a guard that no
     * longer existed.
     */
    fun deepgramSkipReason(title: String): String? =
        if (isInstrumentalTitle(title)) "instrumental" else null

    /**
     * Whether a record-timed song's own lyrics are worth checking against its
     * audio. A video's timing is measured against the record instead (chroma),
     * so this is only for the song version: timed words, at least three sung
     * lines to fit a line through, and words Deepgram can hear — the same test
     * the video path already uses. Instrumentals are refused there; Korean
     * songs are checked (whisper-large hears them, 11-02).
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
     * for an instrumental title and cannot run without the song's
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
    fun isKoreanSong(title: String, artist: String, lyricsTexts: List<String>): Boolean {
        // "Lethe (ENG cover)" by a Korean artist is an English cover, not a
        // Korean recording: an explicit non-Korean version tag wins over the
        // Hangul in the artist credit. Without this it skipped the transcript
        // and kept the Korean original's sheet.
        val named = namedLanguage(title)
        if (named != null && named != Language.KOREAN) return false
        return OriginalNames.worthTrying(title, artist) || Deepgram.languageFor(lyricsTexts) == "ko"
    }

    /**
     * An upload labelled as an instrumental: there are no words to transcribe,
     * so the Gemini request is skipped before it is paid for.
     */
    fun isInstrumentalTitle(title: String): Boolean =
        title.isNotBlank() &&
            (INSTRUMENTAL.containsMatchIn(title) || INST.containsMatchIn(title) || MR.containsMatchIn(title))

    /**
     * The language a title names when it says a version rather than an
     * original: "(ENG cover)", "English ver.", "Japanese ver.", "(JP)",
     * "日本語", "Korean ver.". Only an explicit tag is returned; a title that
     * names two is ambiguous and returns null.
     */
    enum class Language { KOREAN, JAPANESE, ENGLISH }

    fun namedLanguage(title: String): Language? {
        val named = buildSet {
            if (ENGLISH_TAG.containsMatchIn(title)) add(Language.ENGLISH)
            if (JAPANESE_TAG.containsMatchIn(title)) add(Language.JAPANESE)
            if (KOREAN_TAG.containsMatchIn(title)) add(Language.KOREAN)
        }
        return named.singleOrNull()
    }

    private val ENGLISH_TAG = Regex("""(?i)(?<![a-z])(eng|english)(?![a-z])""")
    private val JAPANESE_TAG = Regex("""(?i)(?<![a-z])(jp|jpn|japanese)(?![a-z])|日本語|日語|日本语""")
    private val KOREAN_TAG = Regex("""(?i)(?<![a-z])(kr|kor|korean)(?![a-z])|한국어|한국말|韓国語|韓語|국어""")

    /**
     * The script most of a sheet is written in. Latin wins English, Hangul
     * wins Korean, kana (with its Kanji) wins Japanese; a tie or a sheet with
     * no letters (an instrumental placeholder) is not evidence either way.
     */
    fun dominantLanguage(lines: List<LyricLine>): Language? {
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        for (line in lines) {
            for (ch in line.text) {
                when (Character.UnicodeScript.of(ch.code)) {
                    Character.UnicodeScript.HANGUL -> hangul++
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA,
                    -> kana++
                    Character.UnicodeScript.HAN -> han++
                    Character.UnicodeScript.LATIN -> latin++
                    else -> Unit
                }
            }
        }
        // Kanji without kana may be Chinese, and the title tags this rule
        // knows are Korean/Japanese/English; it is not evidence for them.
        val japanese = if (kana > 0) kana + han else 0
        val top = maxOf(hangul, japanese, latin)
        if (top <= 0) return null
        if (listOf(hangul, japanese, latin).count { it == top } != 1) return null
        return when (top) {
            hangul -> Language.KOREAN
            japanese -> Language.JAPANESE
            else -> Language.ENGLISH
        }
    }

    /** Whether [lines] contradict the language [title] names. Nothing named means nothing to check. */
    fun scriptMismatch(title: String, lines: List<LyricLine>): Boolean =
        namedLanguage(title)?.let { scriptMismatch(it, lines) } ?: false

    fun scriptMismatch(language: Language, lines: List<LyricLine>): Boolean {
        val dominant = dominantLanguage(lines) ?: return false
        return language != dominant
    }

    /** Sheets thrown away for a title's language, so a later lookup does not fetch them again. */
    private const val REMEMBERED = 64

    private val rejected = object : LinkedHashMap<String, Boolean>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > REMEMBERED
    }

    private fun rejectionKey(videoId: String, source: LyricsSource, lines: List<LyricLine>): String =
        buildString {
            append(videoId).append('\u0000').append(source.name).append('\u0000')
            lines.forEach { line -> append(line.timeMs).append('\u0001').append(line.text).append('\u0002') }
        }

    fun isScriptRejected(videoId: String, source: LyricsSource, lines: List<LyricLine>): Boolean =
        synchronized(rejected) { rejected.containsKey(rejectionKey(videoId, source, lines)) }

    fun rememberScriptRejected(videoId: String, source: LyricsSource, lines: List<LyricLine>) {
        synchronized(rejected) { rejected[rejectionKey(videoId, source, lines)] = true }
    }

    private val INSTRUMENTAL = Regex("""\binstrumental\b""", RegexOption.IGNORE_CASE)

    /** "Inst." and "Inst" — a word on its own, never the start of "instrument". */
    private val INST = Regex("""\binst\b\.?""", RegexOption.IGNORE_CASE)

    /**
     * "MR" in capitals (MR, (MR), MR ver., MR제거). Deliberately
     * case-sensitive: "Mr. Brightside" is a song title, not an instrumental.
     */
    private val MR = Regex("""(^|[^A-Za-z])MR([^A-Za-z]|$)""")
}
