package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher

/** The one line that says why a source did or did not supply this track. */
internal fun hqSearchLine(
    target: TrackMatcher.Target,
    source: MusicSource,
    outcome: SourceResolver.SearchOutcome,
): String {
    val best = outcome.best?.let {
        "${it.title}/${it.artist}/${it.durationText ?: "?"}"
    } ?: "-"
    return "hq-search: '${target.title}' ${source.displayName} " +
        "results=${outcome.results} best=$best verdict=${outcome.verdict}"
}

/**
 * How the source race ranks the kinds of row it can be offered, lowest
 * first: a normally credited match, then a row still waiting for the sound
 * comparison (a localised credit or a localised title), then a verified copy
 * that only opened below the request, and last an unverified row that also
 * only opened below it.
 *
 * Request satisfaction outranks artist verification: a copy that actually
 * serves what was asked for must not lose to a settle-for merely because
 * the latter's catalogue localised the credit. Otherwise a lossy localised
 * fallback could discard the verified copy hq-verify would have accepted.
 */
internal fun offerRank(verdict: String, stream: SourceStream): Int = when {
    verdict in UNVERIFIED_VERDICTS && stream.belowRequest ->
        ARTIST_UNVERIFIED_BELOW_REQUEST_RANK
    verdict in UNVERIFIED_VERDICTS -> ARTIST_UNVERIFIED_RANK
    stream.belowRequest -> BELOW_REQUEST_RANK
    else -> MATCH_RANK
}

/**
 * The word for a row that is only waiting for the sound comparison, named by
 * the signal that is missing: a localised credit or a localised title. Both
 * are ranked and gated the same way; the log has to say which one it was.
 */
internal fun unverifiedVerdict(row: Song, target: TrackMatcher.Target): String =
    if (TrackMatcher.isArtistUnverifiedOriginal(row, target, TrackMatcher.ARTIST_UNVERIFIED_DRIFT_SEC)) {
        "artist-unverified"
    } else {
        "title-unverified"
    }

/** The verdicts that mean "offer this to the sound comparison". */
private val UNVERIFIED_VERDICTS = setOf("artist-unverified", "title-unverified")

/** Whether [rank] is one of the localised-row tiers; see [offerRank]. */
internal fun isArtistUnverifiedRank(rank: Int): Boolean =
    rank == ARTIST_UNVERIFIED_RANK || rank == ARTIST_UNVERIFIED_BELOW_REQUEST_RANK

/** How far an opened stream's row sits from the requested runtime. */
internal fun runtimeDrift(stream: SourceStream, target: TrackMatcher.Target): Int {
    val wanted = target.durationSec ?: return 0
    val got = stream.durationSec ?: return 0
    return kotlin.math.abs(wanted - got)
}

/** Whether [this] is a deadline rather than an answer; see [KoSourceCooldown.timedOut]. */
internal fun Throwable.isTimeoutFailure(): Boolean {
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth++ < 8) {
        if (cause is java.net.SocketTimeoutException ||
            cause is java.io.InterruptedIOException ||
            cause is java.util.concurrent.TimeoutException
        ) {
            return true
        }
        cause = cause.cause?.takeIf { it !== cause }
    }
    val text = message?.lowercase() ?: return false
    return text.contains("timed out") || text.contains("timeout") || text.contains("time-out")
}

internal fun Throwable.isNetworkFailure(): Boolean {
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth++ < 8) {
        if (cause is java.net.UnknownHostException ||
            cause is java.net.ConnectException ||
            cause is java.net.NoRouteToHostException ||
            cause is java.net.SocketTimeoutException ||
            cause is java.io.InterruptedIOException ||
            cause is java.util.concurrent.TimeoutException ||
            cause is javax.net.ssl.SSLException
        ) {
            return true
        }
        cause = cause.cause?.takeIf { it !== cause }
    }
    val text = message?.lowercase() ?: return false
    return NETWORK_FAILURE_MARKERS.any(text::contains)
}

private val NETWORK_FAILURE_MARKERS = listOf(
    "unable to resolve host",
    "no address associated with hostname",
    "failed to connect",
    "connection refused",
    "network is unreachable",
    "no route to host",
    "connection reset",
    "reset by peer",
    "broken pipe",
    "timed out",
    "timeout",
    "econnrefused",
    "ehostunreach",
    "enetunreach",
)

/** The source race's ordering of row kinds; see [offerRank]. */
private const val MATCH_RANK = 0
private const val ARTIST_UNVERIFIED_RANK = 1
private const val BELOW_REQUEST_RANK = 2
private const val ARTIST_UNVERIFIED_BELOW_REQUEST_RANK = 3

/**
 * How long one source may take before this track gives up on it.
 *
 * Sources are asked in parallel, so this is a ceiling rather than a sum.
 * A source that needs longer is not lost for good: it is simply not part
 * of this track's answer, and the next track asks it again.
 */
internal const val SOURCE_LOOKUP_TIMEOUT_MS = 4_000L

/** Enough for a cold TDLib session plus one search on the Telegram source. */
private const val TELEGRAM_LOOKUP_TIMEOUT_MS = 15_000L

/**
 * A hand-made upgrade is the listener waiting for one answer, so its
 * per-source window is three times the live path's. The automatic path
 * keeps [SOURCE_LOOKUP_TIMEOUT_MS]: preparing the next track or improving
 * one mid-playback must not spend twelve seconds per source.
 */
private const val MANUAL_SOURCE_LOOKUP_TIMEOUT_MS = 12_000L

/**
 * A resting source's one manual probe. It was parked for failing, so it
 * gets one short chance beside the healthy sources rather than holding the
 * whole request open for the full manual window.
 */
internal const val MANUAL_RESTING_PROBE_TIMEOUT_MS = 4_000L

internal fun sourceLookupTimeoutMs(
    manual: Boolean,
    configured: Long = SOURCE_LOOKUP_TIMEOUT_MS,
): Long = if (manual) MANUAL_SOURCE_LOOKUP_TIMEOUT_MS else configured

/**
 * The timeout for one source in a race.
 *
 * Telegram keeps its client shut between requests, so an automatic search
 * pays the session open before its first TDLib query. Giving it the ordinary
 * four-second window made the upgrade path give up while the client was
 * still starting; the extra time is bounded to this source so a slow addon
 * still loses the race as before.
 */
internal fun sourceLookupTimeoutMsFor(source: MusicSource, base: Long): Long =
    if (source.kind == SourceKind.TELEGRAM) maxOf(base, TELEGRAM_LOOKUP_TIMEOUT_MS) else base

/**
 * The same ceiling for a download, where nobody is waiting on a first note
 * and a module's patient window is worth using.
 */
internal const val DOWNLOAD_SOURCE_TIMEOUT_MS = 25_000L
