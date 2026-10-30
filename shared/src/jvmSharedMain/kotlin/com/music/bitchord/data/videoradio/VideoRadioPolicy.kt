package com.music.bitchord.data.videoradio

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.DownloadHealthPolicy
import com.music.bitchord.data.sources.TrackMatcher
import java.text.Normalizer
import java.util.Locale

/**
 * Which automatic-play rules a seed track selects.
 *
 * A video song (a cover, a live clip, a self-uploaded song video) has to keep
 * feeding other video songs; an ordinary catalogue track must not drift into
 * covers. The judgement is made from the seed once, then both the candidate
 * filter and the ordering below are held to it.
 */
enum class AutoplayMode {
    /** Catalogue song: automatic candidates must not become covers or arrangements. */
    NORMAL,

    /** Video song: automatic candidates stay in the same video-song world. */
    VIDEO_RADIO,
}

/**
 * What the mode decision needs to know about the seed.
 *
 * [isOfficialArtistChannel] is YouTube's own OAC flag. [channelName] is also
 * compared with [artist] because an artist's own uploads are not always tagged
 * as an OAC, and the official-MV rule depends on that identity rather than on
 * the title alone.
 */
data class SeedVideoInfo(
    val videoId: String,
    val title: String,
    val artist: String,
    val channelName: String,
    val isVideo: Boolean,
    val isOfficialArtistChannel: Boolean = false,
)

private val TOPIC_SUFFIX = Regex("""(?i)\s*-\s*topic\s*$""")
private val OFFICIAL_MV = Regex(
    """(?i)(?<![a-z])(?:official|m\s*/\s*v|mv|music\s+video)(?![a-z])""",
)
private val NAME_NOISE = Regex("""[^\p{L}\p{N}]+""")

/**
 * Whether [seed] is a video song rather than an ordinary catalogue track.
 *
 * The order mirrors the decision the listener would make: an audio/catalogue
 * row is normal outright, a `- Topic` upload is the label's audio channel, an
 * official artist channel is the artist's own catalogue, and an official MV on
 * the artist's channel is the official video rather than a cover/live upload.
 * Only what is left — a video on some other channel — is a video song.
 */
fun autoplayModeFor(seed: SeedVideoInfo): AutoplayMode {
    if (!seed.isVideo) return AutoplayMode.NORMAL
    if (isTopicChannel(seed.channelName)) return AutoplayMode.NORMAL
    if (seed.isOfficialArtistChannel) return AutoplayMode.NORMAL
    if (isArtistChannel(seed.channelName, seed.artist) && OFFICIAL_MV.containsMatchIn(seed.title)) {
        return AutoplayMode.NORMAL
    }
    return AutoplayMode.VIDEO_RADIO
}

/** YouTube's auto-generated audio channel, whose uploads are catalogue rows. */
fun isTopicChannel(channelName: String): Boolean = TOPIC_SUFFIX.containsMatchIn(channelName.trim())

private fun isArtistChannel(channelName: String, artist: String): Boolean {
    val channel = normalizedName(channelName)
    val name = normalizedName(artist)
    if (name.isEmpty()) return false
    return channel == name ||
        channel == "${name}official" ||
        channel == "${name}vevo"
}

