package com.music.bitchord.data.sources

import android.net.Uri
import android.util.Log
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.ko.DOWNLOAD_SOURCE_TIMEOUT_MS
import com.music.bitchord.ko.KoRefusedRows
import com.music.bitchord.ko.MANUAL_RESTING_PROBE_TIMEOUT_MS
import com.music.bitchord.ko.SOURCE_LOOKUP_TIMEOUT_MS
import com.music.bitchord.ko.hqSearchLine
import com.music.bitchord.ko.isArtistUnverifiedRank
import com.music.bitchord.ko.isNetworkFailure
import com.music.bitchord.ko.isTimeoutFailure
import com.music.bitchord.ko.offerRank
import com.music.bitchord.ko.runtimeDrift
import com.music.bitchord.ko.sourceLookupTimeoutMsFor
import com.music.bitchord.ko.unverifiedVerdict
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.StreamRefusalRecovery
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

private typealias SearchKey = com.music.bitchord.ko.KoSourceSearchMemo.SearchKey // [KO] search-memo
private typealias SearchAnswer = com.music.bitchord.ko.KoSourceSearchMemo.SearchAnswer // [KO] search-memo

/**
 * Turns a queued track into an openable stream, using whichever source can
 * best serve it.
 *
 * Two things happen here that don't happen in any single [MusicSource]:
 *
 *  1. **The quality question is answered once**, from the connection in hand
 *     and the user's ceiling for it — see [requestForNow], or
 *     [requestForDownload] for the one caller whose answer becomes a file
 *     rather than a stream. Sources are told what to serve; they don't each
 *     re-derive it.
 *
 *  2. **The order is applied.** A track is pinned to the source that produced
 *     it, but a pin is a starting point, not a cage: with lossless on, a
 *     higher-priority source that can serve the same recording bit-exact gets
 *     asked first, and a source that fails gets stepped over rather than
 *     failing the track.
 *
 * Whether another source *has* the same recording is [TrackMatcher]'s question,
 * not this one's. Everything here does with a candidate list is ask that, and
 * everything a source is asked for comes from the same place — so the library,
 * a playlist, radio, search and the home feed all substitute on identical
 * terms, whichever of them a track was queued from.
 */
object SourceResolver {

    private const val TAG = "BitChord"

    internal var offlineCheck: () -> Boolean // [KO] offline-check
        get() = com.music.bitchord.ko.KoSourceRuntime.offlineCheck
        set(value) { com.music.bitchord.ko.KoSourceRuntime.offlineCheck = value }

    internal val SourceCooldown get() = com.music.bitchord.ko.KoSourceCooldown // [KO] source-cooldown

    internal fun sourceLookupTimeoutMs(
        manual: Boolean,
        configured: Long = SOURCE_LOOKUP_TIMEOUT_MS,
    ): Long = com.music.bitchord.ko.sourceLookupTimeoutMs(manual, configured) // [KO] source-timeout

    internal suspend fun matchAndStreamForKo(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        strictOriginal: Boolean = false,
        manual: Boolean = false,
        allowArtistUnverified: Boolean = false,
    ): SearchOutcome = matchAndStream(
        source, target, request, waitForAll, strictLength,
        requireSharedArtist, strictOriginal, manual, allowArtistUnverified,
    ) // [KO] match-stream

    internal suspend fun <T> attemptForKo(
        source: MusicSource,
        block: suspend () -> T,
    ): T? = attempt(source, block = block) // [KO] source-attempt

    private val searchAnswers get() = com.music.bitchord.ko.KoSourceSearchMemo.searchAnswers // [KO] search-memo
    private val manualRestingProbes get() = com.music.bitchord.ko.KoSourceSearchMemo.manualRestingProbes // [KO] manual-probe
    private fun manualProbeKey(session: Any, sourceId: String, target: TrackMatcher.Target): String =
        com.music.bitchord.ko.KoSourceSearchMemo.manualProbeKey(session, sourceId, target) // [KO] manual-probe
    internal fun clearManualProbes(session: Any) =
        com.music.bitchord.ko.KoSourceSearchMemo.clearManualProbes(session) // [KO] manual-probe
    internal fun clearManualProbes() =
        com.music.bitchord.ko.KoSourceSearchMemo.clearManualProbes() // [KO] manual-probe

    /**
     * The per-source detail of one search, for the `hq-search` line.
     *
     * [verdict] is why a result was or was not usable:
     * `match`, `artist-unverified`, `title-unverified`, `duration`, `title`,
     * `artist`, `version`, `auth`, `error`, `timeout` or `resting`. [conclusive]
     * is false when the source could not give a complete answer — a failed
     * backend, a timeout, or being skipped while resting — which is what stops
     * that "none" from being remembered as final.
     *
     * [alternate] is the second-best row found for the same track, kept so a
     * caller whose first choice fails its own accept gate can still consider
     * an unverified copy. It is never adopted without that gate, and
     * [alternateVerdict] says which kind of row it is.
     */
    data class SearchOutcome(
        val stream: SourceStream? = null,
        val results: Int = 0,
        val best: Song? = null,
        val verdict: String = "title",
        val conclusive: Boolean = true,
        val alternate: SourceStream? = null,
        val alternateBest: Song? = null,
        val alternateVerdict: String? = null,
    )

    class SearchReport : com.music.bitchord.ko.KoSearchReport() // [KO] search-report

    internal fun clearSearchAnswers() =
        com.music.bitchord.ko.KoSourceSearchMemo.clearSearchAnswers() // [KO] search-memo

    /** Forgets one source's memoised searches without touching any other source. */
    internal fun clearSearchAnswersForSource(configId: String) =
        com.music.bitchord.ko.KoSourceSearchMemo.clearSearchAnswersForSource(configId) // [KO] search-memo

    internal fun reportSourceFailure(
        configId: String,
        network: Boolean = false,
        restUntilMs: Long = 0L,
    ) {
        if (network) SourceCooldown.networkFailed(configId, online = !offlineCheck())
        else SourceCooldown.failed(configId, restUntilMs)
    } // [KO] source-cooldown

    internal fun reportSourceAuthFailure(configId: String) {
        SourceCooldown.authFailed(configId)
    } // [KO] source-cooldown

    internal fun clearCooldown(configId: String) {
        SourceCooldown.clear(configId)
    } // [KO] source-cooldown

    /** Keeps [stream] as the verified original for [target], for a later play. */
    fun rememberOriginal(target: TrackMatcher.Target, stream: SourceStream) =
        com.music.bitchord.ko.KoSourceSearchMemo.rememberOriginal(target, stream) // [KO] remembered-original

    /** The verified original already found for [target], if it is still fresh. */
    fun rememberedOriginal(target: TrackMatcher.Target): SourceStream? =
        com.music.bitchord.ko.KoSourceSearchMemo.rememberedOriginal(target) // [KO] remembered-original

    private fun targetKey(target: TrackMatcher.Target): String =
        com.music.bitchord.ko.KoSourceSearchMemo.targetKey(target) // [KO] search-memo

    private fun requestKey(request: StreamRequest): String =
        com.music.bitchord.ko.KoSourceSearchMemo.requestKey(request) // [KO] search-memo

    /**
     * What to ask a source for, right now.
     *
     * The lossless switch is a preference, not an override — it loses to the
     * connection's own ceiling, which is the setting someone reached for
     * specifically to protect a data plan. A capped connection gets a capped
     * transcode whether or not lossless is on, because the alternative is a
     * switch in one part of Settings quietly undoing a switch in another, and
     * the one being undone is the one attached to a bill.
     */
    fun requestForNow(): StreamRequest {
        val ceiling = AppSettings.effectiveAudioQuality
        // Always the best the sources can do, bounded only by the connection's
        // own ceiling. There used to be a "Prefer lossless" switch in front of
        // this and it earned its removal: every source already degrades on its
        // own terms — a module hands back its best rendition, JioSaavn its
        // 320kbps AAC, YouTube its Opus — so switching it off asked the module
        // for a *worse* file than it was holding (`StreamRequest.Best` maps to
        // the module's `HIGH` tier) while changing nothing about the two lossy
        // sources. It was a switch whose only real effect was to downgrade the
        // one source that could do better.
        return when {
            ceiling == AudioQuality.LOSSLESS -> StreamRequest.Lossless
            ceiling.maxKbps == Int.MAX_VALUE -> StreamRequest.Best
            else -> StreamRequest.Capped(ceiling.maxKbps)
        }
    }

    /**
     * What to ask a source for on behalf of a file being kept.
     *
     * Reads [AppSettings.downloadQuality] and nothing else — not the ceilings,
     * not the lossless switch. Both of those are about what a *stream* costs on
     * the connection in hand, and this is the one request whose answer outlives
     * the connection: it becomes a file.
     *
     * That independence is the point. The ceilings used to decide this by
     * proxy, so downloading on capped mobile data returned a transcode from
     * YouTube even for someone whose own FLAC server was ranked above it — a
     * setting about data spend silently deciding what a permanent file was made
     * of. Data spend on a download is now [AppSettings.wifiOnlyDownloads]'
     * question, asked once at the point of queueing rather than mixed into
     * this one.
     *
     * @param quality defaults to the setting as it stands, which is what a
     *   caller with no download in flight wants. A caller that is already
     *   fetching one passes the value it started with — a lossless search can
     *   run for twenty seconds (`Downloads.SOURCE_LOOKUP_MS`), which is long
     *   enough for someone to open Settings and change the answer underneath a
     *   file that is half written.
     */
    fun requestForDownload(
        quality: DownloadQuality = AppSettings.downloadQuality.value,
    ): StreamRequest = when {
        quality.keepsLossless -> StreamRequest.Lossless
        quality.maxKbps == Int.MAX_VALUE -> StreamRequest.Best
        else -> StreamRequest.Capped(quality.maxKbps)
    }

    /**
     * @param uri a `bitchord://source?...` URI as built by [SourceRegistry.trackUri].
     * @return the stream, or null when nothing enabled could serve the track.
     */
    suspend fun resolve(uri: Uri): SourceStream? {
        val configId = uri.getQueryParameter("s") ?: return null
        val trackId = uri.getQueryParameter("t") ?: return null
        return resolve(
            configId = configId,
            trackId = trackId,
            target = targetIn(uri),
            pickedMix = uri.getQueryParameter(MIX_PARAM),
        )
    }

