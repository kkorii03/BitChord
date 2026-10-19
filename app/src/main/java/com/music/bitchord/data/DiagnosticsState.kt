package com.music.bitchord.data

import androidx.media3.common.Player

/**
 * The few live facts a diagnostic export needs that live in the player or the
 * UI rather than in a store.
 *
 * The export runs on a background thread while the UI and playback service
 * keep changing, so this is deliberately a handful of volatile fields rather
 * than a callback graph: each producer writes the snapshot it already has,
 * and the export reads whatever is current.
 */
object DiagnosticsState {
    @Volatile
    var screen: String = "unknown"

    @Volatile
    var queueSize: Int = 0

    @Volatile
    var queueIndex: Int = 0

    @Volatile
    var currentTrackId: String? = null

    @Volatile
    var currentTitle: String? = null

    @Volatile
    var repeatMode: Int = Player.REPEAT_MODE_OFF

    @Volatile
    var shuffle: Boolean = false

    fun repeatLabel(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    fun describe(): List<String> = listOf(
        "screen: $screen",
        "queue: ${if (queueSize > 0) "${queueIndex + 1}/$queueSize" else "empty"}",
        "current: ${currentTrackId ?: "-"} ${currentTitle?.takeIf { it.isNotBlank() } ?: ""}".trim(),
        "modes: repeat=${repeatLabel(repeatMode)} shuffle=${if (shuffle) "on" else "off"}",
    )
}
