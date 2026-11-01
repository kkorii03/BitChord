package com.music.bitchord.ko

import android.net.Uri
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.net.LogUrl
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The fork's second-look state and manual-search path, kept out of the
 * upstream [QualityUpgrade] file so an upstream merge has little to resolve.
 *
 * Upstream owns the queue, the search and the swap. This object owns the
 * additions around them: the candidate list from one manual search, the
 * bounded retry after an inconclusive answer, the sources and hosts that
 * failed an audition, and the answers the player menu gives a hand-made
 * request.
 */
internal object KoQualityUpgrade {

    private const val TAG = "BitChord"
    private const val UPGRADED = "hifi"

    /**
     * The candidates one manual search already found, waiting to be tried.
     *
     * A candidate that fails its proof used to send [QualityUpgrade.lookAgain]
     * back through every source, which on the reported device paid the same
     * twelve-second search again and re-asked the same refused Deezer row. The
     * first search already returned every copy worth trying; this keeps them
     * in hand.
     */
    private val candidateQueue = ConcurrentHashMap<String, MutableList<SourceStream>>()

    /** One manual request's identity, renewed when the listener presses again. */
    private val manualSessions = ConcurrentHashMap<String, Any>()

    /**
     * When an inconclusive second look may be tried again, keyed by media id.
     *
     * A search that came back empty only because a source failed, timed out or
     * was still resting is not a verdict. It must not enter
     * [QualityUpgrade.asked], but it also must not be retried on every
     * progress sample — that would be a timer by another name. The timestamp
     * lets the next ordinary event (a progress sample, a resume) ask again once
     * the window has passed, and the automatic path keeps its rest and cache
     * rules.
     */
    private val retryAfter = ConcurrentHashMap<String, Long>()

    /** How long an inconclusive "none" waits before an automatic retry. */
    private const val INCONCLUSIVE_RETRY_MS = 60_000L

    /**
     * The last pending question a second look was asked about, kept so a
     * candidate that fails its audition can hand the same question to the next
     * source without re-deriving what is playing.
     */
    private val lastPending = ConcurrentHashMap<String, QualityUpgrade.Pending>()

    /** Sources and hosts already refused by a failed audition for [mediaId]. */
    private val failedSources = ConcurrentHashMap<String, MutableSet<String>>()
    private val failedHosts = ConcurrentHashMap<String, MutableSet<String>>()

    /** Whether this look is the listener's hand-made request. */
    fun isManual(mediaId: String): Boolean = mediaId in QualityUpgrade.handAsked

    /** The session identity of a hand-made request, or null for an automatic one. */
    fun sessionFor(mediaId: String, manual: Boolean): Any? =
        if (manual) manualSessions.getOrPut(mediaId) { Any() } else null

    fun rememberLastPending(mediaId: String, waiting: QualityUpgrade.Pending) {
        lastPending[mediaId] = waiting
    }

    /** Whether a still-running lookup is not one a failed audition already excluded. */
    fun allowsInFlight(mediaId: String, late: SourceStream): Boolean {
        val lateHost = late.url.let(HqHostTracker::hostOf)
        return late.sourceConfigId !in failedSources[mediaId].orEmpty() &&
            (lateHost == null || lateHost !in failedHosts[mediaId].orEmpty())
    }

    /**
     * A candidate that was chosen but failed its audition or verification.
     *
     * Its source and host are excluded from the next attempt, and the track is
     * put back in the state a fresh second look expects — without touching
     * [lastPending], which is what [retryNext] needs. This is the difference
     * between "this candidate was bad" and "this track has no better copy".
     */
    fun failedCandidate(mediaId: String, sourceConfigId: String?, host: String?) {
        sourceConfigId?.takeIf { it.isNotBlank() }?.let {
            failedSources.getOrPut(mediaId) { ConcurrentHashMap.newKeySet() } += it
        }
        host?.takeIf { it.isNotBlank() }?.let {
            failedHosts.getOrPut(mediaId) { ConcurrentHashMap.newKeySet() } += it
        }
        QualityUpgrade.forced.remove(mediaId)
        QualityUpgrade.shelved.remove(mediaId)
        QualityUpgrade.auditioning -= mediaId
        QualityUpgrade.pending.remove(mediaId)
        QualityUpgrade.asked -= mediaId
        HqHostTracker.endDownload(mediaId, host)
    }

    /**
     * Re-arms the second look after [failedCandidate], so the next source in
     * the same tier can be tried immediately. False when there is no previous
     * question to re-ask.
     */
    fun retryNext(mediaId: String): Boolean {
        val last = lastPending[mediaId] ?: return false
        QualityUpgrade.pending[mediaId] = last
        QualityUpgrade.asked -= mediaId
        return true
    }

