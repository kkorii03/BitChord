package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import java.util.concurrent.ConcurrentHashMap

/**
 * Search answers already in hand, so the same source is not asked the same
 * question again for the same track — the live path, the ahead-of-playback
 * pass and the second look all arrive at the same query.
 */
internal object KoSourceSearchMemo {
    internal data class SearchKey(
        val sourceId: String,
        val target: String,
        val request: String,
        val waitForAll: Boolean,
        val query: String,
    )

    internal class SearchAnswer(val rows: List<Song>, val at: Long)

    internal val searchAnswers = ConcurrentHashMap<SearchKey, SearchAnswer>()

    /**
     * Resting sources already given their one manual probe, per request.
     *
     * The key carries the caller's [manualSession] identity, so this says
     * nothing across two presses of the button: a fresh request is entitled to
     * probe a resting source once again.
     */
    internal val manualRestingProbes = ConcurrentHashMap.newKeySet<String>()

    internal fun manualProbeKey(session: Any, sourceId: String, target: TrackMatcher.Target): String =
        System.identityHashCode(session).toString() + '\u0001' + sourceId + '\u0001' + targetKey(target)

    /**
     * Releases the resting-probe budget of one finished manual request.
     *
     * The set otherwise outlives the request it describes and every press
     * leaves a source/target/key string behind for the life of the process.
     */
    internal fun clearManualProbes(session: Any) {
        val prefix = System.identityHashCode(session).toString() + '\u0001'
        manualRestingProbes.removeIf { it.startsWith(prefix) }
    }

    /** Releases every manual request's probe budget — see [clearManualProbes]. */
    internal fun clearManualProbes() {
        manualRestingProbes.clear()
    }

    /** A substitute stream already judged to be the original recording. */
    private class Remembered(val stream: SourceStream, val at: Long)

    private val remembered = ConcurrentHashMap<String, Remembered>()

    internal fun clearSearchAnswers() {
        searchAnswers.clear()
        remembered.clear()
        manualRestingProbes.clear()
    }

    /** Forgets one source's memoised searches without touching any other source. */
    internal fun clearSearchAnswersForSource(configId: String) {
        searchAnswers.keys.removeIf { it.sourceId == configId }
    }

    /** Keeps [stream] as the verified original for [target], for a later play. */
    fun rememberOriginal(target: TrackMatcher.Target, stream: SourceStream) {
        if (target.title.isBlank()) return
        val now = System.currentTimeMillis()
        remembered.entries.removeIf { now - it.value.at > REMEMBERED_TTL_MS }
        if (remembered.size >= REMEMBERED_MAX) {
            remembered.minByOrNull { it.value.at }?.let { remembered.remove(it.key) }
        }
        remembered[targetKey(target)] = Remembered(stream, now)
    }

    /** The verified original already found for [target], if it is still fresh. */
    fun rememberedOriginal(target: TrackMatcher.Target): SourceStream? {
        val key = targetKey(target)
        val entry = remembered[key] ?: return null
        if (System.currentTimeMillis() - entry.at > REMEMBERED_TTL_MS) {
            remembered.remove(key)
            return null
        }
        return entry.stream
    }

    internal fun targetKey(target: TrackMatcher.Target): String = listOf(
        target.title,
        target.artist,
        target.durationSec,
        target.album,
        target.isExplicit,
        target.isVideo,
    ).joinToString("\u0001")

    internal fun requestKey(request: StreamRequest): String = when (request) {
        is StreamRequest.Lossless -> "lossless"
        is StreamRequest.Best -> "best"
        is StreamRequest.Capped -> "capped:${request.maxKbps}"
    }

    /** How long a verified original is kept for a later play of the same track. */
    private const val REMEMBERED_TTL_MS = 30 * 60 * 1000L
    private const val REMEMBERED_MAX = 64
}
