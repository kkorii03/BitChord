package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.download.Downloads
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.TrackEntry
import com.music.bitchord.data.taste.AutoplayOrder
import com.music.bitchord.data.taste.CandidateSource
import com.music.bitchord.data.taste.LocalTrack
import com.music.bitchord.data.taste.PoolSources
import com.music.bitchord.data.taste.Recommender
import com.music.bitchord.data.taste.SimilarCandidates
import com.music.bitchord.data.taste.TastePool
import com.music.bitchord.data.taste.TasteSignals
import com.music.bitchord.data.taste.asSong
import com.music.bitchord.data.videoradio.AutoplayMode
import com.music.bitchord.data.videoradio.MIN_VIDEO_RADIO_TRACKS
import com.music.bitchord.data.videoradio.VideoRadioFallback
import com.music.bitchord.data.videoradio.VideoRadioSourceRows
import com.music.bitchord.data.videoradio.VideoRadioSources
import com.music.bitchord.data.videoradio.VideoSource
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * Whether AutoPlay is on for the music this device is playing.
 *
 * In a party it is the party's setting and not this phone's: AutoPlay is shared
 * there, so one member's preference cannot be what decides. [localPreference] is
 * `AppSettings.autoplay` and applies everywhere else.
 *
 * Shared by the playback service and by every surface that draws the toggle,
 * because they went their own ways once and the result was a button that lied:
 * the player read the local preference while the service obeyed the party's, so
 * a listener could sit under "AutoPlay on" getting no suggestions at all, press
 * it, and watch the label stay exactly as it was.
 */
fun autoplayEnabledFor(party: ListenTogether.State, localPreference: Boolean): Boolean =
    if (party.inParty) party.playback.autoplayEnabled else localPreference

/**
 * The one device in a party that tops the queue up, or null if there is none.
 *
 * Everybody runs this over the same membership and gets the same answer, which
 * is what stops five phones appending five different sets of suggestions to one
 * queue. The host supplies while it is connected; otherwise the lowest member id
 * takes over, chosen for being stable rather than for meaning anything.
 *
 * Except in a party locked to its host, where there is no stand-in. The server
 * refuses a `queueAdd` from a listener outright — `host_only`, whatever the
 * listener believes it is doing — so electing one produced a control that was
 * always refused, an error on that listener's phone and suggestions for nobody.
 * Returning null instead lets AutoPlay wait for the host, which is what the
 * party's own policy already says should happen.
 */
fun autoplaySupplierId(party: ListenTogether.State): String? {
    if (!party.inParty) return null
    val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
    if (party.hostOnlyControl) return host
    return host ?: party.members.asSequence()
        .filter { it.connected }
        .minByOrNull { it.memberId }
        ?.memberId
}

/**
 * Whether AutoPlay should forget its last seed and try the current track again.
 *
 * A completed load normally leaves its seed behind to de-duplicate the several
 * player callbacks caused by one queue edit. If that edit leaves the current
 * track with nothing after it, however, the seed is no longer useful: keeping
 * it would make an enabled AutoPlay queue stay empty forever.
 */
