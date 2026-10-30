package com.music.bitchord.playback

/**
 * Where a failed track should move the queue to.
 *
 * The ordinary case is Media3's own next index. Repeat-one is the exception:
 * `nextMediaItemIndex` then names the current item, so `seekToNextMediaItem()`
 * stays exactly where it is. For a track the resolver has ruled out that is a
 * permanent loop — the same dead item is prepared, fails, and is "skipped" to
 * itself again.
 *
 * "A different index" is not enough on its own. A queue may contain the same
 * recording twice (an album row and a radio row, or a playlist that was added
 * to itself), and skipping to the duplicate prepares the same dead videoId
 * again. An upcoming row may also already carry a resolver verdict — walking
 * into it spends a prepare and a failure to learn what is already known. So
 * the target is the first row after the failure that is a different recording
 * and has not been ruled out, wrapping only where repeat-one would have.
 *
 * Pure so these cases are tested without a player.
 */
object SkipTargetPolicy {

    /** No row to move to. Matches `C.INDEX_UNSET`. */
    const val NO_TARGET = -1

    /**
     * @param mediaIds every queue row's media id, in order. A row whose id is
     *   [currentIndex]'s is the same recording even when it is a different row.
     * @param unplayable media ids the resolver has already ruled out. They are
     *   stepped over rather than prepared and failed a second time.
     */
    fun targetAfterFailure(
        currentIndex: Int,
        nextIndex: Int,
        mediaIds: List<String>,
        unplayable: Set<String>,
        repeatOne: Boolean,
    ): Int {
        val count = mediaIds.size
        if (count <= 0 || currentIndex !in 0 until count) return NO_TARGET
        val currentMediaId = mediaIds[currentIndex]

        // Media3's own answer comes first. When it is the current row (repeat
        // one) or unset (end of queue with repeat off), fall back the same way
        // the queue would. Only repeat-one may wrap: with repeat off the rows
        // before the current one are already played and must not be revisited.
        var candidate = when {
            nextIndex in 0 until count && nextIndex != currentIndex -> nextIndex
            repeatOne && count > 1 -> (currentIndex + 1) % count
            else -> return NO_TARGET
        }
        val mayWrap = repeatOne

        // At most one pass over the other rows. A row is usable only when it is
        // a different recording and the resolver has not already refused it.
        repeat(count - 1) {
            if (candidate != currentIndex &&
                mediaIds[candidate] != currentMediaId &&
                mediaIds[candidate] !in unplayable
            ) {
                return candidate
            }
            if (candidate == count - 1) {
                if (!mayWrap) return NO_TARGET
                candidate = 0
            } else {
                candidate++
            }
        }
        return NO_TARGET
    }
}
