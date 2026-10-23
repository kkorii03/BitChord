package com.music.bitchord.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists the user's personal queue, index, playhead, and playing state before
 * entering a Listen Together party, and restores it when the user leaves.
 *
 * Backed by SharedPreferences so the personal queue survives process death or
 * recreation while in a party.
 */
object PartyPersonalQueueStash {

    data class Snapshot(
        val songs: List<Song>,
        val index: Int,
        val positionMs: Long,
        val wasPlaying: Boolean,
    )

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun initForTesting(testPrefs: SharedPreferences) {
        prefs = testPrefs
    }

    /** Stash directly from a live player. */
    fun stashFromPlayer(player: Player): Boolean {
        if (!::prefs.isInitialized) return false
        if (player.mediaItemCount == 0) {
            // Player is empty; check if LastPlayed holds a restorable personal queue
            val fallback = LastPlayed.load()
            if (fallback != null && fallback.songs.isNotEmpty()) {
                stash(
                    songs = fallback.songs,
                    index = fallback.index,
                    positionMs = fallback.positionMs,
                    wasPlaying = false,
                )
                return true
            }
            return false
        }
        val all = (0 until player.mediaItemCount).map { idx -> player.getMediaItemAt(idx).toSong() }
        val songs = all.filterNot { it.isDeviceFile() }
        if (songs.isEmpty()) return false
        val safeIndex = stashIndexOf(all, player.currentMediaItemIndex)
        stash(
            songs = songs,
            index = safeIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            wasPlaying = player.playWhenReady,
        )
        return true
    }

    fun stash(songs: List<Song>, index: Int, positionMs: Long, wasPlaying: Boolean) {
        if (!::prefs.isInitialized || songs.isEmpty()) return
        val safeIndex = index.coerceIn(songs.indices)
        val stored = StoredQueue(
            version = SNAPSHOT_VERSION,
            tracks = songs.map { StoredTrack.from(it) },
            index = safeIndex,
            positionMs = positionMs.coerceAtLeast(0L),
            wasPlaying = wasPlaying,
        )
        val encoded = runCatching {
            json.encodeToString(StoredQueue.serializer(), stored)
        }.getOrNull() ?: return
        prefs.edit().putString(KEY_STASH, encoded).commit()
    }

    fun hasStash(): Boolean {
        if (!::prefs.isInitialized) return false
        return prefs.contains(KEY_STASH)
    }

    fun load(): Snapshot? {
        if (!::prefs.isInitialized) return null
        val raw = prefs.getString(KEY_STASH, null) ?: return null
        val stored = runCatching { json.decodeFromString<StoredQueue>(raw) }.getOrNull()
            ?: return null
        if (stored.version < SNAPSHOT_VERSION) return null
        if (stored.tracks.isEmpty()) return null
        val songs = stored.tracks.map(StoredTrack::toSong)
        return Snapshot(
            songs = songs,
            index = stored.index.coerceIn(songs.indices),
            positionMs = stored.positionMs.coerceAtLeast(0L),
            wasPlaying = stored.wasPlaying,
        )
    }

    fun clear() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(KEY_STASH).commit()
    }

    @Serializable
    private data class StoredQueue(
        /** Missing on stashes written before ko-97. */
        val version: Int = 1,
        val tracks: List<StoredTrack>,
        val index: Int = 0,
        val positionMs: Long = 0L,
        val wasPlaying: Boolean = false,
    )

    @Serializable
    private data class StoredTrack(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String? = null,
        val auto: Boolean = false,
        val tier: String? = null,
        val entryId: String? = null,
        val local: String? = null,
        val path: String? = null,
        val duration: String? = null,
        val album: String? = null,
        val explicit: Boolean? = null,
        val video: Boolean = false,
        /** Video origin, kept when a video row was swapped to its catalogue release. */
        val videoOrigin: Boolean? = null,
        val radio: String? = null,
        val source: String? = null,
        val sourceType: String? = null,
        val sourceId: String? = null,
    ) {
        fun toSong(): Song {
            val resolvedTier = tier?.let { runCatching { com.music.bitchord.data.model.QueueTier.valueOf(it) }.getOrNull() }
                ?: if (auto) com.music.bitchord.data.model.QueueTier.AUTOPLAY else com.music.bitchord.data.model.QueueTier.CONTEXT
            return Song(
                videoId = id,
                title = title,
                artist = artist,
                thumbnailUrl = artwork,
                durationText = duration,
                albumName = album,
                isExplicit = explicit,
                isVideo = video,
                isVideoOrigin = videoOrigin ?: video,
                queueTier = resolvedTier,
                queueEntryId = entryId,
                radioName = radio,
                playbackSource = source,
                playbackSourceType = sourceType?.let {
                    runCatching { com.music.bitchord.data.model.PlaybackSourceType.valueOf(it) }.getOrNull()
                },
                playbackSourceId = sourceId,
                localUri = local,
                localPath = path,
            )
        }

        companion object {
            fun from(song: Song) = StoredTrack(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                artwork = song.thumbnailUrl,
                auto = song.fromAutoplay,
                tier = song.queueTier.name,
                entryId = song.queueEntryId,
                local = song.localUri,
                path = song.localPath,
                duration = song.durationText,
                album = song.albumName,
                explicit = song.isExplicit,
                video = song.isVideo,
                videoOrigin = song.isVideoOrigin,
                radio = song.radioName,
                source = song.playbackSource,
                sourceType = song.playbackSourceType?.name,
                sourceId = song.playbackSourceId,
            )
        }
    }

    private fun Song.isDeviceFile(): Boolean =
        videoId.startsWith("content://") || videoId.startsWith("file://")

    /**
     * Where the stash's own entry zero lands in the filtered list.
     *
     * Device files are dropped on the way in, so the player's index no longer
     * names the same row: every removed file *before* it shifts it down by
     * one. Left uncorrected, a listener on the row after a local track came
     * back to a different song — and one far enough down was clamped to the
     * last entry instead of failing loudly.
     */
    internal fun stashIndexOf(songs: List<Song>, currentIndex: Int): Int {
        if (songs.isEmpty()) return 0
        val safeCurrent = currentIndex.coerceIn(0, songs.size - 1)
        val removedBefore = (0 until safeCurrent).count { songs[it].isDeviceFile() }
        val kept = songs.size - songs.count { it.isDeviceFile() }
        if (kept <= 0) return 0
        return (safeCurrent - removedBefore).coerceIn(0, kept - 1)
    }

    /** 2: keeps the music-video type and refuses stashes written without it. */
    private const val SNAPSHOT_VERSION = 2

    private const val PREFS_NAME = "bitchord_party_queue_stash"
    private const val KEY_STASH = "stashed_queue"
}
