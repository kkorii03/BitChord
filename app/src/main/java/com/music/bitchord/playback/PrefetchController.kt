package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The read-ahead half of playback: handing the cache the queue ahead of the
 * playing track, choosing which rendition to warm, and pre-resolving an
 * alternate when the next track is known to be unplayable.
 *
 * Extracted from [PlaybackService] without changing behaviour, log lines or
 * timing. The service owns the player and the queue; this class owns only the
 * prefetch jobs and reaches back through the small callbacks below.
 */
internal class PrefetchController(
    private val scope: CoroutineScope,
    private val currentPlayer: () -> ExoPlayer?,
    private val alternateLookupTimeoutMs: Long,
    private val permanentReason: (Throwable?) -> String?,
    private val swapToAlternate: (MediaItem, Song) -> MediaItem,
) {

    /** Catalogue lookup that decides which rendition of the next video to warm. */
    private var preferredPrefetchJob: Job? = null

    /** Logical queue request, before a video id is replaced by its audio counterpart. */
    private var preferredPrefetchRequest: Pair<Boolean, List<String>>? = null

    private var alternatePreResolveJob: Job? = null

    /** The next-track id [alternatePreResolveJob] is already working on. */
    private var alternatePreResolveFor: String? = null

    /**
     * Hands the cache the queue ahead of the one playing: [AudioCache.QUEUE_DEPTH]
     * tracks is more than it does anything with, but it decides that, not this.
     */
    fun prefetchAround(player: ExoPlayer) {
        val nextIndex = player.nextMediaItemIndex
        val upcomingSongs = if (nextIndex != C.INDEX_UNSET) {
            val end = (nextIndex + AudioCache.QUEUE_DEPTH - 1).coerceAtMost(player.mediaItemCount - 1)
            (nextIndex..end).map { index -> player.getMediaItemAt(index).toSong() }
        } else {
            emptyList()
        }
        // So whatever read-ahead writes for these can be named in the Cached
        // songs folder; its own requests carry nothing but an id.
        AudioCache.noteSongs(upcomingSongs)
        val preferAudio = AppSettings.preferMusicOnly.value
        val request = preferAudio to upcomingSongs.map { it.videoId }
        if (request == preferredPrefetchRequest) return
        preferredPrefetchRequest = request
        preferredPrefetchJob?.cancel()

        fun warm(songs: List<Song>) {
            AudioCache.prefetchQueue(
                songs.map { song ->
                    // The title, artist and runtime the item was built with —
                    // see [Song.toMediaItem]. Read from the queued item because
                    // read-ahead has no other way to reach this metadata.
                    AudioCache.Upcoming(
                        mediaId = song.videoId,
                        target = TrackMatcher.targetOf(song),
                    )
                },
            )
        }

        val next = upcomingSongs.firstOrNull()
        val ahead = if (preferAudio && next != null) {
            SongVersionPolicy.aheadSongVersion(
                isVideo = next.isVideo,
                typeUnknown = next.typeUnknown,
                hasVersion = YtMusicRepository.cachedSongVersionFor(next) != null,
                knownMissing = YtMusicRepository.isSongVersionMissing(next.videoId),
            )
        } else {
            SongVersionPolicy.AheadSongVersion.NOT_WANTED
        }

        // One track ahead: if read-ahead is about to discover a definitive
        // refusal, swap its alternate in now rather than at the transition.
        // It stands down while a version is undecided (or a found version has
        // not reached the queue yet) because the MV id is not the id that will
        // play — resolving it is what started a proxy race before first sound
        // (report 60).
        preResolveAlternateAhead(player)

        when (ahead) {
            SongVersionPolicy.AheadSongVersion.NOT_WANTED,
            SongVersionPolicy.AheadSongVersion.NONE -> {
                preferredPrefetchJob = null
                warm(upcomingSongs)
            }
            SongVersionPolicy.AheadSongVersion.FOUND -> {
                preferredPrefetchJob = null
                val version = next?.let(YtMusicRepository::cachedSongVersionFor)
                // A version row is audio and needs no wait; warming it rather
                // than the video id is what makes the eventual row swap land
                // on bytes already on disk.
                if (version == null || version.videoId == next?.videoId) {
                    warm(upcomingSongs)
                } else {
                    AudioCache.cancel()
                    warm(listOf(version) + upcomingSongs.drop(1))
                }
            }
            SongVersionPolicy.AheadSongVersion.UNDECIDED -> {
                val waiting = next ?: return
                // Do not warm the video's bytes while its catalogue lookup is
                // in flight: if that lookup succeeds, playback will ask for a
                // different media id and every byte spent on the video, and
                // any proxy race its resolve starts, would be wasted. An
                // unchanged result is the fallback and is warmed here too.
                AudioCache.cancel()
                preferredPrefetchJob = scope.launch {
                    val preferred = runCatching {
                        YtMusicRepository.songVersion(waiting, beforeAudio = true)
                    }
                        .onFailure {
                            TrackLog.d(
                                "BitChord",
                                "music-only queue warm-up fell back to video: ${it.message}",
                                about = waiting.videoId,
                            )
                        }
                        .getOrNull() ?: waiting
                    if (preferredPrefetchRequest != request) return@launch
                    warm(listOf(preferred) + upcomingSongs.drop(1))
                    // The answer is in, so the id that will actually play can
                    // now be checked for a definitive refusal.
                    if (preferred.videoId == waiting.videoId) preResolveAlternateAhead(player)
                }
            }
        }
    }

    /** Stops both rendition selection and byte read-ahead, and makes resume retry. */
    fun cancelPrefetch() {
        preferredPrefetchRequest = null
        preferredPrefetchJob?.cancel()
        preferredPrefetchJob = null
        AudioCache.cancel()
    }

    /**
     * Looks one track ahead for a definitive refusal and swaps its alternate
     * into the queue before playback reaches it.
     *
     * Read-ahead resolves the upcoming track anyway (see [AudioCache.prefetchQueue]),
     * and a refused id lands in [StreamResolver]'s verdict cache. This asks the
     * same question directly so the answer is acted on while there is still a
     * track playing, instead of being discovered at the transition and costing
     * the listener a gap. The resolve is coalesced with read-ahead's own, so
     * this adds no second walk.
     */
    private fun preResolveAlternateAhead(player: ExoPlayer) {
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) {
            alternatePreResolveJob?.cancel()
            alternatePreResolveFor = null
            return
        }
        val song = player.getMediaItemAt(nextIndex).toSong()
        // An undecided video's own id is not the recording that will play, and
        // a found version's id differs from this row's. Resolving either here
        // would be a walk (and possibly a proxy race) spent on an id the
        // answer replaces; the row is checked once its version is settled and
        // the queue has been rebuilt on that id.
        val ahead = if (AppSettings.preferMusicOnly.value) {
            SongVersionPolicy.aheadSongVersion(
                isVideo = song.isVideo,
                typeUnknown = song.typeUnknown,
                hasVersion = YtMusicRepository.cachedSongVersionFor(song) != null,
                knownMissing = YtMusicRepository.isSongVersionMissing(song.videoId),
            )
        } else {
            SongVersionPolicy.AheadSongVersion.NOT_WANTED
        }
        if (ahead == SongVersionPolicy.AheadSongVersion.UNDECIDED ||
            ahead == SongVersionPolicy.AheadSongVersion.FOUND
        ) {
            alternatePreResolveJob?.cancel()
            alternatePreResolveFor = null
            return
        }
        if (song.videoId == alternatePreResolveFor && alternatePreResolveJob?.isActive == true) return
        // Only YouTube ids are resolved here; local files and module tracks
        // have their own fallbacks and no catalogue counterpart to search for.
        if (song.videoId.isBlank() ||
            song.localUri != null ||
            song.videoId.startsWith("content://") ||
            song.videoId.startsWith("file://") ||
            SourceRegistry.parseTrackKey(song.videoId) != null
        ) {
            alternatePreResolveJob?.cancel()
            alternatePreResolveFor = null
            return
        }
        alternatePreResolveJob?.cancel()
        alternatePreResolveFor = song.videoId
        alternatePreResolveJob = scope.launch(TrackLog.about(song.videoId)) {
            val definitive = StreamResolver.isUnplayable(song.videoId) || withContext(Dispatchers.IO) {
                try {
                    StreamResolver.resolve(song.videoId)
                    false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    permanentReason(failure) != null
                }
            }
            if (!definitive) return@launch
            val alternate = withContext(Dispatchers.IO) {
                withTimeoutOrNull(alternateLookupTimeoutMs) {
                    YtMusicRepository.findAlternateRecording(song, setOf(song.videoId))
                }
            } ?: return@launch
            withContext(Dispatchers.Main) {
                val live = currentPlayer() ?: return@withContext
                val index = live.nextMediaItemIndex
                if (index == C.INDEX_UNSET || index !in 0 until live.mediaItemCount) return@withContext
                if (live.getMediaItemAt(index).mediaId != song.videoId) return@withContext
                TrackLog.w(
                    "BitChord",
                    "failure: ${song.videoId} unplayable -> alternate ${alternate.videoId} (${alternate.title})",
                    about = song.videoId,
                )
                live.replaceMediaItem(index, swapToAlternate(live.getMediaItemAt(index), alternate))
                // The queue's ids changed; the next prefetch must warm the
                // alternate rather than the id the replace left behind.
                preferredPrefetchRequest = null
            }
        }
    }
}
