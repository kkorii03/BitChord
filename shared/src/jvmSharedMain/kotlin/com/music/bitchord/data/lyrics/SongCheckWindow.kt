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
     * Whether the full pass may be written over the first. An early correction
     * is never replaced by a full pass that found no fit; without a first fit
     * the full answer stands whether or not it fit.
     */
    fun fullMayReplace(firstFitted: Boolean, fullFitted: Boolean): Boolean =
        !firstFitted || fullFitted

    /** Raise when the fit changes: every kept answer and refusal is judged once more, free. */
    const val FIT_VERSION = 1

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
