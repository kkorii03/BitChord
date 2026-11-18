package com.music.bitchord.ko.downloads

/**
 * [KO] OPT2 F4: a download's progress callback fires on every 8 KiB read, and each one
 * published two state flows the UI recomposes on. Only a change of the whole percent shown
 * is worth publishing. One gate per download loop (the loops call it from one coroutine).
 */
internal class KoPercentGate {
    private var lastPercent = -1

    /** The fraction to publish for [written] of [total], or null when the percent shown would not move. */
    fun next(written: Long, total: Long): Float? {
        // Unknown length: published as it always was (no caller sends one today; Downloader skips it).
        if (total <= 0L) return written.toFloat() / total
        val percent = (written * 100 / total).toInt()
        if (percent == lastPercent) return null
        lastPercent = percent
        return written.toFloat() / total
    }
}
