package com.music.bitchord.playback

/**
 * Pure rules for carrying the queue across a crossfade handoff.
 *
 * The standby player is loaded with a copy of the queue when the transition is
 * armed. The outgoing player remains the session until the handoff, so any
 * queue edit made during arming or the first half of the fade — removing or
 * moving an upcoming row, shuffling, or stashing/restoring AutoPlay's tail —
 * exists only on the outgoing copy. Copying just the tracks appended since
 * arming therefore loses every edit, and the queue snaps back when the players
 * trade places.
 *
 * Everything here works on stable queue keys rather than player types, so the
 * handoff can rebuild the standby's tail from the outgoing queue as it stands
 * at the swap.
 */
object HandoffQueuePolicy {

    /**
     * Where [incomingKey] sits in the outgoing queue at the handoff.
     *
     * [outgoingCurrentIndex] wins when it already names the incoming row: an
     * unheld outgoing player can have run on into it. The row immediately after
     * it is next, which also survives a history edit that shifted every later
     * index. Otherwise the index recorded when arming is used while it still
     * names that row, and a full search is the fallback for a queue whose
     * preceding rows were edited.
     *
     * Null means the incoming row is no longer in the outgoing queue.
     */
    fun incomingIndex(
        outgoingKeys: List<String>,
        incomingKey: String,
        incomingIndexHint: Int,
        outgoingCurrentIndex: Int,
    ): Int? {
        if (outgoingKeys.getOrNull(outgoingCurrentIndex) == incomingKey) {
            return outgoingCurrentIndex
        }
        if (outgoingKeys.getOrNull(outgoingCurrentIndex + 1) == incomingKey) {
            return outgoingCurrentIndex + 1
        }
        if (outgoingKeys.getOrNull(incomingIndexHint) == incomingKey) {
            return incomingIndexHint
        }
        return outgoingKeys.indexOf(incomingKey).takeIf { it >= 0 }
    }

    /**
     * The rows that should follow [incomingKey] on the incoming player once the
     * outgoing queue is authoritative: everything after it, in its current
     * order.
     *
     * Null when the incoming row has gone, in which case the caller has nothing
     * coherent to copy.
     */
    fun tailAfterIncoming(
        outgoingKeys: List<String>,
        incomingKey: String,
        incomingIndexHint: Int,
        outgoingCurrentIndex: Int,
    ): List<String>? {
        val index = incomingIndex(
            outgoingKeys = outgoingKeys,
            incomingKey = incomingKey,
            incomingIndexHint = incomingIndexHint,
            outgoingCurrentIndex = outgoingCurrentIndex,
        ) ?: return null
        return outgoingKeys.subList(index + 1, outgoingKeys.size)
    }

    /**
     * Whether the incoming row is still the one that follows the outgoing
     * player's current row.
     *
     * Tail edits — removing, moving or shuffling rows behind the incoming one,
     * or stashing/restoring AutoPlay's tail — leave it next and are reconciled
     * at the handoff. Removing or moving the incoming row itself makes it not
     * next, so the song fading in is no longer what the session's queue says
     * comes next and the transition has to be abandoned.
     *
     * The comparison follows the outgoing player's *current* index rather than
     * an absolute one, so an edit to history that shifts every later row does
     * not invalidate the blend. [wrapsToStart] keeps repeat-all's last-to-first
     * transition valid, including when AutoPlay appends behind the old last
     * row while the fade is running.
     */
    fun incomingStillNext(
        outgoingKeys: List<String>,
        outgoingCurrentIndex: Int,
        incomingKey: String,
        wrapsToStart: Boolean,
    ): Boolean =
        outgoingKeys.getOrNull(outgoingCurrentIndex) == incomingKey ||
            outgoingKeys.getOrNull(outgoingCurrentIndex + 1) == incomingKey ||
            (wrapsToStart && outgoingKeys.getOrNull(0) == incomingKey)
}
