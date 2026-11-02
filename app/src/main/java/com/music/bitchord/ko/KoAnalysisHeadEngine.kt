package com.music.bitchord.ko

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.playback.AnalysisHeadClear
import com.music.bitchord.playback.analysisHeadClearOutcome
import com.music.bitchord.playback.analysisHeadHeldClaimMayRetry
import com.music.bitchord.playback.analysisHeadMayClaimOrRetry
import com.music.bitchord.playback.analysisHeadShouldRetry
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [KO] analysis-head
 *
 * The fork's one-round analysis-head policy: claim, defer while a reader owns
 * the entry, retry only after real progress, and the event-driven wait for the
 * opening. State containers are owned by AudioCache and passed by reference.
 */
class KoAnalysisHeadEngine(
    private val cache: () -> SimpleCache?,
    private val scope: CoroutineScope,
    private val upstreamAvailable: () -> Boolean,
    private val keyFactory: CacheKeyFactory,
    private val analysisHeads: MutableMap<String, Boolean>,
    private val deferredLogged: MutableSet<String>,
    private val heldElsewhere: MutableSet<String>,
    private val inFlight: MutableSet<String>,
    private val stalled: MutableSet<String>,
    private val wantMap: MutableMap<String, Long>,
    private val anotherWriterHas: (String) -> Boolean,
    private val alignmentPrefixBytes: (String) -> Long,
    private val alignmentHeadComplete: (String) -> Boolean,
    private val analysisHeadSize: (Long) -> Long,
    private val fetchRange: suspend (String, Uri, Long, Long, Boolean) -> Unit,
    private val recordContentLength: (String, Long) -> Unit,
    private val invalidateRenditionKeys: (String) -> Unit,
    private val forgetPurgeCandidate: (String) -> Unit,
    private val waitKeys: (String) -> List<String>,
) {
    private val tag = "BitChord"

    /** Waits currently parked on [videoId]; the cache's span event and a round's end both wake them. */
    private val waitWakeups = ConcurrentHashMap<String, MutableSet<Channel<Unit>>>()

    suspend fun awaitAnalysisHead(uri: Uri, timeoutMs: Long = 20_000L): Boolean {
        val openedCache = cache() ?: return false
        val videoId = uri.getQueryParameter("v") ?: return false
        if (alignmentHeadComplete(videoId)) return true
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        // A previous round that wrote nothing would otherwise block retries
        // for the rest of the session — but one that ended with no progress is
        // not worth a second identical fetch either; see stalled.
        if (videoId !in inFlight && videoId !in stalled) {
            analysisHeads.remove(videoId)
        }
        // What a further round has to beat to be worth starting: taken before
        // the round is started so its own bytes cannot count as progress.
        var lastHeld = alignmentPrefixBytes(videoId)
        requestAnalysisHead(opus)
        var rounds = 1
        var askedAt = SystemClock.elapsedRealtime()
        val deadline = askedAt + timeoutMs
        // The player is the likeliest writer of this track's entry — it is
        // either playing it or buffering it ahead in its playlist — and
        // read-ahead is the other. While either holds the entry, racing them
        // for the same key can only fail, so wait for the bytes already being
        // brought in and let alignmentPrefixBytes decide when there are
        // enough. One round is still allowed after they let go: the entry is
        // free then even if the other writer wrote nothing.
        var waitedForWriter = anotherWriterHas(videoId)
        // Wakes on the cache's own span event instead of polling: every write
        // this wait is interested in raises onSpanAdded, and a round ending
        // without a byte raises the completion signal below.
        val wake = Channel<Unit>(Channel.CONFLATED)
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                wake.trySend(Unit)
            }

            override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
                wake.trySend(Unit)
            }

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
                wake.trySend(Unit)
            }
        }
        val keys = waitKeys(videoId)
        keys.forEach { key -> runCatching { openedCache.addListener(key, listener) } }
        registerWakeup(videoId, wake)
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (alignmentHeadComplete(videoId)) return true
                if (anotherWriterHas(videoId)) {
                    waitedForWriter = true
                    awaitWake(wake, deadline)
                    continue
                }
                if (videoId !in inFlight) {
                    if (rounds >= MAX_ALIGNMENT_HEAD_ROUNDS) return alignmentHeadComplete(videoId)
                    val now = SystemClock.elapsedRealtime()
                    val settleAt = askedAt + HEAD_ROUND_SETTLE_MS
                    if (now < settleAt) {
                        // Claiming and registering the claim are two statements;
                        // wait out the gap before looking again.
                        awaitWake(wake, minOf(deadline, settleAt))
                        continue
                    }
                    // A round that got as far as its lock probe — the few
                    // dozen kilobytes written before the stream underneath
                    // them was refused — used to count as bytes on disk and
                    // block every later attempt, so the wait sat out the
                    // whole deadline against a head that was never going to
                    // grow and the switch went ahead unaligned. Below
                    // MIN_ALIGNMENT_PREFIX_BYTES there is nothing here a
                    // measurement can act on, so the round may try again, and
                    // each retry runs clearPartialHead first — which is what
                    // keeps a part-written entry from splicing two encodings.
                    // MAX_ALIGNMENT_HEAD_ROUNDS still bounds how often, and
                    // a round that added nothing is not repeated at all: the
                    // same resolve and range would fail the same way, which is
                    // how one track logged three "came up short: held=0" lines
                    // in two seconds.
                    val held = alignmentPrefixBytes(videoId)
                    if (held < MIN_ALIGNMENT_PREFIX_BYTES) {
                        val progressed = analysisHeadShouldRetry(
                            heldBefore = lastHeld,
                            heldAfter = held,
                            roundsStarted = rounds,
                            maxRounds = MAX_ALIGNMENT_HEAD_ROUNDS,
                            minBytes = MIN_ALIGNMENT_PREFIX_BYTES,
                        )
                        if (!progressed && !waitedForWriter) return false
                        if (rounds >= MAX_ALIGNMENT_HEAD_ROUNDS) return false
                        lastHeld = held
                        analysisHeads.remove(videoId)
                        requestAnalysisHead(opus)
                        waitedForWriter = false
                        rounds++
                    }
                    askedAt = now
                } else {
                    awaitWake(wake, deadline)
                }
            }
            return alignmentHeadComplete(videoId)
        } finally {
            keys.forEach { key -> runCatching { openedCache.removeListener(key, listener) } }
            unregisterWakeup(videoId, wake)
            wake.close()
        }
    }

    /** Completes when the round for [videoId] ends, or after [timeoutMs]. Never polls. */
    suspend fun awaitRoundDone(videoId: String, timeoutMs: Long) {
        if (videoId !in inFlight) return
        val wake = Channel<Unit>(Channel.CONFLATED)
        registerWakeup(videoId, wake)
        try {
            if (videoId !in inFlight) return
            withTimeoutOrNull(timeoutMs) { wake.receive() }
        } finally {
            unregisterWakeup(videoId, wake)
            wake.close()
        }
    }

    private suspend fun awaitWake(wake: Channel<Unit>, deadline: Long) {
        val left = deadline - SystemClock.elapsedRealtime()
        if (left <= 0L) return
        withTimeoutOrNull(left) { wake.receive() }
    }

    private fun registerWakeup(videoId: String, wake: Channel<Unit>) {
        waitWakeups.computeIfAbsent(videoId) { ConcurrentHashMap.newKeySet() }.add(wake)
    }

    private fun unregisterWakeup(videoId: String, wake: Channel<Unit>) {
        val waiting = waitWakeups[videoId] ?: return
        waiting.remove(wake)
        if (waiting.isEmpty()) waitWakeups.remove(videoId, waiting)
    }

    private fun signalWaiters(videoId: String) {
        waitWakeups[videoId]?.forEach { it.trySend(Unit) }
    }

    fun requestAnalysisHead(uri: Uri) {
        // Each of these gates used to stop a head dead with nothing in the
        // log to show for it: an unwired cache or upstream looked from the
        // outside exactly like a network that refuses everything.
        if (cache() == null) {
            TrackLog.w(tag, "analysis head for $uri not started: cache is not initialised")
            return
        }
        if (!upstreamAvailable()) {
            TrackLog.w(tag, "analysis head for $uri not started: no upstream yet")
            return
        }
        val videoId = uri.getQueryParameter("v") ?: return
        // A player read, or read-ahead's own write, holds the entry and is
        // already bringing the bytes in. Fetching the same range here would
        // fail the write and leave this round "short" over data that went on
        // to be cached. Deferral must not consume the one-round claim: doing
        // that used to leave the track marked "already asked" forever, so once
        // the other writer let go no later tick could start the fetch. A claim
        // kept from a previous HeldElsewhere round is retried only after that
        // writer releases, not on every tick: see heldElsewhere.
        val anotherWriter = anotherWriterHas(videoId)
        val heldForReader = videoId in heldElsewhere
        if (!analysisHeadMayClaimOrRetry(
                alreadyRequested = analysisHeads.containsKey(videoId),
                heldForReader = heldForReader,
                anotherWriterHas = anotherWriter,
                inFlight = videoId in inFlight,
            )
        ) {
            if (anotherWriter && deferredLogged.add(videoId)) {
                TrackLog.d(tag, "analysis head for $videoId deferred: another writer has it", videoId)
            }
            return
        }
        // One round per track per session, and it is sized correctly up front
        // rather than grown into. See analysisHeadSize for why growing it was
        // the wrong shape. A held claim is already in the map, so reuse it
        // instead of treating its presence as "already done".
        if (heldForReader) {
            heldElsewhere.remove(videoId)
            // A concurrent release hook may have taken the claim just before
            // this request; put it back defensively so the round still has its
            // one claim behind it.
            analysisHeads.putIfAbsent(videoId, true)
        } else if (analysisHeads.putIfAbsent(videoId, true) != null) {
            return
        }
        deferredLogged.remove(videoId)
        // Checked before the round is claimed, so the playback thread pays a set
        // lookup per tick rather than queueing coroutines four times a second on
        // top of a fetch that is still running.
        if (!inFlight.add(videoId)) {
            // A round slipped in between the claim check and here. Never leave
            // the claim without a round behind it: that is a permanent "asked"
            // mark that no later tick can retry.
            analysisHeads.remove(videoId)
            return
        }
        scope.launch {
            try {
                val total = runCatching { StreamResolver.contentLength(videoId) }
                    .onFailure {
                        TrackLog.w(tag, "contentLength failed for $videoId: ${it.message}", videoId)
                    }
                    .getOrNull() ?: 0L
                val want = analysisHeadSize(total)
                wantMap[videoId] = want
                TrackLog.w(
                    tag,
                    "analysis head round for $videoId: total=$total want=$want " +
                        "held=${alignmentPrefixBytes(videoId)}",
                    videoId,
                )
                // What this round has to beat; a round that ends no higher is
                // the same request against the same refusal and is not worth
                // starting again in this session.
                val before = alignmentPrefixBytes(videoId)
                when (clearPartialHead(videoId, want)) {
                    AnalysisHeadClear.Proceed -> Unit
                    AnalysisHeadClear.AlreadyComplete -> return@launch
                    AnalysisHeadClear.HeldElsewhere -> {
                        if (anotherWriterHas(videoId)) {
                            // Keep the one-round claim and wait for the
                            // reader's release event. Releasing it here made
                            // every 250 ms idle tick launch a coroutine,
                            // re-resolve contentLength and retry the same
                            // locked removeResource for the minutes the
                            // player/read-ahead held the entry.
                            heldElsewhere.add(videoId)
                            TrackLog.w(
                                tag,
                                "analysis head round for $videoId deferred: partial is held by a reader",
                                videoId,
                            )
                        } else {
                            // The lock belongs to a writer this cache does not
                            // track (an in-flight fetch). Release the claim so
                            // the next request can retry; it is short-lived.
                            analysisHeads.remove(videoId)
                            TrackLog.w(
                                tag,
                                "analysis head round for $videoId skipped: partial is held elsewhere",
                                videoId,
                            )
                        }
                        return@launch
                    }
                }
                fetchRange(
                    videoId,
                    // Do not inherit a JioSaavn/lossless StreamChoice from
                    // playback. The base key is reserved for the lightweight
                    // YouTube Opus copy used by Automix analysis.
                    Uri.parse(AutomixAnalysisSource.opusUri(videoId)),
                    0,
                    want,
                    true,
                )
                if (alignmentPrefixBytes(videoId) < MIN_ALIGNMENT_PREFIX_BYTES) {
                    // The plain Opus copy would not come: on the networks where
                    // its googlevideo resolve fails it never does, while
                    // playback of the very same track is unbothered because it
                    // reads whichever source the substitution chose — which is
                    // also exactly what the direction that *works* measures
                    // against. So when the pinned copy comes up short, pull
                    // that copy instead: alignment opens the fullest copy on
                    // disk anyway, and a failed head here otherwise costs the
                    // whole switch its smart align while the swap goes ahead
                    // regardless.
                    // Through fetch, so an entry the player is streaming into
                    // costs a probe rather than the whole track read onto the floor.
                    val watch = Uri.parse("bitchord://watch?v=$videoId")
                    fetchRange(keyFactory.buildCacheKey(DataSpec(watch)), watch, 0, want, false)
                }
                if (total > 0) recordContentLength(videoId, total)
                // The keys this video has on disk were memoized before the round
                // wrote its own (the plain id, pinned): with substitution on the
                // playing copy is "#alt", so the stale list held only that one and
                // the head read as 0 bytes — "came up short" over a complete download,
                // and no measurement ever started. Asked afresh now.
                invalidateRenditionKeys(videoId)
                val held = alignmentPrefixBytes(videoId)
                if (held < MIN_ALIGNMENT_PREFIX_BYTES) {
                    if (held <= before) stalled.add(videoId) else stalled.remove(videoId)
                    // The one line that says *this* fetch failed, as opposed to
                    // the read-ahead chatter around it: every cause below
                    // already logs its own message at debug, but nothing said
                    // where a round ended or what it had to work with.
                    TrackLog.w(
                        tag,
                        "analysis head for $videoId came up short: " +
                            "held=$held want=$want total=$total " +
                            "(cause in the read-ahead lines around this one)",
                        videoId,
                    )
                } else {
                    stalled.remove(videoId)
                }
            } finally {
                inFlight.remove(videoId)
                signalWaiters(videoId)
                // A round stopped for playback left its claim for the reader's
                // release event. If that release already came while this job
                // was unwinding, retry here so the claim does not become one
                // that can never start a round.
                if (analysisHeadHeldClaimMayRetry(
                        heldForReader = videoId in heldElsewhere,
                        anotherWriterHas = anotherWriterHas(videoId),
                        inFlight = videoId in inFlight,
                    )
                ) {
                    heldElsewhere.remove(videoId)
                    analysisHeads.remove(videoId)
                    deferredLogged.remove(videoId)
                    requestAnalysisHead(Uri.parse(AutomixAnalysisSource.opusUri(videoId)))
                }
            }
        }
    }

    /** A reader just let go; retry a held claim only when no other writer has it. */
    fun retryAnalysisHeadIfReaderReleased(videoId: String) {
        // The cancelled round may still be unwinding. Its own completion
        // retries once the in-flight set is clear, so the claim is not lost to
        // a release that arrives during teardown.
        if (!analysisHeadHeldClaimMayRetry(
                heldForReader = videoId in heldElsewhere,
                anotherWriterHas = anotherWriterHas(videoId),
                inFlight = videoId in inFlight,
            )
        ) {
            return
        }
        heldElsewhere.remove(videoId)
        analysisHeads.remove(videoId)
        deferredLogged.remove(videoId)
        requestAnalysisHead(Uri.parse(AutomixAnalysisSource.opusUri(videoId)))
    }

    private fun clearPartialHead(videoId: String, want: Long): AnalysisHeadClear {
        val cache = cache() ?: return AnalysisHeadClear.Proceed
        val held = cache.getCachedBytes(videoId, 0, want)
        if (held <= 0L) return AnalysisHeadClear.Proceed
        if (held >= want) return AnalysisHeadClear.AlreadyComplete
        val removed = runCatching { cache.removeResource(videoId) }
            .onSuccess {
                forgetPurgeCandidate(videoId)
                TrackLog.d(tag, "cleared partial analysis head for $videoId ($held of $want)", about = videoId)
            }
            .onFailure {
                TrackLog.d(tag, "partial head for $videoId is in use: ${it.message}", about = videoId)
            }
            .isSuccess
        return analysisHeadClearOutcome(held = held, want = want, removalSucceeded = removed)
    }

    companion object {
        /** Enough of a YouTube Opus head to decode the opening window the aligner measures. */
        const val MIN_ALIGNMENT_PREFIX_BYTES = 800L * 1024

        private const val MAX_ALIGNMENT_HEAD_ROUNDS = 3

        private const val HEAD_ROUND_SETTLE_MS = 500L
    }
}