    /** A bounded automatic retry after an answer no source could confirm. */
    fun suppressesRetry(mediaId: String): Boolean {
        val retryAt = retryAfter[mediaId] ?: 0L
        if (System.currentTimeMillis() < retryAt) return true
        if (retryAt != 0L) retryAfter.remove(mediaId)
        return false
    }

    fun recordAnswer(mediaId: String, waiting: QualityUpgrade.Pending, conclusive: Boolean) {
        if (conclusive) {
            QualityUpgrade.asked += mediaId
        } else {
            // Not a verdict: a source failed, timed out or was resting. Allow a
            // later event to ask again after a bounded wait instead of recording
            // the track as permanently answered.
            retryAfter[mediaId] = System.currentTimeMillis() + INCONCLUSIVE_RETRY_MS
            TrackLog.d(
                TAG,
                "'${waiting.target.title}' has no confirmed answer yet; a source was unavailable, will retry",
                about = mediaId,
            )
        }
    }

    /**
     * What one walk of the manual candidate list answered: the next stream, any
     * lossless follow-up to arm on it, and whether the walk was conclusive.
     */
    data class ManualLook(
        val stream: SourceStream?,
        val followUp: QualityUpgrade.Pending?,
        val conclusive: Boolean,
    )

    /**
     * A hand-made request asks every source once and keeps the whole candidate
     * list. Walking to the next source after one fails its proof is then a list
     * pop, not another search — the reported upgrade paid the same twelve-second
     * search once per candidate and re-asked a source that had already refused.
     */
    suspend fun manualLook(
        mediaId: String,
        waiting: QualityUpgrade.Pending,
        effectiveDurationSec: Int?,
        report: SourceResolver.SearchReport,
        measuredVerify: (suspend (TrackMatcher.Target, SourceStream) -> Boolean)?,
        excludedSources: Set<String>,
        excludedHosts: Set<String>,
        session: Any?,
    ): ManualLook {
        val queued = candidateQueue[mediaId]
        if (queued != null) {
            // The queue was built against the floor that was playing when it
            // was collected. A candidate popped after the floor moved on (a
            // lossy swap armed a lossless follow-up) must be weighed again, or
            // a copy that only beat the old stream can be swapped in as a
            // downgrade. The source already serving the track is out for the
            // same reason the first search leaves it out.
            val next = queued.firstOrNull {
                it.sourceConfigId !in excludedSources &&
                    it.sourceConfigId != waiting.servedBy &&
                    SourceResolver.worthSwapping(it.format, waiting.playing) &&
                    (it.url.let(HqHostTracker::hostOf)?.let { host -> host !in excludedHosts } ?: true)
            }
            if (next != null) {
                queued.remove(next)
                return ManualLook(next, null, true)
            }
            // Everything the one search found has been tried. That is a complete
            // answer to this request, not a reason to ask the same sources again.
            return ManualLook(null, null, true)
        }
        val effectiveTarget = waiting.target.copy(durationSec = effectiveDurationSec)
        val searched = SourceResolver.upgradeCandidatesFor(
            target = effectiveTarget,
            playing = waiting.playing,
            servedBy = waiting.servedBy,
            excludeSources = excludedSources,
            excludeHosts = excludedHosts,
            manual = true,
            report = report,
            manualSession = session,
            verify = measuredVerify?.let { check ->
                { _, stream -> check(effectiveTarget, stream) }
            },
        )
        val queue = searched.map { it.second }.toMutableList()
        candidateQueue[mediaId] = queue
        val next = queue.firstOrNull()
        if (next != null) queue.removeAt(0)
        val followUp = if (next != null && QualityUpgrade.needsLosslessFollowUp(next.format)) {
            waiting.copy(
                target = waiting.target.copy(durationSec = effectiveDurationSec),
                inFlight = null,
                playing = next.format,
                servedBy = next.sourceConfigId,
            )
        } else {
            null
        }
        return ManualLook(next, followUp, report.allConclusive)
    }

