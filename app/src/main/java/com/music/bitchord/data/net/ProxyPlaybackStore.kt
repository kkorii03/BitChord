package com.music.bitchord.data.net

import android.content.Context

/**
 * The persisted half of [ProxyPlayback]: which videoIds have played through the
 * proxy, and when they last did.
 *
 * Only ids and timestamps are stored. A stream URL is bound to the IP of the
 * player request that minted it and expires within hours, so persisting one
 * would be worse than useless; [ProxyPlayback] mints a fresh URL when the
 * remembered id is played again. The memory is debounced inside
 * [ProxyPlayback.attachPersistence], so this class only parses and writes the
 * settled snapshot.
 */
object ProxyPlaybackStore {

    private const val PREFS = "bitchord_proxy_playback"
    private const val KEY = "remembered"

    /**
     * Loads the saved ids and installs the debounced save callback. Called once
     * from [com.music.bitchord.BitChordApplication] after [AppSettings.initProxy].
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ProxyPlayback.attachPersistence(
            load = { parse(prefs.getString(KEY, null)) },
            save = { map -> prefs.edit().putString(KEY, encode(map)).apply() },
        )
    }

    private fun parse(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(',')
            .mapNotNull { part ->
                val pieces = part.split(':')
                if (pieces.size != 2) return@mapNotNull null
                val videoId = pieces[0]
                val at = pieces[1].toLongOrNull() ?: return@mapNotNull null
                if (videoId.isBlank()) null else videoId to at
            }
            .toMap()
    }

    private fun encode(map: Map<String, Long>): String =
        map.entries.joinToString(",") { "${it.key}:${it.value}" }
}
