package com.music.bitchord.data.net

import android.content.Context

/**
 * The persisted half of [CounterpartSwitch]: until when YouTube Music's
 * counterpart endpoint is not worth asking.
 *
 * One deadline, one preference file, the same shape as [ProxyPlaybackStore].
 * The verdict is per endpoint and per account, so it is not tied to a track or
 * a session; [CounterpartSwitch] decides when to write it.
 */
object CounterpartStore {

    private const val PREFS = "bitchord_song_version"
    private const val KEY_OFF_UNTIL = "counterpart_off_until"

    /**
     * Loads the stored deadline and installs the writer. Called once from
     * [com.music.bitchord.BitChordApplication].
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        CounterpartSwitch.attach(
            object : CounterpartSwitchStore {
                override fun loadOffUntil(): Long? {
                    val stored = prefs.getLong(KEY_OFF_UNTIL, 0L)
                    return stored.takeIf { it > 0L }
                }

                override fun saveOffUntil(until: Long) {
                    prefs.edit().putLong(KEY_OFF_UNTIL, until).apply()
                }
            },
        )
    }
}
