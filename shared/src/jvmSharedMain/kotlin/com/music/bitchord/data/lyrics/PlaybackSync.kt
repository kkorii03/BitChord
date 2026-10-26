package com.music.bitchord.data.lyrics

/**
 * The one rule for putting lyrics on the clock of the copy actually playing.
 *
 * Every stored sync answer — a measurement, an AI fit, the song check — is
 * made against the YouTube upload's clock. A verified high-quality substitute
 * can start later, earlier or run at a slightly different pace, so its stored
 * offset/rate maps YouTube time onto that copy's time. That map must be
 * applied exactly once, after the YouTube-based answer and before the
 * listener's own correction.
 *
 * A base answer that was already measured on the playing copy (a downloaded
 * file's own song check) is on that copy's clock already: applying the map
 * again would move it twice. [hqApplies] is that decision; [at] is the whole
 * arithmetic, kept pure so the table tests and the player use one rule.
 */
object PlaybackSync {

    /** Which copy the player is actually reading right now. */
    enum class Source { YOUTUBE, HQ }

    /**
     * Whether [hq] belongs on top of [base].
     *
     * Only a high-quality copy has a second clock, and only when [base] was
     * not already measured against that copy.
     */
    fun hqApplies(source: Source, baseOnPlayingClock: Boolean): Boolean =
        source == Source.HQ && !baseOnPlayingClock

    /**
     * YouTube time → playing-copy time: [base], then [hq] (already decided by
     * [hqApplies]), then the hand's [manualMs].
     */
    fun transform(
        base: LyricsTimeMap?,
        hq: LyricsTimeMap?,
        manualMs: Long,
    ): (Long) -> Long = { youtubeMs ->
        var at = base?.map(youtubeMs) ?: youtubeMs
        if (hq != null) at = hq.map(at)
        (at + manualMs).coerceAtLeast(0L)
    }

    /** The final time of [youtubeMs] under the whole rule. */
    fun at(
        youtubeMs: Long,
        base: LyricsTimeMap?,
        source: Source,
        hq: LyricsTimeMap?,
        manualMs: Long,
        baseOnPlayingClock: Boolean = false,
    ): Long = transform(
        base = base,
        hq = if (hqApplies(source, baseOnPlayingClock)) hq else null,
        manualMs = manualMs,
    )(youtubeMs)
}
