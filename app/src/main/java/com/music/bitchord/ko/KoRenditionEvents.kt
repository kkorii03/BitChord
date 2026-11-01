package com.music.bitchord.ko

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.AudioCache
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [KO] rendition-completion
 *
 * Cache completion and playback-progress events kept outside the upstream
 * AudioCache file. The cache itself is read through a provider so this stays
 * independent of how the upstream cache is opened.
 */
class KoRenditionEvents(
    private val cache: () -> Cache?,
    private val renditionFor: (String) -> AudioCache.Rendition?,
    private val renditionKeysFor: (String) -> List<String>,
) {
    private val tag = "BitChord"

    private val _renditionCompleted = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val renditionCompleted: SharedFlow<String> = _renditionCompleted.asSharedFlow()

    private val _completedRenditions = MutableStateFlow<Set<String>>(emptySet())
    val completedRenditions: StateFlow<Set<String>> = _completedRenditions.asStateFlow()

    /** Cache keys whose completion listener is installed, so each is watched once. */
    private val watchedCompletionKeys = ConcurrentHashMap.newKeySet<String>()

    /** Keys already reported complete, so adding more spans does not repeat the event. */
    private val completedKeys = ConcurrentHashMap.newKeySet<String>()

    private val _served = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val served: SharedFlow<String> = _served.asSharedFlow()

    /** How many bytes playback has read per video, so a wake-up is not sent for every buffer. */
    private val playedProgress = ConcurrentHashMap<String, Long>()

    fun watch(videoId: String, altSuffix: String) {
        if (cache() == null) return
        watchKey(videoId)
        watchKey("$videoId$altSuffix")
        renditionKeysFor(videoId).forEach(::watchKey)
    }

    fun watchKey(key: String) {
        val currentCache = cache() ?: return
        if (!watchedCompletionKeys.add(key)) return
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                val rendition = renditionFor(key) ?: return
                if (!rendition.isComplete) return
                if (!completedKeys.add(key)) return
                val mediaId = key.substringBefore('#')
                _completedRenditions.update { it + mediaId }
                // Only remember it as delivered when the event actually reached
                // the buffer. A full 16-slot buffer during bulk writes must not
                // mark the completion done and leave the AI button hidden.
                if (!_renditionCompleted.tryEmit(mediaId)) {
                    completedKeys.remove(key)
                }
            }

            override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
                // The entry (or one of its spans) is gone; a later rebuild of
                // the same key must be allowed to report completion again.
                completedKeys.remove(key)
                // Other renditions of the same media (the plain key, the
                // alternate, an older quality) may still hold the whole file.
                // Removing the media id just because this one key lost a span
                // hid AI timing on later plays, and nothing re-added it.
                val mediaId = key.substringBefore('#')
                val stillComplete = completionSurvivesSpanRemoval(
                    removedKey = key,
                    remainingKeys = renditionKeysFor(mediaId),
                ) { otherKey -> renditionFor(otherKey)?.isComplete == true }
                _completedRenditions.update { current ->
                    if (stillComplete) current + mediaId else current - mediaId
                }
            }

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        }
        runCatching { currentCache.addListener(key, listener) }
            .onSuccess {
                // The copy may already be complete from before this listener
                // existed (lyrics arriving late). State must hold that answer:
                // no span-added event will ever be delivered for it.
                val rendition = renditionFor(key) ?: return@onSuccess
                if (!rendition.isComplete || !completedKeys.add(key)) return@onSuccess
                val mediaId = key.substringBefore('#')
                _completedRenditions.update { it + mediaId }
                _renditionCompleted.tryEmit(mediaId)
            }
            .onFailure {
                watchedCompletionKeys.remove(key)
                TrackLog.w(tag, "could not watch cache completion for $key: ${it.message}")
            }
    }

    fun noteServed(videoId: String) {
        _served.tryEmit(videoId)
    }

    fun notePlayedBytes(videoId: String, bytes: Int) {
        if (bytes <= 0) return
        val after = playedProgress.merge(videoId, bytes.toLong(), Long::plus) ?: return
        val before = after - bytes
        if (before / PROGRESS_STEP_BYTES != after / PROGRESS_STEP_BYTES) _served.tryEmit(videoId)
    }

    companion object {
        private const val PROGRESS_STEP_BYTES = 256L * 1024

        /**
         * Whether losing [removedKey] still leaves another complete rendition of
         * the same media.
         *
         * A media id has several cache keys (plain, subtitle, alternate qualities).
         * Completion is a property of the media, not of whichever key happened to
         * fire the removal, so the id may only be forgotten when no other key
         * remains whole.
         */
        fun completionSurvivesSpanRemoval(
            removedKey: String,
            remainingKeys: List<String>,
            isComplete: (String) -> Boolean,
        ): Boolean = remainingKeys.any { it != removedKey && isComplete(it) }
    }
}
