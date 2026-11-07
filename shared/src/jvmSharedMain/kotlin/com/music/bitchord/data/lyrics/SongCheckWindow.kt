package com.music.bitchord.data.lyrics

/**
 * The part of a record-timed song's lyrics a song check listens to.
 *
 * The check hears only the opening of the track (see `VideoAudio.songWav`),
 * so only the lines the provider timed inside that opening have anything to
 * be checked against. A file shorter than the requested window yields its
 * real length, and its lines beyond it are left alone rather than fitted
 * through words that were never heard.
 *
 * Pure, so the window rule is testable without audio or a decoder.
 */
object SongCheckWindow {

    /**
     * The opening a cold song check asks about first. The full ninety seconds
     * answer three minutes after the song starts: most of that is waiting for
     * the longer opening to reach the disk and Whisper reading it. Forty
     * seconds is an intro and a verse, and it still has to clear the same
     * agreement and shift floors; the full opening follows to refine it.
     */
    const val FIRST_SECONDS = 40.0

    /**
     * Whether the full opening follows a first pass that ended with [result].
     * A `no-answer` either spent the engine on a refusal (a 429, no network)
     * or never reached it, so it must not be asked again; every other result
     * is worth the longer audio — applied to refine it, otherwise to try.
     */
    fun needsFullOpening(result: String): Boolean = result != "no-answer"

    /**
     * The opening a playing video hears first, with the song check's quick request
     * (Groq when it can answer, which it does up to two minutes of sound). A video's
     * only other words were one whole-song Deepgram request, which answered 8-184 s in
     * on 11-08's phone run: WwFUZS8dv_4 kept a measurement 1.1 s early for its whole play.
     */
    const val VIDEO_QUICK_SECONDS = 90.0

    /**
     * Whether a video's words request also hears [VIDEO_QUICK_SECONDS] first, beside the
     * whole-song request that refines it. Only for the row playing: a next row has its
     * head only, and its bundle waits on the request. Only while the word engine is still
     * due on this sheet: a kept answer is not bought twice. A song has its own song check.
     * Only with the quick engine: without it the quick request is a second paid Deepgram
     * one beside the whole-song request (Sol review F4).
     */
    fun videoQuickFirst(isVideo: Boolean, nextRow: Boolean, wordEngineDue: Boolean, quickEngine: Boolean): Boolean =
        isVideo && !nextRow && wordEngineDue && quickEngine

    /**
     * Whether a video's opening is heard as its track starts, before its lyrics are in:
     * a streamed video (a downloaded one is decoded from its file, at the gate), with
     * the quick engine set up, and the same switches every automatic listen obeys. Only
     * when something will read the words: a lyrics lookup runs ([lyricsLookup], Sol F5)
     * and the word engine the AI ask needs is usable ([wordEngine], Sol F4).
     */
    fun videoHearAtStart(
        isVideo: Boolean,
        streamed: Boolean,
        quickEngine: Boolean,
        heavyWorkAllowed: Boolean,
        aiTimingAhead: Boolean,
        wordEngine: Boolean,
        lyricsLookup: Boolean,
    ): Boolean = isVideo && streamed && quickEngine && heavyWorkAllowed && aiTimingAhead && wordEngine && lyricsLookup

    /** A transcript asked in this language heard whatever was sung (Whisper's own detection). */
    const val ANY_LANGUAGE = "multi"

    /**
     * The language a video's quick listen asks in: the one its opening was heard in at the
     * track's start when that is the sheet's or [ANY_LANGUAGE], or when the sheet's own
     * language is unknown ([ANY_LANGUAGE]), so the kept words are read rather than bought
     * again (11-08 n5: a mixed title's "multi" words were asked again in "ko"/"en" and came
     * back the same, 5 of 9 rows); else the sheet's.
     */
    fun quickLanguage(heardAtStart: String?, sheetLanguage: String): String =
        if (heardAtStart != null &&
            (heardAtStart == sheetLanguage || heardAtStart == ANY_LANGUAGE || sheetLanguage == ANY_LANGUAGE)
        ) {
            heardAtStart
        } else {
            sheetLanguage
        }

        /**
     * Whether an answer from rough words may be written. Not over an exact fit of the same
     * sheet, or Deepgram answering first would lose to Groq arriving after it.
     */
    fun roughMayApply(sameSheet: Boolean, hasFit: Boolean, fitRough: Boolean): Boolean =
        !sameSheet || !hasFit || fitRough

/**
     * Whether the full pass may be written over the first. An early correction
     * is never replaced by a full pass that found no fit; without a first fit
     * the full answer stands whether or not it fit.
     */
    fun fullMayReplace(firstFitted: Boolean, fullFitted: Boolean): Boolean =
        !firstFitted || fullFitted

    /**
     * Raise when the fit changes: every kept answer and refusal is judged once more, free.
     *
     * 2: 11-08, [TimingPieces] refuses a move over 5 s on under 0.6 of the sheet, so a
     * stored pieces answer made under the old 15 s guard (eqKhhG4FVoQ, a 7.08 s jump on
     * 0.51) is dropped by `LyricsTiming.refit` when the re-judged fit refuses.
     */
    const val FIT_VERSION = 2

    /** Fewer new words' worth of sound than this says nothing new. */
    private const val LONGER_MS = 8_000L

    /**
     * Whether a sheet is worth fitting again on the kept words: older code judged it,
     * it was judged for another sheet, or the words now reach further. An engine is
     * spent once per sheet, so nothing else ever looks at it again (11-06).
     */
    fun refitDue(seenVersion: Int, sameSheet: Boolean, seenMs: Long, keptMs: Long): Boolean =
        seenVersion < FIT_VERSION || !sameSheet || keptMs > seenMs + LONGER_MS

    /** RIFF/WAVE header before the samples: 16 kHz, mono, 16-bit WAV. */
    const val WAV_HEADER_BYTES = 44

    /** One millisecond of 16 kHz mono 16-bit audio. */
    const val BYTES_PER_MS = 32

    /** How many ms of audio a WAV of [sizeBytes] carries. */
    fun wavMs(sizeBytes: Int): Long =
        (sizeBytes - WAV_HEADER_BYTES).coerceAtLeast(0) / BYTES_PER_MS.toLong()

    /**
     * The indices of lines whose recorded start lies inside `[0, untilMs]`.
     *
     * A window of zero (or less) is no audio at all — the header alone, or
     * nothing — so there is nothing to check even for a line timed at zero.
     */
    fun indices(timesMs: List<Long>, untilMs: Long): List<Int> =
        if (untilMs <= 0L) emptyList()
        else timesMs.indices.filter { timesMs[it] in 0..untilMs }
}
