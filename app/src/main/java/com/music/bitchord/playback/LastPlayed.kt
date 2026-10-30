package com.music.bitchord.playback

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.withVideoThumbnailFallback
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A bounded queue snapshot for restoring playback after process death.
 *
 * Queue contents are serialized only when Media3 reports that the playlist
 * changed. The frequently changing index and position are stored separately as
 * primitives, so progress sampling never walks or serializes the song list.
 */
object LastPlayed {

    class Snapshot(
        val songs: List<Song>,
        val index: Int,
        val positionMs: Long,
        /**
         * The order the queue held before it was shuffled, when shuffle was on
         * as this snapshot was written. Null otherwise; see
         * [QueueShuffle.restorePersisted].
         */
        val shuffleOrder: List<String>? = null,
    )

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    internal fun initForTesting(testPrefs: SharedPreferences) {
        prefs = testPrefs
    }

    internal fun window(size: Int, index: Int): IntRange {
        if (size <= 0) return IntRange.EMPTY
        val safeIndex = index.coerceIn(0, size - 1)
        val start = (safeIndex - MAX_QUEUE_HISTORY).coerceAtLeast(0)
        return start until (safeIndex + 1 + KEEP_AHEAD).coerceAtMost(size)
    }

    /** Persist at most 25 previous entries, the current one, and 50 upcoming entries. */
    fun saveQueue(songs: List<Song>, index: Int, positionMs: Long) {
        if (songs.isEmpty()) {
            clear()
            return
        }
        val safeIndex = index.coerceIn(songs.indices)
        val bounds = window(songs.size, safeIndex)
        val stored = StoredQueue(
            version = SNAPSHOT_VERSION,
            tracks = songs.subList(bounds.first, bounds.last + 1).map { StoredTrack.from(it) },
            shuffleOrder = QueueShuffle.persistedOrder(),
        )
        val encoded = runCatching {
            json.encodeToString(StoredQueue.serializer(), stored)
        }.getOrNull() ?: return
        prefs.edit()
            .putString(KEY_QUEUE, encoded)
            .putInt(KEY_INDEX, safeIndex - bounds.first)
            // The queue and the playhead belong to the same moment. Leaving the
            // old position here restored a freshly edited queue at wherever the
            // previous one happened to be.
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .apply()
    }

    /**
     * Atomically replaces the restart snapshot and waits until it reaches disk.
     * Used at an explicit queue boundary, where restoring the queue from before
     * that boundary would be worse than the small cost of a synchronous write.
     */
    fun saveQueueImmediately(songs: List<Song>, index: Int, positionMs: Long) {
        if (songs.isEmpty()) {
            clearImmediately()
            return
        }
        val safeIndex = index.coerceIn(songs.indices)
        val bounds = window(songs.size, safeIndex)
        val stored = StoredQueue(
            version = SNAPSHOT_VERSION,
            tracks = songs.subList(bounds.first, bounds.last + 1).map { StoredTrack.from(it) },
            shuffleOrder = QueueShuffle.persistedOrder(),
        )
        val encoded = runCatching {
            json.encodeToString(StoredQueue.serializer(), stored)
        }.getOrNull() ?: return
        prefs.edit()
            .putString(KEY_QUEUE, encoded)
            .putInt(KEY_INDEX, safeIndex - bounds.first)
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .commit()
    }

    /** Update only cheap scalar state; this performs no queue conversion or JSON work. */
    fun savePlaybackState(index: Int, positionMs: Long) {
        if (!prefs.contains(KEY_QUEUE)) return
        prefs.edit()
            .putInt(KEY_INDEX, index.coerceAtLeast(0))
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .apply()
    }

    fun load(): Snapshot? {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return null
        val stored = runCatching { json.decodeFromString<StoredQueue>(raw) }.getOrNull()
            ?: return null
        // Written before the music-video type was trustworthy: its `video`
        // flag can call a music video a song, so the whole snapshot is stale.
        if (stored.version < SNAPSHOT_VERSION) return null
        if (stored.tracks.isEmpty()) return null
        val songs = stored.tracks.map(StoredTrack::toSong)
        val index = if (prefs.contains(KEY_INDEX)) {
            prefs.getInt(KEY_INDEX, 0)
        } else {
            stored.legacyIndex ?: 0
        }
        val positionMs = if (prefs.contains(KEY_POSITION)) {
            prefs.getLong(KEY_POSITION, 0L)
        } else {
            stored.legacyPositionMs ?: 0L
        }
        return Snapshot(
            songs = songs,
            index = index.coerceIn(songs.indices),
            positionMs = positionMs.coerceAtLeast(0L),
            shuffleOrder = stored.shuffleOrder?.takeIf { it.isNotEmpty() },
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Remove a stale queue before another one is installed. */
    fun clearImmediately() {
        prefs.edit().clear().commit()
    }

    @Serializable
    private data class StoredQueue(
        /** Missing on snapshots written before ko-97; read as the oldest format. */
        val version: Int = 1,
        val tracks: List<StoredTrack>,
        /** Compatibility with queue snapshots written before scalar state was separated. */
        val index: Int? = null,
        /** Compatibility with queue snapshots written before scalar state was separated. */
        val positionMs: Long? = null,
        /**
         * The pre-shuffle running order, when shuffle was on. Optional so
         * snapshots written before this field existed still decode.
         */
        val shuffleOrder: List<String>? = null,
    ) {
        val legacyIndex: Int? get() = index
        val legacyPositionMs: Long? get() = positionMs
    }

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
        /**
         * Whether the recording came from a video upload even when it has
         * since been swapped to its catalogue release. Null on snapshots
         * written before this field existed, where [video] is the best answer
         * available.
         */
        val videoOrigin: Boolean? = null,
        val radio: String? = null,
        val source: String? = null,
        val sourceType: String? = null,
        val sourceId: String? = null,
        // A cover shown as its song (see CoverTitles), kept whole across a restart.
        val channelId: String? = null,
        val originalTitle: String? = null,
        val originalArtist: String? = null,
        val coverSinger: String? = null,
        val coverOriginalArtist: String? = null,
        val videoRadio: Boolean = false,
    ) {
        fun toSong(): Song {
            val resolvedTier = when (tier) {
                "USER_QUEUE" -> com.music.bitchord.data.model.QueueTier.USER_QUEUE
                "CONTEXT" -> com.music.bitchord.data.model.QueueTier.CONTEXT
                "AUTOPLAY" -> com.music.bitchord.data.model.QueueTier.AUTOPLAY
                else -> if (auto) com.music.bitchord.data.model.QueueTier.AUTOPLAY else com.music.bitchord.data.model.QueueTier.CONTEXT
            }
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
                artistId = channelId,
                originalTitle = originalTitle,
                originalArtist = originalArtist,
                coverSinger = coverSinger,
                coverOriginalArtist = coverOriginalArtist,
                videoRadio = videoRadio,
            ).withVideoThumbnailFallback()
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
                channelId = song.artistId,
                originalTitle = song.originalTitle,
                originalArtist = song.originalArtist,
                coverSinger = song.coverSinger,
                coverOriginalArtist = song.coverOriginalArtist,
                videoRadio = song.videoRadio,
            )
        }
    }

    /** 2: a `video` flag before ko-97 could be false for a music video. */
    private const val SNAPSHOT_VERSION = 2

    private const val KEEP_AHEAD = 50
    private const val PREFS_NAME = "bitchord_last_played"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
}
