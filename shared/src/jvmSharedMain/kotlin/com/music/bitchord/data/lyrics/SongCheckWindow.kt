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
     * agreement and shift floors; the full opening follows only when this
     * pass had no answer to keep (see [needsFullOpening]).
     */
    const val FIRST_SECONDS = 40.0

    /**
     * Lines the first pass must have placed before the longer opening is
     * still worth asking for. The same floor the fit itself needs: once this
     * many lines are placed, the engine already has enough sheet to make its
     * call, and waiting for the ninety-second opening only returns the same
     * answer about a minute later (and may overwrite an earlier correction).
     */
    const val ENOUGH_FIRST_LINES = LyricsAiGuard.MIN_AI_LINES

    /**
     * Whether the full opening follows a first pass that ended with [result]
     * after placing [placedLines] lyric lines. A `no-answer` either spent the
     * engine on a refusal (a 429, no network) or never reached it, so it must
     * not be asked again. A pass that already placed enough lines and applied
     * its correction is kept as it is: waiting for the ninety-second opening
     * would only overwrite it a minute later, after the screen moved on. A
     * pass that found no fit or was refused still gets the longer audio to
     * try for the answer.
     */
    fun needsFullOpening(result: String, placedLines: Int): Boolean =
        result != "no-answer" &&
            !(result == "applied" && placedLines >= ENOUGH_FIRST_LINES)

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
