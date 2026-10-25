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

    /** RIFF/WAVE header before the samples: 16 kHz, mono, 16-bit WAV. */
    const val WAV_HEADER_BYTES = 44

    /** One millisecond of 16 kHz mono 16-bit audio. */
    const val BYTES_PER_MS = 32

    /** How many ms of audio a WAV of [sizeBytes] carries. */
    fun wavMs(sizeBytes: Int): Long =
        (sizeBytes - WAV_HEADER_BYTES).coerceAtLeast(0) / BYTES_PER_MS.toLong()

    /** The indices of lines whose recorded start lies inside `[0, untilMs]`. */
    fun indices(timesMs: List<Long>, untilMs: Long): List<Int> =
        timesMs.indices.filter { timesMs[it] in 0..untilMs }
}