fun autoplayQueueNeedsRefresh(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

/**
 * Finds the YouTube id that should seed AutoPlay for a song. Module tracks do not
 * carry YouTube ids, so they are matched on YouTube before the radio request.
 */
suspend fun youtubeSeedFor(song: Song): String? {
    if (SourceRegistry.parseTrackKey(song.videoId) == null) return song.videoId
    val target = TrackMatcher.targetOf(song)
    val query = TrackMatcher.queries(target).firstOrNull() ?: return null
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrNull()
        ?.filterIsInstance<SearchResult.Track>()
        ?.map { it.song }
        ?.let { TrackMatcher.best(it, target) }
        ?.videoId
}

/**
 * Loads, de-duplicates and resolves one AutoPlay batch. The playback service is
 * the only caller for the AutoPlay toggle; the player UI's explicit radio start
 * uses this same helper for its initial station batch.
 *
 * With personalised autoplay on, YouTube's radio is one of several sources and
 * its order is no longer the answer — see [rankedExtensions]. Off, the watch
 * queue is queued exactly as YouTube returned it, which is the behaviour this
 * helper had before taste was recorded at all.
 *
 * @param sessionSong the most recently completed track, when the caller knows
 *   it. Its radio is the second — and last — network fetch of a top-up.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
    sessionSong: Song? = null,
    sessionIds: Set<String> = emptySet(),
): Result<List<Song>> {
    val seed = youtubeSeedFor(seedSong) ?: return Result.success(emptyList())
    val mode = videoRadioModeFor(seedSong)
    // A shared queue is one running order for everyone; a per-listener video
    // station would make that order depend on whose phone topped it up.
    val videoWanted = AppSettings.videoRadio.value &&
        mode == AutoplayMode.VIDEO_RADIO &&
        !ListenTogether.state.value.inParty
    val offline = AppSettings.meteredConnection.value == null

    // Offline the only video songs that can play are the downloaded ones; no
    // station request is worth starting just to fail.
    if (videoWanted && offline) {
        return loadOfflineVideoRadio(seedSong, existing, sessionSong, limit)
    }

    if (videoWanted) {
        // The video mix and the seed channel are the material the design asks
        // for; the Music radio below is only paid for when they are too thin.
        val sourceRows = VideoRadioSources.collect(
            seedVideoId = seed,
            channelName = seedSong.originalArtist?.takeIf { it.isNotBlank() }
                ?: seedSong.artist,
        )
        var sourceSongs = sourceRows.songs
        var sourceMap = sourceRows.sources.toMutableMap()
        var usedSameMode = false
        var pick = pickVideoRadio(seedSong, sourceSongs, existing, sessionSong, sourceMap)
        var radioResult: Result<List<Song>>? = null

        if (pick.ordered.size < MIN_VIDEO_RADIO_TRACKS) {
            radioResult = YtMusicRepository.radio(seed)
            val radio = radioResult.getOrElse { emptyList() }
            if (radio.isNotEmpty()) {
                val merged = mergeVideoSources(sourceSongs, sourceMap, radio, VideoSource.RADIO)
                val more = pickVideoRadio(
                    seedSong,
                    merged.songs,
                    existing,
                    sessionSong,
                    merged.sources,
                )
                if (more.ordered.size > pick.ordered.size) {
                    pick = more
                }
                // Keep the radio in the pool even when it did not improve this
                // pick: the same-mode replacement below used to combine it with
                // the session radio, and every old term is kept.
                sourceSongs = merged.songs
                sourceMap = merged.sources.toMutableMap()
            }
        }

        // Same-mode replacement: the last completed track's station is more
        // of the same world, and is normally already in the radio cache. This
        // is only paid when the seed's own material was too thin.
        if (pick.ordered.size < MIN_VIDEO_RADIO_TRACKS && AppSettings.heavyWorkAllowed) {
            val sessionRadio = sessionRadioFor(seedSong, sessionSong)
            if (sessionRadio.isNotEmpty()) {
                val merged = mergeVideoSources(
                    sourceSongs,
                    sourceMap,
                    sessionRadio,
                    VideoSource.RADIO,
                )
                val more = pickVideoRadio(
                    seedSong,
                    merged.songs,
                    existing,
                    sessionSong,
                    merged.sources,
                )
                if (more.ordered.size > pick.ordered.size) {
                    pick = more
                    sourceSongs = merged.songs
                    sourceMap = merged.sources.toMutableMap()
                    usedSameMode = true
                }
            }
        }
        if (pick.ordered.size >= MIN_VIDEO_RADIO_TRACKS) {
            val stage = if (usedSameMode) VideoRadioFallback.SAME_MODE else VideoRadioFallback.NONE
            TrackLog.i("BitChord", pick.logLine(stage.wire), about = seedSong.videoId)
            return Result.success(videoRadioRows(pick, sourceSongs, seedSong, limit))
        }

        // Too thin to keep playing. The log keeps the video pick's own source
        // and count, and names the ordinary path as the stage that replaced it.
        val fallbackLog = { stage: VideoRadioFallback ->
            TrackLog.i("BitChord", pick.logLine(stage.wire), about = seedSong.videoId)
        }
        val fallbackRadio = radioResult
            ?.getOrElse { return Result.failure(it) }
            ?: YtMusicRepository.radio(seed).getOrElse { return Result.failure(it) }
        return loadNormalAutoplayTracks(
            existing = existing,
            seedSong = seedSong,
            radio = fallbackRadio,
            limit = limit,
            sessionSong = sessionSong,
            sessionIds = sessionIds,
            onEmpty = { fallbackLog(VideoRadioFallback.EMPTY) },
            onFilled = { fallbackLog(VideoRadioFallback.NORMAL) },
        )
    }

    val radio = YtMusicRepository.radio(seed).getOrElse { return Result.failure(it) }
    return loadNormalAutoplayTracks(
        existing = existing,
        seedSong = seedSong,
        radio = radio,
        limit = limit,
        sessionSong = sessionSong,
        sessionIds = sessionIds,
        onEmpty = {
            TrackLog.i(
                "BitChord",
                "video-radio: seed=${seedSong.videoId} mode=music picked=0 from=radio dropped= fallback=empty",
                about = seedSong.videoId,
            )
        },
        onFilled = { rows ->
            TrackLog.i(
                "BitChord",
                "video-radio: seed=${seedSong.videoId} mode=music picked=$rows from=radio dropped= fallback=none",
                about = seedSong.videoId,
            )
        },
    )
}

/**
 * Offline video radio: the downloaded video rows are the whole world, so they
 * are read and filtered off the main thread. A thin but non-empty batch is
 * still returned — silence is worse than two songs, and the queue's own empty
 * state is reserved for having nothing playable at all.
 */
private suspend fun loadOfflineVideoRadio(
    seedSong: Song,
    existing: List<Song>,
    sessionSong: Song?,
    limit: Int,
): Result<List<Song>> {
    val (downloaded, pick) = withContext(Dispatchers.IO) {
        val rows = Downloads.savedMetadataSnapshot().keys
            .mapNotNull { Downloads.savedSongFor(it) }
            .filter { it.isVideo }
        rows to pickVideoRadio(seedSong, rows, existing, sessionSong)
    }
    val stage = when {
        pick.ordered.isEmpty() -> VideoRadioFallback.EMPTY
        pick.ordered.size < MIN_VIDEO_RADIO_TRACKS -> VideoRadioFallback.PARTIAL
        else -> VideoRadioFallback.NONE
    }
    TrackLog.i("BitChord", pick.logLine(stage.wire), about = seedSong.videoId)
    return Result.success(
        if (pick.ordered.isEmpty()) emptyList() else videoRadioRows(pick, downloaded, seedSong, limit),
    )
}

/** Adds [extra] after [songs], keeping the first row for an id and its source. */
private fun mergeVideoSources(
    songs: List<Song>,
    sources: Map<String, VideoSource>,
    extra: List<Song>,
    extraSource: VideoSource,
): VideoRadioSourceRows {
    val mergedSongs = LinkedHashMap<String, Song>()
    val mergedSources = HashMap<String, VideoSource>(sources)
    songs.forEach { mergedSongs.putIfAbsent(it.videoId, it) }
    extra.forEach { song ->
        if (song.videoId.isNotBlank() && mergedSongs.putIfAbsent(song.videoId, song) == null) {
            mergedSources[song.videoId] = extraSource
        }
    }
    return VideoRadioSourceRows(mergedSongs.values.toList(), mergedSources)
}

/** Turns the ordered video rows into queue entries, marked so they stay video. */
private fun videoRadioRows(
    pick: VideoRadioPick,
    sourceSongs: List<Song>,
    seedSong: Song,
    limit: Int,
): List<Song> {
    val byId = sourceSongs.associateBy { it.videoId }
    return pick.ordered.take(limit.coerceAtLeast(0)).mapNotNull { byId[it.videoId] }.map {
        it.copy(
            videoRadio = true,
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    }
}

/**
 * The radio rows with cover, karaoke and tribute uploads removed.
 *
 * A station answer is cached by [YtMusicRepository.radio], so the same list is
 * handed back for every settings toggle that makes the service consider a
 * top-up again. The scan itself is not free — every row is matched against the
 * whole cover-marker table — and the "dropped N" line was written once per
 * re-scan, which is how one station filled the log with the same 37 rows.
 * The answer is therefore kept per seed and per exact radio list, and the log
 * is written only when the scan actually happens.
 */
internal fun coverFilteredRadio(seedSong: Song, radio: List<Song>): List<Song> {
    val key = CoverFilterKey(
        seedSong.videoId,
        seedSong.title,
        seedSong.artist,
        seedSong.albumName,
        radio,
    )
    synchronized(coverFilterCache) { coverFilterCache[key] }?.let { return it }
    val related = radio.filterNot { TrackMatcher.isCoverArrangement(it, seedSong) }
    if (related.size != radio.size) {
        TrackLog.d(
            "BitChord",
            "autoplay: dropped ${radio.size - related.size} cover/karaoke rows",
            about = seedSong.videoId,
        )
    }
    synchronized(coverFilterCache) { coverFilterCache[key] = related }
    return related
}

private data class CoverFilterKey(
    val seedId: String,
    val seedTitle: String,
    val seedArtist: String,
    val seedAlbum: String?,
    val radio: List<Song>,
)

/** Bounded so a long session cannot retain every station it has ever seen. */
private val coverFilterCache = object : LinkedHashMap<CoverFilterKey, List<Song>>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CoverFilterKey, List<Song>>) =
        size > 8
}

