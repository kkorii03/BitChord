package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceResolver

/**
 * What every source in one walk answered, so a caller can tell
 * "nobody has it" from "nobody could tell us right now".
 *
 * Only a walk whose every source answered conclusively is a `none` worth
 * caching. A source that timed out, failed, or was skipped for resting is
 * exactly the case that must be asked again later.
 */
open class KoSearchReport {
    private val entries = mutableListOf<SourceResolver.SearchOutcome>()
    private val authSources = mutableListOf<String>()

    @Volatile
    var allConclusive: Boolean = true
        private set

    /**
     * Time this walk spent searching, excluding the proof callback.
     *
     * Filled by [QualityUpgrade.lookAgain][com.music.bitchord.playback.QualityUpgrade]
     * so the upgrade timing line can name the search and the decode
     * separately; a 14s Qobuz decode belongs to verification, not to the
     * catalogue.
     */
    @Volatile
    var searchMs: Long = 0L

    /** Time this walk spent inside the proof callback. */
    @Volatile
    var verifyMs: Long = 0L

    /** [KO] OPT1: sources skipped for resting (config id to name), and whether anything else left the walk open. */
    private val restingSources = mutableListOf<Pair<String, String>>()
    private var openOtherwise = false

    /** [restingId] is the config id of a source this walk skipped for resting. */
    fun record(outcome: SourceResolver.SearchOutcome, sourceName: String? = null, restingId: String? = null) {
        synchronized(entries) {
            entries += outcome
            if (outcome.verdict == "auth" && sourceName != null) authSources += sourceName
            if (!outcome.conclusive) {
                if (outcome.verdict == "resting" && restingId != null) {
                    restingSources += restingId to (sourceName ?: restingId)
                } else {
                    openOtherwise = true
                }
            }
        }
        if (!outcome.conclusive) allConclusive = false
    }

    /**
     * The walk stopped on a good enough answer with sources still running. They
     * never reached [record], so without this the walk reads as every source
     * having answered, and the track as settled.
     */
    fun markEndedEarly() {
        synchronized(entries) { openOtherwise = true }
        allConclusive = false
    }

    /**
     * [KO] OPT1: whether the inconclusive retry ([KoQualityUpgrade.RETRY_AFTER_POSITION_MS]
     * later) could only meet the same rests. True when resting sources alone left this
     * walk open and each would be skipped again then; an error, a timeout or an early
     * end still earns the retry. Phone 11-16..17: 29 of 33 retries found nothing.
     */
    fun retryOnlyMeetsRests(
        mediaId: String,
        nowMs: Long = System.currentTimeMillis(),
        skippedAt: (String, Long) -> Boolean = KoSourceCooldown::skippedAt,
    ): Boolean {
        if (allConclusive) return false
        val resting = synchronized(entries) {
            if (openOtherwise) return false
            restingSources.toList()
        }
        val atMs = nowMs + KoQualityUpgrade.RETRY_AFTER_POSITION_MS
        if (resting.isEmpty() || !resting.all { skippedAt(it.first, atMs) }) return false
        TrackLog.d(
            "BitChord",
            "hq-tier: retry skipped, only resting sources (${resting.map { it.second }.distinct().joinToString()})",
            about = mediaId,
        )
        return true
    }

    /** Sources whose credential was refused, for the listener-facing answer. */
    val authSourceNames: List<String>
        get() = synchronized(entries) { authSources.distinct() }

    /** Test seam: the per-source verdicts this walk collected. */
    internal fun outcomes(): List<SourceResolver.SearchOutcome> = synchronized(entries) { entries.toList() }
}
