package com.music.bitchord.playback

/**
 * Where a failed track should move the queue to.
 *
 * The ordinary case is Media3's own next index. Repeat-one is the exception:
 * `nextMediaItemIndex` then names the current item, so `seekToNextMediaItem()`
 * stays exactly where it is. For a track the resolver has ruled out that is a
 * permanent loop — the same dead item is prepared, fails, and is "skipped" to
 * itself again. With more than one row in the queue, the honest answer is the
 * following row; with only one, there is nowhere to go and the caller should
 * stop and report instead.
 *
 * Pure so the repeat-one case is tested without a player.
 */
object SkipTargetPolicy {

    /** No row to move to. Matches `C.INDEX_UNSET`. */
    const val NO_TARGET = -1

    fun targetAfterFailure(
        currentIndex: Int,
        nextIndex: Int,
        itemCount: Int,
        repeatOne: Boolean,
    ): Int {
        if (itemCount <= 0 || currentIndex !in 0 until itemCount) return NO_TARGET
        if (nextIndex in 0 until itemCount && nextIndex != currentIndex) return nextIndex
        if (repeatOne && itemCount > 1) return (currentIndex + 1) % itemCount
        return NO_TARGET
    }
}
