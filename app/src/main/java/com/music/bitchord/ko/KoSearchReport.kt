package com.music.bitchord.ko

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

    fun record(outcome: SourceResolver.SearchOutcome, sourceName: String? = null) {
        synchronized(entries) {
            entries += outcome
            if (outcome.verdict == "auth" && sourceName != null) authSources += sourceName
        }
        if (!outcome.conclusive) allConclusive = false
    }

    /** Sources whose credential was refused, for the listener-facing answer. */
    val authSourceNames: List<String>
        get() = synchronized(entries) { authSources.distinct() }

    /** Test seam: the per-source verdicts this walk collected. */
    internal fun outcomes(): List<SourceResolver.SearchOutcome> = synchronized(entries) { entries.toList() }
}
