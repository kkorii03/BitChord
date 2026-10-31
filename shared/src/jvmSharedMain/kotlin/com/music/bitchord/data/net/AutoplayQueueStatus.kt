package com.music.bitchord.data.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The track whose latest AutoPlay request really returned zero songs.
 *
 * The queue row used to read emptiness as "no recommendations", which also
 * described the normal moment before a request had finished. Publishing the
 * actual zero result lets the row wait quietly while a load is in flight and
 * only report a miss once the request has answered.
 *
 * Process-wide and event-driven: the playback service writes it when a request
 * starts and comes back empty, and clears it when a request produces songs, the
 * track changes or the mode changes. The player only reads the latest value.
 */
object AutoplayQueueStatus {

    private val _emptyRequestSeedId = MutableStateFlow<String?>(null)

    /** Seed of the last completed request that returned zero songs, or null. */
    val emptyRequestSeedId: StateFlow<String?> = _emptyRequestSeedId.asStateFlow()

    /** A request for [seedId] returned zero songs. */
    fun markEmpty(seedId: String) {
        _emptyRequestSeedId.value = seedId
    }

    /** A request for [seedId] is running or produced songs; no miss holds. */
    fun clearEmpty(seedId: String) {
        if (_emptyRequestSeedId.value == seedId) _emptyRequestSeedId.value = null
    }

    /** The current track or mode changed; no earlier miss applies any more. */
    fun clear() {
        _emptyRequestSeedId.value = null
    }
}
