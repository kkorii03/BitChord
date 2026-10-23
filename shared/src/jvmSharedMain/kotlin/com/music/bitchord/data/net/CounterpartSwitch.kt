package com.music.bitchord.data.net

import java.util.concurrent.atomic.AtomicLong

/**
 * Where the counterpart switch's deadline is kept between launches.
 *
 * The shared module only names the store; the app supplies the small
 * SharedPreferences implementation (see `data/net/CounterpartStore.kt` in the
 * app module), the same split [ProxyPlayback] uses for its remembered ids.
 */
interface CounterpartSwitchStore {
    /** The stored deadline, or null when nothing was ever stored. */
    fun loadOffUntil(): Long?

    fun saveOffUntil(until: Long)
}

/**
 * Whether YouTube Music's counterpart (`next`) endpoint is worth asking.
 *
 * Signed-out `next` answers carry no pairing rows, so after
 * [SongVersionPolicy.COUNTERPART_EMPTY_LIMIT] empty answers in a row the
 * lookup is pointless. That verdict used to last only for the session, which
 * meant every launch spent five more 0.8–1.1 s requests on it; it is now kept
 * for [SongVersionPolicy.COUNTERPART_OFF_MS] (a week) through
 * [CounterpartSwitchStore], and the run of empty answers starts over when the
 * deadline passes.
 *
 * Deliberately tiny and free of Android types: the decision itself lives in
 * [SongVersionPolicy], which a plain JVM test exercises.
 */
object CounterpartSwitch {

    /** No stored verdict. [Long.MIN_VALUE] so it can live in an [AtomicLong]. */
    private const val NO_DEADLINE = Long.MIN_VALUE

    private val offUntil = AtomicLong(NO_DEADLINE)

    @Volatile
    private var store: CounterpartSwitchStore? = null

    /**
     * Loads the stored deadline and installs the writer. Called once at app
     * start, before the first queue is built.
     */
    @Synchronized
    fun attach(store: CounterpartSwitchStore) {
        this.store = store
        store.loadOffUntil()?.let { offUntil.set(it) }
    }

    /** Whether the lookup is still switched off at [now]. */
    fun isSuppressed(now: Long = System.currentTimeMillis()): Boolean =
        SongVersionPolicy.counterpartSuppressed(offUntilOrNull(), now)

    /**
     * Records that the endpoint answered nothing: it stays off for a week
     * from [now], including across restarts.
     */
    @Synchronized
    fun suppress(now: Long = System.currentTimeMillis()) {
        val until = SongVersionPolicy.counterpartOffUntil(now)
        offUntil.set(until)
        store?.saveOffUntil(until)
    }

    /** The stored deadline, for tests and log lines; null while the switch is on. */
    fun offUntil(): Long? = offUntilOrNull()

    private fun offUntilOrNull(): Long? = offUntil.get().takeIf { it != NO_DEADLINE }
}
