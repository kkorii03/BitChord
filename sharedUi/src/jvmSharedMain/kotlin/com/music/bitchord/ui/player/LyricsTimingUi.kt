package com.music.bitchord.ui.player

/**
 * Whether the lyrics on screen were moved onto the video's own clock, and how
 * — for the AI timing button and the line under the lyrics.
 */
data class LyricsTimingUi(
    /** The lyrics are timed and not already the video's own, so they can be moved at all. */
    val adjustable: Boolean = false,
    /** Every line moved by this much, in milliseconds; null when not shifted as a block. */
    val shiftMs: Long? = null,
    /** Each line moved on its own (a video on a different pace). */
    val retimed: Boolean = false,
    /** The current timing came from Gemini rather than the automatic measurement. */
    val byAi: Boolean = false,
    /** Gemini is watching the video right now. */
    val aiRunning: Boolean = false,
    /** The last Gemini request came back with nothing usable. */
    val aiFailed: Boolean = false,
)
