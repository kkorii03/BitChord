package com.music.bitchord.data.lyrics

/**
 * When a refine deserves one more, longer listen.
 *
 * A cover timed to a different arrangement can sit a whole intro away from
 * the provider's lines. The wide window needs ten agreeing lines, but the
 * first ninety seconds may contain only five of them, so the move is never
 * seen. Extending to the end of the song once gives the wide window the rest
 * of the lines — but only when the kept transcript actually ran out before
 * the lyrics did.
 *
 * The same holds for a refine that did apply: a drift is only measured up to
 * the last word heard (`LyricsTiming.refineMove` holds it there), so a song
 * whose pace keeps running is seconds off past the transcript's end until it
 * is heard further. Pure, so the rule is checked without audio.
 */
object RefineExtendPolicy {

    /**
     * However long the song, the second listen stops here. The transcript is
     * paid for by the minute, and one 16 kHz clip of this length is already
     * 9.6 MB of the 16 MB decoded-audio cache.
     */
    const val MAX_EXTEND_SECONDS = 300.0

    /** A line this far past the kept transcript's end is one it never heard. */
    const val MARGIN_MS = 30_000L

    /**
     * [result] is what [RefinePolicy] made of the kept [heardSeconds] of the
     * song, and [lastLineMs] is the last sung line's time. Extending is worth
     * a request when the refine applied a drift it only knows to the end of
     * the transcript, when the lyrics run past the transcript's end, or when
     * the refine did not apply and fewer lines than the wide window needs were
     * matched. The longer window itself is never heard twice.
     */
    fun shouldExtend(result: RefinePolicy.Result, heardSeconds: Double, lastLineMs: Long): Boolean {
        if (!heardSeconds.isFinite() || heardSeconds >= MAX_EXTEND_SECONDS) return false
        val heardMs = (heardSeconds * 1000.0).toLong()
        val unheard = lastLineMs >= heardMs + MARGIN_MS
        // A measured drift carried past its own evidence was the defect this
        // rule exists for: 11-04, 7% of the lines past 90 s off by >1.5 s
        // against 2% before it, with no listen ever made further.
        if (result.applied) return result.model == RefinePolicy.Model.LINEAR || unheard
        return result.lines < RefinePolicy.MIN_WIDE_LINES && unheard
    }

    /**
     * Whether [targetSeconds] of the song is already on disk. Starting the
     * longer listen while the stream still has minutes to fill makes it wait
     * for the rest of the track at playback speed, and its answer lands after
     * the song ended. [availableSeconds] may be infinite when a file is whole
     * but its length is not stated.
     */
    fun hasAudioFor(targetSeconds: Double, availableSeconds: Double): Boolean =
        targetSeconds.isFinite() && availableSeconds >= targetSeconds

    /**
     * Seconds of a partly cached copy: the byte fraction of the stated length.
     * Negative when the length or the duration is unknown, so each caller keeps
     * its own answer for that case instead of guessing.
     */
    fun cachedSeconds(cachedPrefix: Long, contentLength: Long, durationSeconds: Double): Double =
        if (contentLength > 0L && durationSeconds.isFinite() && durationSeconds > 0.0) {
            cachedPrefix.toDouble() * durationSeconds / contentLength
        } else {
            -1.0
        }
}
