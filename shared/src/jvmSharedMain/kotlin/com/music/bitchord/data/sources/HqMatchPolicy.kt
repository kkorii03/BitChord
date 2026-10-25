package com.music.bitchord.data.sources

import com.music.bitchord.data.lyrics.LyricsTimeMap
import kotlin.math.roundToLong

/**
 * The pure half of "is this high-quality candidate the same recording, and if
 * so where does it start relative to the YouTube song version?"
 *
 * The measurement itself is [ChromaAlignment][com.music.bitchord.data.lyrics.ChromaAlignment],
 * which is already used to move lyrics between versions. Its alignment maths
 * is deliberately untouched: this file only decides whether the answer clears
 * the bar and turns the offset/rate into the map the lyric clock needs.
 *
 * The reference clock is always the YouTube song version (ATV). A candidate
 * that starts later, earlier, or runs at a slightly different pace is fine as
 * long as it is audibly the same performance; the resulting map carries the
 * lyrics back onto the candidate's clock.
 */
object HqMatchPolicy {

    /**
     * How much of the reference's sounding frames must line up.
     *
     * ChromaAlignment already refuses a poor alignment, but that refusal is
     * tuned for "this is a cover of the same song" — it will align a karaoke
     * backing track. A substitute has to be the *recording*, so this sits
     * above the alignment's own floor and above the 70% the AI timing uses.
     */
    const val MIN_MATCH_PERCENT = 75

    /** A different take can be a little slower or faster; a different song is not. */
    const val MIN_RATE = 0.90
    const val MAX_RATE = 1.10

    /**
     * The largest start difference accepted. Ninety seconds are compared, so a
     * longer offset means the match is being carried by the extrapolation
     * rather than by the audio.
     */
    const val MAX_ABS_OFFSET_MS = 90_000L

    /** What the verdict was, and why — the reason is for the log and tests. */
    data class Verdict(
        val accepted: Boolean,
        val matchPercent: Int,
        val offsetMs: Long,
        val rate: Double,
        val reason: String,
    )

    /**
     * The decision for one measured pair.
     *
     * [offsetMs] is `candidateTime - youtubeTime` at the start of the song, so
     * a candidate with a 1.2 s intro gets +1200. [rate] is candidate seconds
     * per YouTube second.
     */
    fun verdict(matchPercent: Int?, offsetMs: Long, rate: Double): Verdict {
        val match = matchPercent?.coerceIn(0, 100) ?: 0
        return when {
            match < MIN_MATCH_PERCENT ->
                Verdict(false, match, offsetMs, rate, "match ${match}% < $MIN_MATCH_PERCENT%")
            !rate.isFinite() || rate < MIN_RATE || rate > MAX_RATE ->
                Verdict(false, match, offsetMs, rate, "rate ${"%.3f".format(java.util.Locale.ROOT, rate)} outside $MIN_RATE..$MAX_RATE")
            offsetMs < -MAX_ABS_OFFSET_MS || offsetMs > MAX_ABS_OFFSET_MS ->
                Verdict(false, match, offsetMs, rate, "offset ${offsetMs}ms outside ±$MAX_ABS_OFFSET_MS")
            else -> Verdict(true, match, offsetMs, rate, "accepted")
        }
    }

    /**
     * Where [youtubeMs] falls on a candidate's clock:
     * `candidate = offset + rate × youtube`.
     *
     * Kept as a pure function so the same arithmetic is used by the stored
     * map, the tests and the log line.
     */
    fun applyOffset(youtubeMs: Long, offsetMs: Long, rate: Double): Long =
        (offsetMs + youtubeMs * rate).roundToLong().coerceAtLeast(0L)

    /** The lyric map for a verified candidate: YouTube time → candidate time. */
    fun mapFor(offsetMs: Long, rate: Double): LyricsTimeMap? =
        if (!rate.isFinite() || rate <= 0.0) null else LyricsTimeMap.linear(rate, offsetMs)
}
