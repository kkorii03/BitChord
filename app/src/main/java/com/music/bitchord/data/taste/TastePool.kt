package com.music.bitchord.data.taste

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder

/**
 * A track this device already knows, as the local candidate sources offer it.
 *
 * The store keeps the song's own name and who sang the upload — a cover is
 * recorded as its original's title and credited artist plus the cover singer —
 * so this carries those three facts rather than a [Song] whose title would be
 * re-read as if it had never been parsed.
 */
data class LocalTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val coverSinger: String? = null,
)

/** Everything one AutoPlay top-up has fetched, before any ranking. */
data class PoolSources(
    /** The current track's own radio, the batch that always exists. */
    val seedRadio: List<Song> = emptyList(),

    /** The radio of the last completed track, when it differs from the seed. */
    val sessionRadio: List<Song> = emptyList(),

    /** The listener's own tracks, read locally from the taste store. */
    val local: List<LocalTrack> = emptyList(),
)

/**
 * The merged candidate rows and how many each source ended up contributing.
 *
 * The counts are what the debug line reports; they are taken after duplicates
 * are dropped, so they sum to [candidates]' size.
 */
data class CandidatePool(
    val candidates: List<Candidate>,
    val counts: Map<CandidateSource, Int>,
)

/**
 * Turns the fetched radio batches and the local taste store into one ranked
 * pool of [Candidate]s.
 *
 * Pure and Android-free apart from the shared [QueueBuilder] and [CoverTitles]
 * normalisation, so the merging rules can be tested without a player: which
 * source wins a duplicate, how many local rows each source may contribute, and
 * which of them are too recently played to offer again.
 *
 * Caps are per top-up, not per session: a favourite played today is not offered
 * again until a day has passed, and the radio batches are naturally different
 * each time.
 */
object TastePool {

    /** At most this many of the listener's own favourite tracks per top-up. */
    const val FAVOURITE_LIMIT = 6

    /** At most this many liked-but-rarely-played tracks per top-up. */
    const val REDISCOVER_LIMIT = 3

    /** A liked track played this few times still counts as rarely played. */
    const val REDISCOVER_PLAY_LIMIT = 1.0

    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000

    fun build(sources: PoolSources, taste: TasteView, nowMs: Long): CandidatePool {
        val seed = sources.seedRadio.mapIndexedNotNull { index, song ->
            candidateOf(song, CandidateSource.RADIO_SEED, index)
        }
        val session = sources.sessionRadio.mapIndexedNotNull { index, song ->
            candidateOf(song, CandidateSource.RADIO_SESSION, index)
        }
        val favourites = favouriteTracks(sources.local, taste, nowMs).mapIndexedNotNull { index, track ->
            candidateOf(track, CandidateSource.FAVOURITE, index)
        }
        val rediscover = rediscoverTracks(sources.local, taste).mapIndexedNotNull { index, track ->
            candidateOf(track, CandidateSource.REDISCOVER, index)
        }

        // Seed first, then the session's, then what the listener already owns:
        // a duplicate keeps the strongest statement of intent.
        val merged = (seed + session + favourites + rediscover).distinctBy { it.videoId }
        val counts = CandidateSource.entries.associateWith { source ->
            merged.count { it.source == source }
        }
        return CandidatePool(candidates = merged, counts = counts)
    }

    /**
     * The listener's most-affinity tracks that have not been heard today.
     *
     * A track played inside the last day is left out rather than merely pushed
     * down: an autoplay queue that offers the song just listened to is the one
     * thing every listener notices.
     */
    private fun favouriteTracks(
        local: List<LocalTrack>,
        taste: TasteView,
        nowMs: Long,
    ): List<LocalTrack> = local
        .asSequence()
        .filter { track -> taste.tracks[track.videoId]?.rating != TasteRating.DISLIKE }
        .filter { track ->
            val last = taste.tracks[track.videoId]?.lastPlayedAt ?: 0L
            last <= 0L || last <= nowMs - ONE_DAY_MS
        }
        .sortedByDescending { track ->
            Recommender.trackAffinity(taste.tracks[track.videoId] ?: TrackTaste())
        }
        .take(FAVOURITE_LIMIT)
        .toList()

    /**
     * Liked tracks heard once or never — the local shape of SmartPlaylists'
     * "liked but rarely played". Only songs this store has an explicit like
     * for qualify, since a play count alone does not say it is wanted.
     */
    private fun rediscoverTracks(
        local: List<LocalTrack>,
        taste: TasteView,
    ): List<LocalTrack> = local
        .asSequence()
        .filter { track ->
            val tasteOf = taste.tracks[track.videoId] ?: return@filter false
            tasteOf.rating == TasteRating.LIKE && tasteOf.plays <= REDISCOVER_PLAY_LIMIT
        }
        .sortedBy { track -> taste.tracks[track.videoId]?.plays ?: 0.0 }
        .take(REDISCOVER_LIMIT)
        .toList()

    private fun candidateOf(song: Song, source: CandidateSource, rank: Int): Candidate? {
        if (song.videoId.isBlank()) return null
        val cover = CoverTitles.read(song)
        val credited = CoverTitles.creditedArtist(song).trim().ifEmpty { song.artist.trim() }
        val names = song.artists.map { it.name }.filter { it.isNotBlank() }
        return Candidate(
            videoId = song.videoId,
            title = song.title,
            artistNames = names.ifEmpty { listOfNotNull(credited.takeIf { it.isNotBlank() }) },
            baseTitle = QueueBuilder.baseTitle(cover?.title ?: song.title),
            source = source,
            sourceRank = rank,
            coverSinger = cover?.singer,
            artistIds = song.artists.mapNotNull { it.browseId },
        )
    }

    private fun candidateOf(track: LocalTrack, source: CandidateSource, rank: Int): Candidate? {
        if (track.videoId.isBlank()) return null
        return Candidate(
            videoId = track.videoId,
            title = track.title,
            artistNames = listOfNotNull(track.artist.takeIf { it.isNotBlank() }),
            baseTitle = QueueBuilder.baseTitle(track.title),
            source = source,
            sourceRank = rank,
            coverSinger = track.coverSinger,
        )
    }
}

/**
 * A locally stored row as something the player can queue.
 *
 * Not marked a video: the store never kept that flag, and [QueueBuilder] drops
 * every video row when a batch has any catalogue track, so guessing "video"
 * here would quietly remove the listener's own songs from the station.
 */
fun LocalTrack.asSong(): Song = Song(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = null,
)
