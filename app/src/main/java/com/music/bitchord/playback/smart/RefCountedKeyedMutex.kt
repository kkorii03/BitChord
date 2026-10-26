package com.music.bitchord.playback.smart

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * One mutex per key, kept alive until the last caller that took or queued for
 * it has left.
 *
 * Removing a lock the moment its holder finishes looks tidy and is wrong: a
 * caller already waiting still holds the old mutex, so a newcomer creates a
 * second one and both run at once. The count is what closes that window.
 */
internal class RefCountedKeyedMutex<K> {

    internal class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = ConcurrentHashMap<K, Entry>()

    fun acquire(key: K): Entry =
        entries.compute(key) { _, existing ->
            (existing ?: Entry()).also { it.users++ }
        }!!

    fun release(key: K, entry: Entry) {
        entries.computeIfPresent(key) { _, existing ->
            if (existing !== entry) {
                existing
            } else {
                existing.users--
                if (existing.users <= 0) null else existing
            }
        }
    }
}
