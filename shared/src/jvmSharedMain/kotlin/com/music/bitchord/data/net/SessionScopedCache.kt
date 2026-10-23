package com.music.bitchord.data.net

/**
 * Whether a short-lived cache entry may still answer for the current session.
 *
 * Entries such as Android Auto's Recents, Quick Picks and Home are cached
 * briefly, and the answer belongs to the listener who asked: a channel or
 * account switch must not serve the previous one's rows for the rest of the
 * window. Keyed by `Innertube.sessionKey()`, an entry from another session
 * misses here even while it is still fresh.
 *
 * Pure so the rule can be tested without a session or a clock.
 */
fun sessionScopedCacheHit(
    storedKey: String?,
    sessionKey: String,
    storedAtMs: Long,
    nowMs: Long,
    ttlMs: Long,
): Boolean = storedKey != null && storedKey == sessionKey && nowMs - storedAtMs < ttlMs
