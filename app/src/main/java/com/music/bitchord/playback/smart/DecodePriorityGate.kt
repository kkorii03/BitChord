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
 * time and was thrown away.
 *
 * [KO] decode-yield: going first in the queue was not enough. A decode that
 * already held the slot kept it to its end — the video's own song check (90 s
 * and whole-song reads) and lyric decode held it 42-92 s on the 11-08 fleet
 * (183c, 187c, 190d) while the late handover's alignment waited, and the
 * handover was refused at its budget every time. So a running non-urgent
 * decode now steps aside at its next decode pass ([yieldToUrgent]) while an
 * urgent one waits, and takes the slot back — ahead of anything queued after
 * it — once no urgent decode is left. Still one decode at a time: the paused
 * one does no work while it waits.
 *
 * Blocking, like the semaphore it replaces; an interrupt while waiting throws
 * [InterruptedException] as before.
 */
internal class DecodePriorityGate {
    private val lock = Object()
    private var busy = false
    private var holderUrgent = false
    private var foregroundWaiting = 0

    /** Read without the lock on every decode pass, so a pass with nothing waiting costs a volatile read. */
    @Volatile
    private var urgentWaiting = 0

    /** A holder that stepped aside and waits to take the slot back; it goes before every non-urgent waiter. */
    private var resuming = 0

    /** What [yieldToUrgent] did. */
    enum class Yield {
        /** No urgent decode waited (or the holder is urgent itself): the slot was never let go. */
        KEPT,

        /** Stepped aside for urgent work and holds the slot again. */
        RESUMED,

        /** Interrupted while stepped aside: the slot is NOT held, and the caller must not [release] it. */
        LOST,
    }

    @Throws(InterruptedException::class)
    fun acquire(background: Boolean, urgent: Boolean = false) {
        synchronized(lock) {
            if (urgent) urgentWaiting++ else if (!background) foregroundWaiting++
            var acquired = false
            try {
                while (busy ||
                    (!urgent && urgentWaiting > 0) ||
                    (!urgent && resuming > 0) ||
                    (!urgent && background && foregroundWaiting > 0)
                ) {
                    lock.wait()
                }
                busy = true
                holderUrgent = urgent
                acquired = true
            } finally {
                if (urgent) urgentWaiting-- else if (!background) foregroundWaiting--
                // A waiter interrupted out of the queue may have been the only
                // thing holding a lower one back from a free slot.
                if (!acquired) lock.notifyAll()
            }
        }
    }

    /**
     * Called by the holder between decode passes: while an urgent decode
     * waits, a non-urgent holder lets the slot go, waits until no urgent
     * decode is running or waiting, and takes it back. See [Yield].
     */
    fun yieldToUrgent(): Yield {
        if (urgentWaiting == 0) return Yield.KEPT
        synchronized(lock) {
            if (!busy || holderUrgent || urgentWaiting == 0) return Yield.KEPT
            busy = false
            resuming++
            lock.notifyAll()
            var held = false
            try {
                while (busy || urgentWaiting > 0) lock.wait()
                busy = true
                holderUrgent = false
                held = true
                return Yield.RESUMED
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Yield.LOST
            } finally {
                resuming--
                // Given up while stepped aside: the waiters it held back may go.
                if (!held) lock.notifyAll()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            busy = false
            holderUrgent = false
            lock.notifyAll()
        }
    }
}
