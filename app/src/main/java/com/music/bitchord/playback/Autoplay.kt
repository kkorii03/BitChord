package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.taste.CandidateSource
import com.music.bitchord.data.taste.PoolSources
import com.music.bitchord.data.taste.Recommender
import com.music.bitchord.data.taste.TastePool
import com.music.bitchord.data.taste.TasteSignals
import com.music.bitchord.data.taste.asSong
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CancellationException
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
): Result<List<Song>> {
    val seed = youtubeSeedFor(seedSong) ?: return Result.success(emptyList())
    val related = YtMusicRepository.radio(seed).getOrElse { return Result.failure(it) }
    val extra = if (AppSettings.personalisedAutoplay.value) {
        rankedExtensions(existing, seedSong, sessionSong, related, limit)
    } else {
        QueueBuilder.extend(existing, related, limit)
    }
    if (extra.isEmpty()) return Result.success(emptyList())

    return Result.success(extra.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    })
}

/**
 * The personalised half of a top-up: build a pool from the seed's radio, the
 * last completed track's radio and the listener's own library, rank it, then
 * let [QueueBuilder] do its usual de-duplication and per-artist caps.
 *
 * The network budget is two radio calls: the one the caller already made and
 * at most one more for the session track. That second call is skipped in low
 * power, where it is the cheapest thing to give up — the local sources still
 * contribute.
 */
private suspend fun rankedExtensions(
    existing: List<Song>,
    seedSong: Song,
    sessionSong: Song?,
    seedRadio: List<Song>,
    limit: Int,
): List<Song> {
    val sessionRadio = sessionRadioFor(seedSong, sessionSong)
    val now = System.currentTimeMillis()
    val taste = TasteSignals.taste(now)
    val local = TasteSignals.localTracks()
    val pool = TastePool.build(
        PoolSources(
            seedRadio = seedRadio,
            sessionRadio = sessionRadio,
            local = local,
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
            " rediscover=${pool.counts[CandidateSource.REDISCOVER] ?: 0}" +
            " hardDropped=$dropped ranked=${ranked.size}",
        about = seedSong.videoId,
    )

    // The ranker speaks in candidates; the queue needs the playable row each
    // one came from, and a local favourite has to be rebuilt from its stored
    // metadata because only the store knows it.
    val known = (seedRadio + sessionRadio).associateBy { it.videoId }.toMutableMap()
    local.forEach { known.putIfAbsent(it.videoId, it.asSong()) }
    val ordered = ranked.mapNotNull { known[it.videoId] }
    return QueueBuilder.extend(existing, ordered, limit)
}

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
