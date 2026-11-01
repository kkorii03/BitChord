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
import com.music.bitchord.data.sources.SourceResolver.SearchOutcome
import com.music.bitchord.data.sources.SourceResolver.SearchReport
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
import kotlin.math.abs

internal object KoSourceResolverCore {

    private const val TAG = "BitChord"
    private const val MATCH_CANDIDATES = 15
    private const val STREAM_ATTEMPTS = 3
    private const val UPGRADE_DRIFT_SEC = 2
    private const val SAME_RECORDING_SEC = 3
    private const val SEARCH_MEMO_TTL_MS = 10 * 60 * 1000L
    private const val SEARCH_MEMO_MAX = 256

    internal suspend fun upgradeFor(
        target: TrackMatcher.Target,
        playing: StreamFormat? = null,
        servedBy: String? = null,
        excludeSources: Set<String> = emptySet(),
        excludeHosts: Set<String> = emptySet(),
        allowSameRendition: Boolean = false,
        manual: Boolean = false,
        report: SearchReport? = null,
        manualSession: Any? = null,
        collect: MutableList<Pair<MusicSource, SourceStream>>? = null,
        verify: (suspend (MusicSource, SourceStream) -> Boolean)? = null,
    ): SourceStream? {
        if (target.title.isBlank() || target.durationSec == null || target.isVideo) return null
        val request = SourceResolver.requestForNow()
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val worth: suspend (MusicSource, SourceStream) -> Boolean = { candidate, stream ->
            val acceptable = SourceResolver.worthSwapping(stream.format, playing) ||
                (allowSameRendition && HqQuality.isSameOrBetter(stream.format, playing))
            if (!acceptable) {
                TrackLog.d(
                    TAG,
                    "${candidate.displayName}'s ${stream.format.summary} isn't worth swapping " +
                        "'${target.title}' off ${playing?.summary ?: "an unmeasured stream"}",
                )
            }
            acceptable
        }
        val (source, chosen) = bestAcross(
            rankedAbove(youtube.configId, active, target).filterNot {
                it.configId == servedBy || it.configId in excludeSources
            },
            target,
            request,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            strictOriginal = true,
            allowArtistUnverified = true,
            excludeHosts = excludeHosts,
            manual = manual,
            manualSession = manualSession,
            report = report,
            collect = collect,
            collectGate = worth,
        ) { candidate, stream ->
            worth(candidate, stream) && (verify?.invoke(candidate, stream) ?: true)
        } ?: return null
        TrackLog.d(TAG, "upgrade found: '${target.title}' at ${chosen.format.summary} from ${source.displayName}")
        return chosen
    }

    internal suspend fun forDownload(
        target: TrackMatcher.Target,
        request: StreamRequest = SourceResolver.requestForDownload(),
        excludeSources: Set<String> = emptySet(),
    ): SourceStream? = coroutineScope {
        if (target.title.isBlank()) return@coroutineScope null
        val wantsLossless = when (request) {
            is StreamRequest.Lossless -> true
            is StreamRequest.Best -> false
            is StreamRequest.Capped -> return@coroutineScope null
        }
        val active = SourceRegistry.activeForDownload()
            .filterNot { it.configId in excludeSources }
        val youtubeId = active.firstOrNull { it.kind == SourceKind.YOUTUBE }?.configId
        val ranked = rankedAbove(youtubeId.orEmpty(), active, target)
        val strictLength = target.durationSec != null

        val bitExact = if (wantsLossless) ranked.filter { it.kind.canServeLossless } else emptyList()
        val elsewhere = ranked - bitExact.toSet()

        val elsewhereBest = async {
            bestAcross(
                elsewhere,
                target,
                StreamRequest.Best,
                waitForAll = true,
                strictLength = strictLength,
                strictOriginal = true,
                sourceTimeoutMs = DOWNLOAD_SOURCE_TIMEOUT_MS,
            ) { source, stream ->
                val ok = wantsLossless || stream.format.isLossless != true
                if (!ok) {
                    TrackLog.d(
                        TAG,
                        "${source.displayName} offered ${stream.format.summary}; " +
                            "more than this download asked for",
                    )
                }
                ok
            }
        }

        var best: Pair<MusicSource, SourceStream>? = null
        for (source in bitExact) {
            val stream = matchAndStreamWithin(
                source,
                target,
                request,
                waitForAll = true,
                strictLength = strictLength,
                strictOriginal = true,
                baseTimeoutMs = DOWNLOAD_SOURCE_TIMEOUT_MS,
            )?.stream ?: continue
            if (HqQuality.isLosslessTier(stream.format) || stream.format.isDolbyAtmos) {
                TrackLog.d(
                    TAG,
                    "download: '${target.title}' from ${source.displayName} at ${stream.format.summary}",
                )
                elsewhereBest.cancel()
                return@coroutineScope stream
            }
            TrackLog.d(
                TAG,
                "${source.displayName} offered ${stream.format.summary} to download; not lossless or Dolby",
            )
            if (isBetter(stream.format, best?.second?.format)) best = source to stream
        }

        elsewhereBest.await()?.let { (source, stream) ->
            if (isBetter(stream.format, best?.second?.format)) best = source to stream
        }
        val (winner, chosen) = best ?: return@coroutineScope null
        if (!SourceResolver.beatsYouTubeAac(chosen.format)) {
            TrackLog.d(
                TAG,
                "best offered for '${target.title}' is ${winner.displayName}'s " +
                    "${chosen.format.summary}; taking YouTube's AAC over that",
            )
            return@coroutineScope null
        }
        TrackLog.d(
            TAG,
            "download: '${target.title}' from ${winner.displayName} at ${chosen.format.summary}, " +
                "over YouTube's AAC",
        )
        chosen
    }