private fun normalizedName(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(NAME_NOISE, "")

/** Where a video-radio candidate came from, for the `from=` log field. */
enum class VideoSource(val wire: String) {
    MIX("mix"),
    CHANNEL("channel"),
    RADIO("radio"),
    PLAYLIST("playlist"),
}

/**
 * One row offered to automatic play, before any rule has judged it.
 *
 * [singer] is the act the row is filed under, when the source knows it; the
 * channel is the fallback because a channel upload is usually the singer's own
 * upload. [songKey] is optional and exists for sources that already know the
 * underlying song; when absent it is derived from [title] so two different
 * covers of one song still compare equal.
 */
data class VideoCandidate(
    val videoId: String,
    val title: String,
    val channelName: String,
    val singer: String = "",
    val durationSec: Int? = null,
    val description: String = "",
    val categories: List<String> = emptyList(),
    val isShort: Boolean = false,
    /** The row is a YouTube upload, not a catalogue audio track. */
    val isVideoUpload: Boolean = true,
    /** The source already knows this is a listener's own upload, not an official row. */
    val isUserUpload: Boolean = false,
    val songKey: String = "",
    val source: VideoSource = VideoSource.MIX,
) {
    val effectiveSinger: String get() = singer.ifBlank { channelName }
    val effectiveSongKey: String get() = songKey.ifBlank { songKeyOf(title) }
}

/** Why a candidate was dropped, counted per reason for the `dropped=` log field. */
enum class VideoDropReason(val wire: String) {
    INVALID("invalid"),
    NO_METADATA("no-metadata"),
    DUPLICATE("duplicate"),
    RECENT("recent"),
    SHORTS("shorts"),
    DURATION("duration"),
    TALK("talk"),
    ARRANGEMENT("arrangement"),
    FAN_CLIP("fan-clip"),
    USER_UPLOAD("user-upload"),
    NOT_SONG("not-song"),
    OFFICIAL("official"),
    SAME_SONG("same-song"),
}

/**
 * Context a filter needs but a candidate does not carry.
 *
 * [recentVideoIds] is the last fifty videos heard. [previousSongKey] is the
 * song that just played, so a different cover of it is not queued immediately.
 * The runtime window is the design's 90 seconds to eight minutes.
 */
data class VideoFilterOptions(
    val recentVideoIds: Set<String> = emptySet(),
    /** Underlying songs heard recently, so another cover of one is not repeated. */
    val recentSongKeys: Set<String> = emptySet(),
    val previousSongKey: String? = null,
    val minDurationSec: Int = 90,
    val maxDurationSec: Int = 8 * 60,
)

/** Kept rows plus a count per drop reason. */
data class VideoFilterResult(
    val kept: List<VideoCandidate>,
    val dropped: Map<VideoDropReason, Int>,
) {
    val droppedTotal: Int get() = dropped.values.sum()

    /** The `dropped=` half of the video-radio log line. */
    fun droppedSummary(): String =
        VideoDropReason.entries.mapNotNull { reason ->
            val count = dropped[reason] ?: 0
            if (count == 0) null else "${reason.wire}=$count"
        }.joinToString(",")
}

/**
 * Applies every candidate rule for [mode], preserving source order.
 *
 * A video-song station needs a song marker (or a music category) because the
 * mix also carries chatter, vlogs and reaction clips. A normal station needs
 * the opposite guarantee: [DownloadHealthPolicy.isArrangement] is the same
 * arrangement vocabulary download health and HQ matching already use, backed
 * by [TrackMatcher.isCoverArrangement]'s wider marker list, so a cover,
 * karaoke, piano, instrumental, MR, 8-bit or lofi upload cannot leak in from
 * any automatic source. Fan clips and listener uploads are not an arrangement
 * by title alone, so they get their own checks in normal mode.
 */
fun filterVideoCandidates(
    mode: AutoplayMode,
    seed: SeedVideoInfo,
    candidates: List<VideoCandidate>,
    options: VideoFilterOptions = VideoFilterOptions(),
): VideoFilterResult {
    val kept = ArrayList<VideoCandidate>(candidates.size)
    val dropped = linkedMapOf<VideoDropReason, Int>()
    val seen = HashSet<String>()
    val previousSongKey = options.previousSongKey
        ?.let(::songKeyOf)
        ?.takeIf { it.isNotEmpty() }
    val recentSongKeys = options.recentSongKeys.mapNotNullTo(HashSet()) { key ->
        songKeyOf(key).takeIf { it.isNotEmpty() }
    }

    for (candidate in candidates) {
        val reason = dropReasonFor(
            mode,
            seed,
            candidate,
            options,
            seen,
            previousSongKey,
            recentSongKeys,
        )
        if (reason == null) {
            kept += candidate
        } else {
            dropped[reason] = (dropped[reason] ?: 0) + 1
        }
    }
    return VideoFilterResult(kept, dropped)
}

private fun dropReasonFor(
    mode: AutoplayMode,
    seed: SeedVideoInfo,
    candidate: VideoCandidate,
    options: VideoFilterOptions,
    seen: MutableSet<String>,
    previousSongKey: String?,
    recentSongKeys: Set<String>,
): VideoDropReason? {
    if (candidate.videoId.isBlank()) return VideoDropReason.INVALID
    // A row with no title or no singer/channel is the nameless queue item the
    // ordinary AutoPlay path already refuses; video radio must not queue it
    // just because the source knows a video id.
    if (candidate.title.isBlank() || candidate.effectiveSinger.isBlank()) {
        return VideoDropReason.NO_METADATA
    }
    if (!seen.add(candidate.videoId)) return VideoDropReason.DUPLICATE
    if (candidate.videoId in options.recentVideoIds) return VideoDropReason.RECENT
    if (isShort(candidate)) return VideoDropReason.SHORTS
    val duration = candidate.durationSec
    if (duration == null || duration !in options.minDurationSec..options.maxDurationSec) {
        return VideoDropReason.DURATION
    }
    if (isTalk(candidate)) return VideoDropReason.TALK
    if (mode == AutoplayMode.NORMAL && isArrangement(candidate, seed)) {
        return VideoDropReason.ARRANGEMENT
    }
    if (mode == AutoplayMode.NORMAL && candidate.isUserUpload) {
        return VideoDropReason.USER_UPLOAD
    }
    if (mode == AutoplayMode.NORMAL && isFanClip(candidate)) {
        return VideoDropReason.FAN_CLIP
    }
    if (mode == AutoplayMode.VIDEO_RADIO) {
        // A video station still must not become a karaoke, 8-bit, piano or
        // backing-track station. Cover and live markers are deliberately not
        // in this list: those are the uploads the mode exists for.
        if (isVideoArrangement(candidate)) return VideoDropReason.ARRANGEMENT
        // The label's own catalogue rows are what normal mode plays. Mixing
        // them back in is the exact drift this mode was added to stop.
        if (!candidate.isVideoUpload) return VideoDropReason.OFFICIAL
        if (isOfficialCatalogue(candidate)) return VideoDropReason.OFFICIAL
        if (!looksLikeSong(candidate)) return VideoDropReason.NOT_SONG
    }
    if (previousSongKey != null && candidate.effectiveSongKey == previousSongKey) {
        return VideoDropReason.SAME_SONG
    }
    val songKey = candidate.effectiveSongKey
    if (songKey.isNotEmpty() && songKey in recentSongKeys) {
        return VideoDropReason.SAME_SONG
    }
    return null
}

private val SHORTS_MARKER = Regex("""(?i)(?:#shorts\b|\bshorts\b)""")

private fun isShort(candidate: VideoCandidate): Boolean =
    candidate.isShort ||
        candidate.durationSec?.let { it <= 60 } == true ||
        SHORTS_MARKER.containsMatchIn(candidate.title) ||
        SHORTS_MARKER.containsMatchIn(candidate.description)

private val SONG_MARKERS = listOf(
    Regex("""(?i)\bcover(s|ed|ing)?\b"""),
    Regex("""커버"""),
    Regex("""불러봤"""),
    Regex("""불러보았"""),
    Regex("""(?i)\blive\b"""),
    Regex("""라이브"""),
    Regex("""노래"""),
    Regex("""(?i)\bsing(s|ing)?\b"""),
    Regex("""歌ってみた"""),
)

private val MUSIC_CATEGORY = Regex("""(?i)\bmusic\b|음악""")

private fun looksLikeSong(candidate: VideoCandidate): Boolean =
    candidate.categories.any { MUSIC_CATEGORY.containsMatchIn(it) } ||
        SONG_MARKERS.any {
            it.containsMatchIn(candidate.title) ||
                it.containsMatchIn(candidate.description) ||
                it.containsMatchIn(candidate.channelName)
        }

private val TALK_MARKERS = listOf(
    Regex("""(?i)\btalk\b"""),
    Regex("""잡담"""),
    Regex("""(?i)\bvlog\b"""),
    Regex("""브이로그"""),
    Regex("""(?i)\breaction\b"""),
    Regex("""리액션"""),
    Regex("""(?i)\bhighlight\b"""),
    Regex("""하이라이트"""),
    Regex("""(?i)\bclip\s+(compilation|collection|montage)\b"""),
    Regex("""클립\s*모음"""),
    Regex("""(?i)\bcompilation\b"""),
    Regex("""(?i)\binterview\b"""),
    Regex("""인터뷰"""),
    Regex("""(?i)\bbehind\b"""),
    Regex("""비하인드"""),
    Regex("""예능"""),
)

private fun isTalk(candidate: VideoCandidate): Boolean =
    TALK_MARKERS.any {
        it.containsMatchIn(candidate.title) ||
            it.containsMatchIn(candidate.description) ||
            it.containsMatchIn(candidate.channelName)
    }

private fun isArrangement(candidate: VideoCandidate, seed: SeedVideoInfo): Boolean {
    val downloadVerdict = DownloadHealthPolicy.isArrangement(
        sourceTitle = candidate.title,
        sourceArtist = candidate.channelName,
        songTitle = seed.title,
        songArtist = seed.artist,
    )
    if (downloadVerdict) return true
    // The catalogue matcher already carries the wider marker list (16-bit,
    // emulation, vocal version, game version, ...). Reusing it keeps normal
    // mode at least as strict as the cover filter AutoPlay already applies.
    val source = Song(
        videoId = candidate.videoId,
        title = candidate.title,
        artist = candidate.channelName,
        thumbnailUrl = null,
    )
    val target = Song(
        videoId = seed.videoId,
        title = seed.title,
        artist = seed.artist,
        thumbnailUrl = null,
    )
    return TrackMatcher.isCoverArrangement(source, target)
}

/**
 * Arrangements that are not a performance of the song, whichever mode is on.
 *
 * Deliberately narrower than [isArrangement]: a video station is made of
 * covers and live clips, so those words cannot be a veto here.
 */
private val VIDEO_ARRANGEMENT_MARKERS = listOf(
    Regex("""(?i)\bkaraoke\b"""),
    Regex("""노래방"""),
    Regex("""(?i)\b8[- ]?bit\b"""),
    Regex("""(?i)\b16[- ]?bit\b"""),
    Regex("""(?i)\bpiano\b"""),
    Regex("""피아노"""),
    Regex("""(?i)\binst(rumental)?\b"""),
    Regex("""반주"""),
    Regex("""(?<![A-Za-z])MR(?![A-Za-z])"""),
)

private fun isVideoArrangement(candidate: VideoCandidate): Boolean =
    VIDEO_ARRANGEMENT_MARKERS.any {
        it.containsMatchIn(candidate.title) ||
            it.containsMatchIn(candidate.channelName) ||
            it.containsMatchIn(candidate.description)
    }

/** A `- Topic` or artist-channel official upload, i.e. the catalogue row. */
private fun isOfficialCatalogue(candidate: VideoCandidate): Boolean {
    if (isTopicChannel(candidate.channelName)) return true
    val channel = normalizedName(candidate.channelName)
    val singer = normalizedName(candidate.effectiveSinger)
    if (channel.isEmpty() || singer.isEmpty()) return false
    return channel == singer && OFFICIAL_MV.containsMatchIn(candidate.title)
}

private val FAN_CLIP_MARKERS = listOf(
    Regex("""(?i)\bfan\s*cam\b"""),
    Regex("""(?i)\bfancam\b"""),
    Regex("""직캠"""),
    Regex("""(?i)\bfan\s*clip\b"""),
    Regex("""(?i)\bfanclip\b"""),
    Regex("""(?i)\buser\s+upload(ed)?\b"""),
    Regex("""사용자\s*업로드"""),
)

private fun isFanClip(candidate: VideoCandidate): Boolean =
    FAN_CLIP_MARKERS.any {
        it.containsMatchIn(candidate.title) ||
            it.containsMatchIn(candidate.channelName) ||
            it.containsMatchIn(candidate.description)
    }

private val BRACKETED = Regex("""[\(\[\{（【「『].*?[\)\]\}）】」』]""")
private val SONG_KEY_MARKER = Regex(
    """(?i)\b(?:cover(?:s|ed|ing)?|live|official|m\s*/\s*v|mv|music\s+video|lyrics?|karaoke|inst(?:rumental)?)\b""" +
        """|커버|라이브|노래방|불러봤|불러보았|歌ってみた""",
)
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
private val WHITESPACE = Regex("""\s+""")

/**
 * The underlying song of a title, with packaging removed.
 *
 * Two covers of one song are different uploads but the same song, so their
 * bracket text, `cover`/`live`/`official` tags and punctuation are stripped
 * before comparison. The result is only used to stop the same song playing
 * twice in a row; it is never shown to a listener.
 */
fun songKeyOf(title: String): String =
    Normalizer.normalize(title, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(BRACKETED, " ")
        .replace(SONG_KEY_MARKER, " ")
        .replace(NON_WORD, " ")
        .replace(WHITESPACE, " ")
        .trim()

/** Fewer video rows than this fall through to the existing AutoPlay path. */
const val MIN_VIDEO_RADIO_TRACKS = 3

/** Which source a video-radio top-up finally used. */
enum class VideoRadioFallback(val wire: String) {
    /** Enough video rows were found; no replacement was needed. */
    NONE("none"),

    /** The same video world had more material to offer. */
    SAME_MODE("same-mode"),

    /** The video world was empty or too thin, so ordinary AutoPlay filled in. */
    NORMAL("normal"),

    /** Fewer than a full batch, but these are the only playable rows (offline). */
    PARTIAL("partial"),

    /** Nothing playable was found; the queue says so instead of repeating. */
    EMPTY("empty"),
}

/**
 * The staged replacement the design asks for when a video batch is too thin.
 *
 * A station that returns one or two rows is not enough to keep playing after
 * them, so the caller asks the same mode for more material first, then the
 * ordinary AutoPlay path, and only then gives up. This is pure so the order of
 * those stages is pinned by tests rather than by whichever branch ran.
 *
 * [partialAvailable] covers the case where the ordinary path cannot answer at
 * all — a failed or empty radio request on a broken connection. One or two
 * playable video rows are still better than silence, so they are kept rather
 * than discarded because the replacement failed.
 */
fun videoRadioFallbackStage(
    picked: Int,
    sameModeExhausted: Boolean,
    normalAvailable: Boolean,
    minimum: Int = MIN_VIDEO_RADIO_TRACKS,
    partialAvailable: Boolean = false,
): VideoRadioFallback = when {
    picked >= minimum -> VideoRadioFallback.NONE
    !sameModeExhausted -> VideoRadioFallback.SAME_MODE
    normalAvailable -> VideoRadioFallback.NORMAL
    partialAvailable && picked > 0 -> VideoRadioFallback.PARTIAL
    else -> VideoRadioFallback.EMPTY
}

/** The next step for a thin video pick. */
enum class VideoRadioFallbackAction {
    /** Run the ordinary (radio/ favourites / similar / session) path. */
    USE_NORMAL,

    /** Return the video rows already picked. */
    KEEP_PICK,

    /** There is neither a pick nor an ordinary answer: report the failure. */
    FAIL,
}

/**
 * What to do with a thin video pick, before and after the ordinary path.
 *
 * [normalRows] is null until that path has run. A successful but empty station
 * still runs it, because the local sources it adds are exactly what an empty
 * station lacks. Once it has run, zero rows mean the pick is the only playable
 * material and must not be thrown away.
 */
fun videoRadioFallbackAction(
    picked: Int,
    fallbackFailed: Boolean,
    normalRows: Int? = null,
): VideoRadioFallbackAction = when {
    fallbackFailed -> if (picked > 0) {
        VideoRadioFallbackAction.KEEP_PICK
    } else {
        VideoRadioFallbackAction.FAIL
    }
    normalRows == null -> VideoRadioFallbackAction.USE_NORMAL
    normalRows > 0 -> VideoRadioFallbackAction.USE_NORMAL
    picked > 0 -> VideoRadioFallbackAction.KEEP_PICK
    else -> VideoRadioFallbackAction.USE_NORMAL
}

/** One same-singer row for every two other-singer rows. */
const val VIDEO_RADIO_SAME_SINGER_RATIO = 1
const val VIDEO_RADIO_OTHER_SINGER_RATIO = 2

/** The design's cap: no singer may run for more than two rows. */
const val VIDEO_RADIO_MAX_SAME_SINGER_RUN = 2

/**
 * Orders candidates for video radio: seed singer once, then two other singers,
 * repeating, with no singer allowed to run more than twice.
 *
 * A station that simply plays the seed's channel forever is a discography, and
 * a station that ignores the seed's channel is not a continuation of what the
 * listener chose. The ratio keeps both present. The run cap is separate: the
 * two "other" slots may still name the same act, so [takeCandidate] prefers a
 * different singer before it reuses the previous one.
 *
 * The function is deterministic and source-order preserving within each group.
 * When one group runs out the other fills the remainder; when the run cap
 * cannot be met (three rows by one singer and nothing else) the extra rows are
 * left out rather than breaking the cap.
 */
fun orderVideoCandidates(
    candidates: List<VideoCandidate>,
    seedSinger: String,
    maxSameSingerRun: Int = VIDEO_RADIO_MAX_SAME_SINGER_RUN,
): List<VideoCandidate> {
    if (candidates.isEmpty()) return emptyList()
    val seedKey = singerKeyOf(seedSinger)
    val same = ArrayDeque<VideoCandidate>()
    val other = ArrayDeque<VideoCandidate>()
    for (candidate in candidates) {
        if (seedKey.isNotEmpty() && singerKeyOf(candidate) == seedKey) {
            same.addLast(candidate)
        } else {
            other.addLast(candidate)
        }
    }

    val result = ArrayList<VideoCandidate>(candidates.size)
    var lastSinger: String? = null
    var lastSongKey: String? = null
    var run = 0
    var sameTaken = 0
    var otherTaken = 0

    while (same.isNotEmpty() || other.isNotEmpty()) {
        val preferSame = same.isNotEmpty() &&
            sameTaken * VIDEO_RADIO_OTHER_SINGER_RATIO <=
            otherTaken * VIDEO_RADIO_SAME_SINGER_RATIO
        var picked = if (preferSame) {
            takeCandidate(same, lastSinger, lastSongKey, run, maxSameSingerRun)
        } else {
            null
        }
        var pickedSame = picked != null
        if (picked == null) {
            picked = takeCandidate(other, lastSinger, lastSongKey, run, maxSameSingerRun)
            pickedSame = false
        }
        if (picked == null && !preferSame) {
            picked = takeCandidate(same, lastSinger, lastSongKey, run, maxSameSingerRun)
            pickedSame = picked != null
        }
        if (picked == null) break

        val singer = singerKeyOf(picked)
        if (singer == lastSinger) {
            run++
        } else {
            lastSinger = singer
            run = 1
        }
        lastSongKey = picked.effectiveSongKey.takeIf { it.isNotEmpty() }
        if (pickedSame) sameTaken++ else otherTaken++
        result += picked
    }
    return result
}

private fun takeCandidate(
    pool: ArrayDeque<VideoCandidate>,
    lastSinger: String?,
    lastSongKey: String?,
    run: Int,
    maxRun: Int,
): VideoCandidate? {
    if (pool.isEmpty()) return null
    val predicates = listOf<(VideoCandidate) -> Boolean>(
        { candidate ->
            singerKeyOf(candidate) != lastSinger && songDiffers(candidate, lastSongKey)
        },
        { candidate -> singerKeyOf(candidate) != lastSinger },
        { candidate -> songDiffers(candidate, lastSongKey) && run < maxRun },
        { _ -> run < maxRun },
    )
    for (predicate in predicates) {
        val index = pool.indexOfFirst(predicate)
        if (index >= 0) return pool.removeAt(index)
    }
    return null
}

private fun songDiffers(candidate: VideoCandidate, lastSongKey: String?): Boolean {
    if (lastSongKey.isNullOrEmpty()) return true
    return candidate.effectiveSongKey != lastSongKey
}

private fun singerKeyOf(candidate: VideoCandidate): String =
    singerKeyOf(candidate.effectiveSinger).ifEmpty { candidate.videoId }

private fun singerKeyOf(singer: String): String =
    Normalizer.normalize(singer, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(NON_WORD, " ")
        .replace(WHITESPACE, " ")
        .trim()