    /**
     * Opens the second look for a track the listener asked about by hand when
     * nothing left it pending.
     *
     * The automatic look empties [QualityUpgrade.pending] as it asks, and
     * [QualityUpgrade.askByHand] drops [lastPending], so a track whose automatic
     * search had already answered reached the manual pass with nothing to search
     * with. [QualityUpgrade.adoptUnresolved] only takes tracks played from the
     * cache, so a resolved track was turned away with "can't change the quality
     * right now" before any search ran (10-31, '체포하겠어' and 'Friction', no line
     * after "asked for by hand").
     */
    fun adoptByHand(mediaId: String, target: TrackMatcher.Target, playing: StreamFormat?): Boolean {
        if (target.title.isBlank() || !isManual(mediaId)) return false
        QualityUpgrade.pending[mediaId] = QualityUpgrade.Pending(
            target,
            inFlight = null,
            playing = playing,
        )
        retryAfter.remove(mediaId)
        NerdStats.onLosslessRaceStart(mediaId)
        TrackLog.d(
            TAG,
            "manual upgrade: searching again for '${target.title}' over ${playing?.summary ?: "an unmeasured stream"}",
            about = mediaId,
        )
        return true
    }

    /** The fork's additions after upstream has reopened the sets in [QualityUpgrade.askByHand]. */
    fun afterAskByHand(mediaId: String) {
        failedSources.remove(mediaId)
        failedHosts.remove(mediaId)
        lastPending.remove(mediaId)
        retryAfter.remove(mediaId)
        // A new press is a new request: its resting-source probe budget and its
        // candidate list start empty, even if an earlier press left some.
        val session = Any()
        manualSessions.put(mediaId, session)?.let(SourceResolver::clearManualProbes)
        candidateQueue.remove(mediaId)
    }

    /**
     * Whether a running automatic pass must leave the manual request that joined
     * it unanswered, so the waiter can run the manual pass itself.
     *
     * The pass captured `manual = false` when it started, before the listener
     * pressed the button; its search therefore skipped resting sources and
     * replayed the memoised empty answer. A `none` from that pass is not the
     * answer to the manual request — the request was for a search under the
     * manual rules — and answering it would clear the player's
     * `manualUpgradeFor` before the waiter could run. See 10-31 'Flowers'.
     */
    fun defersAutomaticAnswerToManual(
        startedManual: Boolean,
        manualPending: Boolean,
        manualJoined: Boolean,
    ): Boolean = !startedManual && manualPending && manualJoined

    /** The fork state a fresh play clears in [QualityUpgrade.onPlaybackStarted]. */
    fun onPlaybackStarted(mediaId: String) {
        QualityUpgrade.refused -= mediaId
        lastPending.remove(mediaId)
        failedSources.remove(mediaId)
        failedHosts.remove(mediaId)
        retryAfter.remove(mediaId)
    }

    /** The fork state [QualityUpgrade.forget] drops with the track. */
    fun forget(mediaId: String) {
        lastPending.remove(mediaId)
        failedSources.remove(mediaId)
        failedHosts.remove(mediaId)
        retryAfter.remove(mediaId)
        candidateQueue.remove(mediaId)
        manualSessions.remove(mediaId)?.let(SourceResolver::clearManualProbes)
    }

    /** The fork state a new player session must not inherit. */
    fun forgetLastSession() {
        manualSessions.clear()
        SourceResolver.clearManualProbes()
        retryAfter.clear()
    }

    /** A lossy swap landed, so candidates collected against the old floor are stale. */
    fun onLossySwapContinued(mediaId: String) {
        candidateQueue.remove(mediaId)
    }

    /**
     * Drops the forced stream serving [url] when only the URL is known.
     *
     * A swapped-in lossless/module rendition has no YouTube videoId in its URL,
     * so the resolver's rate-limit feedback cannot name the mediaId. Matching
     * the opened URL against the parked stream is what keeps the next open from
     * serving the URL the host just asked us to leave alone.
     */
    fun forgetUrl(url: String) {
        val key = canonicalUrl(url)
        val mediaId = QualityUpgrade.forced.entries.firstOrNull { canonicalUrl(it.value.url) == key }?.key
        if (mediaId == null) {
            // Not silent: the resolver reported this URL as rate-limited and the
            // forced stream is the only copy that would be served again. A
            // no-match means the keys differ (encoding, redirect) and the pin
            // survives, so the next open repeats the paused URL.
            TrackLog.w(TAG, "no forced stream to forget for rate-limited URL: ${LogUrl.of(url)}")
            return
        }
        QualityUpgrade.forget(mediaId)
        StreamChoice.forget(mediaId)
    }

