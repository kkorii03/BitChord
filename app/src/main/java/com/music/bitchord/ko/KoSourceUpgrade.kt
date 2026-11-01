package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.telegram.TelegramUpgradeGate

/**
 * Sources an automatic upgrade, substitute or fallback walk may ask.
 *
 * Telegram is ranked above YouTube and joins these walks only when
 * [MusicSource.automaticUpgradeEligible] says there is an account and a
 * selected chat to search. A source left on without a session stays out,
 * which is what keeps an automatic upgrade from opening TDLib for an
 * account that has not signed in; [target] lets that one case leave the
 * `hq-search` line instead of disappearing silently.
 */
internal fun automaticUpgradeCandidates(
    sources: List<MusicSource>,
    target: TrackMatcher.Target? = null,
): List<MusicSource> {
    val kept = ArrayList<MusicSource>(sources.size)
    for (source in sources) {
        if (source.automaticUpgradeEligible) {
            kept += source
            continue
        }
        val reason = source.automaticUpgradeIneligibleReason
        if (target != null && source.kind == SourceKind.TELEGRAM &&
            reason == TelegramUpgradeGate.SIGNED_OUT
        ) {
            TrackLog.d(
                "BitChord",
                hqSearchLine(
                    target,
                    source,
                    SourceResolver.SearchOutcome(results = 0, verdict = reason),
                ),
            )
        }
    }
    return kept
}

/** The one place the upgrade candidate list's quality order is defined. */
internal fun orderUpgradeCandidates(
    found: List<Pair<MusicSource, SourceStream>>,
): List<Pair<MusicSource, SourceStream>> = found
    .distinctBy { it.second.url }
    .sortedWith(
        compareBy<Pair<MusicSource, SourceStream>> { HqQuality.tierOf(it.second.format).rank }
            .thenByDescending { it.second.format.kbps ?: 0 }
            .thenByDescending { it.second.format.bitDepth ?: 0 }
            .thenByDescending { it.second.format.sampleRateHz ?: 0 },
    )

/**
 * Every worthwhile upgrade candidate, best rendition first, from **one**
 * search.
 *
 * [SourceResolver.upgradeFor] answers "what should play now". This answers
 * "what else did the same search find", which is what a caller walking
 * candidates after a failed verification needs. Re-running the upgrade for
 * each one asked every source again.
 *
 * The list is ordered by [HqQuality]'s ladder: 24-bit before 16-bit
 * lossless before lossy. Verification is expensive (a Qobuz 24-bit head
 * measured ~14s), so the copy with the most to gain is proved first.
 */
internal suspend fun upgradeCandidatesFor(
    target: TrackMatcher.Target,
    playing: StreamFormat? = null,
    servedBy: String? = null,
    excludeSources: Set<String> = emptySet(),
    excludeHosts: Set<String> = emptySet(),
    allowSameRendition: Boolean = false,
    manual: Boolean = false,
    report: SourceResolver.SearchReport? = null,
    manualSession: Any? = null,
    verify: (suspend (MusicSource, SourceStream) -> Boolean)? = null,
): List<Pair<MusicSource, SourceStream>> {
    val found = mutableListOf<Pair<MusicSource, SourceStream>>()
    SourceResolver.upgradeFor(
        target = target,
        playing = playing,
        servedBy = servedBy,
        excludeSources = excludeSources,
        excludeHosts = excludeHosts,
        allowSameRendition = allowSameRendition,
        manual = manual,
        report = report,
        manualSession = manualSession,
        collect = found,
        verify = verify,
    )
    return orderUpgradeCandidates(found)
}
