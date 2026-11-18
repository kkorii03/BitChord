package com.music.bitchord.ko

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * [KO] UPG-5: the words check of a doubted row, asked as soon as the
 * candidate's sixty-second opening is held instead of after the whole
 * ninety-second decode and the chroma alignment.
 *
 * Only the engine's answer is taken early. The verify still judges in its old
 * order: the chroma verdict first, and the early answer is compared only where
 * the old code would have heard the clip (an accepted match, or a doubted row
 * with no alignment). Everywhere else the answer is dropped unread by [close].
 *
 * [scope] must not be a child of the verify's own scope, or every return of
 * the verify would wait for an upload nobody reads.
 */
class KoEarlyHearing<T>(
    private val scope: CoroutineScope,
    private val hear: suspend (KoOpeningClip) -> T,
) {
    private var clip: KoOpeningClip? = null
    private var hearing: Deferred<T>? = null
    private var closed = false

    /** The opening is full (decoder thread). Asks once; a later clip of a retried download is not asked. */
    @Synchronized
    fun start(opening: KoOpeningClip) {
        if (closed || hearing != null) return
        clip = opening
        hearing = scope.async { hear(opening) }
    }

    /**
     * The early hearing of [opening], or null when none was started for this
     * very clip; the caller then hears the clip itself, as before.
     */
    @Synchronized
    fun take(opening: KoOpeningClip?): Deferred<T>? {
        if (closed || opening == null || clip !== opening) return null
        return hearing
    }

    /** The verify is over: an answer still on its way is not wanted. */
    @Synchronized
    fun close() {
        closed = true
        hearing?.cancel()
    }

    companion object {
        /**
         * Asked early only for a doubted row, whose words decide whether it may
         * play at all, and only of Groq. Asking before the chroma verdict
         * spends one upload on rows the sound then refuses; Deepgram is paid
         * per request, so a Deepgram hearing keeps the old order (after an
         * accepted match only), and so does an undoubted row's optional hearing.
         */
        fun wanted(needsWords: Boolean, hearer: KoVersionGuard.Hearer): Boolean =
            needsWords && hearer == KoVersionGuard.Hearer.GROQ
    }
}
