package com.music.bitchord.playback.smart

/**
 * [AudioDecoder]'s one-at-a-time gate, with two rules added: a decode for work
 * done ahead of time (the next track's song check, its high-quality
 * comparison) waits while any decode for the playing track is waiting, and an
 * urgent decode (a version alignment, which a swap is waiting on) is next in
 * line ahead of both.
 *
 * With the plain fair semaphore the next-track bundle's checks queued ahead of
 * the playing track's own: on the 11-02 smoke the playing song's correction
 * landed 10-18 minutes after it started, behind checks for rows that never
 * played. The same queue starved the late song-version handover on the 11-08
 * fleet (round-score 9): its two 45 s alignment decodes waited up to 160 s
 * behind the video's own lyric and Automix decodes, so every pass ran out of
 * time and was thrown away. Urgent never interrupts a running decode, it only
 * goes first once the slot is free.
 *
 * Blocking, like the semaphore it replaces; an interrupt while waiting throws
 * [InterruptedException] as before.
 */
internal class DecodePriorityGate {
    private val lock = Object()
    private var busy = false
    private var foregroundWaiting = 0
    private var urgentWaiting = 0

    @Throws(InterruptedException::class)
    fun acquire(background: Boolean, urgent: Boolean = false) {
        synchronized(lock) {
            if (urgent) urgentWaiting++ else if (!background) foregroundWaiting++
            var acquired = false
            try {
                while (busy ||
                    (!urgent && urgentWaiting > 0) ||
                    (!urgent && background && foregroundWaiting > 0)
                ) {
                    lock.wait()
                }
                busy = true
                acquired = true
            } finally {
                if (urgent) urgentWaiting-- else if (!background) foregroundWaiting--
                // A waiter interrupted out of the queue may have been the only
                // thing holding a lower one back from a free slot.
                if (!acquired) lock.notifyAll()
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
