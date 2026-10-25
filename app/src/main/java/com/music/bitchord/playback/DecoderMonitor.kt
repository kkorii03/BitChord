package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog

/**
 * The decoders running right now, for the one battery line the log keeps.
 *
 * Every decoder the app opens — ExoPlayer's playback and standby renderers, the
 * silent quality audition, and each background analysis pass — announces itself
 * here and withdraws when it closes. The log line is written only when the set
 * changes, so a minute of playback is one line rather than four a second.
 *
 * This is deliberately just bookkeeping. It does not gate anything: the policy
 * that decides what may run with the screen off lives at each caller, because
 * only the caller knows whether its decoder is making sound.
 */
internal object DecoderMonitor {

    private const val TAG = "BitChord"
    private val lock = Any()
    private val active = LinkedHashMap<String, Int>()
    private var lastSignature: String? = null

    fun start(name: String) = change(name, 1)

    fun stop(name: String) = change(name, -1)

    fun clear() {
        synchronized(lock) {
            active.clear()
            publishLocked()
        }
    }

    /** The names currently open, for the state line and for tests. */
    internal fun activeNames(): List<String> = synchronized(lock) { namesLocked() }

    /** How many decoders are open, counting two players on the same codec twice. */
    internal fun activeCount(): Int = synchronized(lock) { activeCountLocked() }

    private fun change(name: String, delta: Int) {
        synchronized(lock) {
            val next = (active[name] ?: 0) + delta
            if (next <= 0) active.remove(name) else active[name] = next
            publishLocked()
        }
    }

    private fun publishLocked() {
        val names = namesLocked()
        val signature = active.entries.joinToString("|") { "${it.key}=${it.value}" }
        if (signature == lastSignature) return
        lastSignature = signature
        TrackLog.i(
            TAG,
            "power: decoders active=${activeCountLocked()} $names",
        )
    }

    private fun activeCountLocked(): Int = active.values.sum()

    private fun namesLocked(): List<String> = active.keys.toList()
}
