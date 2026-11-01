package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A source that has the same recording as a YouTube row which this network
 * refuses, found under a deadline instead of after the first note.
 *
 * [SourceResolver.substituteForYouTube] deliberately never searches before
 * playback: on a playable track a catalogue walk would compete for the radio
 * and delay the first note. A *blocked* track has no first note to protect, so
 * this is the one place the live path may spend a bounded search before sound.
 * The result is matched by [TrackMatcher] (title, artist, runtime, original
 * arrangement) exactly as the upgrade path matches; the existing
 * `hq-verify` comparison still runs afterwards, during playback, so a cover
 * that slipped through can be refused by the same rule as any upgrade.
 *
 * Null is a normal answer: the caller moves on to the next row.
 */
internal suspend fun blockedStartStream(
    target: TrackMatcher.Target,
    timeoutMs: Long,
): SourceStream? {
    if (target.title.isBlank() || target.isVideo || timeoutMs <= 0L) return null
    val active = SourceRegistry.activeForPlayback()
    val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
    val candidates = automaticUpgradeCandidates(
        active.take(active.indexOfFirst { it.configId == youtube.configId }),
        target,
    )
    if (candidates.isEmpty()) return null
    val found = withTimeoutOrNull(timeoutMs) {
        SourceResolver.bestAcross(
            candidates,
            target,
            SourceResolver.requestForNow(),
            waitForAll = false,
            strictLength = target.durationSec != null,
            requireSharedArtist = true,
            strictOriginal = true,
        )
    } ?: return null
    TrackLog.d(
        "BitChord",
        "blocked start: '${target.title}' served by ${found.first.displayName} " +
            "at ${found.second.format.summary}",
    )
    return found.second
}

/**
 * [SourceResolver.matchAndStream] under the same per-source deadline the
 * races use.
 *
 * The sequential walks — the stream upgrade in `resolve` and the bit-exact
 * download ladder in `forDownload` — call `matchAndStream` directly rather
 * than through `bestAcross`. A cold TDLib session can spend its whole
 * search window just opening, so without this bound those walks would hold
 * the client lease and the first note for as long as TDLib takes to open.
 * Null means this source ran out of time and the caller steps over it
 * exactly as it would a miss.
 */
internal suspend fun matchAndStreamWithin(
    source: MusicSource,
    target: TrackMatcher.Target,
    request: StreamRequest,
    waitForAll: Boolean = false,
    strictLength: Boolean = false,
    strictOriginal: Boolean = false,
    baseTimeoutMs: Long = SOURCE_LOOKUP_TIMEOUT_MS,
): SourceResolver.SearchOutcome? {
    val timeoutMs = sourceLookupTimeoutMsFor(source, baseTimeoutMs)
    val answer = withTimeoutOrNull(timeoutMs) {
        SourceResolver.matchAndStreamForKo(
            source,
            target,
            request,
            waitForAll = waitForAll,
            strictLength = strictLength,
            strictOriginal = strictOriginal,
        )
    }
    if (answer == null) {
        // A timeout while the device is offline is the connection, not the
        // source — the same rule `bestAcross` follows. Telegram's cold
        // session is not a failure either; see [KoSourceCooldown.timedOut].
        SourceResolver.SourceCooldown.timedOut(
            source.configId,
            source.kind,
            online = !SourceResolver.offlineCheck(),
        )
        TrackLog.d("BitChord", "${source.displayName} timed out after ${timeoutMs}ms; skipping for this track")
        TrackLog.d(
            "BitChord",
            hqSearchLine(target, source, SourceResolver.SearchOutcome(verdict = "timeout", conclusive = false)),
        )
    } else if (answer.stream != null) {
        SourceResolver.SourceCooldown.succeeded(source.configId)
    }
    return answer
}

/**
 * Open the exact pair a sound comparison already accepted.
 *
 * The ordinary download path searches again, which can find a different
 * row under the same title and make the replacement a different recording
 * from the one that was proved. The automatic high-quality pass therefore
 * pins both ids: [sourceConfigId] and [trackId] are the pair stored with
 * the verdict, and this asks only that source for that row.
 *
 * Null when the source is disabled, no longer configured, is YouTube
 * itself, or cannot serve the row right now. The caller keeps the old file
 * rather than falling back to an unverified search.
 */
internal suspend fun forDownloadVerified(
    sourceConfigId: String,
    trackId: String,
    request: StreamRequest = SourceResolver.requestForDownload(),
): SourceStream? {
    if (sourceConfigId.isBlank() || trackId.isBlank()) return null
    val source = SourceRegistry.activeForDownload()
        .firstOrNull { it.configId == sourceConfigId }
        ?: return null
    if (source.kind == SourceKind.YOUTUBE) return null
    if (SourceResolver.SourceCooldown.isResting(source.configId)) return null
    val opened = SourceResolver.attemptForKo(source) { source.stream(trackId, request) } ?: return null
    SourceResolver.SourceCooldown.succeeded(source.configId)
    return opened.copy(sourceConfigId = source.configId, trackId = trackId)
}
