package com.music.bitchord.playback

/**
 * Tells a row the listener moved to from a row the app moved to.
 *
 * Media3 reports both as a seek, so the transition reason cannot separate them.
 * Music-only skipping an official video with no release is itself a seek, and
 * the next such row was then read as a deliberate tap and played its video's
 * sound. The session player is the only surface a listener's command arrives
 * through (the service's own moves go straight to the ExoPlayer underneath), so
 * it notes each move here and the next transition takes the note.
 *
 * Taken once, whatever kind the transition was, so a note can never outlive the
 * move it was for; [windowMs] bounds a move that landed on no transition at all.
 */
internal class ListenerMoves(private val windowMs: Long = WINDOW_MS) {

    private var movedAtMs: Long? = null

    fun note(nowMs: Long) {
        movedAtMs = nowMs
    }

    /** Whether the transition arriving now is the listener's own move. */
    fun consume(automatic: Boolean, nowMs: Long): Boolean {
        val at = movedAtMs
        movedAtMs = null
        return !automatic && at != null && nowMs - at <= windowMs
    }

    private companion object {
        const val WINDOW_MS = 5_000L
    }
}
