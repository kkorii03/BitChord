package com.music.bitchord.ko

import com.music.bitchord.playback.NextPrepPolicy
import kotlinx.coroutines.Job

/**
 * [KO] TR7 F3: the next-row bundle tries each step once per sound (the `attempted` set
 * [NextPrepPolicy.plan] reads). A step the bundle was cancelled in the middle of (the row
 * stopped being next: a skip, a queue change) got no answer at all, so it must not count:
 * when the same sound was next again (skip back, a queue moved back) its lyrics and sync
 * were never prepared again. A step that answered, failed or ran out of its own deadline
 * inside a bundle still running stays tried, as before.
 */
internal object KoNextPrepAttempts {

    /** Takes [step] back out of [steps] (guarded by [lock]) when [bundle] was cancelled. */
    fun forgetIfCancelled(
        steps: MutableSet<NextPrepPolicy.Step>,
        lock: Any,
        step: NextPrepPolicy.Step,
        bundle: Job?,
    ) {
        if (bundle != null && !bundle.isActive) synchronized(lock) { steps.remove(step) }
    }
}