    /**
     * `&mix=` on an addon row's playback URI: which mix the row itself was,
     * [MIX_ATMOS] or [MIX_STEREO] as its search answer tagged it, or [MIX_ANY]
     * when it said neither.
     *
     * Present on every addon row and nothing else. Those reach the queue one
     * way — picked by hand from the Addons search, then replayed from history
     * or a playlist — and the marker makes that play strict: the row the
     * listener chose, never its Atmos sibling, another source's better copy,
     * or YouTube's Opus. That last covers Automix too, which would otherwise
     * fetch a YouTube match just to measure the track — see
     * [isPickedAddonRow]. Absent, nothing changes.
     */
    const val MIX_PARAM = "mix"
    const val MIX_STEREO = "stereo"
    const val MIX_ATMOS = "atmos"
    const val MIX_ANY = "any"
    fun mixOf(sourceQuality: String?): String = when (sourceQuality) {
        null -> MIX_ANY
        ModuleSource.DOLBY -> MIX_ATMOS
        else -> MIX_STEREO
    }

    /**
     * The stream for downloading a source row as itself — a track picked from
     * the Addons search, or one queued from such a pick — or null when its
     * source can't serve it now.
     *
     * [forDownload] matches a recording by title across every source, which is
     * the right question for a YouTube track and the wrong one for a row the
     * listener chose from a particular addon: it could come back as JioSaavn's
     * copy, or as the Atmos mix of a stereo pick. Same rules as [resolve]'s
     * pinned play, minus a source whose manifest forbids downloads.
     */
    suspend fun forDownloadOfRow(
        configId: String,
        trackId: String,
        sourceQuality: String?,
        request: StreamRequest = requestForDownload(),
    ): SourceStream? {
        val source = SourceRegistry.activeForDownload().firstOrNull { it.configId == configId } ?: return null
        return attempt(source) {
            if (source is AddonSource) {
                source.stream(trackId, request, stereoOnly = mixOf(sourceQuality) == MIX_STEREO)
            } else {
                source.stream(trackId, request)
            }
        }?.copy(sourceConfigId = source.configId)
    }

    fun directAddons(): List<MusicSource> {
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return emptyList()
        return rankedAbove(youtube.configId, active).filter { it.kind == SourceKind.ADDON }
    }

