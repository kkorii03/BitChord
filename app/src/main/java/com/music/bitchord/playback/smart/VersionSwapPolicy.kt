package com.music.bitchord.playback.smart

/**
 * Decides whether a measured version alignment is trustworthy enough to hang a
 * swap on. Kept free of Android types so it can be exercised by a plain JVM
 * test.
 */
object VersionSwapPolicy {
    /**
     * Below this normalized correlation the two openings are not recognisably
     * the same recording (a cover, a different song, or a bad decode), and
     * swapping would silently replace what the listener chose with something
     * else. Measured swaps that a listener would call correct sit well above
     * it; covers land around 0.45.
     */
    const val MIN_SWAP_CONFIDENCE = 0.6

    fun shouldSwap(confidence: Double): Boolean = confidence >= MIN_SWAP_CONFIDENCE

    /**
     * Below this a song already playing is taken off for being another song.
     * Stricter than [MIN_SWAP_CONFIDENCE], because the listener hears this one:
     * a cover sits around 0.45 and is still the same song to them, so between
     * here and the swap bar the row is left alone. A different song lands well
     * under both.
     */
    const val REFUTES_BELOW_CONFIDENCE = 0.45

    fun refutesPlaying(confidence: Double): Boolean = confidence < REFUTES_BELOW_CONFIDENCE
}
