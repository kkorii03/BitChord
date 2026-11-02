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
     * The opening the first pass reads. The short one exists to correct a
     * visible screen while the song plays; with the screen off nobody sees
     * that correction, so the first pass reads the full opening as it did
     * before and no second pass runs (one decode and one engine call, not two).
     */
    fun firstPassSeconds(screenOn: Boolean, fullSeconds: Double): Double =
        if (screenOn) FIRST_SECONDS else fullSeconds

    /**
     * The window for the second pass, or null when it must not run. The screen
     * is read again after the first pass: if it went off meanwhile the
     * correction has nobody to show, so the full opening is not read twice.
     * A first pass that already read the full opening never follows with
     * another one.
     */
    fun secondPassSeconds(
        screenOnAtStart: Boolean,
        screenOnNow: Boolean,
        result: String,
        fullSeconds: Double,
    ): Double? =
        if (screenOnAtStart && screenOnNow && needsFullOpening(result)) fullSeconds else null

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
