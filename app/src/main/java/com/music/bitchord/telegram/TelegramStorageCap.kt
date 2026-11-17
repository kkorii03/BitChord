package com.music.bitchord.telegram

import org.drinkless.tdlib.TdApi

/**
 * [KO] A ceiling on the audio TDLib keeps under `telegram-native/files`.
 *
 * Every Telegram track that was played, auditioned or verified stayed there:
 * TDLib's own storage optimizer is off by default and nothing here ever asked
 * it to delete, so a FLAC channel grew the app by gigabytes a month and only
 * a logout or "clear storage" gave the space back (review 11-17, C3b-1).
 *
 * TDLib's `optimizeStorage` does the deleting, oldest access first, down to
 * [MAX_BYTES], and drops anything not touched for [TTL_SECONDS]. The immunity
 * delay stays at TDLib's default (-1), so a file written in the last hour is
 * never a candidate. The manager sends it only when no stream, metadata or
 * search lease is held, so no reader is in the middle of a file; a listener's
 * own downloads are copies in the app's download folder and are not here.
 *
 * No timer: [takeDue] is asked on that lease event and says yes at most once
 * per [MIN_INTERVAL_MS], so a day of track changes costs one pass.
 */
internal class TelegramStorageCap(private val minIntervalMs: Long = MIN_INTERVAL_MS) {

    private var lastRunMs: Long? = null

    /** True when a pass is due at [nowMs] (a monotonic clock), and records it as run. */
    @Synchronized
    fun takeDue(nowMs: Long): Boolean {
        val last = lastRunMs
        if (last != null && nowMs - last < minIntervalMs) return false
        lastRunMs = nowMs
        return true
    }

    companion object {
        /** About fifty full FLAC albums' worth of tracks; the oldest go first past this. */
        const val MAX_BYTES = 2L shl 30

        /** A file nobody has opened for a month is not a cache any more. */
        const val TTL_SECONDS = 30 * 86_400

        const val MIN_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** The pass runs on the client worker before a close; it must not hold it long. */
        const val TIMEOUT_MS = 5_000L

        /** TDLib's defaults (-1, empty lists) for everything but the size and age limits. */
        fun request(): TdApi.OptimizeStorage = TdApi.OptimizeStorage(
            MAX_BYTES,
            TTL_SECONDS,
            -1,
            -1,
            arrayOf<TdApi.FileType>(),
            LongArray(0),
            LongArray(0),
            false,
            0,
        )
    }
}
