package com.music.bitchord.data.innertube

/**
 * How many times a video's picture is asked before it is remembered as
 * missing. A network failure is worth another surface or track change; a
 * definite "no playable clients" is not, and asking it on every repaint is the
 * once-a-second loop the phone log showed.
 */
object VideoPictureRetry {
    const val MAX_ATTEMPTS = 3

    /** Whether this failure should stop every later ask for the same video. */
    fun giveUp(attempts: Int, transient: Boolean): Boolean = !transient || attempts >= MAX_ATTEMPTS

    /**
     * The missing set after a picture finally loaded: the id comes out of the
     * published set and out of the settled [noPicture] verdict, so a later
     * failure or surface starts clean instead of showing artwork over a picture
     * that did load.
     */
    fun recovered(missing: Set<String>, noPicture: MutableSet<String>, videoId: String): Set<String> {
        noPicture.remove(videoId)
        return missing - videoId
    }
}