    internal suspend fun bestAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        strictOriginal: Boolean = false,
        allowArtistUnverified: Boolean = false,
        excludeHosts: Set<String> = emptySet(),
        sourceTimeoutMs: Long = SOURCE_LOOKUP_TIMEOUT_MS,
        manual: Boolean = false,
        report: SearchReport? = null,
        manualSession: Any? = null,
        collect: MutableList<Pair<MusicSource, SourceStream>>? = null,
        collectGate: (suspend (MusicSource, SourceStream) -> Boolean)? = null,
        accept: suspend (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): Pair<MusicSource, SourceStream>? = coroutineScope {
        val lookupTimeoutMs = SourceResolver.sourceLookupTimeoutMs(manual, sourceTimeoutMs)
        val usable = sources.filterNot { source ->
            val resting = SourceResolver.SourceCooldown.isResting(source.configId)
            val unmeasurable = resting && SourceResolver.SourceCooldown.isUnmeasurable(source.configId)
            val alreadyProbed = resting && manual && (
                unmeasurable || (manualSession != null &&
                    !KoSourceSearchMemo.manualRestingProbes.add(
                        KoSourceSearchMemo.manualProbeKey(manualSession, source.configId, target),
                    ))
                )
            if (alreadyProbed) {
                val skipped = SearchOutcome(verdict = "resting", conclusive = false)
                report?.record(skipped, source.displayName)
                TrackLog.d(TAG, hqSearchLine(target, source, skipped))
                true
            } else if (resting && !manual) {
                TrackLog.d(
                    TAG,
                    "${source.displayName} is resting after repeated failures; skipping for this track",
                )
                val skipped = SearchOutcome(verdict = "resting", conclusive = false)
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
                        val timedOut = SearchOutcome(verdict = "timeout", conclusive = false)
                        TrackLog.d(TAG, hqSearchLine(target, source, timedOut))
                        source to timedOut
                    } else {
                        if (answer.stream != null) SourceResolver.SourceCooldown.succeeded(source.configId)
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
                val readyDeferred = listOf(first) + running.filter { it !== first && it.isCompleted }
                running -= readyDeferred.toSet()
                val arrived = readyDeferred.map { it.await() }
                arrived.forEach { (source, outcome) -> report?.record(outcome, source.displayName) }
                val ready = if (waitForAll) {
                    arrived.sortedBy {
                        HqQuality.tierOf(it.second.stream?.format).rank
                    }
                } else {
                    arrived
                }
                val candidateOrder = HqVerifyPolicy.candidateOrder(
                    ready.map { HqHostTracker.hostOf(it.second.stream?.url) },
                ) { HqHostTracker.isResting(it) }
                for (index in candidateOrder) {
                    val (source, outcome) = ready[index]
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
                        if (host != null && HqHostTracker.isResting(host)) {
                            TrackLog.d(TAG, "${source.displayName}'s $host is resting; skipping for this track")
                            continue
                        }
                        val current = best
                        val earlierSource = current != null &&
                            (order[source.configId] ?: Int.MAX_VALUE) <
                            (order[current.first.configId] ?: Int.MAX_VALUE)
                        val candidateRank = offerRank(verdict, stream)
                        val currentRank = current?.let { offerRank(bestVerdict ?: "match", it.second) }
                            ?: Int.MAX_VALUE
                        val couldWin = HqQuality.couldWin(stream.format, current?.second?.format, earlierSource)
                        val hostPreferred = current != null && !couldWin &&
                            !HqQuality.isBetter(current.second.format, stream.format) &&
                            HqHostTracker.penalty(host) <
                            HqHostTracker.penalty(HqHostTracker.hostOf(current.second.url))
                        val beats = when {
                            current == null -> true
                            candidateRank != currentRank -> candidateRank < currentRank
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
        var last = SearchOutcome()
        for (query in TrackMatcher.queries(target)) {
            val key = KoSourceSearchMemo.SearchKey(
                sourceId = source.configId,
                target = KoSourceSearchMemo.targetKey(target),
                request = KoSourceSearchMemo.requestKey(request),
                waitForAll = waitForAll,
                query = query,
            )
            val now = System.currentTimeMillis()
            val mark = SourceResolver.SourceCooldown.failureMark(source.configId)
            val authMark = SourceResolver.SourceCooldown.authMark(source.configId)
            var authRejected = false
            val cached = if (manual) null else {
                KoSourceSearchMemo.searchAnswers[key]
                    ?.takeIf { now - it.at < SEARCH_MEMO_TTL_MS }?.rows
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
                        request = request,
                    )
                } ?: return SearchOutcome(
                    verdict = if (SourceResolver.SourceCooldown.authMark(source.configId) != authMark) "auth" else "error",
                    conclusive = false,
                ).also {
                    TrackLog.d(TAG, hqSearchLine(target, source, it))
                }
                authRejected = SourceResolver.SourceCooldown.authMark(source.configId) != authMark
                conclusive = SourceResolver.SourceCooldown.failureMark(source.configId) == mark
                if (conclusive) {
                    if (KoSourceSearchMemo.searchAnswers.size >= SEARCH_MEMO_MAX) {
                        KoSourceSearchMemo.searchAnswers.clear()
                    }
                    KoSourceSearchMemo.searchAnswers[key] =
                        KoSourceSearchMemo.SearchAnswer(rows, System.currentTimeMillis())
                }
                rows
            }
            val ranked = TrackMatcher.ranked(candidates, target)
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
                            abs((target.durationSec ?: 0) - got)
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
                    last = SearchOutcome(
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
                last = SearchOutcome(
                    results = candidates.size,
                    best = best,
                    verdict = verdict,
                    conclusive = conclusive,
                )
                if (!conclusive) {
                    TrackLog.d(TAG, hqSearchLine(target, source, last))
                    return last
                }
                continue
            }
            val verifiedMatches = matches.filterNot { it in artistUnverified }
            val unverifiedMatches = matches.filter { it in artistUnverified }
            val verifiedOpened = if (verifiedMatches.isEmpty()) {
                null
            } else {
                streamBest(source, verifiedMatches, target, request)
            }
            val localisedIsAlternate =
                verifiedOpened != null && !verifiedOpened.second.belowRequest
            val localisedOpened = if (
                unverifiedMatches.isEmpty() ||
                SourceResolver.SourceCooldown.authMark(source.configId) != authMark
            ) {
                null
            } else {
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
            if (SourceResolver.SourceCooldown.authMark(source.configId) != authMark) {
                authRejected = true
            }
            if (SourceResolver.SourceCooldown.failureMark(source.configId) != mark) {
                conclusive = false
            }
            val chosen = opened?.first
            last = SearchOutcome(
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
        val wantsAtmos = atmosWanted()
        if (wantsAtmos) {
            val immersiveFirst = eligible.sortedByDescending { it.sourceQuality == ModuleSource.DOLBY }
            if (immersiveFirst.firstOrNull()?.sourceQuality == ModuleSource.DOLBY) return immersiveFirst
        }
        fun tierOf(song: Song): HqTier =
            if (!wantsAtmos && song.sourceQuality == ModuleSource.DOLBY) {
                HqTier.NORMAL
            } else {
                HqQuality.tierOfDeclared(song.sourceQuality, song.sourceHiRes)
            }
        return eligible.sortedBy { tierOf(it).rank }
    }

    private fun atmosWanted(): Boolean =
        DeviceCodecs.playsDolbyAtmos && AppSettings.dolbyAtmos.value

    internal suspend fun streamBest(
        source: MusicSource,
        matches: List<Song>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        preserveOrder: Boolean = false,
        countFailure: Boolean = true,
    ): Pair<Song, SourceStream>? {
        val wantsLossless = request is StreamRequest.Lossless
        val ordered = if (preserveOrder) matches else preferred(matches, target, wantsLossless)
        var settleFor: Pair<Song, SourceStream>? = null
        val authMark = SourceResolver.SourceCooldown.authMark(source.configId)
        for (match in ordered.take(STREAM_ATTEMPTS)) {
            val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            val opened = attempt(source, countFailure) { source.stream(trackId, request) }
            if (SourceResolver.SourceCooldown.authMark(source.configId) != authMark) return settleFor
            if (opened == null) continue
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
            val lower = match to stream.copy(belowRequest = true)
            if (settleFor == null || isBetter(lower.second.format, settleFor.second.format)) {
                settleFor = lower
            }
        }
        return settleFor
    }

    internal fun isBetter(candidate: StreamFormat, current: StreamFormat?): Boolean =
        HqQuality.isBetter(candidate, current)

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
        if (countFailure && source.kind != SourceKind.YOUTUBE) {
            if (source.kind == SourceKind.TELEGRAM && e.isTimeoutFailure()) {
            } else if (e.isNetworkFailure()) {
                SourceResolver.SourceCooldown.networkFailed(
                    source.configId,
                    online = !SourceResolver.offlineCheck(),
                )
            } else {
                SourceResolver.SourceCooldown.failed(source.configId)
            }
        }
        TrackLog.w(TAG, "${source.displayName} failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun rankedAbove(
        configId: String,
        active: List<MusicSource>,
        target: TrackMatcher.Target? = null,
    ): List<MusicSource> =
        active.indexOfFirst { it.configId == configId }
            .let { if (it < 0) active.size else it }
            .let { active.take(it) }
            .let { automaticUpgradeCandidates(it, target) }
}
