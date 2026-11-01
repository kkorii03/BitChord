package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.HqVerifyPolicy
import com.music.bitchord.data.sources.ModuleSource
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

private typealias SearchKey = KoSourceSearchMemo.SearchKey
private typealias SearchAnswer = KoSourceSearchMemo.SearchAnswer
private typealias SearchOutcome = SourceResolver.SearchOutcome

private const val MATCH_CANDIDATES = 15
private const val STREAM_ATTEMPTS = 3
private const val UPGRADE_DRIFT_SEC = 2
private const val SAME_RECORDING_SEC = 3
private const val SEARCH_MEMO_TTL_MS = 10 * 60 * 1000L
private const val SEARCH_MEMO_MAX = 256

private fun isBetter(candidate: StreamFormat, current: StreamFormat?): Boolean =
    HqQuality.isBetter(candidate, current)

    internal suspend fun bestAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        strictOriginal: Boolean = false,
        /**
         * Keep a row whose artist is localised into another script as an
         * `artist-unverified` candidate. Only the upgrade path sets this: the
         * caller then has `hq-verify` accept it before it may play.
         */
        allowArtistUnverified: Boolean = false,
        excludeHosts: Set<String> = emptySet(),
        sourceTimeoutMs: Long = SOURCE_LOOKUP_TIMEOUT_MS,
        /**
         * A request the listener made by hand. It ignores the per-track
         * search cache and asks a resting source once more: the answer being
         * overruled is "nobody has it", which the listener has evidence
         * against. The automatic path keeps the rest rule.
         */
        manual: Boolean = false,
        /** Collects each source's verdict so a caller can tell a real none from an interrupted one. */
        report: SourceResolver.SearchReport? = null,
        /**
         * One manual request's identity. A resting source is probed once per
         * session, not once per candidate walk inside it — the walk is the
         * same request, and re-asking a source that already said it is
         * resting only spends another timeout.
         */
        manualSession: Any? = null,
        /**
         * Every offer that passes [accept], including ones that did not beat
         * the current best. The upgrade path needs the fallbacks behind a
         * candidate that later fails its audition, and cannot afford to search
         * for them again.
         */
        collect: MutableList<Pair<MusicSource, SourceStream>>? = null,
        /**
         * The cheap gate for [collect]'s fallbacks. They are kept in case the
         * best candidate later fails its audition, but are not proved now:
         * verifying every lossless fallback before returning the first would
         * cost more than the re-search this list exists to avoid.
         */
        collectGate: (suspend (MusicSource, SourceStream) -> Boolean)? = null,
        accept: suspend (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): Pair<MusicSource, SourceStream>? = coroutineScope {
        val lookupTimeoutMs = SourceResolver.sourceLookupTimeoutMs(manual, sourceTimeoutMs)
        val usable = sources.filterNot { source ->
            val resting = SourceResolver.SourceCooldown.isResting(source.configId)
            // A source parked for unmeasurable candidates is not probed even by
            // hand: its search works, its download is what keeps being refused.
            val unmeasurable = resting && SourceResolver.SourceCooldown.isUnmeasurable(source.configId)
            val alreadyProbed = resting && manual && (
                unmeasurable || (manualSession != null &&
                    !KoSourceSearchMemo.manualRestingProbes.add(KoSourceSearchMemo.manualProbeKey(manualSession, source.configId, target)))
                )
            if (alreadyProbed) {
                val skipped = SourceResolver.SearchOutcome(verdict = "resting", conclusive = false)
                report?.record(skipped, source.displayName)
                TrackLog.d(TAG, hqSearchLine(target, source, skipped))
                true
            } else if (resting && !manual) {
                TrackLog.d(
                    TAG,
                    "${source.displayName} is resting after repeated failures; skipping for this track",
                )
                val skipped = SourceResolver.SearchOutcome(verdict = "resting", conclusive = false)
                report?.record(skipped, source.displayName)
                TrackLog.d(TAG, hqSearchLine(target, source, skipped))
                true
            } else {
                if (resting) {
                    TrackLog.d(
                        TAG,
                        "${source.displayName} is resting; asking it once because this upgrade was requested by hand",
                    )
                }
                false
            }
        }
        if (usable.isEmpty()) return@coroutineScope null
        val order = usable.mapIndexed { index, source -> source.configId to index }.toMap()
        val running: MutableList<Deferred<Pair<MusicSource, SearchOutcome>>> = usable
            .map { source ->
                async {
                    // Telegram's client is deliberately cold between requests
                    // (stage one closes it when no lease holds it), so the
                    // four-second catalogue window can expire before TDLib has
                    // even opened its session. This is a per-source timeout,
                    // not a longer wait for the other sources.
                    // A resting source gets one short probe, in parallel with
                    // everyone else. It was parked for failing; waiting the
                    // full manual window for it is what made one hand-made
                    // upgrade spend twelve seconds before its first candidate.
                    val sourceTimeout = if (manual && SourceResolver.SourceCooldown.isResting(source.configId)) {
                        minOf(lookupTimeoutMs, MANUAL_RESTING_PROBE_TIMEOUT_MS)
                    } else {
                        sourceLookupTimeoutMsFor(source, lookupTimeoutMs)
                    }
                    val answer = withTimeoutOrNull(sourceTimeout) {
                        matchAndStream(
                            source,
                            target,
                            request,
                            waitForAll,
                            strictLength,
                            requireSharedArtist,
                            strictOriginal,
                            manual,
                            allowArtistUnverified,
                        )
                    }
                    if (answer == null) {
                        // A timeout while the device is offline (or behind a
                        // captive portal) is the connection, not the source.
                        // Counting it here parked every source for 30 minutes
                        // after a dorm Wi-Fi drop, long after the link was
                        // back.
                        SourceResolver.SourceCooldown.timedOut(
                            source.configId,
                            source.kind,
                            online = !SourceResolver.offlineCheck(),
                        )
                        TrackLog.d(
                            TAG,
                            "${source.displayName} timed out after ${sourceTimeout}ms; " +
                                "skipping for this track",
                        )
                        val timedOut = SourceResolver.SearchOutcome(verdict = "timeout", conclusive = false)
                        TrackLog.d(TAG, hqSearchLine(target, source, timedOut))
                        source to timedOut
                    } else {
                        // A stream is proof the source is healthy. A miss is
                        // neither proof of health nor a failure: it answered.
                        if (answer.stream != null) SourceResolver.SourceCooldown.succeeded(source.configId)
                        // A hand-made request is also the one chance a resting
                        // source gets to prove it is back. A complete answer is
                        // enough evidence, even when the catalogue held nothing.
                        if (manual && answer.conclusive) SourceResolver.SourceCooldown.succeeded(source.configId)
                        source to answer
                    }
                }
            }
            .toMutableList()
        var best: Pair<MusicSource, SourceStream>? = null
        var bestVerdict: String? = null
        try {
            while (running.isNotEmpty()) {
                val first = select {
                    running.forEach { candidate -> candidate.onAwait { candidate } }
                }
                // Anything that crossed the line while that one was being waited
                // on is already sitting there. Folding those in costs no time at
                // all and is what lets rank break a tie between two sources that
                // both answered quickly.
                val readyDeferred = listOf(first) + running.filter { it !== first && it.isCompleted }
                running -= readyDeferred.toSet()
                val arrived = readyDeferred.map { it.await() }
                arrived.forEach { (source, outcome) -> report?.record(outcome, source.displayName) }
                // On a path that is already waiting for everyone (an upgrade,
                // read-ahead or download), prove the best rendition first.
                // A Qobuz 24-bit head measured ~14s; spending that after a
                // 320kbps copy has been decoded wastes the comparison the
                // listener is actually waiting for.
                val ready = if (waitForAll) {
                    arrived.sortedBy {
                        HqQuality.tierOf(it.second.stream?.format).rank
                    }
                } else {
                    arrived
                }
                // A song with another addon answer must try the healthy host
                // first; a resting one is still kept as the last resort rather
                // than dropped. The loop below also refuses a resting host, so
                // this order only decides which healthy answer is seen first.
                val candidateOrder = HqVerifyPolicy.candidateOrder(
                    ready.map { HqHostTracker.hostOf(it.second.stream?.url) },
                ) { HqHostTracker.isResting(it) }
                for (index in candidateOrder) {
                    val (source, outcome) = ready[index]
                    // The row the source actually chose comes first. Its
                    // alternate is the other copy of the same recording — the
                    // localised credit whose artist could not be compared —
                    // and is only consulted when the first choice fails this
                    // walk's own gate below.
                    val offers = buildList {
                        outcome.stream?.let { add(Triple(it, outcome.verdict, outcome.best)) }
                        outcome.alternate?.let {
                            add(
                                Triple(
                                    it,
                                    outcome.alternateVerdict ?: "artist-unverified",
                                    outcome.alternateBest,
                                ),
                            )
                        }
                    }
                    for ((stream, verdict, _) in offers) {
                        val host = HqHostTracker.hostOf(stream.url)
                        if (host != null && host in excludeHosts) continue
                        // A host that just rate-limited us is not asked again for
                        // this track. SourceResolver.SourceCooldown still handles the source-level
                        // failure; this is the narrower server-level rest.
                        if (host != null && HqHostTracker.isResting(host)) {
                            TrackLog.d(TAG, "${source.displayName}'s $host is resting; skipping for this track")
                            continue
                        }
                        val current = best
                        val earlierSource = current != null &&
                            (order[source.configId] ?: Int.MAX_VALUE) <
                            (order[current.first.configId] ?: Int.MAX_VALUE)
                        // A normally credited match always outranks a localised
                        // one. The localised row is a candidate for the sound
                        // comparison, not evidence that the recording is this
                        // source's, so it must not discard a match that hq-verify
                        // may well prefer. A verified row that opened only as a
                        // below-request settle-for is ranked after the localised
                        // row: the fallback exists precisely because that copy
                        // was not what the request asked for.
                        val candidateRank = offerRank(verdict, stream)
                        val currentRank = current?.let { offerRank(bestVerdict ?: "match", it.second) }
                            ?: Int.MAX_VALUE
                        // Only an answer that could actually win needs the expensive
                        // gate. That gate is the ninety-second sound comparison for
                        // the read-ahead, and running it for a candidate already
                        // beaten on rendition cost a decode *and* added it to the
                        // tail of the race, after even the slowest search had
                        // finished — see [HqQuality.couldWin].
                        val couldWin = HqQuality.couldWin(stream.format, current?.second?.format, earlierSource)
                        // A host with a recent 429, a poor success rate or very
                        // slow transfers sorts behind a healthy one at the same
                        // rendition. The format still decides first.
                        val hostPreferred = current != null && !couldWin &&
                            !HqQuality.isBetter(current.second.format, stream.format) &&
                            HqHostTracker.penalty(host) <
                            HqHostTracker.penalty(HqHostTracker.hostOf(current.second.url))
                        val beats = when {
                            current == null -> true
                            candidateRank != currentRank -> candidateRank < currentRank
                            // Among localised rows the smallest runtime
                            // difference is the required choice; quality only
                            // breaks an equal drift.
                            isArtistUnverifiedRank(candidateRank) -> {
                                val candidateDrift = runtimeDrift(stream, target)
                                val currentDrift = runtimeDrift(current.second, target)
                                if (candidateDrift != currentDrift) {
                                    candidateDrift < currentDrift
                                } else {
                                    couldWin || hostPreferred
                                }
                            }
                            else -> couldWin || hostPreferred
                        }
                        if (!beats) {
                            // Still offered as a fallback when the caller asked
                            // for a list. A better candidate can fail its own
                            // proof, and the next copy must already be in hand
                            // rather than costing another search.
                            if (collect != null &&
                                (collectGate?.invoke(source, stream) ?: accept(source, stream))
                            ) {
                                collect += source to stream
                            }
                            continue
                        }
                        if (!accept(source, stream)) continue
                        collect?.let { if (it.none { pair -> pair.second.url == stream.url }) it += source to stream }
                        best = source to stream
                        bestVerdict = verdict
                    }
                }
                // Playback needs the first usable answer so sound can start.
                // An upgrade is different: it runs while audio is already
                // playing, and its caller explicitly requested every source's
                // patient result. Let every source finish so a fast lower tier
                // cannot cancel a later, better rendition.
                if (best != null && !waitForAll) break
            }
        } finally {
            running.forEach { it.cancel() }
        }
        best
    }

    internal suspend fun matchAndStream(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        strictOriginal: Boolean = false,
        manual: Boolean = false,
        allowArtistUnverified: Boolean = false,
    ): SearchOutcome {
        var last = SourceResolver.SearchOutcome()
        for (query in TrackMatcher.queries(target)) {
            val key = SearchKey(
                sourceId = source.configId,
                target = KoSourceSearchMemo.targetKey(target),
                request = KoSourceSearchMemo.requestKey(request),
                waitForAll = waitForAll,
                query = query,
            )
            val now = System.currentTimeMillis()
            // Taken before either the search or the stream calls below, so the
            // same comparison covers both. A stream open that 500s or times out
            // is a failure of the whole answer even when the search itself
            // looked healthy — see [SourceResolver.SourceCooldown.failureMark].
            val mark = SourceResolver.SourceCooldown.failureMark(source.configId)
            val authMark = SourceResolver.SourceCooldown.authMark(source.configId)
            var authRejected = false
            // A hand-made request is evidence that the cached "none" was wrong.
            // Leave it unread, so the source is asked again; the fresh answer
            // then replaces the memo below.
            val cached = if (manual) null else {
                KoSourceSearchMemo.searchAnswers[key]?.takeIf { now - it.at < SEARCH_MEMO_TTL_MS }?.rows
            }
            var conclusive = true
            val candidates = if (cached != null) {
                cached
            } else {
                val rows = attempt(source) {
                    source.search(
                        query,
                        limit = MATCH_CANDIDATES,
                        waitForAll = waitForAll,
                        // The search and the stream are one question here: a
                        // catalogue that files its rows by tier should describe
                        // them at the tier this is about to ask for.
                        request = request,
                    )
                } ?: return SourceResolver.SearchOutcome(
                    verdict = if (SourceResolver.SourceCooldown.authMark(source.configId) != authMark) "auth" else "error",
                    conclusive = false,
                ).also {
                    TrackLog.d(TAG, hqSearchLine(target, source, it))
                }
                // Some sources swallow a transport or credential fault and
                // return an empty list. An answer produced while this source
                // reported a failure is not a confirmed miss, and must not be
                // memoised — see [SourceResolver.SourceCooldown.failureMark].
                authRejected = SourceResolver.SourceCooldown.authMark(source.configId) != authMark
                conclusive = SourceResolver.SourceCooldown.failureMark(source.configId) == mark
                if (conclusive) {
                    if (KoSourceSearchMemo.searchAnswers.size >= SEARCH_MEMO_MAX) KoSourceSearchMemo.searchAnswers.clear()
                    KoSourceSearchMemo.searchAnswers[key] = SearchAnswer(rows, System.currentTimeMillis())
                }
                rows
            }
            val ranked = TrackMatcher.ranked(candidates, target)
            // Rows whose only disagreement is an artist credit in another
            // script are kept apart so the ordinary match order still wins.
            // Judged over every result, not just the top row: the localised
            // copy is often not what the search backend puts first.
            val artistUnverified = mutableSetOf<Song>()
            var matches: List<Song> = if (strictOriginal) {
                val verified = ranked.filter { TrackMatcher.isOriginalRecording(it, target) }
                if (allowArtistUnverified) {
                    val localised = (ranked + candidates)
                        .distinct()
                        .filterNot { it in verified }
                        .filter {
                            TrackMatcher.isArtistUnverifiedOriginal(
                                it,
                                target,
                                TrackMatcher.ARTIST_UNVERIFIED_DRIFT_SEC,
                            )
                        }
                        .sortedBy { candidate ->
                            val got = TrackMatcher.secondsOf(candidate.durationText)
                                ?: Int.MAX_VALUE
                            kotlin.math.abs((target.durationSec ?: 0) - got)
                        }
                    artistUnverified += localised
                    verified + localised
                } else {
                    verified
                }
            } else {
                ranked
            }
            if (requireSharedArtist) {
                matches = matches.filter {
                    it in artistUnverified ||
                        TrackMatcher.sharesArtist(target.artist, it.artist)
                }
            }
            // JioSaavn can return different audio under the same title and
            // artist on different releases. With no album on the requested
            // track there is no honest way to choose between those rows;
            // duration is not enough when the wrong recording is only a
            // second away. Treat it as this source missing and retain the
            // known-correct fallback.
            // Only normally credited rows take part in the JioSaavn release
            // collision rule. A localised-artist row is already waiting for
            // the sound comparison, and letting its album veto a verified copy
            // would reinstate the failure this fallback exists to remove.
            val verifiedForConflict = matches.filterNot { it in artistUnverified }
            if (source.kind == SourceKind.JIOSAAVN &&
                TrackMatcher.hasConflictingAlbums(verifiedForConflict, target)
            ) {
                val canonical = TrackMatcher.uniquelyMostCreditedCloseMatch(verifiedForConflict, target)
                if (canonical == null) {
                    TrackLog.w(
                        TAG,
                        "${source.displayName} returned conflicting albums for '${target.title}'; refusing to guess",
                    )
                    last = SourceResolver.SearchOutcome(
                        results = candidates.size,
                        best = matches.firstOrNull(),
                        verdict = "version",
                        conclusive = conclusive,
                    )
                    continue
                }
                TrackLog.d(
                    TAG,
                    "${source.displayName} resolved conflicting albums for '${target.title}' " +
                        "using the uniquely fullest credit: '${canonical.artist}'",
                )
                matches = listOf(canonical)
            }
            // The extra bar for standing in for one specific recording: the
            // replacement has to be the same *length*, to the second or so. A
            // title and an artist can agree across two different edits of a
            // song; a runtime that agrees this closely is one recording, and
            // nothing else is worth cutting a listener's audio for — or filing
            // on their device under the name of the track they asked for.
            if (strictLength) {
                matches = matches.filter {
                    val drift = if (it in artistUnverified) {
                        TrackMatcher.ARTIST_UNVERIFIED_DRIFT_SEC
                    } else {
                        UPGRADE_DRIFT_SEC
                    }
                    TrackMatcher.withinSeconds(it, target, drift)
                }
            }
            if (matches.isEmpty()) {
                val best = matches.firstOrNull() ?: ranked.firstOrNull()
                    ?: candidates.firstOrNull()
                val verdict = when {
                    // A source that failed while producing this answer said
                    // nothing about the catalogue, even when the rows it sent
                    // back were empty — see the `error` contract in
                    // [SearchOutcome]. A refused credential is its own verdict
                    // because the listener can fix it, unlike a server fault.
                    !conclusive -> if (authRejected) "auth" else "error"
                    candidates.isEmpty() -> "title"
                    best == null -> "title"
                    else -> TrackMatcher.rejectionReason(
                        best,
                        target,
                        strictOriginal = strictOriginal,
                        requireSharedArtist = requireSharedArtist,
                        strictLength = strictLength,
                        driftSec = UPGRADE_DRIFT_SEC,
                        allowArtistUnverified = allowArtistUnverified,
                    )
                }
                last = SourceResolver.SearchOutcome(
                    results = candidates.size,
                    best = best,
                    verdict = verdict,
                    conclusive = conclusive,
                )
                // A source that reported a fault will not answer the other
                // query either; asking it again only spends a request.
                if (!conclusive) {
                    TrackLog.d(TAG, hqSearchLine(target, source, last))
                    return last
                }
                continue
            }
            // A normal artist match always goes first, but only when it
            // actually opened what was asked for. A below-request settle-for
            // must not stop the localised row from being tried: that row may
            // be the lossless copy the request wanted, and hq-verify decides
            // whether it is the same recording. See [offerRank] for the order
            // the source race applies afterwards.
            val verifiedMatches = matches.filterNot { it in artistUnverified }
            val unverifiedMatches = matches.filter { it in artistUnverified }
            val verifiedOpened = if (verifiedMatches.isEmpty()) {
                null
            } else {
                streamBest(source, verifiedMatches, target, request)
            }
            // When the credited copy already serves the request, the localised
            // row is only an alternate for hq-verify, not something worth
            // opening now. It is still opened so a caller whose gate rejects
            // the credited copy has it in hand, but a failure here must not be
            // charged to the source: the verified copy is fine, and counting
            // it would mark this walk inconclusive and retry the track.
            val localisedIsAlternate =
                verifiedOpened != null && !verifiedOpened.second.belowRequest
            val localisedOpened = if (
                unverifiedMatches.isEmpty() ||
                // A refused credential already stopped the credited rows; the
                // localised ones would only be refused again.
                SourceResolver.SourceCooldown.authMark(source.configId) != authMark
            ) {
                null
            } else {
                // These are already ordered closest-runtime-first; do not let
                // [preferred]'s quality tiers reorder them. The closest cut is
                // the candidate hq-verify is asked about first.
                streamBest(
                    source,
                    unverifiedMatches,
                    target,
                    request,
                    preserveOrder = true,
                    countFailure = !localisedIsAlternate,
                )
            }
            var opened = verifiedOpened
            var alternate: Pair<Song, SourceStream>? = null
            var alternateVerdict: String? = null
            when {
                verifiedOpened != null && !verifiedOpened.second.belowRequest -> {
                    opened = verifiedOpened
                    if (localisedOpened != null) {
                        alternate = localisedOpened
                        alternateVerdict = "artist-unverified"
                    }
                }
                localisedOpened != null &&
                    (verifiedOpened == null ||
                        !localisedOpened.second.belowRequest ||
                        isBetter(localisedOpened.second.format, verifiedOpened.second.format)) -> {
                    opened = localisedOpened
                    if (verifiedOpened != null) {
                        alternate = verifiedOpened
                        alternateVerdict = "match"
                    }
                }
                verifiedOpened != null -> {
                    opened = verifiedOpened
                }
            }
            val stream = opened?.second
            // Every stream open can fail — a 500, a timeout, a host that went
            // away between the search and the open — and [attempt] counts each
            // one against the source. A search that looked healthy but could
            // not produce a stream is not a confirmed miss, so the caller must
            // be allowed to ask again once the source is back. That holds even
            // when a stream did open: [streamBest] falls back to a row below the
            // request when the better one failed (10-31 Flowers: HTTP 500), and
            // the copy it settled for is still played, just not taken as final.
            if (SourceResolver.SourceCooldown.authMark(source.configId) != authMark) {
                authRejected = true
            }
            if (SourceResolver.SourceCooldown.failureMark(source.configId) != mark) {
                conclusive = false
            }
            val chosen = opened?.first
            last = SourceResolver.SearchOutcome(
                stream = stream,
                results = candidates.size,
                best = chosen,
                verdict = when {
                    stream == null -> if (authRejected) "auth" else "error"
                    chosen in artistUnverified -> "artist-unverified"
                    else -> "match"
                },
                conclusive = conclusive,
                alternate = alternate?.second,
                alternateBest = alternate?.first,
                alternateVerdict = alternateVerdict,
            )
            TrackLog.d(TAG, hqSearchLine(target, source, last))
            return last
        }
        // One line per source per walk, whether it found the recording or not.
        TrackLog.d(TAG, hqSearchLine(target, source, last))
        return last
    }

    internal fun preferred(
        matches: List<Song>,
        target: TrackMatcher.Target,
        wantsLossless: Boolean,
    ): List<Song> {
        val sameLength = matches.filter { TrackMatcher.withinSeconds(it, target, SAME_RECORDING_SEC) }
        val eligible = sameLength.ifEmpty { matches }
        // The immersive mix goes first when the listener asked for immersive
        // audio and the device can decode it. This is not a quality rung and
        // is not competing with one: a catalogue may publish the Atmos mix as
        // its own row rather than as an alternate rendition of the stereo one,
        // in which case no `?atmos=` hint on the stereo row can ever reach it
        // — picking the row *is* the only way to hear it. Ordering is enough;
        // [streamBest] stops at the first row that answers, and an Atmos
        // answer on a device that cannot play it was already refused upstream
        // by [ModuleSource.unplayable].
        val wantsAtmos = atmosWanted()
        if (wantsAtmos) {
            val immersiveFirst = eligible.sortedByDescending { it.sourceQuality == ModuleSource.DOLBY }
            if (immersiveFirst.firstOrNull()?.sourceQuality == ModuleSource.DOLBY) return immersiveFirst
        }
        // An Atmos row the listener did not ask for is just a row, and not a
        // top one: the immersive rung is only ahead of lossless when the mix is
        // wanted. See [atmosWanted].
        fun tierOf(song: Song): HqTier =
            if (!wantsAtmos && song.sourceQuality == ModuleSource.DOLBY) {
                HqTier.NORMAL
            } else {
                HqQuality.tierOfDeclared(song.sourceQuality, song.sourceHiRes)
            }
        // Stable, so the confidence order [TrackMatcher.ranked] produced
        // survives inside each tier. A 320kbps row is a better place to ask
        // than a 128kbps one even when the request is merely "best".
        return eligible.sortedBy { tierOf(it).rank }
    }

    private fun atmosWanted(): Boolean =
        DeviceCodecs.playsDolbyAtmos && AppSettings.dolbyAtmos.value

    private suspend fun streamBest(
        source: MusicSource,
        matches: List<Song>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        /**
         * Keep the caller's order instead of [preferred]'s quality order. The
         * localised fallback is already sorted by runtime difference, which is
         * the choice the sound comparison is meant to see first.
         */
        preserveOrder: Boolean = false,
        /**
         * Whether a failed open counts against the source's health. False for
         * an alternate that is only being kept in case the primary is rejected:
         * an unused copy failing says nothing about the copy that opened.
         */
        countFailure: Boolean = true,
    ): Pair<Song, SourceStream>? {
        val wantsLossless = request is StreamRequest.Lossless
        val ordered = if (preserveOrder) matches else preferred(matches, target, wantsLossless)
        var settleFor: Pair<Song, SourceStream>? = null
        // A credential refusal is not a property of this row: every other row
        // will be refused the same way. Stop after the first refusal instead of
        // spending up to three `/stream` calls (and raising the source's
        // failure mark each time) against a source that just said no.
        val authMark = SourceResolver.SourceCooldown.authMark(source.configId)
        for (match in ordered.take(STREAM_ATTEMPTS)) {
            val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            val opened = attempt(source, countFailure) { source.stream(trackId, request) }
            if (SourceResolver.SourceCooldown.authMark(source.configId) != authMark) return settleFor
            if (opened == null) continue
            // The row this URL came from knows how long the recording is; the
            // URL itself doesn't. Carried along so a caller swapping this into
            // a track already playing can check it — see [SourceStream.durationSec].
            val stream = opened.copy(
                durationSec = TrackMatcher.secondsOf(match.durationText),
                sourceConfigId = source.configId,
                trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId,
                trackTitle = match.title,
                trackArtist = match.artist,
            )
            val served = stream.format
            if (!wantsLossless || HqQuality.isLosslessTier(served) || served.isDolbyAtmos || served.statesNothingLossy) {
                TrackLog.d(
                    TAG,
                    "${source.displayName} matched '${match.title}' by '${match.artist}' " +
                        "id='${match.videoId}' album='${match.albumName ?: "?"}' " +
                        "duration=${match.durationText ?: "?"} explicit=${match.isExplicit ?: "?"} " +
                        "→ ${served.summary}",
                )
                return match to stream
            }
            TrackLog.d(
                TAG,
                "${source.displayName} offered ${served.summary} for '${match.title}' " +
                    "by '${match.artist}' id='${match.videoId}' album='${match.albumName ?: "?"}' " +
                    "duration=${match.durationText ?: "?"} explicit=${match.isExplicit ?: "?"}; looking further",
            )
            // The floor is the *best* of what was refused, not the first of
            // it. These arrive in match order, which has nothing to do with
            // quality: a 320kbps AAC and a 128kbps MP3 are both rejections,
            // and which one the listener ends up on if nothing better exists
            // should not come down to which catalogue happened to be asked
            // first.
            val lower = match to stream.copy(belowRequest = true)
            if (settleFor == null || isBetter(lower.second.format, settleFor.second.format)) {
                settleFor = lower
            }
        }
        return settleFor
    }

    private val StreamFormat.statesNothingLossy: Boolean
        get() = isLossless == null && kbps == null

    internal suspend fun <T> attempt(
        source: MusicSource,
        countFailure: Boolean = true,
        block: suspend () -> T,
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A transport fault raises the failure mark just like an HTTP error:
        // an empty answer produced after it is not proof that the catalogue
        // lacks the track, so it must not be memoised as a confirmed miss. It
        // counts toward the rest only when the device is online — during an
        // outage every source fails at once and parking them would outlast it.
        // [countFailure] is off for an optional open whose result is not
        // needed; its failure says nothing about this track's answer.
        if (countFailure && source.kind != SourceKind.YOUTUBE) {
            if (source.kind == SourceKind.TELEGRAM && e.isTimeoutFailure()) {
                // A slow TDLib open/search is not the source saying "no"; it is
                // the source still working. Counting it was the rest bug.
            } else if (e.isNetworkFailure()) {
                SourceResolver.SourceCooldown.networkFailed(source.configId, online = !SourceResolver.offlineCheck())
            } else {
                SourceResolver.SourceCooldown.failed(source.configId)
            }
        }
        TrackLog.w(TAG, "${source.displayName} failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