    /** The same URL OkHttp opens, so `Uri` re-encoding cannot lose the match. */
    private fun canonicalUrl(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url

    /**
     * Forgets a replacement that is being reverted, together with the choice
     * that still names it.
     *
     * [QualityUpgrade.forget] drops the forced stream, but [StreamChoice] keeps
     * the replacement recorded when the swap went out. Reopening the previous
     * URI with that choice in place resolves the replacement URL under the old
     * rendition key, which can append a different file to a partial cache entry.
     * The direct-YouTube path already releases both; this is the same hand-back
     * for a revert onto the previous stream.
     */
    fun forgetReverted(mediaId: String) {
        QualityUpgrade.forget(mediaId)
        StreamChoice.forget(mediaId)
    }

    /**
     * The upgrade generation a rendition key's suffix names: `hifi` is 1,
     * `hifi-2` is 2, and anything else is 0.
     *
     * Used when a plain play request picks among the renditions already on
     * disk. A later generation replaced an earlier one only because it was
     * better, so the newest complete upgrade is the one to open.
     */
    fun upgradeGeneration(tag: String?): Int {
        if (tag == null || !tag.startsWith(UPGRADED)) return 0
        return tag.removePrefix(UPGRADED)
            .removePrefix("-")
            .toIntOrNull()
            ?: 1
    }

    /**
     * Why [QualityUpgrade.couldStillUpgrade] said no, in the words a manual
     * request is answered with.
     */
    fun refusalFor(mediaId: String, uri: Uri?): QualityUpgrade.Refusal = refusalOf(
        hasStream = uri?.getQueryParameter("v") != null,
        musicVideo = uri?.getQueryParameter("m") == "1",
        canSubstitute = SourceResolver.canSubstituteForYouTube(),
    )

    fun refusalOf(hasStream: Boolean, musicVideo: Boolean, canSubstitute: Boolean): QualityUpgrade.Refusal = when {
        !hasStream -> QualityUpgrade.Refusal.NO_STREAM
        // Substitution never matches a music-video row against a catalogue
        // recording (SourceResolver refuses video targets): the listener's way
        // to lossless is the song version of the row, not this one.
        musicVideo -> QualityUpgrade.Refusal.MUSIC_VIDEO
        !canSubstitute -> QualityUpgrade.Refusal.NO_SOURCE
        else -> QualityUpgrade.Refusal.ALREADY_CHECKED
    }

    /**
     * The decoder's own source identity, when it has spoken.
     *
     * It is the only record of what is actually being read, and the menu used
     * to disagree with the badge when a stored verdict or a stale
     * [StreamChoice] still named YouTube over a module stream. Null keeps the
     * caller's existing [StreamChoice] answer.
     */
    fun youtubeOwnVerdict(mediaId: String): Boolean? {
        val snapshot = NerdStats.current.value?.takeIf { it.mediaId == mediaId } ?: return null
        val kind = snapshot.sourceConfigId
            ?.let { SourceRegistry.config(it)?.kind }
        return when {
            kind == SourceKind.YOUTUBE -> true
            kind != null -> false
            snapshot.sourceName == "YouTube" -> true
            snapshot.sourceName != null -> false
            else -> null
        }
    }

    /** The fork's lossless-tier test, kept beside the follow-up rule it feeds. */
    fun isLosslessTier(format: StreamFormat?): Boolean = HqQuality.isLosslessTier(format)

    /**
     * Separates the cost of the catalogue search from the cost of the sound
     * comparison, and fills the report used by the upgrade timing line.
     */
    class LookScope(
        private val verify: (suspend (TrackMatcher.Target, SourceStream) -> Boolean)?,
        val excludedSources: Set<String>,
        val excludedHosts: Set<String>,
    ) {
        private val startedNs = System.nanoTime()
        private var verifyNanos = 0L

        val measuredVerify: (suspend (TrackMatcher.Target, SourceStream) -> Boolean)? =
            verify?.let { check ->
                { target: TrackMatcher.Target, stream: SourceStream ->
                    val started = System.nanoTime()
                    try {
                        check(target, stream)
                    } finally {
                        verifyNanos += System.nanoTime() - started
                    }
                }
            }

        fun finish(report: SourceResolver.SearchReport) {
            val elapsedNs = System.nanoTime() - startedNs
            report.verifyMs = verifyNanos / 1_000_000L
            report.searchMs = ((elapsedNs - verifyNanos).coerceAtLeast(0L)) / 1_000_000L
        }
    }

    fun beginLook(
        mediaId: String,
        verify: (suspend (TrackMatcher.Target, SourceStream) -> Boolean)?,
    ): LookScope = LookScope(
        verify = verify,
        excludedSources = failedSources[mediaId].orEmpty().toSet(),
        excludedHosts = failedHosts[mediaId].orEmpty().toSet(),
    )
}
