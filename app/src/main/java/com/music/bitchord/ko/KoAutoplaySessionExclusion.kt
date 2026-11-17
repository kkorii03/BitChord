package com.music.bitchord.ko

/**
 * [KO] P1-4
 *
 * Which ids an AutoPlay top-up must not offer again.
 *
 * With "don't repeat songs in current session" on, everything played or
 * suggested since the service started. With it off (the default) only the most
 * recent ones: enough that a song which just left the queue (history keeps 25
 * rows) or was suggested in the last few top-ups does not land straight after
 * the seed, but a long session no longer filters a station down to nothing.
 * The live queue itself is excluded separately by the caller.
 */
internal object KoAutoplaySessionExclusion {

    /** About three top-ups' worth of seeds and suggestions. */
    const val RECENT_WHEN_REPEATS_ALLOWED = 30

    /** [sessionIds] in the order they were first added (a LinkedHashSet). */
    fun excludedIds(
        dontRepeat: Boolean,
        sessionIds: Collection<String>,
        currentId: String,
        recent: Int = RECENT_WHEN_REPEATS_ALLOWED,
    ): Set<String> {
        val kept = if (dontRepeat) sessionIds else sessionIds.toList().takeLast(recent.coerceAtLeast(0))
        return LinkedHashSet(kept).apply { add(currentId) }
    }
}
