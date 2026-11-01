package com.music.bitchord.playback

/**
 * What a car may resume when it asks before anything is queued.
 *
 * The player's own queue comes first, then the queue the listener last actually
 * played. The service used to fall through to "the first downloaded song", then
 * "the first local file", then "the first home recommendation", so connecting a
 * car could start a track the listener had never chosen. With no history the
 * answer is empty: the car then shows its browse list instead.
 */
internal object AutoResumptionPolicy {

    data class Resume<T>(
        val items: List<T>,
        val startIndex: Int,
        val startPositionMs: Long,
    )

    fun <T> choose(
        activeItems: List<T>,
        activeIndex: Int,
        activePositionMs: Long,
        lastPlayedItems: List<T>,
        lastPlayedIndex: Int,
        lastPlayedPositionMs: Long,
    ): Resume<T>? {
        if (activeItems.isNotEmpty()) {
            return Resume(
                items = activeItems,
                startIndex = activeIndex.coerceIn(activeItems.indices),
                startPositionMs = activePositionMs.coerceAtLeast(0L),
            )
        }
        if (lastPlayedItems.isNotEmpty()) {
            return Resume(
                items = lastPlayedItems,
                startIndex = lastPlayedIndex.coerceIn(lastPlayedItems.indices),
                startPositionMs = lastPlayedPositionMs.coerceAtLeast(0L),
            )
        }
        return null
    }
}
