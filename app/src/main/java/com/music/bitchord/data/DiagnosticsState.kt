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
    /**
     * The sounding and next rows, set on every change of either. A Deepgram ask for a row
     * that has left stops on it ([com.music.bitchord.playback.smart.SongHearing.askWhileWanted]),
     * with no poll. Declared first: the setters below write it.
     */
    private val _rows = kotlinx.coroutines.flow.MutableStateFlow<Pair<String?, String?>>(null to null)
    val rows: kotlinx.coroutines.flow.StateFlow<Pair<String?, String?>> get() = _rows

    private fun rowsMoved() {
        _rows.value = soundingTrackId to nextTrackId
    }

    @Volatile
    var screen: String = "unknown"

    @Volatile
    var queueSize: Int = 0

    @Volatile
    var queueIndex: Int = 0

    @Volatile
    var currentTrackId: String? = null
        set(value) {
            if (field != value) currentSinceMs = System.currentTimeMillis()
            field = value
            currentSwapId = null
        }

    /**
     * When [currentTrackId] last changed. The row has played at most this long, so a
     * re-ask can tell it still has time left without reading the player.
     */
    @Volatile
    var currentSinceMs: Long = 0L

    /**
     * The id an in-place version swap put on the current row, or null. Such a
     * swap books no transition, so [currentTrackId] keeps the replaced id.
     * 11-09 phone 6rS7OUGXUik -> G5Lj9tsCK6A: every hearing for the copy that
     * played was skipped as "no longer wanted", so no AI timing all song.
     */
    @Volatile
    var currentSwapId: String? = null
        set(value) {
            field = value
            rowsMoved()
        }

    /** The id whose sound the current row plays: what hearing is for. */
    val soundingTrackId: String? get() = currentSwapId ?: currentTrackId

    /** The row prepared after the current one; a measurement is wanted only for these two. */
    @Volatile
    var nextTrackId: String? = null
        set(value) {
            field = value
            rowsMoved()
        }

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
