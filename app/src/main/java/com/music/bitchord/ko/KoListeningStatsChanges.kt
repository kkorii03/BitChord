package com.music.bitchord.ko

import com.music.bitchord.data.stats.WriteThrottle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [KO] listening-changes
 *
 * Fork-only state around listening writes: the Recents wake-up signal and the
 * routine-write throttle. Event-driven; no timer and no polling.
 */
internal object KoListeningStatsChanges {

    /** At most one routine month write per two minutes of playback. */
    private const val MIN_WRITE_INTERVAL_MS = 2L * 60L * 1000L

    /**
     * Emits when a playthrough starts and when a play is counted, so Home's
     * Recents can follow this device's record immediately. Event-driven: no
     * timer and no polling, and at most twice per track. A slow collector may
     * miss an intermediate event, which is harmless because it reads the whole
     * recent list when it wakes.
     */
    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private val writeThrottle = WriteThrottle(MIN_WRITE_INTERVAL_MS)

    fun emit() {
        _changes.tryEmit(Unit)
    }

    fun shouldWrite(nowMs: Long): Boolean = writeThrottle.shouldWrite(nowMs)

    fun wrote(nowMs: Long) = writeThrottle.wrote(nowMs)
}
