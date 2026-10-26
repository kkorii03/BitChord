package com.music.bitchord.data.lyrics

/**
 * When a refine that could not apply deserves one more, longer listen.
 *
 * A cover timed to a different arrangement can sit a whole intro away from
 * the provider's lines. The wide window needs ten agreeing lines, but the
 * first ninety seconds may contain only five of them, so the move is never
 * seen. Extending to the opening of the song once gives the wide window the
 * rest of the lines — but only when the kept transcript actually ran out
 * before the lyrics did. Pure, so the rule is checked without audio.
 */
object RefineExtendPolicy {

    /** The once-only second listen covers this much of the song. */
    const val EXTEND_SECONDS = 180.0

    /** A line this far past the kept transcript's end is one it never heard. */
    const val MARGIN_MS = 30_000L

    /**
     * [result] is what [RefinePolicy] made of the kept [heardSeconds] of the
     * song, and [lastLineMs] is the last sung line's time. Extending is only
     * worth a request when the refine did not apply, fewer lines than the wide
     * window needs were matched, the lyrics run past the transcript's end, and
     * the longer window has not already been heard.
     */
    fun shouldExtend(result: RefinePolicy.Result, heardSeconds: Double, lastLineMs: Long): Boolean {
        if (result.applied) return false
        if (result.lines >= RefinePolicy.MIN_WIDE_LINES) return false
        if (!heardSeconds.isFinite() || heardSeconds >= EXTEND_SECONDS) return false
        val heardMs = (heardSeconds * 1000.0).toLong()
        return lastLineMs >= heardMs + MARGIN_MS
    }
}
