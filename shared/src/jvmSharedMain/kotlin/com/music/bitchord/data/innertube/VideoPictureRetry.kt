package com.music.bitchord.data.innertube

/**
 * How many times a music video's picture is asked for before the player settles
 * on the still artwork. YouTube can have no video-only stream for a track that
 * plays audio fine; without a cap the resolver walks every client again on each
 * surface or track change for the length of the song.
 */
object VideoPictureRetry {

    /** Three failed asks per video; a sign-in starts a fresh budget. */
    const val MAX_ATTEMPTS = 3

    /** Videos whose failed asks are remembered; the oldest goes when this fills. */
    const val MAX_VIDEOS = 16

    private val failures = LinkedHashMap<String, Int>()

    /** Whether one more ask is allowed after [attempts] failed attempts. */
    fun canTry(attempts: Int): Boolean = attempts < MAX_ATTEMPTS

    /**
     * Records a failed ask and returns whether the video is settled for the
     * session (no picture at all, not even a retry). A transient failure is
     * never settled: after [MAX_ATTEMPTS] its count is dropped so a later
     * surface can ask again, while the player shows the still artwork.
     */
    fun failed(videoId: String, permanent: Boolean): Boolean {
        if (permanent) {
            failures.remove(videoId)
            return true
        }
        val attempt = (failures[videoId] ?: 0) + 1
        if (!canTry(attempt)) {
            failures.remove(videoId)
            return false
        }
        if (videoId !in failures && failures.size >= MAX_VIDEOS) {
            failures.remove(failures.keys.first())
        }
        failures[videoId] = attempt
        return false
    }

    /** Forgets a video's failed asks once its picture resolved. */
    fun succeeded(videoId: String) {
        failures.remove(videoId)
    }

    /** Forgets every count, after a sign-in can change what YouTube serves. */
    fun clear() {
        failures.clear()
    }

    internal fun attempts(videoId: String): Int = failures[videoId] ?: 0

    internal fun rememberedVideos(): Int = failures.size
}
