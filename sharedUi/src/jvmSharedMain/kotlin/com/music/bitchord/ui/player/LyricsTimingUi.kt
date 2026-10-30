package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricsWordAlign

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
    /**
     * A hand-asked timing can reach an engine that can answer for this song:
     * Gemini's key, or Deepgram's when the title/lyrics aren't skipped and the
     * audio is on disk. The action is not offered otherwise — the press could
     * only end in `aiFailed`.
     */
    val aiAvailable: Boolean = false,
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
    /** The lines are timed by the video's own captions: nothing to measure, only Gemini or a hand to move them. */
    val videoOwn: Boolean = false,
    /** Who gave the AI answer: "Deepgram" or "Gemini" — shown beside it. */
    val aiEngine: String? = null,
    /**
     * The words the transcript heard for this video, on the displayed lyric
     * clock: the panel lights each word at its real time instead of guessing.
     * Empty when no transcript answer applies — see `LyricsTiming.Timing.heardWords`.
     */
    val heardWords: List<LyricsWordAlign.Heard> = emptyList(),
)

/**
 * Whether the sync drawer can offer its AI timing action.
 *
 * A hand-asked timing may spend Deepgram, Whisper or Gemini, so at least one
 * engine must be able to answer for this song and its audio — see
 * [LyricsTimingUi.aiAvailable]. It used to be gated on the Gemini song-meaning
 * key alone, which hid the action from a listener with only Deepgram set up.
 * The shown answer can still be taken back without a key, so `byAi` keeps the
 * action reachable even after a key is removed.
 */
internal fun aiTimingActionEnabled(timing: LyricsTimingUi): Boolean =
    timing.adjustable && (timing.byAi || timing.aiAvailable)

/**
 * Whether the romanization toggle belongs on the lyric controls.
 *
 * It is about the text on screen, not about timing. Leaving the slot empty
 * whenever AI timing was offered made the toggle unreachable on Android,
 * where the AI timing action is always offered to the player.
 */
internal fun romanizationActionEnabled(hasLyrics: Boolean): Boolean = hasLyrics
