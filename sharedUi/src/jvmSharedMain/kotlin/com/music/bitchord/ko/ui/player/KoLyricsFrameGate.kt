package com.music.bitchord.ko.ui.player

/** The fastest lyric highlight is redrawn, whatever the display refresh rate. */
internal const val KO_LYRIC_FRAME_INTERVAL_NANOS = 16_000_000L

internal fun koShouldDrawLyricFrame(now: Long, lastDrawnNanos: Long): Boolean =
    lastDrawnNanos != 0L && now - lastDrawnNanos < KO_LYRIC_FRAME_INTERVAL_NANOS