/**
 * The original AutoPlay path, unchanged for normal mode and for the staged
 * replacement when a video batch is too thin. The caller supplies the already
 * fetched radio and is told whether rows were produced, so the video log can
 * name the stage without a second request.
 */
private suspend fun loadNormalAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    radio: List<Song>,
    limit: Int,
    sessionSong: Song?,
    sessionIds: Set<String>,
    onEmpty: () -> Unit,
    onFilled: (Int) -> Unit,
): Result<List<Song>> {
    // A radio station returns the cover, karaoke and tribute uploads filed
    // under the seed's title as readily as the recordings; the queue used to
    // take them as they came. Filter at the front so the TASTE ranking below
    // only ever chooses between recordings, and so the non-personalised path
    // (queue exactly as YouTube returned it) is held to the same rule.
    val related = coverFilteredRadio(seedSong, radio)
    val extra = if (AppSettings.personalisedAutoplay.value) {
        rankedExtensions(existing, seedSong, sessionSong, related, limit, sessionIds)
    } else {
        QueueBuilder.extend(existing, related, limit, seedArtist = seedSong.artist)
            .filterNot { it.videoId in sessionIds }
            .also { songs ->
                TrackLog.i(
                    "BitChord",
                    "autoplay: +radio ${songs.size} +similar 0 +fav 0 (" +
                        songs.joinToString(" | ") { "${it.title} - ${it.artist}" } + ")",
                    about = seedSong.videoId,
                )
            }
    }
    if (extra.isEmpty()) {
        onEmpty()
        return Result.success(emptyList())
    }

    // Whatever a source offered, a queue row with no name is unplayable to the
    // listener and indistinguishable from a broken player. Drop it here as the
    // final gate as well as at the source, and say which id it was. The cover
    // filter is repeated for the sources that did not pass through the radio
    // list above (similar rows and favourites), so every path is held to it.
    val playable = extra.filter {
        it.title.isNotBlank() && it.artist.isNotBlank() &&
            !TrackMatcher.isCoverArrangement(it, seedSong)
    }
    extra.filterNot { it.title.isNotBlank() && it.artist.isNotBlank() }.forEach {
        TrackLog.d("BitChord", "autoplay: dropped ${it.videoId} (no metadata)", about = seedSong.videoId)
    }
    extra.filter {
        it.title.isNotBlank() && it.artist.isNotBlank() &&
            TrackMatcher.isCoverArrangement(it, seedSong)
    }.forEach {
        TrackLog.d("BitChord", "autoplay: dropped ${it.videoId} (cover/karaoke)", about = seedSong.videoId)
    }
    if (playable.isEmpty()) {
        onEmpty()
        return Result.success(emptyList())
    }

    val rows = playable.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    }
    onFilled(rows.size)
    // Music-only works on the rows before they are appended, like every other
    // queue build: the first few are resolved here and the rest as playback
    // approaches them. A row with no song version is queued as its video.
    return Result.success(
        if (AppSettings.preferMusicOnly.value) {
            try {
                YtMusicRepository.resolveQueueAhead(rows, 0, ahead = 4)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                TrackLog.w("BitChord", "autoplay song versions failed: ${failure.message}", about = seedSong.videoId)
                rows
            }
        } else {
            rows
        },
    )
}

