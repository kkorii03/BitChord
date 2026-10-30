package com.music.bitchord.playback

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.videoradio.AutoplayMode
import com.music.bitchord.data.videoradio.SeedVideoInfo
import com.music.bitchord.data.videoradio.VideoCandidate
import com.music.bitchord.data.videoradio.VideoFilterOptions
import com.music.bitchord.data.videoradio.VideoFilterResult
import com.music.bitchord.data.videoradio.VideoSource
import com.music.bitchord.data.videoradio.autoplayModeFor
import com.music.bitchord.data.videoradio.filterVideoCandidates
import com.music.bitchord.data.videoradio.orderVideoCandidates
import com.music.bitchord.data.videoradio.songKeyOf

/**
 * The bridge between a [Song] in the queue and the pure video-radio rules.
 *
 * A cover row keeps its upload title and channel in [Song.originalTitle] and
 * [Song.originalArtist], while the row itself reads as the song and singer.
 * The mode decision needs the upload's own facts, so those fields win when
 * they are present. A row already tagged [Song.videoRadio] is a video song
 * even if a catalogue match later filled in a square thumbnail.
 */
fun videoRadioSeedInfo(song: Song): SeedVideoInfo {
    val uploadTitle = song.originalTitle?.takeIf { it.isNotBlank() } ?: song.title
    val channel = song.originalArtist?.takeIf { it.isNotBlank() } ?: song.artist
    val artist = song.coverOriginalArtist?.takeIf { it.isNotBlank() }
        ?: song.originalArtist?.takeIf { it.isNotBlank() }
        ?: song.artist
    return SeedVideoInfo(
        videoId = song.videoId,
        title = uploadTitle,
        artist = artist,
        channelName = channel,
        isVideo = song.isVideo || song.videoRadio,
        // A YouTube Music row that already names a release is the catalogue
        // side of the video, not a fan upload. Cover rows never carry one.
        isOfficialArtistChannel = !song.videoRadio &&
            song.albumId != null &&
            song.originalTitle == null,
    )
}

/** Whether [song] should continue as a video-song station. */
fun videoRadioModeFor(song: Song): AutoplayMode = autoplayModeFor(videoRadioSeedInfo(song))

/** Whether the setting is on and [song] selects the video-song world. */
fun videoRadioEnabledFor(song: Song): Boolean =
    AppSettings.videoRadio.value && videoRadioModeFor(song) == AutoplayMode.VIDEO_RADIO

/**
 * Video candidates are fetched once the queue has at most three rows ahead.
 *
 * [com.music.bitchord.data.net.autoplayTopUpNeeded] compares with `<`, so the
 * value passed as its minimum is one above the design's threshold.
 */
const val VIDEO_RADIO_MIN_QUEUED_AHEAD = 4

/** The underlying song key used to stop two covers of one song in a row. */
fun videoRadioSongKey(song: Song): String =
    songKeyOf(CoverTitles.read(song)?.title ?: song.originalTitle ?: song.title)

/**
 * The radio rows as video candidates.
 *
 * No category is invented here: a youtube.com row does not state one, and
 * stamping every row "Music" would defeat the song-marker filter. The shared
 * filter therefore judges these rows on their real title and channel. Rows
 * known to have come from the seed's video mix or channel are uploads even
 * when the web renderer could not confirm a wide thumbnail.
 */
fun videoRadioCandidates(
    songs: List<Song>,
    seed: SeedVideoInfo,
    sources: Map<String, VideoSource> = emptyMap(),
): List<VideoCandidate> = songs.mapNotNull { song ->
    if (song.videoId.isBlank()) return@mapNotNull null
    val rawTitle = song.originalTitle?.takeIf { it.isNotBlank() } ?: song.title
    val channel = song.originalArtist?.takeIf { it.isNotBlank() } ?: song.artist
    val singer = song.coverSinger?.takeIf { it.isNotBlank() }
        ?: CoverTitles.read(song)?.singer?.takeIf { it.isNotBlank() }
        ?: song.artist
    val source = sources[song.videoId] ?: when {
        channel.equals(seed.channelName, ignoreCase = true) -> VideoSource.CHANNEL
        song.isVideo -> VideoSource.MIX
        else -> VideoSource.RADIO
    }
    VideoCandidate(
        videoId = song.videoId,
        title = rawTitle,
        channelName = channel,
        singer = singer,
        durationSec = TrackMatcher.secondsOf(song.durationText),
        isVideoUpload = song.isVideo || song.videoRadio ||
            source == VideoSource.MIX || source == VideoSource.CHANNEL,
        songKey = songKeyOf(CoverTitles.read(song)?.title ?: rawTitle),
        source = source,
    )
}

/** What one video-radio pass found, ready for the log line and the queue. */
data class VideoRadioPick(
    val mode: AutoplayMode,
    val seed: SeedVideoInfo,
    val filter: VideoFilterResult,
    val ordered: List<VideoCandidate>,
) {
    val from: String
        get() = ordered.groupingBy { it.source }.eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.wire
            ?: VideoSource.RADIO.wire

    /** The one-line debug summary the phone log is read for. */
    fun logLine(fallback: String): String =
        "video-radio: seed=${seed.videoId} mode=${mode.wire()} picked=${ordered.size} " +
            "from=$from dropped=${filter.droppedSummary()} fallback=$fallback"
}

private fun AutoplayMode.wire(): String = when (this) {
    AutoplayMode.VIDEO_RADIO -> "video"
    AutoplayMode.NORMAL -> "music"
}

/**
 * Filters and orders the radio rows for a video station.
 *
 * The recent window is the last fifty rows the queue already holds, including
 * the seed. [previousSong] is the song that just finished, so another cover of
 * it is not placed immediately after.
 */
fun pickVideoRadio(
    seedSong: Song,
    radio: List<Song>,
    existing: List<Song>,
    previousSong: Song? = null,
    sources: Map<String, VideoSource> = emptyMap(),
): VideoRadioPick {
    val seed = videoRadioSeedInfo(seedSong)
    val recent = existing.takeLast(RECENT_VIDEO_WINDOW)
    val options = VideoFilterOptions(
        recentVideoIds = (recent.map { it.videoId } + seedSong.videoId).toSet(),
        recentSongKeys = recent.mapTo(HashSet(), ::videoRadioSongKey),
        previousSongKey = previousSong?.let(::videoRadioSongKey)
            ?: videoRadioSongKey(seedSong),
    )
    val filter = filterVideoCandidates(
        mode = AutoplayMode.VIDEO_RADIO,
        seed = seed,
        candidates = videoRadioCandidates(radio, seed, sources),
        options = options,
    )
    return VideoRadioPick(
        mode = AutoplayMode.VIDEO_RADIO,
        seed = seed,
        filter = filter,
        ordered = orderVideoCandidates(filter.kept, seed.channelName),
    )
}

/** The design's recent-video window for the same-song rule. */
private const val RECENT_VIDEO_WINDOW = 50
