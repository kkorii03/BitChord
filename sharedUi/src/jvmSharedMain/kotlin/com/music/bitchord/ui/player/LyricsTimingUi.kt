package com.music.bitchord.ui.player

/**
 * Whether the lyrics on screen were moved onto the video's own clock, and how
 * — for the AI timing button, the line under the lyrics and the sync drawer.
 */
data class LyricsTimingUi(
    /** The lyrics are timed and not already the video's own, so they can be moved at all. */
    val adjustable: Boolean = false,
    /** Where the first line moved to, in milliseconds; null when nothing moved it. */
    val shiftMs: Long? = null,
    /** The current timing came from Gemini rather than the automatic measurement. */
    val byAi: Boolean = false,
    /** Gemini is watching the video right now. */
    val aiRunning: Boolean = false,
    /** The last Gemini request came back with nothing usable. */
    val aiFailed: Boolean = false,
    /** Video seconds per record second, when the video runs at its own pace; null at the record's. */
    val rate: Double? = null,
    /** How much of the song agreed with the timing, 0–100; null when not known. */
    val matchPercent: Int? = null,
    /** The lyrics are timed at all, so a correction for this song by hand means something. */
    val manualAvailable: Boolean = false,
    /** This song's own correction by hand, ms; positive shows the lyrics later. */
    val manualMs: Long = 0L,
    /** What the measurement compared and how it came out — a diagnostic for the sync drawer. */
    val note: String? = null,
)