/**
 * The personalised half of a top-up: build a pool from the seed's radio, the
 * last completed track's radio and the listener's own library, rank it, then
 * let [QueueBuilder] do its usual de-duplication and per-artist caps.
 *
 * The network budget is two radio calls: the one the caller already made and
 * at most one more for the session track. That second call is skipped in low
 * power, where it is the cheapest thing to give up — the local sources still
 * contribute. On a cache miss the collaborative-filtering source adds one
 * ListenBrainz seed lookup, one similar-recordings call and at most four
 * anonymous YouTube searches; that whole fetch happens at most once per fifteen
 * minutes and only for an artist it has not just answered for, so the other
 * top-ups in between reuse it and cost nothing. The answer is cached per seed
 * for a week. None of it runs in low power or offline.
 */
private suspend fun rankedExtensions(
    existing: List<Song>,
    seedSong: Song,
    sessionSong: Song?,
    seedRadio: List<Song>,
    limit: Int,
    sessionIds: Set<String>,
): List<Song> {
    val sessionRadio = sessionRadioFor(seedSong, sessionSong)
        .filterNot { TrackMatcher.isCoverArrangement(it, seedSong) }
    val now = System.currentTimeMillis()
    val taste = TasteSignals.taste(now)
    val local = localTracksWithMetadata(seedSong)
    val similar = SimilarCandidates.candidatesFor(seedSong, now)
    val pool = TastePool.build(
        PoolSources(
            seedRadio = seedRadio,
            sessionRadio = sessionRadio,
            local = local,
            similar = similar,
        ),
        taste = taste,
        nowMs = now,
    )
    var dropped = 0
    val ranked = Recommender.rank(
        candidates = pool.candidates,
        taste = taste,
        session = TasteSignals.sessionContext(),
        nowMs = now,
        random = Random(),
        onDropped = { dropped++ },
    )
    TrackLog.d(
        "BitChord",
        "TASTE top-up seed=${pool.counts[CandidateSource.RADIO_SEED] ?: 0}" +
            " session=${pool.counts[CandidateSource.RADIO_SESSION] ?: 0}" +
            " favourite=${pool.counts[CandidateSource.FAVOURITE] ?: 0}" +
            " similar=${pool.counts[CandidateSource.SIMILAR] ?: 0}" +
            " rediscover=${pool.counts[CandidateSource.REDISCOVER] ?: 0}" +
            " hardDropped=$dropped ranked=${ranked.size}",
        about = seedSong.videoId,
    )

    // The ranker speaks in candidates; the queue needs the playable row each
    // one came from, and a local favourite has to be rebuilt from its stored
    // metadata because only the store knows it.
    val known = (seedRadio + sessionRadio + similar).associateBy { it.videoId }.toMutableMap()
    local.forEach { known.putIfAbsent(it.videoId, it.asSong()) }

    // Apply the queue's de-duplication and per-artist caps first, with room to
    // spare, so the ordering pass below sees rows that will really be playable.
    val rankedSongs = ranked.mapNotNull { known[it.videoId] }
    val eligible = QueueBuilder.extend(existing, rankedSongs, limit * 2, seedArtist = seedSong.artist)
    val eligibleIds = eligible.mapTo(HashSet()) { it.videoId }
    val ordered = AutoplayOrder.order(
        candidates = ranked.filter { it.videoId in eligibleIds },
        seed = seedProfileOf(seedSong),
        seedIds = (seedRadio + similar).mapTo(HashSet()) { it.videoId },
        excludedIds = sessionIds,
        limit = limit,
    )
    if (ordered.isEmpty()) return emptyList()

    val byId = eligible.associateBy { it.videoId }
    val songs = ordered.mapNotNull { byId[it.videoId] }
    val favourites = ordered.filter {
        it.source == CandidateSource.FAVOURITE || it.source == CandidateSource.REDISCOVER
    }
    TasteSignals.noteSuggested(favourites.map { it.videoId }, now)

    val radio = ordered.count {
        it.source == CandidateSource.RADIO_SEED || it.source == CandidateSource.RADIO_SESSION
    }
    val similarCount = ordered.count { it.source == CandidateSource.SIMILAR }
    val favouriteCount = favourites.size
    val titles = ordered.joinToString(" | ") {
        "${it.title} - ${it.artistNames.firstOrNull().orEmpty()}"
    }
    TrackLog.i(
        "BitChord",
        "autoplay: +radio $radio +similar $similarCount +fav $favouriteCount ($titles)",
        about = seedSong.videoId,
    )
    return songs
}

