package com.music.bitchord.playback.smart

/**
 * [AudioDecoder]'s one-at-a-time gate, with one rule added: a decode for work
 * done ahead of time (the next track's song check, its high-quality
 * comparison) waits while any decode for the playing track is waiting.
 *
 * With the plain fair semaphore the next-track bundle's checks queued ahead of
 * the playing track's own: on the 11-02 smoke the playing song's correction
 * landed 10-18 minutes after it started, behind checks for rows that never
 * played. Blocking, like the semaphore it replaces; an interrupt while waiting
 * throws [InterruptedException] as before.
 */
internal class DecodePriorityGate {
    private val lock = Object()
    private var busy = false
    private var foregroundWaiting = 0

    @Throws(InterruptedException::class)
    fun acquire(background: Boolean) {
        synchronized(lock) {
            if (!background) foregroundWaiting++
            try {
                while (busy || (background && foregroundWaiting > 0)) lock.wait()
                busy = true
            } finally {
                if (!background) foregroundWaiting--
            }
        }
    }

    fun release() {
        synchronized(lock) {
            busy = false
            lock.notifyAll()
        }
    }
}
