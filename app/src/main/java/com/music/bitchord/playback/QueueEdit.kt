package com.music.bitchord.playback

/**
 * Bounds checks for the queue panel's edits.
 *
 * The drawer draws the queue it was handed a moment ago and hands back list
 * positions. The session's timeline can have changed since — AutoPlay switched
 * off, a track skipped, a playlist shortened — and `removeMediaItem` /
 * `moveMediaItem` throw `IndexOutOfBoundsException` on a stale index. A UI
 * event that arrives late is not an error worth crashing over, so every edit
 * is checked against the count the player reports now.
 *
 * Kept apart from the call sites so the rules are testable without a player.
 */
internal object QueueEdit {

    /** Whether [index] still names a queue entry in a queue of [count]. */
    fun canRemove(index: Int, count: Int): Boolean = index in 0 until count

    /**
     * Whether moving [from] to [to] is a real edit of a queue of [count].
     *
     * A move onto itself is not: the player has nothing to do and some
     * implementations still treat it as a playlist change.
     */
    fun canMove(from: Int, to: Int, count: Int): Boolean =
        from != to && canRemove(from, count) && canRemove(to, count)
}