/** The seed's artists and ids, as the ordering pass compares them. */
private fun seedProfileOf(song: Song): AutoplayOrder.SeedProfile =
    AutoplayOrder.SeedProfile(
        videoId = song.videoId,
        title = song.title,
        artistNames = buildList {
            add(CoverTitles.creditedArtist(song))
            add(song.artist)
            song.artists.forEach { add(it.name) }
        }.filter { it.isNotBlank() }.distinct(),
        artistIds = (listOfNotNull(song.artistId) + song.artists.mapNotNull { it.browseId })
            .filter { it.isNotBlank() }
            .distinct(),
    )

/**
 * The local rows, with the metadata a favourite needs before it can be queued.
 *
 * A rating can create a taste entry before its song has ever played through
 * this store, leaving title and artist blank. [ListeningStats] has seen the
 * same id when it did play, so title, artist, artwork and duration are filled
 * from there. A row that still has no title or artist is not offered at all,
 * and says so once in the log rather than appearing as a nameless queue item.
 */
private suspend fun localTracksWithMetadata(seedSong: Song): List<LocalTrack> {
    val local = TasteSignals.localTracks()
    if (local.isEmpty()) return emptyList()
    val missing = local.filter {
        it.title.isBlank() || it.artist.isBlank() || it.thumbnailUrl.isNullOrBlank() ||
            it.durationText.isNullOrBlank()
    }
    val stats: Map<String, TrackEntry> = if (missing.isEmpty()) {
        emptyMap()
    } else {
        runCatching { ListeningStats.recentTracks(METADATA_LOOKUP_LIMIT) }
            .getOrNull()
            .orEmpty()
            .associateBy { it.id }
    }
    val enriched = local.map { track ->
        val entry = stats[track.videoId]
        track.copy(
            title = track.title.ifBlank { entry?.title.orEmpty() },
            artist = track.artist.ifBlank { entry?.artist.orEmpty() },
            thumbnailUrl = track.thumbnailUrl?.takeIf { it.isNotBlank() } ?: entry?.art,
            durationText = track.durationText?.takeIf { it.isNotBlank() } ?: entry?.durationText,
        )
    }
    return enriched.filter { track ->
        val complete = track.title.isNotBlank() && track.artist.isNotBlank()
        if (!complete) {
            TrackLog.d(
                "BitChord",
                "autoplay: dropped ${track.videoId} (no metadata)",
                about = seedSong.videoId,
            )
        }
        complete
    }
}

/** How many recently played rows are checked for missing local metadata. */
private const val METADATA_LOOKUP_LIMIT = 600

/** The last completed track's radio, or empty when it would repeat the seed. */
private suspend fun sessionRadioFor(seedSong: Song, sessionSong: Song?): List<Song> {
    if (sessionSong == null) return emptyList()
    // The same recording is the same statement of intent; don't pay for its
    // radio twice just because the upload ids differ.
    if (QueueBuilder.isSameRecording(sessionSong, seedSong)) return emptyList()
    if (!AppSettings.heavyWorkAllowed) return emptyList()
    val seed = youtubeSeedFor(sessionSong) ?: return emptyList()
    return YtMusicRepository.radio(seed).getOrNull().orEmpty()
}
