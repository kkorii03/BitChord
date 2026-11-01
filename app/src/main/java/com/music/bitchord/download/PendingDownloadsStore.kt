package com.music.bitchord.download

import com.music.bitchord.data.StoredSongDisplay
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.withVideoThumbnailFallback
import com.music.bitchord.data.sources.HqTier
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The downloads waiting for the service, written to disk so a process death
 * does not lose a queue the listener already asked for.
 *
 * Only [Downloads] writes this; it is deliberately a small, self-contained
 * record with no Android types so the restart path can be exercised by a JVM
 * test. A corrupt record decodes as empty rather than taking the queue down.
 */
internal object PendingDownloadsStore {

    private const val KEY = "downloaded_pending"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingEntry.serializer())

    fun key(): String = KEY

    fun encode(entries: Collection<PendingEntry>): String =
        json.encodeToString(serializer, entries.toList())

    fun decode(raw: String?): List<PendingEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun storedSong(song: Song): StoredSong = StoredSong.from(song)

    fun restoredSong(stored: StoredSong): Song = stored.toSong()

    fun storedHq(hq: HqDownloadSource): StoredHq = StoredHq.from(hq)

    fun restoredHq(stored: StoredHq): HqDownloadSource? = stored.toSource()

    @Serializable
    internal data class PendingEntry(
        val song: StoredSong,
        val quality: String? = null,
        val hq: StoredHq? = null,
        val avoidSource: String? = null,
        val forceReplace: Boolean = false,
        val youtubeOnly: Boolean = false,
        val minimumTier: String? = null,
        val automatic: Boolean = false,
        val mvSoundRepair: Boolean = false,
    )

    @Serializable
    internal data class StoredHq(
        val sourceConfigId: String,
        val trackId: String,
        val tier: String,
        val offsetMs: Long = 0L,
        val rate: Double = 1.0,
        val sourceTitle: String? = null,
        val sourceArtist: String? = null,
    ) {
        fun toSource(): HqDownloadSource? {
            val resolvedTier = runCatching { HqTier.valueOf(tier) }.getOrNull() ?: return null
            return HqDownloadSource(
                sourceConfigId = sourceConfigId,
                trackId = trackId,
                tier = resolvedTier,
                offsetMs = offsetMs,
                rate = rate,
                sourceTitle = sourceTitle,
                sourceArtist = sourceArtist,
            )
        }

        companion object {
            fun from(source: HqDownloadSource) = StoredHq(
                sourceConfigId = source.sourceConfigId,
                trackId = source.trackId,
                tier = source.tier.name,
                offsetMs = source.offsetMs,
                rate = source.rate,
                sourceTitle = source.sourceTitle,
                sourceArtist = source.sourceArtist,
            )
        }
    }

    @Serializable
    internal data class StoredSong(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String? = null,
        val duration: String? = null,
        val album: String? = null,
        val explicit: Boolean? = null,
        val video: Boolean = false,
        val videoOrigin: Boolean? = null,
        val typeUnknown: Boolean = false,
        val tier: String? = null,
        val entryId: String? = null,
        val local: String? = null,
        val path: String? = null,
        val radio: String? = null,
        val source: String? = null,
        val sourceType: String? = null,
        val sourceId: String? = null,
        val artistId: String? = null,
        val uploaderChannelId: String? = null,
        val originalTitle: String? = null,
        val originalArtist: String? = null,
        val coverSinger: String? = null,
        val coverOriginalArtist: String? = null,
        val videoRadio: Boolean = false,
    ) {
        fun toSong(): Song {
            val resolvedTier = when (tier) {
                QueueTier.USER_QUEUE.name -> QueueTier.USER_QUEUE
                QueueTier.CONTEXT.name -> QueueTier.CONTEXT
                QueueTier.AUTOPLAY.name -> QueueTier.AUTOPLAY
                else -> QueueTier.CONTEXT
            }
            return StoredSongDisplay.clean(
                Song(
                    videoId = id,
                    title = title,
                    artist = artist,
                    thumbnailUrl = artwork,
                    durationText = duration,
                    albumName = album,
                    isExplicit = explicit,
                    isVideo = video,
                    isVideoOrigin = videoOrigin ?: video,
                    typeUnknown = typeUnknown,
                    queueTier = resolvedTier,
                    queueEntryId = entryId,
                    radioName = radio,
                    playbackSource = source,
                    playbackSourceType = sourceType?.let {
                        runCatching { PlaybackSourceType.valueOf(it) }.getOrNull()
                    },
                    playbackSourceId = sourceId,
                    localUri = local,
                    localPath = path,
                    artistId = artistId,
                    channelId = uploaderChannelId,
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                    coverSinger = coverSinger,
                    coverOriginalArtist = coverOriginalArtist,
                    videoRadio = videoRadio,
                ),
            ).withVideoThumbnailFallback()
        }

        companion object {
            fun from(song: Song) = StoredSong(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                artwork = song.thumbnailUrl,
                duration = song.durationText,
                album = song.albumName,
                explicit = song.isExplicit,
                video = song.isVideo,
                videoOrigin = song.isVideoOrigin,
                typeUnknown = song.typeUnknown,
                tier = song.queueTier.name,
                entryId = song.queueEntryId,
                local = song.localUri,
                path = song.localPath,
                radio = song.radioName,
                source = song.playbackSource,
                sourceType = song.playbackSourceType?.name,
                sourceId = song.playbackSourceId,
                artistId = song.artistId,
                uploaderChannelId = song.channelId,
                originalTitle = song.originalTitle,
                originalArtist = song.originalArtist,
                coverSinger = song.coverSinger,
                coverOriginalArtist = song.coverOriginalArtist,
                videoRadio = song.videoRadio,
            )
        }
    }
}