    suspend fun directStream(
        target: TrackMatcher.Target,
        /**
         * The track this lookup is for, when the caller knows it — passed on to
         * the probe so a candidate refused for lying about its own bytes takes
         * the copy it filled on an earlier play with it. See
         * [com.music.bitchord.ko.KoStreamProbe.servesBytes].
         */
        mediaId: String? = null,
        /**
         * The sound comparison, when the caller has one — the same proof the
         * upgrade path demands before a candidate may replace what is playing
         * (see [upgradeFor]'s own `verify`).
         *
         * Opening is not being the recording. A candidate can serve its first
         * bytes flawlessly and still be a different song: a FLAC of something
         * else, of a similar length, passes every check this path used to make
         * and then plays *instead of* the track that was queued, from its first
         * note. Null means the caller has no comparison to offer, and the probe
         * is then the whole of the proof, as it was.
         *
         * Deliberately before [probe]: a trailing lambda belongs to the last
         * parameter, and every existing caller passes the probe that way.
         */
        verify: (suspend (SourceStream) -> Boolean)? = null,
        /**
         * YouTube's runtime for the track, asked only when the queue row never
         * carried one. Started here, *beside* the addon search rather than
         * ahead of it, and awaited only once a search has answered and its rows
         * are about to be filtered — so the search's seconds cover the lookup's
         * fraction of one. Null back means it could not be had, and the match
         * falls back to title and artist alone.
         */
        lengthOf: (suspend () -> Int?)? = null,
        /**
         * Whether the candidate can actually serve its first bytes — see
         * [com.music.bitchord.ko.KoStreamProbe]. Injected so the choice can be
         * decided without a network, and so nothing else here has to know about
         * HTTP.
         *
         * A candidate this refuses is skipped and the next one is tried, so a
         * dead URL is discovered inside the lookup rather than by the player
         * after it has already started on it. The refused *row* is remembered
         * (see [KoRefusedRows]) and the same source is asked again for another
         * row: the refusal is evidence about that file, not about the
         * catalogue that also holds a good copy of the same song.
         */
        probe: suspend (SourceStream) -> Boolean = {
            com.music.bitchord.ko.KoStreamProbe.servesBytes(it, mediaId)
        },
    ): SourceStream? = coroutineScope {
        if (target.title.isBlank() || target.isVideo) return@coroutineScope null
        val addons = directAddons()
        if (addons.isEmpty()) return@coroutineScope null
        // Never allowed to fail: a child of this scope that throws takes the
        // addon search down with it, and a missing runtime is not worth that.
        val length = lengthOf?.takeIf { target.durationSec == null }?.let { fetch ->
            async {
                try {
                    fetch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        val lengthLater: (suspend () -> Int?)? = length?.let { pending -> { pending.await() } }
        // This stream plays *instead of* YouTube's own copy, so it clears the
        // same bar as every other replacement: the original recording, its
        // artist, its runtime and no cover/tribute arrangement. Ungated, a
        // tribute filed under the original title won on rank alone and a
        // "Crazy" request played a jazz/sax tribute (phone 11-04).
        //
        // The probe goes first because it is the cheap refusal, and the sound
        // comparison second because it is the only one that can tell a
        // correctly-credited different recording from this one. A candidate
        // that fails either is skipped and the next is tried, so the real
        // recording from a slower source still gets its turn.
        // A row the probe refuses is the wrong file, not the source being down
        // — and a catalogue serves one recording as several rows, so the source
        // is asked once more for another copy of the same song rather than
        // being written off for the whole walk. Only the row is remembered (see
        // [KoRefusedRows]), until several songs in a row get nothing better (see
        // [KoSourceCooldown.wrongFile]); a genuine 403 or timeout is still the source's own
        // failure and rests it through the ordinary rules. YouTube answers only
        // after every addon has had its next row, which is the difference
        // between "no addon has this" and "one addon's first row was broken".
        val refusedRows = LinkedHashSet<MusicSource>()
        val accept: suspend (MusicSource, SourceStream) -> Boolean = { candidateSource, candidate ->
            // Before the probe: a preview is refused without a network call,
            // and it is the one refusal that would otherwise reach the player
            // sounding perfect for its first thirty seconds. The row is refused
            // rather than the source — see [KoRefusedRows] — because the same
            // catalogue usually holds the full recording too.
            if (TrackMatcher.isWrongLength(target.durationSec, candidate.durationSec)) {
                TrackLog.w(
                    TAG,
                    "stream refused: ${candidate.durationSec}s for a ${target.durationSec}s track " +
                        "from ${candidateSource.displayName}",
                )
                KoRefusedRows.note(candidateSource.configId, candidate.trackId)
                SourceCooldown.wrongFile(candidateSource.configId, targetKey(target))
                refusedRows += candidateSource
                false
            } else if (!probe(candidate)) {
                // A host that refused the URL (403/404/410) has been recorded
                // by the probe and is the source's own failure, not a wrong
                // file: asking it again in this walk only costs the listener
                // the first note. Only a wrong file earns the next row.
                if (StreamRefusalRecovery.refusedStream(candidate.url) == null) {
                    KoRefusedRows.note(candidateSource.configId, candidate.trackId)
                    SourceCooldown.wrongFile(candidateSource.configId, targetKey(target))
                    refusedRows += candidateSource
                }
                false
            } else {
                SourceCooldown.rightFile(candidateSource.configId)
                verify?.invoke(candidate) ?: true
            }
        }
        val found = try {
            var result = bestAcross(
                addons,
                target,
                requestForNow(),
                strictOriginal = true,
                lengthLater = lengthLater,
                accept = accept,
            )
            var retries = 0
            while (result == null && refusedRows.isNotEmpty() && retries++ < REFUSED_ROW_RETRIES) {
                val again = refusedRows.toList()
                refusedRows.clear()
                result = bestAcross(
                    again,
                    target,
                    requestForNow(),
                    strictOriginal = true,
                    lengthLater = lengthLater,
                    accept = accept,
                )
            }
            result
        } finally {
            length?.cancel()
        }
        val (source, stream) = found ?: return@coroutineScope null
        TrackLog.d(
            TAG,
            "direct: '${target.title}' from ${source.displayName} at ${stream.format.summary}" +
                if (stream.belowRequest) " (below request)" else "",
        )
        return@coroutineScope stream
    }

    /** Whether [uri] is an addon row played as itself — see [MIX_PARAM]. */
    fun isPickedAddonRow(uri: Uri): Boolean =
        uri.authority == "source" && uri.getQueryParameter(MIX_PARAM) != null

    /**
     * The recording a playback URI describes, for matching it elsewhere.
     *
     * Title, artist and runtime ride in the URI because they are what a
     * cross-source match is made on, and the resolver runs on ExoPlayer's
     * loader thread with nothing but a DataSpec in hand — see
     * [toMediaItem][com.music.bitchord.playback.toMediaItem].
     */
    fun targetIn(uri: Uri) = TrackMatcher.Target(
        title = uri.getQueryParameter("n").orEmpty(),
        artist = uri.getQueryParameter("a").orEmpty(),
        durationSec = uri.getQueryParameter("d")?.toIntOrNull(),
        album = uri.getQueryParameter("l"),
        isExplicit = uri.getQueryParameter("e")?.let { it == "1" },
        isVideo = uri.getQueryParameter("m") == "1",
    )

    /**
     * @param target is what a cross-source match is made on. Without it the
     *   only possible behaviour is "the pinned source or nothing", which is
     *   still a correct outcome — just a worse one.
     */
    suspend fun resolve(
        configId: String,
        trackId: String,
        target: TrackMatcher.Target,
        pickedMix: String? = null,
    ): SourceStream? {
        val request = requestForNow()
        val active = SourceRegistry.activeForPlayback()
        // A pin identifies where the row originally came from; it does not
        // override the source switch. Looking the instance up directly used to
        // reopen disabled JioSaavn/addon tracks already sitting in the queue.
        // Resolve the pin only from the enabled list so Off means off for both
        // newly matched tracks and source-backed rows queued earlier.
        val pinned = active.firstOrNull { it.configId == configId }

        // The upgrade path: with lossless asked for and the pinned source
        // unable to serve it, anything ranked above it that can is worth
        // asking first. This is the whole reason the list is ordered — it is
        // what makes "my own FLAC of this, if I have one, else stream it"
        // expressible.
        // A row picked by hand is not up for substitution — see [MIX_PARAM].
        val picked = pickedMix != null && pinned is AddonSource
        if (!picked && request is StreamRequest.Lossless && pinned?.kind?.canServeLossless != true) {
            for (source in rankedAbove(configId, active, target)) {
                if (!source.kind.canServeLossless) continue
                val upgraded = matchAndStreamWithin(
                    source,
                    target,
                    request,
                    strictOriginal = true,
                )?.stream ?: continue
                // Only a genuinely lossless answer is an upgrade. A source that
                // *can* serve lossless but settled for a transcode of this
                // particular track has not beaten the pinned source at anything
                // — and returning its settle-for here jumped ahead of the
                // track's own source, which is the one the user picked and may
                // well hold something better. It falls through to the pinned
                // source instead, and to [bestAcross] below if that fails.
                if (!HqQuality.isLosslessTier(upgraded.format)) continue
                TrackLog.d(TAG, "lossless upgrade: '${target.title}' served by ${source.displayName}")
                return upgraded
            }
        }

        if (pinned != null) {
            attempt(pinned) {
                if (picked) {
                    (pinned as AddonSource).stream(trackId, request, stereoOnly = pickedMix == MIX_STEREO)
                } else {
                    pinned.stream(trackId, request)
                }
            }
                ?.let { return it.copy(sourceConfigId = pinned.configId) }
        }

        // Last resort. A track whose own source is down is still a track the
        // user asked for, and another source having it is not unlikely — this
        // is the difference between a dead server skipping the queue forward
        // and a dead server being invisible.
        //
        // Not YouTube, though, for a row picked from the Addons search: its
        // Opus is a lesser copy of a different upload, and the listener chose
        // this addon's precisely to not be hearing it — see [MIX_PARAM].
        val fallbacks = automaticUpgradeCandidates(
            active.filterNot {
                it.configId == configId || (pickedMix != null && it.kind == SourceKind.YOUTUBE)
            },
            target,
        )
        val (fallbackSource, stream) = bestAcross(fallbacks, target, request, strictOriginal = true) ?: return null
        TrackLog.d(TAG, "fallback: '${target.title}' served by ${fallbackSource.displayName}")
        return stream
    }

    /**
     * The stream for a YouTube track from a source the user ranked above
     * YouTube, or null when nothing has been verified for it yet.
     *
     * The first note never waits for a catalogue search. A stream already
     * proved to be the original recording — found by [prefetchSubstitute]
     * ahead of this track, or by an earlier play's second look — is returned;
     * otherwise this returns null at once and the caller starts YouTube. The
     * search that would have delayed the start runs under the music instead,
     * and its answer is used from the next play.
     */
    suspend fun substituteForYouTube(target: TrackMatcher.Target): SourceStream? {
        // Video rows always use YouTube's own stream unless the listener
        // explicitly converts the current item in the player. A module match
        // is another recording and can be a wrong song altogether.
        if (target.title.isBlank() || target.isVideo) return null
        // The first note never waits for a catalogue search. A stream already
        // proved to be the original — found ahead of the previous track or
        // during a previous play — is used; otherwise this track starts on
        // YouTube and the search runs under it (see [prefetchSubstitute] and
        // the second look), so its answer is ready for the next play.
        val remembered = rememberedOriginal(target) ?: run {
            TrackLog.d(TAG, "substitute: '${target.title}' no verified original; keeping YouTube")
            return null
        }
        val sourceName = remembered.sourceConfigId
            ?.let { SourceRegistry.config(it)?.displayName }
            ?: "source"
        TrackLog.d(
            TAG,
            "substituted: '${target.title}' served by $sourceName over YouTube" +
                " at ${remembered.format.summary}" +
                if (remembered.belowRequest) " (below request)" else "",
        )
        return remembered
    }

    suspend fun blockedStartStream(
        target: TrackMatcher.Target,
        timeoutMs: Long,
    ): SourceStream? = com.music.bitchord.ko.blockedStartStream(target, timeoutMs) // [KO] blocked-start

    /**
     * The reliable last rung for a source-backed queue item whose chosen stream
     * failed in the player.
     *
     * Unlike [resolve], this asks only YouTube. The failed addon/JioSaavn source
     * must not get another chance to return the identical URL, and unlike
     * [substituteForYouTube] this starts with metadata rather than an existing
     * YouTube video id, so it first finds the matching YouTube Music row.
     */
    suspend fun youtubeFallback(target: TrackMatcher.Target): SourceStream? {
        if (target.title.isBlank() || target.isVideo) return null
        val youtube = SourceRegistry.activeForPlayback()
            .firstOrNull { it.kind == SourceKind.YOUTUBE }
            ?: return null
        return matchAndStream(
            source = youtube,
            target = target,
            request = StreamRequest.Best,
            strictLength = target.durationSec != null,
            requireSharedArtist = true,
            strictOriginal = true,
        ).stream?.also {
            TrackLog.d(TAG, "YouTube fallback matched '${target.title}' after its higher-quality source failed")
        }
    }

    /**
     * Finds and verifies the original recording for the track *after* the one
     * playing, so the next transition can start on it.
     *
     * Runs only after the current row has made a sound (report 60). Every
     * enabled source ranked above YouTube is asked in parallel with a
     * per-source timeout; only candidates that pass
     * [TrackMatcher.isOriginalRecording] are considered, and the best of those
     * is returned. The caller pins it through
     * [StreamChoice][com.music.bitchord.playback.StreamChoice] and caches its
     * opening, which is what makes the next track start high-quality with no
     * mid-song swap.
     *
     * A returned stream is only useful if the *same* stream is what playback
     * goes on to open, and the caller is expected to record it before caching a
     * byte of it. Without that, playback re-runs the race, may land on a
     * different source, and writes a second file into the cache entry the warm
     * one already half-filled — which is the exact corruption
     * [StreamChoice] exists to prevent, arrived at from a new direction.
     *
     * Returning null is not a failure: nothing has been pinned, so playback
     * starts on YouTube as usual.
     */
    suspend fun prefetchSubstitute(
        target: TrackMatcher.Target,
        /**
         * Whether to keep the answer as the verified original immediately.
         *
         * False for callers that verify the audio first: remembering an
         * unverified stream here is how a cover would reach the next play
         * before the sound comparison had a chance to refuse it. Those callers
         * use [rememberOriginal] once the verification passes.
         */
        remember: Boolean = true,
        /**
         * The sound comparison itself, when the caller has one. Every
         * candidate is offered to it before it can win, so a cover from a fast
         * source is skipped and the real recording from a slower one is still
         * found — rather than the fastest answer becoming the only answer.
         * Also admits a row whose artist or title is filed under another
         * script's name — see `allowArtistUnverified` in `bestAcross`.
         */
        allowArtistUnverified: Boolean = false,
        accept: suspend (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): SourceStream? {
        if (target.title.isBlank() || target.isVideo) return null
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val candidates = rankedAbove(youtube.configId, active, target)
        if (candidates.isEmpty()) {
            TrackLog.d(TAG, "upgrade ahead: '${target.title}' none (keeping YouTube)")
            return null
        }
        val found = bestAcross(
            candidates,
            target,
            requestForNow(),
            waitForAll = true,
            strictOriginal = true,
            allowArtistUnverified = allowArtistUnverified,
            accept = accept,
        )
        if (found == null) {
            TrackLog.d(TAG, "upgrade ahead: '${target.title}' none (keeping YouTube)")
            TrackLog.d(TAG, "substitute: '${target.title}' no original on any configured source; keeping YouTube")
            return null
        }
        val (source, stream) = found
        if (remember) rememberOriginal(target, stream)
        TrackLog.d(
            TAG,
            "upgrade ahead: '${target.title}' ready at ${stream.format.summary} from ${source.displayName}",
        )
        return stream
    }

    /**
     * A stream that genuinely satisfies the current request, for a track that
     * is already playing on one that doesn't — or null if there isn't one.
     *
     * The same search as [substituteForYouTube] with two differences, both of
     * which are only affordable because sound is already coming out:
     *
     *  - Every module *within* a source is waited for, including the one the
     *    live path gave up on to get playback started (`waitForAll`). That
     *    module is frequently the point: dropping it is what left the listener
     *    on a stream from whoever happened to be quick.
     *  - A result that isn't lossless is still worth having when it is
     *    audibly better than what is playing — see [worthSwapping]. Refusing
     *    those outright is what left a track on YouTube's 160kbps Opus while
     *    a 320kbps AAC from a module sat in hand, unused, because it wasn't
     *    the FLAC that had been asked for.
     *  - Every source is asked, not only the ones that can serve lossless. That
     *    follows from the bullet above: once a lossy stream can win, a lossy
     *    *source* has to be allowed to offer one.
     *
     * Where it matches [substituteForYouTube] exactly is in taking the first
     * answer that clears the bar rather than the best of all of them — see
     * [bestAcross]. The bar here is [worthSwapping] rather than "satisfies the
     * request", and it is a high one: anything clearing it is lossless, or a
     * gain of [UPGRADE_MIN_GAIN_KBPS] over what the listener is hearing. Holding
     * such a stream back to see whether a slower source can do better trades a
     * certain improvement now for a possible improvement later, and "later" here
     * was measured at thirteen seconds.
     *
     * The cost of that choice is real and worth naming: a slow source holding a
     * FLAC can lose to a fast one holding a 320kbps AAC, and the track then
     * plays lossy for the rest of its length, because [QualityUpgrade][com.music.bitchord.playback.QualityUpgrade]
     * marks a track asked once the answer is yes. It is the same trade the live
     * path makes, made for the same reason.
     *
     * [target] must carry the runtime of the track *actually playing* — see
     * [matchAndStream]'s use of it. Swapping the audio under a listener is
     * only defensible when the replacement is the same recording, and length
     * is the check that a title cannot fake.
     *
     * @param playing what the listener is hearing now, so a lossy candidate
     *   can be judged against it rather than against the request. Null means
     *   unknown, and an unknown floor is treated as one nothing lossy clears:
     *   a swap that might be a downgrade is worse than no swap at all.
     * @param servedBy the [MusicSource.configId] already serving the track, if
     *   known. Asking it again is wasted: the same source, asked the same
     *   query at the same tier, is deterministic and can only reproduce the
     *   stream already playing — which [worthSwapping] would reject anyway,
     *   but not before spending a search and a stream call on it. See the
     *   'isn't worth swapping ... off' log line this was written to stop.
     * @param allowSameRendition accepts a candidate on [playing]'s rung instead
     *   of requiring [worthSwapping]'s improvement. Only the 429 resume path
     *   sets this: it is continuing a track already at the listener's chosen
     *   quality, where another source at that same rung is the right answer
     *   and YouTube is the last resort. Every ordinary upgrade leaves it false
     *   and keeps the old bar.
     */
    suspend fun upgradeFor(
        target: TrackMatcher.Target,
        playing: StreamFormat? = null,
        servedBy: String? = null,
        excludeSources: Set<String> = emptySet(),
        excludeHosts: Set<String> = emptySet(),
        allowSameRendition: Boolean = false,
        /**
         * A search the listener asked for. It ignores the memoised answer for
         * this track. A resting source is probed once on this walk either way —
         * see `probeResting` in [bestAcross].
         */
        manual: Boolean = false,
        /** Filled with each source's verdict, so a caller can tell none from not-now. */
        report: SearchReport? = null,
        /**
         * One manual request's identity, so a resting source is probed at
         * most once for it even when the caller walks several candidates.
         */
        manualSession: Any? = null,
        /**
         * Every candidate that cleared the quality gate, in the order the
         * race produced it. The upgrade path keeps this so moving to the next
         * source after a failed verification does not search again.
         */
        collect: MutableList<Pair<MusicSource, SourceStream>>? = null,
        /**
         * The sound comparison, when the caller has one. It runs as each
         * candidate arrives rather than after every source has timed out.
         */
        verify: (suspend (MusicSource, SourceStream) -> Boolean)? = null,
    ): SourceStream? {
        if (target.title.isBlank() || target.durationSec == null || target.isVideo) return null
        val request = requestForNow()
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        // Every source gets asked, whether or not it can serve lossless, and
        // they are asked at once.
        //
        // There used to be a `canServeLossless` skip here, applied whenever the
        // request was [StreamRequest.Lossless] — which is what an unmetered
        // connection asks for, i.e. nearly always. Its reasoning was that only a
        // lossless source can satisfy a lossless request, and that is true and
        // beside the point: this function is not serving the request, it is
        // deciding whether anything beats what is *already playing*.
        // [worthSwapping] is the bar for that, and a 320kbps source clears it
        // over YouTube's 160kbps Opus by nearly twice the required margin.
        //
        // Racing them matters as much as asking them. Walked in rank order this
        // took 13.6s on 'Bounce' — a module needed 7.6s to search and another
        // 5.0s to produce a 128kbps MP3 that was then refused, and only after
        // all of that was JioSaavn asked, which answered with 320kbps in 246ms:
        //
        // ```
        //   46:44.823  'Bounce' is playing 141 kbps … looking for a better copy
        //   46:57.477  Ricky's Addon offered MP3 · 128 kbps
        //   46:57.478  … isn't worth swapping 'Bounce' off 141 kbps
        //   46:57.478  ▶ JioSaavn searchSongs()        ← 12.65s in
        //   46:58.608  upgraded to MP4 · 320 kbps at 13569ms
        // ```
        //
        // Thirteen seconds of a 143-second track played at the wrong bitrate,
        // and the seam then landed mid-song rather than near its start. Raced,
        // the same swap happens inside a second.
        val worth: suspend (MusicSource, SourceStream) -> Boolean = { candidate, stream ->
            val acceptable = worthSwapping(stream.format, playing) ||
                (allowSameRendition && HqQuality.isSameOrBetter(stream.format, playing))
            // Named rather than skipped silently. This is the one refusal
            // in the upgrade path that discards a stream already found,
            // matched and length-checked, and a silent skip reads in the log
            // exactly like a source having nothing — which is how a null
            // [playing] came to quietly turn the whole cached-track path
            // lossless-only for a while without leaving a trace.
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
            driftSec = upgradeSlackSec(),
            requireSharedArtist = true,
            strictOriginal = true,
            allowArtistUnverified = true,
            excludeHosts = excludeHosts,
            manual = manual,
            manualSession = manualSession,
            // The one walk that is specifically looking for quality, so a
            // source resting after two failures still gets its one short
            // probe — a parked source may be the one holding the FLAC.
            probeResting = true,
            report = report,
            collect = collect,
            collectGate = worth,
        ) { candidate, stream ->
            worth(candidate, stream) && (verify?.invoke(candidate, stream) ?: true)
        } ?: return null
        TrackLog.d(TAG, "upgrade found: '${target.title}' at ${chosen.format.summary} from ${source.displayName}")
        return chosen
    }

    internal suspend fun upgradeCandidatesFor(
        target: TrackMatcher.Target,
        playing: StreamFormat? = null,
        servedBy: String? = null,
        excludeSources: Set<String> = emptySet(),
        excludeHosts: Set<String> = emptySet(),
        allowSameRendition: Boolean = false,
        manual: Boolean = false,
        report: SearchReport? = null,
        manualSession: Any? = null,
        verify: (suspend (MusicSource, SourceStream) -> Boolean)? = null,
    ): List<Pair<MusicSource, SourceStream>> =
        com.music.bitchord.ko.upgradeCandidatesFor(
            target, playing, servedBy, excludeSources, excludeHosts,
            allowSameRendition, manual, report, manualSession, verify,
        ) // [KO] upgrade-candidates

    /** The one place the upgrade candidate list's quality order is defined. */
    internal fun orderUpgradeCandidates(
        found: List<Pair<MusicSource, SourceStream>>,
    ): List<Pair<MusicSource, SourceStream>> =
        com.music.bitchord.ko.orderUpgradeCandidates(found) // [KO] upgrade-candidates

    /**
     * The copy of a track about to be downloaded that is worth keeping over
     * YouTube's, or null when nothing configured has one.
     *
     * The download path has never come through here. It resolves YouTube
     * directly — see
     * [resolveForDownload][com.music.bitchord.data.innertube.StreamResolver.resolveForDownload]
     * — so someone with a FLAC server ranked above YouTube was *streaming* the
     * FLAC and *downloading* a transcode of the same recording. This closes that
     * gap, and it is the only search in this class whose result becomes a file.
     *
     * ### The order it asks in
     *
     *  1. **Bit-exact, in rank order** — on the Lossless rung only, since that
     *     is the only rung asking for it. The first source that hands back a
     *     genuinely lossless file wins outright and nothing else is asked: it is
     *     the whole point of the setting and no bitrate competes with it.
     *  2. **The best lossy copy otherwise.** A lossless source that settled for
     *     a transcode is not thereby wasted — its offer is kept and weighed
     *     against what the lossy sources hold, and the better rendition wins on
     *     [isBetter]. This is where JioSaavn's 320kbps AAC comes in: it outranks
     *     YouTube for playback and used to be skipped entirely here, so a track
     *     streamed at 320 was filed at whatever YouTube's ladder gave.
     *  3. **YouTube**, by this returning null. Which is not a failure — see the
     *     floor below for when it is deliberately preferred.
     *
     * Steps 1 and 2 are started together rather than in sequence. A module
     * search runs to twenty seconds and JioSaavn answers in about four tenths of
     * one, and the whole lookup is bounded by `Downloads.SOURCE_LOOKUP_MS` —
     * queued behind the modules, the fast source would routinely have the
     * timeout land on it and the download would fall to YouTube holding a 320
     * it never got to hear about. Same reasoning as [bestAcross], for the same
     * measured reason.
     *
     * On the **High** rung step 1 is empty and every ranked source goes into
     * step 2's race, asked for [StreamRequest.Best]. A bit-exact answer to that
     * is refused rather than kept: someone who chose High over Lossless chose
     * the smaller file, and a FLAC filed under this setting is still found by
     * the already-on-disk check in `Downloads.prepare`, which looks for
     * lossless extensions on every rung.
     *
     * ### The floor, and why a lossy answer can still lose to YouTube
     *
     * A lossy candidate is only kept if it beats [YOUTUBE_BEST_AAC_KBPS] — the
     * top of YouTube's own AAC ladder. Anything at or under that is trading one
     * lossy copy for another and giving up the more reliable fetch to do it,
     * which is what the old blanket refusal of settle-fors was really protecting
     * against. So a 320 wins and a module's 128kbps MP3 does not, and neither
     * does a format that never stated a bitrate: a download has to *name the
     * file* before the first byte lands, and an unstated rendition is nothing to
     * judge. That last part is stricter than [streamBest]'s
     * [statesNothingLossy] allowance, deliberately — playback can hand an
     * undescribed URL to the decoder and let it work the codec out.
     *
     * ### Which settings get this far
     *
     * [DownloadQuality.LOSSLESS][com.music.bitchord.data.settings.DownloadQuality.LOSSLESS]
     * and [DownloadQuality.HIGH][com.music.bitchord.data.settings.DownloadQuality.HIGH];
     * not [DownloadQuality.STANDARD][com.music.bitchord.data.settings.DownloadQuality.STANDARD].
     * Standard is a 128kbps ceiling chosen to fit more on the device, and a
     * source cannot be asked for *that* rung — only for the best it has, which
     * is a bigger file than the setting exists to avoid. YouTube's ladder is the
     * only one that can be capped, so Standard stays entirely YouTube's.
     *
     * Note which setting decides this, since it used to be the wrong one: the
     * connection's ceiling ended this here instead, which meant a download on
     * mobile data took a transcode of a recording the user owns losslessly. That
     * was a data-plan setting deciding what a permanent file was made of, and
     * [AppSettings.wifiOnlyDownloads] does the data-plan job properly now —
     * before a byte is fetched rather than by quietly downgrading the result.
     *
     * @param target the recording to look for, off the row being downloaded. A
     *   blank title can only produce a wrong match; a null runtime is allowed
     *   and costs the length check rather than the search.
     * @param request what the download is for, pinned by the caller for the
     *   duration of one file — see [requestForDownload].
     * @param keep the caller's word on whether a candidate can become a file,
     *   asked of *every* candidate before it may win: a winner thrown away
     *   afterwards sends the download to YouTube past a runner-up that could
     *   have been kept. It may also return the stream better described — see
     *   `Downloads.keepable`, which reads the bytes for the codec a source
     *   left unstated.
     * @param budgetMs when to stop and decide on whatever has answered. Bounded
     *   from outside instead, a slow addon used to take every answer with it —
     *   JioSaavn's included — and the download went to YouTube.
     */
    suspend fun forDownload(
        target: TrackMatcher.Target,
        request: StreamRequest = requestForDownload(),
        keep: suspend (SourceStream) -> SourceStream? = { it },
        excludeSources: Set<String> = emptySet(),
        budgetMs: Long? = null,
    ): SourceStream? = coroutineScope {
        val until = budgetMs?.let { System.nanoTime() + it * 1_000_000 }
        if (target.title.isBlank()) return@coroutineScope null
        val wantsLossless = when (request) {
            is StreamRequest.Lossless -> true
            is StreamRequest.Best -> false
            // Standard, and the one rung the sources are no use for: it is a
            // ceiling of 128kbps chosen to fit more on the device, and a source
            // has no way to be asked for *that* rung rather than for the best it
            // has. JioSaavn's 320 is not a better answer to it, it is the wrong
            // answer to it. YouTube's ladder is the only one that can be capped.
            is StreamRequest.Capped -> return@coroutineScope null
        }
        // [SourceRegistry.active] rather than the playback list: a download is
        // not budgeted by the connection's streaming ceiling. What it keeps is
        // [DownloadQuality]'s answer and what it may spend is
        // [AppSettings.wifiOnlyDownloads]'s, and a mobile-data rung of Medium
        // has no business deciding that a file saved over Wi-Fi later is a
        // YouTube one. Minus any addon whose manifest says `allowDownloads: 0`:
        // that one plays, but the file comes from whoever is next in line.
        val active = SourceRegistry.activeForDownload()
            .filterNot { it.configId in excludeSources }
        // YouTube can be switched off, and a download still goes to it when
        // nothing here answers — the download path never consults this list. So
        // an absent YouTube means everything enabled outranks it, which is
        // already what [rankedAbove] says about a config that isn't in the list.
        val youtubeId = active.firstOrNull { it.kind == SourceKind.YOUTUBE }?.configId
        val ranked = rankedAbove(youtubeId.orEmpty(), active, target)
        val strictLength = target.durationSec != null

        // Who is in the bit-exact walk, and who is left to the race below it.
        //
        // On the High rung nobody is: there is no lossless answer to look for,
        // so every ranked source goes into the race and competes on rendition
        // alone. That is what puts JioSaavn's 320 in front of YouTube's AAC for
        // a setting whose own description is "best AAC on offer".
        val bitExact = if (wantsLossless) ranked.filter { it.kind.canServeLossless } else emptyList()
        val elsewhere = ranked - bitExact.toSet()

        // Asked now, read at the end — and raced rather than walked, because
        // these differ in speed by two orders of magnitude and the whole lookup
        // is on a clock. See [bestAcross], which takes the first answer that
        // clears the bar rather than the best of all of them: JioSaavn's four
        // tenths of a second is what that rule is tuned for, and a module that
        // beats it to the line with something thin is a case the floor below
        // catches by sending the download to YouTube.
        // On the High rung a bit-exact answer is held back rather than thrown
        // away: someone who picked High picked the ~8MB file, so any lossy copy
        // that beats YouTube's AAC still wins, but a FLAC from their own addon
        // is a better answer than YouTube's Opus when nothing lossy is on offer.
        var losslessOverBudget: Pair<MusicSource, SourceStream>? = null
        val elsewhereBest = async {
            bestAcross(
                elsewhere,
                target,
                // Their best lossy rendition, whichever rung is being served.
                // Putting the lossless request to a source that cannot meet it
                // would only come back marked [SourceStream.belowRequest] to
                // say so.
                StreamRequest.Best,
                waitForAll = true,
                strictLength = strictLength,
                strictOriginal = true,
                sourceTimeoutMs = DOWNLOAD_SOURCE_TIMEOUT_MS,
                refine = { _, stream -> keep(stream) },
                timeoutMs = budgetMs,
            ) { source, stream ->
                // Bit-exact audio is *over* budget on the High rung. Someone
                // who picked High over Lossless picked the ~8MB file, and a
                // FLAC filed under this setting is still found by the
                // already-on-disk check in `Downloads.prepare`, which looks for
                // lossless extensions on every rung.
                val ok = wantsLossless || stream.format.isLossless != true
                if (!ok) {
                    TrackLog.d(
                        TAG,
                        "${source.displayName} offered ${stream.format.summary}; " +
                            "more than this download asked for, kept in reserve",
                    )
                    if (losslessOverBudget == null) losslessOverBudget = source to stream
                }
                ok
            }
        }

        // The best lossy copy seen so far and who offered it — carried together
        // so the log line at the end can say where the file came from, which is
        // the only record of it there will be once it is on disk.
        var best: Pair<MusicSource, SourceStream>? = null
        for (source in bitExact) {
            val remaining = until?.let { remainingMs(it) }
            if (remaining != null && remaining <= 0L) break
            val stream = matchAndStreamWithin(
                source,
                target,
                // The request the guard above already narrowed, rather than a
                // second statement of it that could drift from the first.
                request,
                waitForAll = true,
                strictLength = strictLength,
                strictOriginal = true,
                baseTimeoutMs = DOWNLOAD_SOURCE_TIMEOUT_MS,
            )?.stream?.let { keep(it) } ?: continue
            // Dolby Atmos is not bit-exact PCM, but it is the native premium
            // rendition that playback already prefers.  Keep it on the same
            // first-class download path instead of throwing it away and
            // falling back to YouTube solely because it has no bitrate tag.
            if (HqQuality.isLosslessTier(stream.format) || stream.format.isDolbyAtmos) {
                TrackLog.d(
                    TAG,
                    "download: '${target.title}' from ${source.displayName} at ${stream.format.summary}",
                )
                // Nothing is waiting on it any more, and leaving it running
                // would hold this whole call open on a source whose answer has
                // just been beaten by the requested premium rendition.
                elsewhereBest.cancel()
                return@coroutineScope stream
            }
            TrackLog.d(
                TAG,
                "${source.displayName} offered ${stream.format.summary} to download; not lossless or Dolby",
            )
            if (isBetter(stream.format, best?.second?.format)) best = source to stream
        }

        // Nothing bit-exact anywhere — or nothing was being looked for.
        // Whatever is left competes on rendition alone, wherever it came from.
        elsewhereBest.await()?.let { (source, stream) ->
            if (isBetter(stream.format, best?.second?.format)) best = source to stream
        }
        val lossy = best?.takeIf { beatsYouTubeAac(it.second.format) }
        if (lossy == null) {
            losslessOverBudget?.let { (source, stream) ->
                TrackLog.d(
                    TAG,
                    "download: nothing lossy for '${target.title}' beats YouTube's AAC; " +
                        "keeping ${source.displayName}'s ${stream.format.summary} instead",
                )
                return@coroutineScope stream
            }
        }
        val (winner, chosen) = lossy ?: run {
            best?.let { (source, stream) ->
                TrackLog.d(
                    TAG,
                    "best offered for '${target.title}' is ${source.displayName}'s " +
                        "${stream.format.summary}; taking YouTube's AAC over that",
                )
            }
            return@coroutineScope null
        }
        TrackLog.d(
            TAG,
            "download: '${target.title}' from ${winner.displayName} at ${chosen.format.summary}, " +
                "over YouTube's AAC",
        )
        chosen
    }

    suspend fun forDownloadVerified(
        sourceConfigId: String,
        trackId: String,
        request: StreamRequest = requestForDownload(),
    ): SourceStream? =
        com.music.bitchord.ko.forDownloadVerified(sourceConfigId, trackId, request) // [KO] download-verified

    /**
     * Whether a lossy [candidate] is worth keeping as a file over whatever
     * YouTube's own AAC ladder would have given for the same track.
     *
     * The floor under [forDownload]'s step 2, and deliberately a floor rather
     * than a comparison: see [YOUTUBE_BEST_AAC_KBPS] for why the rung YouTube
     * actually holds is not worth the walk it would take to find out.
     *
     * An unstated bitrate is a no. A download has to name the file before the
     * first byte lands, and a rendition that described itself as nothing is
     * nothing to weigh against the alternative — which is stricter than
     * [streamBest]'s [statesNothingLossy] allowance, deliberately: playback can
     * hand an undescribed URL to the decoder and let it work the codec out.
     */
    internal fun beatsYouTubeAac(candidate: StreamFormat): Boolean =
        (candidate.kbps ?: 0) > YOUTUBE_BEST_AAC_KBPS

    /**
     * Whether [candidate] is enough better than [playing] to be worth the
     * break in the audio that swapping to it costs.
     *
     * Lossless always is: it is what was asked for, and the whole point.
     *
     * A lossy candidate has to clear [UPGRADE_MIN_GAIN_KBPS] over what is
     * already playing, which is deliberately a wide gap rather than a strict
     * improvement. Bitrate compares poorly across codecs — Opus at 160kbps
     * and AAC at 256kbps are much the same thing to listen to — so a margin
     * narrow enough to be codec-sensitive would be a margin that buys a seam
     * in the audio for nothing. 160 to 320 clears it; 128 to 192 does not.
     *
     * An immersive mix is a candidate on every route. A stereo one — Bluetooth
     * included — plays it by folding the 5.1 bed down
     * ([com.music.bitchord.playback.audio.Downmix]), which is what an Atmos
     * upgrade was refused for before that existed (phone 11-04).
     */
    internal fun worthSwapping(
        candidate: StreamFormat,
        playing: StreamFormat?,
    ): Boolean {
        // Never away from an immersive mix. A FLAC is a better *copy* and a
        // worse *mix* once Atmos is what the listener chose, and cutting one in
        // over the other mid-song is a downgrade dressed as an upgrade.
        // [QualityUpgrade.needsLosslessFollowUp] already treats Atmos as final,
        // so this is the belt to that braces — it also covers the late answer
        // that arrives from a pass started before the Atmos stream landed.
        if (playing?.isDolbyAtmos == true && !candidate.isDolbyAtmos) return false
        if (candidate.isDolbyAtmos) return true
        // An unknown floor is not evidence of an improvement. Lossless and
        // Atmos still are: they are better than anything lossy by definition.
        if (playing == null) return HqQuality.isLosslessTier(candidate)
        // An unstated lossy bitrate on either side cannot be weighed.
        if (playing.isLossless != true && playing.kbps == null &&
            !HqQuality.isLosslessTier(candidate) &&
            !candidate.isDolbyAtmos
        ) {
            return false
        }
        val candidateTier = HqQuality.tierOf(candidate)
        val playingTier = HqQuality.tierOf(playing)
        if (candidateTier.rank < playingTier.rank) return true
        if (candidateTier.rank > playingTier.rank) return false
        val gain = (candidate.kbps ?: return false) - (playing?.kbps ?: return false)
        return gain >= UPGRADE_MIN_GAIN_KBPS
    }

    /**
     * Whether two runtimes are close enough to be the same recording, for a
     * swap into a track that is already playing.
     *
     * The same [UPGRADE_DRIFT_SEC] bar [matchAndStream] applies to the
     * candidates it finds itself, exposed for the one candidate it doesn't:
     * the live lookup [QualityUpgrade][com.music.bitchord.playback.QualityUpgrade]
     * inherits when the fallback wins the race.
     *
     * Either side being unknown is a no. An unverifiable length is not a
     * length that agrees, and the cost of being wrong here is a listener's
     * song replaced mid-play by a different cut of it.
     *
     * @param slackSec the listener's own tolerance when it is a swap being
     *   decided — see [upgradeSlackSec].
     */
    fun sameRecordingAs(
        candidateSec: Int?,
        playingSec: Int?,
        slackSec: Int = UPGRADE_DRIFT_SEC,
    ): Boolean {
        if (candidateSec == null || playingSec == null) return false
        return kotlin.math.abs(candidateSec - playingSec) <= slackSec
    }

    /**
     * How far a replacement's runtime may sit from the playing track's for a
     * mid-song swap: Settings' "Track length tolerance".
     *
     * The setting used to reach only the audition and the check after the
     * swap, both of which run on a stream already chosen. Choosing it ran on
     * [UPGRADE_DRIFT_SEC] alone, so every candidate between two seconds and
     * the setting was thrown out before the setting was ever consulted — the
     * slider could tighten the bar and never loosen it. Measured on 'Take on
     * Me': a 24-bit/192 kHz remaster at 3:48 against YouTube's 3:45, refused
     * three seconds out under a tolerance set to five, and the upgrade landed
     * on the 16-bit album cut instead.
     */
    fun upgradeSlackSec(): Int = AppSettings.upgradeLengthSlackSeconds.value

    /**
     * Whether anything outranks YouTube right now — i.e. whether a YouTube
     * track is worth offering around before it is resolved.
     *
     * Answerable from the source list alone, without a search, which is what
     * lets the cache and the read-ahead in
     * [AudioCache][com.music.bitchord.playback.AudioCache] decide how to treat
     * a YouTube id before anyone has asked a source for it.
     */
    fun canSubstituteForYouTube(): Boolean {
        val active = SourceRegistry.activeForPlayback()
        val above = active.indexOfFirst { it.kind == SourceKind.YOUTUBE }
        return above > 0 && automaticUpgradeCandidates(active.take(above)).isNotEmpty()
    }

    internal fun automaticUpgradeCandidates(
        sources: List<MusicSource>,
        target: TrackMatcher.Target? = null,
    ): List<MusicSource> =
        com.music.bitchord.ko.automaticUpgradeCandidates(sources, target) // [KO] auto-upgrade-candidates

    /**
     * The sources ranked above [configId], in order.
     *
     * A config that isn't in [active] ranks last: it is disabled or incomplete,
     * and everything that *is* enabled is worth trying ahead of it. [target] is
     * only for the automatic walk's own log line — see
     * [automaticUpgradeCandidates].
     */
    private fun rankedAbove(
        configId: String,
        active: List<MusicSource>,
        target: TrackMatcher.Target? = null,
    ): List<MusicSource> =
        active.indexOfFirst { it.configId == configId }
            .let { if (it < 0) active.size else it }
            .let { active.take(it) }
            .let { automaticUpgradeCandidates(it, target) }

    /**
     * The best stream the configured [sources] can serve for [target] — **all
     * of them asked at once** — or null if none of them has the recording.
     *
     * ### Why they race rather than queue
     *
     * Asking them in rank order is the obvious reading of an ordered list, and
     * it is wrong here, because the sources differ in speed by nearly two orders
     * of magnitude. Measured on '9:45':
     *
     * ```
     *   JioSaavn        search 245ms + stream 131ms   ≈ 0.4s
     *   Ricky's Addon   search → settled stream       ≈ 13.5s
     * ```
     *
     * Queued behind the module, JioSaavn's answer arrives at ~14s. Nobody is
     * waiting that long for a song to start, so YouTube wins the race in
     * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s
     * `resolveWithModulePriority` every single time and the listener gets
     * 160kbps Opus — while a 320kbps copy sat four tenths of a second away.
     * Raced, the same answer arrives before YouTube's own walk finishes and the
     * track starts on it.
     *
     * ### What rank still decides, and what it no longer does
     *
     * Rank decides who is *asked* — [rankedAbove] is still what builds this list
     * — and it breaks ties between answers that arrive together, since each
     * sweep folds in everything that has already crossed the line and picks the
     * best of them with [isBetter]. What it no longer does is let a slow
     * favourite hold up a fast alternative.
     *
     * **A slow source is not thereby lost.** Whatever is returned here starts
     * playing; if it is [SourceStream.belowRequest] the track is marked for a
     * second look, and [upgradeFor] then asks *every* source again with no time
     * limit and swaps up only if what comes back genuinely beats what is playing
     * — see [worthSwapping]. So a module that needed thirteen seconds to find a
     * FLAC still gets to serve it, mid-track, and one that needed thirteen
     * seconds to find a 128kbps MP3 is correctly ignored. That is the trade this
     * whole path exists to make: sound now, quality shortly after.
     *
     * On the latency-critical path, sources still running when an answer is
     * taken are cancelled — the second look re-asks them properly, and leaving
     * them running would spend a listener's radio on a result nothing is waiting
     * for. The background-upgrade path passes [waitForAll], however: it already
     * chose to wait for every source's patient search window, so it must also
     * collect the resulting streams before choosing. Previously that flag only
     * reached each source's search call; this loop still returned the first
     * usable stream and cancelled the rest, which could discard an exact Tidal
     * FLAC or JioSaavn 320 result during an upgrade.
     *
     * Two answers of equal quality go to the higher-ranked source, whenever
     * each arrived. Arrival order used to decide that, which handed every tie
     * to JioSaavn's four tenths of a second over an addon the user had ranked
     * first.
     *
     * @param refine runs on each source's answer inside that source's own
     *   lookup, so its cost is paid in parallel. It may rewrite the stream
     *   (with what its bytes turned out to be) or refuse it with null.
     * @param timeoutMs ends the race at that point with whatever has already
     *   answered, rather than losing those answers along with the slow ones.
     * @param driftSec the [strictLength] bar — see [matchAndStream].
     * @param lengthLater, [checkFileLength] — see [matchAndStream]. Shared by
     *   every source's lookup, so it must be safe to call from each of them.
     * @return the winning source alongside its stream, so callers can name it in
     *   a log line without searching the list again.
     */
    internal suspend fun bestAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        /**
         * The runtime bar [strictLength] applies. A mid-song upgrade passes
         * the listener's own tolerance ([upgradeSlackSec]); a download and the
         * YouTube fallback keep the fixed [UPGRADE_DRIFT_SEC].
         */
        driftSec: Int = UPGRADE_DRIFT_SEC,
        requireSharedArtist: Boolean = false,
        strictOriginal: Boolean = false,
        /**
         * Keep a row that differs from the request only in a signal that cannot
         * be compared — an artist localised into another script
         * (`artist-unverified`) or a title filed under the catalogue's own
         * language (`title-unverified`). Only the upgrade path sets this: the
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
        report: SearchReport? = null,
        /**
         * One manual request's identity. A resting source is probed once per
         * session, not once per candidate walk inside it — the walk is the
         * same request, and re-asking a source that already said it is
         * resting only spends another timeout.
         */
        manualSession: Any? = null,
        /**
         * Ask a resting source once even when this walk is not the listener's
         * own request.
         *
         * The rest rule exists so a dead addon cannot cost every track its
         * timeout, and it is right for a walk that only needs *an* answer. An
         * upgrade re-search is not that walk: it is specifically looking for
         * the better copy, and a source parked after two failures may be the
         * one holding it. It gets the same one short probe a hand-made request
         * already gets — that mechanism, not a second one — and a conclusive
         * answer from it clears the rest, exactly as it does there.
         */
        probeResting: Boolean = false,
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
        /** Runs on each source's answer inside its own lookup, so its cost is paid in parallel. */
        lengthLater: (suspend () -> Int?)? = null,
        checkFileLength: Boolean = false,
        refine: suspend (MusicSource, SourceStream) -> SourceStream? = { _, stream -> stream },
        /** Ends the race at that point with whatever has already answered. */
        timeoutMs: Long? = null,
        accept: suspend (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): Pair<MusicSource, SourceStream>? = coroutineScope {
        val until = timeoutMs?.let { System.nanoTime() + it * 1_000_000 }
        val lookupTimeoutMs = sourceLookupTimeoutMs(manual, sourceTimeoutMs)
        // A hand-made request already gets one short probe of a resting source;
        // an upgrade re-search asks for the same thing by name (see
        // [probeResting]) rather than through a second mechanism.
        val probesResting = manual || probeResting
        val usable = sources.filterNot { source ->
            val resting = SourceCooldown.isResting(source.configId)
            // A source parked for unmeasurable candidates is not probed even by
            // hand: its search works, its download is what keeps being refused.
            val unmeasurable = resting && SourceCooldown.isUnmeasurable(source.configId)
            val alreadyProbed = resting && probesResting && (
                unmeasurable || (manualSession != null &&
                    !manualRestingProbes.add(manualProbeKey(manualSession, source.configId, target))) ||
                    // A hand-made request is worth its probe; a walk the app
                    // started on its own waits its turn.
                    (!manual && !com.music.bitchord.ko.KoSourceCooldown.takeRestProbe(source.configId))
                )
            if (alreadyProbed) {
                val skipped = SearchOutcome(verdict = "resting", conclusive = false)
                report?.record(skipped, source.displayName)
                TrackLog.d(TAG, hqSearchLine(target, source, skipped))
                true
            } else if (resting && !probesResting) {
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
                    val why = if (manual) {
                        "this upgrade was requested by hand"
                    } else {
                        "this walk is looking for a better copy"
                    }
                    TrackLog.d(
                        TAG,
                        "${source.displayName} is resting; asking it once because $why",
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
                    val sourceTimeout = if (probesResting && SourceCooldown.isResting(source.configId)) {
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
                            driftSec,
                            lengthLater,
                            checkFileLength,
                        )
                    }
                    if (answer == null) {
                        // A timeout while the device is offline (or behind a
                        // captive portal) is the connection, not the source.
                        // Counting it here parked every source for 30 minutes
                        // after a dorm Wi-Fi drop, long after the link was
                        // back.
                        SourceCooldown.timedOut(
                            source.configId,
                            source.kind,
                            online = !offlineCheck(),
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
                        // A stream is proof the source is healthy. A miss is
                        // neither proof of health nor a failure: it answered.
                        if (answer.stream != null) SourceCooldown.succeeded(source.configId)
                        // A hand-made request, or an upgrade re-search, is also
                        // the one chance a resting source gets to prove it is
                        // back. A complete answer is enough evidence, even when
                        // the catalogue held nothing.
                        if (probesResting && answer.conclusive) SourceCooldown.succeeded(source.configId)
                        val refined = if (answer.stream != null) {
                            answer.copy(stream = refine(source, answer.stream))
                        } else {
                            answer
                        }
                        source to refined
                    }
                }
            }
            .toMutableList()
        var best: Pair<MusicSource, SourceStream>? = null
        var bestVerdict: String? = null
        try {
            while (running.isNotEmpty()) {
                val awaiting = suspend {
                    select {
                        running.forEach { candidate -> candidate.onAwait { candidate } }
                    }
                }
                val first = if (until == null) {
                    awaiting()
                } else {
                    withTimeoutOrNull(remainingMs(until)) { awaiting() } ?: break
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
                        // this track. SourceCooldown still handles the source-level
                        // failure; this is the narrower server-level rest.
                        if (host != null && HqHostTracker.isResting(host)) {
                            TrackLog.d(TAG, "${source.displayName}'s $host is resting; skipping for this track")
                            continue
                        }
                        // A URL that already refused its bytes is not asked again
                        // for this track: the lookup that minted it is
                        // deterministic, so a probe would be the same refused
                        // request. Answered from the record the playback path
                        // left behind — see [StreamRefusalRecovery.refusedStream].
                        if (StreamRefusalRecovery.refusedStream(stream.url) != null) {
                            TrackLog.d(
                                TAG,
                                "${source.displayName}'s stream already refused this track; not asking it again",
                            )
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

    private suspend fun matchAndStreamWithin(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        strictOriginal: Boolean = false,
        baseTimeoutMs: Long = SOURCE_LOOKUP_TIMEOUT_MS,
    ): SearchOutcome? = com.music.bitchord.ko.matchAndStreamWithin(
        source, target, request, waitForAll, strictLength, strictOriginal, baseTimeoutMs,
    ) // [KO] match-stream-within

    /**
     * Searches [source] for the recording in [target] and streams it if one of
     * the answers really is that recording — see [TrackMatcher].
     *
     * Each query the matcher offers is tried in turn, because the first one
     * failing is usually the catalogue disagreeing about how a track is
     * *filed*, not about whether it holds it. Stopping at the first empty
     * answer is what made a source look like it was missing half of what it
     * had. A source that *throws* still gets no second chance: that is its
     * server having a problem, and asking it again differently won't fix it.
     *
     * @param waitForAll holds a multi-backend search open for every backend
     *   instead of answering from whichever of them are quick — affordable only
     *   when nobody is waiting on the first note.
     * @param strictLength requires a candidate's runtime to agree with
     *   [target]'s to within [driftSec]. Kept apart from [waitForAll]
     *   because it is only meaningful when the target *has* a runtime:
     *   [TrackMatcher.withinSeconds] answers false for every candidate against a
     *   null one, so asking for this against a target that never carried a
     *   duration is not a strict search but an empty one.
     * @param driftSec [UPGRADE_DRIFT_SEC] unless the caller is deciding a
     *   mid-song swap, which answers to the listener's tolerance instead — see
     *   [upgradeSlackSec]. A download and the YouTube fallback keep the fixed
     *   bar: neither cuts into audio already playing, which is what the setting
     *   is about.
     * @param lengthLater the runtime for a [target] that has none, already
     *   being fetched by the caller. Awaited after the first search answers,
     *   and the rows are then ranked and length-checked against it.
     * @param checkFileLength measures each opened stream's file before
     *   accepting it — see [cutShort]. Only for a caller with no audition
     *   behind it.
     */
    private suspend fun matchAndStream(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        strictOriginal: Boolean = false,
        manual: Boolean = false,
        allowArtistUnverified: Boolean = false,
        driftSec: Int = UPGRADE_DRIFT_SEC,
        lengthLater: (suspend () -> Int?)? = null,
        checkFileLength: Boolean = false,
    ): SearchOutcome {
        var last = SearchOutcome()
        var matchOn = target
        for (query in TrackMatcher.queries(target)) {
            val key = SearchKey(
                sourceId = source.configId,
                target = targetKey(target),
                request = requestKey(request),
                waitForAll = waitForAll,
                query = query,
            )
            val now = System.currentTimeMillis()
            // Taken before either the search or the stream calls below, so the
            // same comparison covers both. A stream open that 500s or times out
            // is a failure of the whole answer even when the search itself
            // looked healthy — see [SourceCooldown.failureMark].
            val mark = SourceCooldown.failureMark(source.configId)
            val authMark = SourceCooldown.authMark(source.configId)
            var authRejected = false
            // A hand-made request is evidence that the cached "none" was wrong.
            // Leave it unread, so the source is asked again; the fresh answer
            // then replaces the memo below. A resting source is here to prove it
            // is back, which an answer from before it went down cannot do.
            val cached = if (manual || SourceCooldown.isResting(source.configId)) null else {
                searchAnswers[key]?.takeIf { now - it.at < SEARCH_MEMO_TTL_MS }?.rows
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
                } ?: return SearchOutcome(
                    verdict = if (SourceCooldown.authMark(source.configId) != authMark) "auth" else "error",
                    conclusive = false,
                ).also {
                    TrackLog.d(TAG, hqSearchLine(target, source, it))
                }
                // Some sources swallow a transport or credential fault and
                // return an empty list. An answer produced while this source
                // reported a failure is not a confirmed miss, and must not be
                // memoised — see [SourceCooldown.failureMark].
                authRejected = SourceCooldown.authMark(source.configId) != authMark
                conclusive = SourceCooldown.failureMark(source.configId) == mark
                if (conclusive) {
                    if (searchAnswers.size >= SEARCH_MEMO_MAX) searchAnswers.clear()
                    searchAnswers[key] = SearchAnswer(rows, System.currentTimeMillis())
                }
                rows
            }
            // A runtime the queue row never carried, looked up beside the
            // search by the caller and asked for only now that the search has
            // answered — so waiting on it costs the difference, if any, and
            // only the first query ever waits.
            if (matchOn.durationSec == null && lengthLater != null) {
                lengthLater()?.let { matchOn = matchOn.copy(durationSec = it) }
            }
            // A strict search against no runtime at all is an empty one — see
            // [strictLength] — so with nothing to measure by, it is not strict.
            val strict = strictLength && matchOn.durationSec != null
            val ranked = TrackMatcher.ranked(candidates, matchOn)
            // Rows whose only disagreement is an artist credit in another
            // script are kept apart so the ordinary match order still wins.
            // Judged over every result, not just the top row: the localised
            // copy is often not what the search backend puts first.
            val unverified = mutableSetOf<Song>()
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
                            ) || TrackMatcher.isTitleUnverifiedOriginal(
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
                    unverified += localised
                    verified + localised
                } else {
                    verified
                }
            } else {
                ranked
            }
            if (requireSharedArtist) {
                matches = matches.filter {
                    it in unverified ||
                        TrackMatcher.sharesArtist(matchOn.artist, it.artist)
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
            val verifiedForConflict = matches.filterNot { it in unverified }
            if (source.kind == SourceKind.JIOSAAVN &&
                TrackMatcher.hasConflictingAlbums(verifiedForConflict, matchOn)
            ) {
                val canonical = TrackMatcher.uniquelyMostCreditedCloseMatch(verifiedForConflict, matchOn)
                if (canonical == null) {
                    TrackLog.w(
                        TAG,
                        "${source.displayName} returned conflicting albums for '${matchOn.title}'; refusing to guess",
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
                    "${source.displayName} resolved conflicting albums for '${matchOn.title}' " +
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
            if (strict) {
                val (sameLength, otherLength) = matches.partition {
                    val drift = if (it in unverified) {
                        TrackMatcher.ARTIST_UNVERIFIED_DRIFT_SEC
                    } else {
                        driftSec
                    }
                    TrackMatcher.withinSeconds(it, matchOn, drift)
                }
                // Named rather than dropped silently: a copy refused for its
                // length reads in the log exactly like a source that never had
                // it, and the two have opposite fixes.
                if (otherLength.isNotEmpty()) {
                    TrackLog.d(
                        TAG,
                        "${source.displayName}: ${otherLength.size} match(es) for '${matchOn.title}' outside " +
                            "±${driftSec}s of ${matchOn.durationSec}s: " +
                            otherLength.joinToString { "'${it.title}' ${it.durationText ?: "?"}" },
                    )
                }
                matches = sameLength
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
                        matchOn,
                        strictOriginal = strictOriginal,
                        requireSharedArtist = requireSharedArtist,
                        strictLength = strict,
                        driftSec = driftSec,
                        allowArtistUnverified = allowArtistUnverified,
                    )
                }
                last = SearchOutcome(
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
            val verifiedMatches = matches.filterNot { it in unverified }
            val unverifiedMatches = matches.filter { it in unverified }
            val sameLengthSec =
                if (strict) maxOf(SAME_RECORDING_SEC, driftSec) else SAME_RECORDING_SEC
            val verifiedOpened = if (verifiedMatches.isEmpty()) {
                null
            } else {
                streamBest(
                    source,
                    verifiedMatches,
                    matchOn,
                    request,
                    sameLengthSec = sameLengthSec,
                    // Only the patient lookups can afford to open more than one row.
                    compareRows = waitForAll,
                    checkFileLength = checkFileLength,
                )
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
                SourceCooldown.authMark(source.configId) != authMark
            ) {
                null
            } else {
                // These are already ordered closest-runtime-first; do not let
                // [preferred]'s quality tiers reorder them. The closest cut is
                // the candidate hq-verify is asked about first.
                streamBest(
                    source,
                    unverifiedMatches,
                    matchOn,
                    request,
                    preserveOrder = true,
                    countFailure = !localisedIsAlternate,
                    checkFileLength = checkFileLength,
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
                        alternateVerdict = unverifiedVerdict(localisedOpened.first, matchOn)
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
            if (SourceCooldown.authMark(source.configId) != authMark) {
                authRejected = true
            }
            if (SourceCooldown.failureMark(source.configId) != mark) {
                conclusive = false
            }
            val chosen = opened?.first
            last = SearchOutcome(
                stream = stream,
                results = candidates.size,
                best = chosen,
                verdict = when {
                    stream == null -> if (authRejected) "auth" else "error"
                    // Which signal is missing decides the word; both are the
                    // same tier and both still need the sound comparison.
                    chosen == null || chosen !in unverified -> "match"
                    else -> unverifiedVerdict(chosen, matchOn)
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

    /**
     * Opens the best of [matches] that can actually serve [request].
     *
     * Two things happen here that a single "take the top match" cannot:
     *
     *  1. **Rows that advertise the tier asked for go first.** Every one of
     *     these is genuinely the recording, so which one plays is a question
     *     about quality, not identity — and a catalogue that has already said
     *     it holds a FLAC is a better place to ask for one than a catalogue
     *     that said nothing. Without this the order was confidence alone, and
     *     a 16-bit FLAC lost to a Deezer row over how its artists were spelt.
     *
     *  2. **What comes back is checked against what was asked for.** A module
     *     that cannot serve lossless does not always say so; some quietly walk
     *     their own fallback chain and hand back a 128kbps MP3 with the right
     *     title on it. Reading [StreamFormat] before accepting the URL is what
     *     turns that into "this one can't, try the next" instead of into the
     *     listener's evening.
     *
     * The under-quality stream is kept rather than dropped: if nothing better
     * exists anywhere, playing the MP3 is still better than skipping the
     * track. It is a floor, not a first choice.
     */
    /**
     * The matching rows, in the order they are worth opening.
     *
     * Two rules, and the order of them is the point:
     *
     *  1. **Length decides which recording, first.** When any candidate agrees
     *     with the runtime being asked for to within a couple of seconds, only
     *     the candidates that agree are eligible at all. A catalogue holding
     *     the track under its right title and right artist can still be
     *     holding a different *cut* of it — a DJ edit on a compilation, an
     *     extended mix — and the runtime is what separates those when nothing
     *     in the title does. If nothing agrees, nothing is excluded: the
     *     runtimes are simply not informative here and the score stands alone.
     *
     *  2. **Quality decides between equals, second.** Among rows that are the
     *     same recording, one advertising a lossless copy is the better place
     *     to ask. This was doing that job *first*, which is how a 185-second
     *     "Punjabi Dj Holi songs" cut beat the 180-second album track on the
     *     strength of the word `flac` in its listing. A declared tier is a
     *     reason to prefer one copy of a recording over another; it is not a
     *     reason to play a different recording.
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun preferred(
        matches: List<Song>,
        target: TrackMatcher.Target,
        wantsLossless: Boolean,
        sameLengthSec: Int = SAME_RECORDING_SEC,
    ): List<Song> {
        val sameLength = matches.filter { TrackMatcher.withinSeconds(it, target, sameLengthSec) }
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

    /**
     * Whether an immersive mix is worth preferring right now — the same two
     * gates the playback side enforces in [ModuleSource.unplayable] and the
     * addon path sends its `?atmos=` hint on, asked in the one place that
     * chooses between rows.
     */
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
        sameLengthSec: Int = SAME_RECORDING_SEC,
        /**
         * Keep opening the remaining lossless rows after one has answered, and
         * return the best of them by [isBetter], rather than the first. Only
         * for a caller that is not holding up a first note: each row is another
         * stream call.
         */
        compareRows: Boolean = false,
        checkFileLength: Boolean = false,
    ): Pair<Song, SourceStream>? {
        val wantsLossless = request is StreamRequest.Lossless
        val ordered = if (preserveOrder) matches else preferred(matches, target, wantsLossless, sameLengthSec)
        var settleFor: Pair<Song, SourceStream>? = null
        var best: Pair<Song, SourceStream>? = null
        // A credential refusal is not a property of this row: every other row
        // will be refused the same way. Stop after the first refusal instead of
        // spending up to three `/stream` calls (and raising the source's
        // failure mark each time) against a source that just said no.
        val authMark = SourceCooldown.authMark(source.configId)
        for (match in ordered.take(STREAM_ATTEMPTS)) {
            if (best != null) {
                // Past the first answer, only rows that could beat it: another
                // lossless one. An immersive answer is beaten by nothing.
                if (best.second.format.isDolbyAtmos || match.sourceQuality != ModuleSource.LOSSLESS) break
            }
            val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            // A row already refused for being the wrong file is not a source
            // that cannot serve: skip it and let the next row answer. Without
            // this the walk's re-ask of that source would open the same
            // refused row again — see [directStream].
            if (KoRefusedRows.refused(source.configId, trackId)) continue
            val opened = attempt(source, countFailure) { source.stream(trackId, request) }
            if (SourceCooldown.authMark(source.configId) != authMark) return best ?: settleFor
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
            // Dropped outright, not kept as a floor: a clip is not a lesser
            // copy of the recording, it is not the recording.
            if (checkFileLength && cutShort(source, match, stream, target)) continue
            val served = stream.format
            if (!wantsLossless || HqQuality.isLosslessTier(served) || served.isDolbyAtmos || served.statesNothingLossy) {
                TrackLog.d(
                    TAG,
                    "${source.displayName} matched '${match.title}' by '${match.artist}' " +
                        "id='${match.videoId}' album='${match.albumName ?: "?"}' " +
                        "duration=${match.durationText ?: "?"} explicit=${match.isExplicit ?: "?"} " +
                        "→ ${served.summary}",
                )
                if (!compareRows) return match to stream
                if (best == null || isBetter(stream.format, best.second.format)) best = match to stream
                continue
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
        return best ?: settleFor
    }

    /**
     * Whether the file behind [stream] is too small to hold the recording —
     * an addon's 30-second preview, or a cut-off upload, under a row that
     * claims the full runtime.
     *
     * The row cannot say so: it is the row that is wrong. The size of the file
     * can, at the cost of one header-only request — see [StreamLengthProbe].
     * Measured against YouTube's runtime when the queue carried one, the row's
     * otherwise, and with the listener's "Track length tolerance" as the bar.
     *
     * Answers no whenever it cannot measure — a manifest, a server that will
     * not say how big the file is — and leaves the rest to the decoded-length
     * check playback makes once the track is open.
     */
    private suspend fun cutShort(
        source: MusicSource,
        match: Song,
        stream: SourceStream,
        target: TrackMatcher.Target,
    ): Boolean {
        val expectedSec = target.durationSec ?: TrackMatcher.secondsOf(match.durationText) ?: return false
        if (StreamContainer.isManifest(stream.url)) return false
        val bytes = StreamLengthProbe.bytesOf(stream) ?: return false
        if (!StreamLengthProbe.tooShort(bytes, stream.format, expectedSec, upgradeSlackSec())) return false
        TrackLog.w(
            TAG,
            "${source.displayName} served ${bytes / 1024}kB of ${stream.format.summary} for " +
                "'${match.title}', too little to hold ${expectedSec}s — a preview or a cut-off file; looking further",
        )
        return true
    }

    /**
     * Whether [candidate] is a better rendition than [current], by codec first
     * and bitrate second. A null [current] is beaten by anything.
     *
     * The one rule for ranking two copies of the same recording, kept in one
     * place because three different walks now need it: [streamBest] choosing
     * between rows inside a source, [bestAcross] choosing between sources, and
     * [upgradeFor] choosing what to cut into a track that is already playing.
     *
     * Note that this is *not* [worthSwapping]. This asks which of two streams is
     * better; that one asks whether the difference is worth a break in the
     * audio, which is a much higher bar and only meaningful mid-playback.
     */
    internal fun isBetter(candidate: StreamFormat, current: StreamFormat?): Boolean =
        HqQuality.isBetter(candidate, current)

    /** The higher-quality of two streams — see [isBetter]. */
    private fun betterOf(current: SourceStream?, candidate: SourceStream): SourceStream =
        if (current == null || isBetter(candidate.format, current.format)) candidate else current

    /**
     * Whether a format has said nothing that rules lossless out.
     *
     * Unknown is not the same as lossy, and a source that reports neither a
     * codec nor a bitrate has not failed the request — it has declined to
     * describe it, and the decoder will say soon enough. A stated bitrate is
     * different: nothing states a bitrate for a FLAC.
     */
    private val StreamFormat.statesNothingLossy: Boolean
        get() = isLossless == null && kbps == null

    /**
     * Runs [block], turning any failure into null and a log line.
     *
     * Every call into a source is a call to somebody else's server, and a
     * source that throws must cost the *source* its turn, not the track its
     * playback. Cancellation is re-thrown: that is the caller giving up, and
     * swallowing it would keep walking sources for a track nobody is waiting
     * for any more.
     */
    private suspend fun <T> attempt(
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
                SourceCooldown.networkFailed(source.configId, online = !offlineCheck())
            } else {
                SourceCooldown.failed(source.configId)
            }
        }
        TrackLog.w(TAG, "${source.displayName} failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    internal fun isNetworkFault(failure: Throwable): Boolean = failure.isNetworkFailure() // [KO] network-fault

    /** Milliseconds left before [until], a [System.nanoTime] instant; never negative. */
    private fun remainingMs(until: Long): Long =
        ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(0)

    /**
     * How many answers per query are worth weighing.
     *
     * Wider than it needs to be for a well-behaved catalogue, because
     * [TrackMatcher.best] scores the whole list rather than taking the first
     * acceptable row: a backend that ranks the karaoke version, three covers
     * and a sped-up edit above the album cut still has the album cut in here
     * somewhere, and the extra rows cost one response body, not one request.
     */
    private const val MATCH_CANDIDATES = 15

    /**
     * How many of the matching rows are worth actually opening.
     *
     * Each one is a round trip to a stream endpoint, so this is the budget for
     * "the first copy wasn't the quality asked for" — enough to get past a
     * module whose lossless backend is down, not enough to spend a listener's
     * patience walking a whole result list.
     */
    private const val STREAM_ATTEMPTS = 3

    /**
     * How many further rows one source gets after the probe refused the one it
     * opened — see [directStream].
     *
     * Two, not the whole result list: enough to step over a catalogue that
     * files a broken copy first, without turning a source with five bad rows
     * into five walks. Each pass asks only the sources that were refused.
     */
    private const val REFUSED_ROW_RETRIES = 2

    /**
     * How far a replacement's runtime may sit from the playing track's before
     * it stops being the same recording.
     *
     * Far tighter than [TrackMatcher]'s own tolerance, and deliberately: that
     * one is deciding what to play, this one is deciding whether to cut the
     * audio a listener is in the middle of. Two seconds allows for a service
     * rounding a runtime differently and nothing else.
     */
    private const val UPGRADE_DRIFT_SEC = 2

    /**
     * How close two runtimes have to be to be the same cut of a song.
     *
     * Wide enough for a catalogue rounding, or a second of lead-in trimmed
     * differently. Narrow enough to separate the album track from the DJ edit
     * sitting next to it in the same search results under the same name.
     */
    private const val SAME_RECORDING_SEC = 3

    /**
     * The top of YouTube's own AAC ladder, and so the bar a lossy source has to
     * clear to be worth keeping as a file — see [forDownload].
     *
     * Not a measurement of any one track. Most of the catalogue offers itag 140
     * at 128kbps and a signed-in account reaches itag 141 at 256, and which of
     * those a given track has cannot be known without resolving it — a full
     * player walk, spent to answer a question about a stream we may then not
     * use. So the bar is the *best* YouTube could turn out to have: clearing it
     * means the source's copy wins whichever rung was waiting, and failing it
     * means YouTube might well be better and is certainly the more reliable
     * fetch. JioSaavn's 320 clears it; its 160 does not, and neither does a
     * module's 128kbps MP3 settle-for.
     */
    private const val YOUTUBE_BEST_AAC_KBPS = 256

    /**
     * How many kbps a lossy stream has to gain before it earns a seam in the
     * audio — see [worthSwapping].
     *
     * Sized off the two rates this actually decides between: YouTube's Opus,
     * which lands around 160, and a lossy module tier, which is 320. Anything
     * much smaller would start firing on differences no one can hear.
     */
    private const val UPGRADE_MIN_GAIN_KBPS = 96

    /**
     * How long a source may take before this track gives up on it.
     */
    // [KO] source-timeout

    /** How long a source's search answer is reused for the same track. */
    private const val SEARCH_MEMO_TTL_MS = 10 * 60 * 1000L
    private const val SEARCH_MEMO_MAX = 256
}
