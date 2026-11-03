package com.music.bitchord.ko

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.CatalogSongMapper
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.CounterpartSwitch
import com.music.bitchord.data.net.SharedLookups
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.net.SongVersionVerdictStore
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Song-version, counterpart and rescue lookups kept out of the upstream file. */
internal object KoSongVersion {

    private const val TAG = "BitChord"

    /** How far a rescue copy's stated runtime may be from the refused track. */
    private const val ALTERNATE_DURATION_TOLERANCE_SEC = 3

    /**
     * Finds a different upload of the same recording when [song] itself has
     * been refused definitively.
     *
     * The catalogue counterpart first ([resolveAudio]), then a song search and
     * a video search for "title artist". Every candidate must share an artist
     * with [song] ([TrackMatcher.bestSameArtist]), must not be [song] itself or
     * another id in [excluding], and must not already carry an unplayable
     * verdict — offering one of those would send playback straight back into
     * the failure this is meant to escape.
     *
     * A rescue is a *song*, not a video: the queue may be playing an official
     * audio track and a search's top hit can be its MV, a live fancam or a
     * fan upload. Those differ in runtime by more than the three seconds a
     * same-recording replacement is allowed, so this accepts only rows flagged
     * `isVideo = false` (an Art Track included) whose title matches, whose
     * artist shares a credit, and whose stated length is within
     * [ALTERNATE_DURATION_TOLERANCE_SEC]. Anything else means "no alternate",
     * and the caller moves on to the next track.
     *
     * Returns null when there is no other version, which is a normal answer:
     * the caller then skips the track as before.
     */
    suspend fun findAlternateRecording(song: Song, excluding: Set<String> = emptySet()): Song? {
        if (song.title.isBlank() || song.artist.isBlank()) return null
        val target = TrackMatcher.targetOf(song)
        val excluded = excluding + song.videoId
        fun acceptable(candidate: Song): Boolean =
            candidate.videoId.isNotBlank() &&
                candidate.videoId !in excluded &&
                !candidate.isVideo &&
                !StreamResolver.isUnplayable(candidate.videoId) &&
                TrackMatcher.sharesArtist(song.artist, candidate.artist) &&
                TrackMatcher.sameTitle(song, candidate) &&
                TrackMatcher.withinSeconds(candidate, target, ALTERNATE_DURATION_TOLERANCE_SEC)

        val catalogue = try {
            YtMusicRepository.resolveAudio(song)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "alternate: audio lookup failed for ${song.videoId}: ${failure.message}")
            null
        }
        catalogue?.takeIf(::acceptable)?.let { match ->
            Log.d(TAG, "alternate: '${song.title}' -> '${match.title}' (catalogue)")
            return match
        }

        for ((filter, kind) in listOf(
            SearchFilter.SONGS to SearchKind.AUDIO_SWITCH,
            SearchFilter.VIDEOS to SearchKind.VIDEO_SWITCH,
        )) {
            for (query in TrackMatcher.queries(target)) {
                val candidates = try {
                    YtMusicRepository.search(query, filter, kind).getOrNull()
                        ?.filterIsInstance<SearchResult.Track>()
                        ?.map { it.song }
                        .orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.w(TAG, "alternate search failed for '$query': ${failure.message}")
                    emptyList()
                }
                TrackMatcher.bestSameArtist(candidates.filter(::acceptable), target)?.let { match ->
                    Log.d(TAG, "alternate: '${song.title}' -> '${match.title}' (search)")
                    return match
                }
            }
        }
        Log.w(TAG, "alternate: no other recording of '${song.title}' by '${song.artist}'")
        return null
    }

    /** YouTube Music's own counterpart row for a video, plus "no counterpart". */
    private val counterpartAudioCache = ConcurrentHashMap<String, Song>()
    private val counterpartMissing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val counterpartLookups = ConcurrentHashMap<String, CompletableDeferred<Song?>>()
    /**
     * Signed-out `next` responses carry no pairing rows at all, so a run of
     * empty answers is a property of the session, not of one track. Counted
     * across requests; any response that does carry one resets the run.
     */
    private val counterpartEmptyRun = AtomicInteger(0)

    /**
     * The catalogue release YouTube Music pairs with music-video [videoId], as
     * its own Song/Video switch does, or null when it pairs none.
     *
     * Read whole rather than by id: a queue built with the song version up
     * front needs the counterpart's title, credits, art and runtime, not just
     * its id. Only a successful `next` response is cached, so an offline
     * attempt can be retried later.
     *
     * A run of [SongVersionPolicy.COUNTERPART_EMPTY_LIMIT] responses with no
     * pairing rows disables this for a week ([CounterpartSwitch]): a
     * signed-out `next` cannot answer it, and the caller's search fallback is
     * cheaper. The verdict outlives the process so a restart does not spend
     * five more empty requests on it.
     */
    suspend fun counterpartAudio(videoId: String): Song? {
        if (videoId.isBlank()) return null
        counterpartAudioCache[videoId]?.let { return it }
        if (videoId in counterpartMissing) return null
        if (CounterpartSwitch.isSuppressed()) return null
        val mine = CompletableDeferred<Song?>()
        counterpartLookups.putIfAbsent(videoId, mine)?.let {
            return try {
                it.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
        try {
            val found = YtMusicRepository.call("counterpart:$videoId") {
                val response = Innertube.next(videoId)
                val wrappers = InnertubeParser.countCounterpartWrappers(response)
                val song = InnertubeParser.parseCounterpartSong(response, videoId)
                TrackLog.i(
                    TAG,
                    "counterpart: $videoId wrappers=$wrappers found=${song?.videoId ?: "none"}",
                    about = videoId,
                )
                if (wrappers > 0) {
                    counterpartEmptyRun.set(0)
                } else if (SongVersionPolicy.counterpartDisabled(counterpartEmptyRun.incrementAndGet()) &&
                    !CounterpartSwitch.isSuppressed()
                ) {
                    CounterpartSwitch.suppress()
                    TrackLog.i(TAG, "counterpart: off for a week (no wrappers)")
                }
                song
            }
            found.onSuccess { song ->
                if (song == null) counterpartMissing.add(videoId) else counterpartAudioCache[videoId] = song
            }
            val answer = found.getOrNull()
            mine.complete(answer)
            return answer
        } catch (failure: Throwable) {
            mine.completeExceptionally(failure)
            throw failure
        } finally {
            counterpartLookups.remove(videoId, mine)
        }
    }

    /**
     * The official music video of catalogue track [videoId], as YouTube Music
     * itself pairs them (its Song/Video switch), or null. Unlike
     * [resolveVideo] nothing is searched for, so nothing else — a lyric
     * video, a fan upload with a translation — can be picked by mistake.
     */
    suspend fun officialVideo(videoId: String): String? =
        counterpartAudio(videoId)?.videoId

    /**
     * Song-version answers by music-video id, with "no version" cached too.
     * A miss left by a cut-short lookup is never stored as a verdict; see
     * [SongVersionVerdictStore].
     */
    private val songVersionVerdicts = SongVersionVerdictStore()

    /** Distinct rows whose "not a video" skip has already been logged this run. */
    private const val SONG_VERSION_SKIP_LOG_SIZE = 256
    private val songVersionSkipLogged =
        object : LinkedHashMap<String, Boolean>(SONG_VERSION_SKIP_LOG_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
                size > SONG_VERSION_SKIP_LOG_SIZE
        }

    private fun logSongVersionSkipOnce(videoId: String) {
        val first = synchronized(songVersionSkipLogged) {
            songVersionSkipLogged.put(videoId, true) == null
        }
        if (first) TrackLog.i(TAG, "song version: $videoId skipped (not a video)", about = videoId)
    }

    /**
     * Lookups in flight, shared by every asker for the same video id, run in
     * [lookupScope] rather than in the waiter: a queue build that gives up on
     * its 1.5 s budget must not throw the work away. The service's pre-audio
     * ask for the same track then joins it (or reads the cache it fills)
     * instead of searching the same song from the start.
     */
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val songVersionLookups = SharedLookups<SongVersionAnswer>(lookupScope)

    /** A resolved answer and the source that gave it, for the log line. */
    private data class SongVersionAnswer(
        val song: Song?,
        val via: String,
        val definitive: Boolean,
    )

    /**
     * A song version already resolved in this run, for callers that must decide
     * before the first sound and cannot afford a suspension to ask again. The
     * queue fields are taken from [song], so the answer belongs to this row.
     */
    fun cachedSongVersionFor(song: Song): Song? =
        songVersionVerdicts.cached(song.videoId)?.withQueueFieldsOf(song)

    /** Test seam: whether the shared lookup for [videoId] is still running. */
    internal fun isSongVersionLookupRunning(videoId: String): Boolean =
        songVersionLookups.isRunning(videoId)

    /** True when this run has already established that [videoId] has no song version. */
    fun isSongVersionMissing(videoId: String): Boolean =
        songVersionVerdicts.isMissing(videoId)

    /**
     * True when a before-audio lookup on [videoId] timed out or was cancelled
     * and the one post-audio retry has not been spent yet. See
     * [SongVersionPolicy.shouldRetrySongVersionAfterAudio].
     */
    fun isSongVersionRetrySuggested(videoId: String): Boolean =
        songVersionVerdicts.isRetrySuggested(videoId)

    /**
     * The song version of a music-video row, resolved before it reaches the
     * queue instead of after it has started playing.
     *
     * YouTube Music's own counterpart comes first: it is exact, and it is what
     * the official app's Song/Video switch uses. A catalogue search
     * ([resolveAudio], the same-artist matcher) is the fallback for rows with
     * no counterpart. Null means "play the video as it is", never silence.
     *
     * Queue fields stay on the original row, so the resolved track keeps its
     * entry identity, tier, playlist slot and playback source. The answer is
     * cached per video id — found or definitively not found — so the same
     * video is never asked about twice in one run; an unsuccessful pair of
     * requests is not cached, so an offline attempt can be retried.
     *
     * @param beforeAudio the caller is resolving a track that has not made a
     *   sound yet. Success is logged with the `before-audio` marker and a
     *   definitive "no version" as `kept (none)`, which is the pair the
     *   pre-playback path is read by.
     */
    suspend fun songVersion(
        song: Song,
        beforeAudio: Boolean = false,
        retryTimedOut: Boolean = false,
    ): Song? {
        if (SongVersionPolicy.keepsLocalFile(song.localUri != null, song.isVideo, song.typeUnknown)) {
            return null
        }
        if (!SongVersionPolicy.wantsSongVersion(song.isVideo, song.typeUnknown)) {
            logSongVersionSkipOnce(song.videoId)
            return null
        }
        if (song.title.isBlank() || song.artist.isBlank()) {
            if (beforeAudio) {
                logSongVersion(song.videoId, null, "none", beforeAudio = true)
            } else {
                TrackLog.w(
                    TAG,
                    "song version: ${song.videoId} -> none via none (no title/artist)",
                    about = song.videoId,
                )
            }
            // Not cached: a sparse row can gain its metadata later.
            return null
        }
        if (CoverTitles.isCover(song)) {
            if (beforeAudio) {
                logSongVersion(song.videoId, null, "none", beforeAudio = true)
            } else {
                TrackLog.i(TAG, "song version: ${song.videoId} -> none via none (cover)", about = song.videoId)
            }
            songVersionVerdicts.markMissing(song.videoId)
            return null
        }
        songVersionVerdicts.cached(song.videoId)?.let {
            logSongVersion(song.videoId, it, "cache", beforeAudio)
            return it.withQueueFieldsOf(song)
        }
        if (retryTimedOut) {
            // The retry exists precisely because the first answer was cut
            // short; forget its provisional miss but keep the waiver until the
            // in-flight lookup that produced it has finished. Clearing the
            // waiver now would let that lookup cache its cut-short miss as a
            // verdict before the fresh search below even starts.
            songVersionVerdicts.beginRetry(song.videoId)
            if (YtMusicRepository.audioVersionCache[song.videoId]?.videoId == song.videoId) {
                YtMusicRepository.audioVersionCache.remove(song.videoId)
            }
        } else {
            when (songVersionVerdicts.settleMiss(song.videoId)) {
                SongVersionVerdictStore.Miss.SETTLED -> {
                    logSongVersion(song.videoId, null, "cache", beforeAudio)
                    return null
                }
                SongVersionVerdictStore.Miss.RETRY -> {
                    if (YtMusicRepository.audioVersionCache[song.videoId]?.videoId == song.videoId) {
                        YtMusicRepository.audioVersionCache.remove(song.videoId)
                    }
                }
                SongVersionVerdictStore.Miss.NONE -> Unit
            }
        }
        // Waiting is what the caller's 1.5 s budget bounds; the lookup itself
        // keeps running in the shared scope, so a timeout here costs nothing
        // but the wait and the answer still lands in the cache.
        val lookup = songVersionLookups.lookup(song.videoId) { resolveSongVersion(song) }
        var answer = try {
            lookup.result.await()
        } catch (cancelled: CancellationException) {
            // The answer landed while this caller's deadline was firing, so it
            // was cached before the waiver was known. Reopen a miss; a found
            // recording is already useful.
            if (songVersionVerdicts.onWaiterCancelled(song.videoId, lookup.result.isCompleted) &&
                YtMusicRepository.audioVersionCache[song.videoId]?.videoId == song.videoId
            ) {
                YtMusicRepository.audioVersionCache.remove(song.videoId)
            }
            throw cancelled
        }
        recordSongVersionAnswer(song, answer)
        if (retryTimedOut && answer.song == null) {
            // The timed-out lookup may still have been in flight, so this
            // retry joined it and got either the same provisional miss or a
            // stale verdict recorded before the waiver was known. Do not trust
            // it: spend the one retry on a fresh search after the join.
            songVersionVerdicts.beginFreshSearch(song.videoId)
            if (YtMusicRepository.audioVersionCache[song.videoId]?.videoId == song.videoId) {
                YtMusicRepository.audioVersionCache.remove(song.videoId)
            }
            answer = try {
                resolveSongVersion(song)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                SongVersionAnswer(null, "none", definitive = false)
            }
            recordSongVersionAnswer(song, answer)
        }
        logSongVersion(
            song.videoId,
            answer.song,
            when {
                !answer.definitive -> "retry"
                lookup.joined -> "${answer.via} shared"
                else -> answer.via
            },
            beforeAudio,
            definitive = answer.definitive,
        )
        return answer.song?.withQueueFieldsOf(song)
    }

    /** Keeps the retry marker in step with one answer, definitive or not. */
    private fun recordSongVersionAnswer(song: Song, answer: SongVersionAnswer) {
        songVersionVerdicts.recordAnswer(song.videoId, answer.song, answer.definitive)
    }

    /**
     * The one lookup behind [songVersion]. Runs in the shared scope, so a
     * waiter that times out leaves it running; caches the answer here for the
     * waiters that join it and for later asks.
     */
    private suspend fun resolveSongVersion(song: Song): SongVersionAnswer {
        // One audio-switch pass, no counterpart request. The `next` pairing
        // answer never named a catalogue release in three days of logs
        // (0.8–1.1 s each), and it spent the first song's whole 1.5 s budget
        // before the search that does find one could even start.
        val search = try {
            Result.success(YtMusicRepository.resolveAudio(song))
        } catch (cancelled: CancellationException) {
            // A cancelled lookup is never a "no version" verdict. The shared
            // job normally outlives a waiter's timeout, so this only fires when
            // the lookup itself is cancelled.
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        // A song search can still surface a video upload; the whole point
        // here is to play the catalogue release, never another music video.
        val found = search.getOrNull()?.takeIf { it.videoId != song.videoId && !it.isVideo }
        val via = if (found != null) "search" else "none"
        // A completed search is the verdict, found or not. A failed request
        // is not "no version" and must be retried later. resolveAudio returns
        // the original row for both shapes, so its cache is what tells the
        // definitive "no catalogue row" apart from a search that never ran.
        val answered = YtMusicRepository.audioVersionCache.containsKey(song.videoId)
        val waitedOut = songVersionVerdicts.waitedOut(song.videoId)
        val definitive = SongVersionPolicy.songVersionVerdictIsDefinitive(
            searchSucceeded = search.isSuccess,
            answered = answered,
            found = found != null,
            waitedOut = waitedOut,
        )
        // A provisional miss is never stored. If resolveAudio recorded one
        // through its own cache, drop it so the post-audio retry really
        // searches again.
        val provisional = songVersionVerdicts.store(song.videoId, found, definitive)
        if (provisional && YtMusicRepository.audioVersionCache[song.videoId]?.videoId == song.videoId) {
            YtMusicRepository.audioVersionCache.remove(song.videoId)
        }
        // The store has the last word: a waiter that gave up after the check
        // above still leaves a miss provisional, and the retry is then owed.
        return SongVersionAnswer(found, via, definitive && !provisional)
    }

    /**
     * One line per answer, in the shape the pre-playback path is read by.
     *
     * Before audio the useful fact is either "this is the version being
     * played" or "this video is being kept", never a mid-song replacement;
     * elsewhere the original `-> id via source` line is kept as it was.
     */
    private fun logSongVersion(
        videoId: String,
        found: Song?,
        via: String,
        beforeAudio: Boolean,
        definitive: Boolean = true,
    ) {
        if (beforeAudio) {
            if (found != null) {
                TrackLog.i(
                    TAG,
                    "song version: $videoId -> ${found.videoId} via $via before-audio",
                    about = videoId,
                )
            } else {
                // A deadline/cancelled answer is not "kept because there is no
                // version"; say that it is retryable so the late retry's line
                // can be read beside it.
                val reason = if (definitive) "none" else "retry"
                TrackLog.i(TAG, "song version: $videoId kept ($reason)", about = videoId)
            }
        } else {
            TrackLog.i(
                TAG,
                "song version: $videoId -> ${found?.videoId ?: "none"} via ${if (definitive) via else "retry"}",
                about = videoId,
            )
        }
    }

    /**
     * The song versions of the queue rows around [startIndex], resolved in
     * parallel. Current + next few is the look-ahead the queue needs; rows
     * further out are resolved as playback approaches them.
     *
     * Each row is given [SongVersionPolicy.BEFORE_AUDIO_TIMEOUT_MS] at most:
     * a queue build must not stall behind a slow lookup, and a row that could
     * not be answered in that window is played as its video and is not
     * replaced mid-song later.
     */
    suspend fun resolveQueueAhead(
        songs: List<Song>,
        startIndex: Int,
        ahead: Int = 4,
        timeoutMs: Long = SongVersionPolicy.BEFORE_AUDIO_TIMEOUT_MS,
    ): List<Song> {
        if (songs.isEmpty() || ahead <= 0) return songs
        val first = startIndex.coerceIn(0, songs.lastIndex)
        val last = (first + ahead - 1).coerceAtMost(songs.lastIndex)
        val candidates = (first..last).filter {
            SongVersionPolicy.wantsSongVersion(songs[it].isVideo, songs[it].typeUnknown)
        }
        if (candidates.isEmpty()) return songs
        val resolved = coroutineScope {
            candidates.map { index ->
                async(Dispatchers.IO) {
                    val song = songs[index]
                    val version = try {
                        withTimeout(timeoutMs) { songVersion(song, beforeAudio = true) }
                    } catch (timeout: TimeoutCancellationException) {
                        TrackLog.i(TAG, "song version: ${song.videoId} kept (timeout)", about = song.videoId)
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Log.w(TAG, "song version failed for ${song.videoId}: ${failure.message}")
                        null
                    }
                    index to version
                }
            }.awaitAll()
        }
        if (resolved.none { it.second != null }) return songs
        return songs.toMutableList().also { out ->
            resolved.forEach { (index, version) ->
                if (version != null && !CatalogSongMapper.isCover(songs[index])) {
                    out[index] = version
                }
            }
        }
    }

    private fun Song.withQueueFieldsOf(source: Song): Song = copy(
        // A counterpart row is normally complete, but a search result can be
        // sparse; never blank the metadata the queue already had.
        title = title.ifBlank { source.title },
        artist = artist.ifBlank { source.artist },
        thumbnailUrl = thumbnailUrl ?: source.thumbnailUrl,
        durationText = durationText ?: source.durationText,
        // A version swap replaces the recording, not the artist. A sparse
        // search row used to lose the channel the original row carried, so the
        // artist name then had to be searched again and could land on a
        // same-name channel.
        artistId = artistId ?: source.artistId,
        artists = artists.ifEmpty { source.artists },
        albumId = albumId ?: source.albumId,
        albumName = albumName ?: source.albumName,
        isVideoOrigin = true,
        queueTier = source.queueTier,
        queueEntryId = source.queueEntryId,
        radioName = source.radioName,
        playbackSource = source.playbackSource,
        playbackSourceType = source.playbackSourceType,
        playbackSourceId = source.playbackSourceId,
        setVideoId = source.setVideoId,
    )

    private val officialBySearch = ConcurrentHashMap<String, String>()

    /**
     * The music video of catalogue track [song] when YouTube Music doesn't
     * pair one ([officialVideo] — signed out, it often doesn't): a video
     * search, but only a result posted by the track's own artist channel —
     * the same channel id the track credits, or an "…VEVO" channel of the
     * same name — titled with the song and nothing that makes it a lyric
     * video, a live stage, a cover or a reaction. Anything else — a fan's
     * subtitled upload, a TV show's stage — is no answer at all.
     */
    suspend fun officialVideoBySearch(song: Song): String? {
        if (song.isVideo || song.videoId.isBlank()) return null
        officialBySearch[song.videoId]?.let { return it.ifEmpty { null } }
        val title = song.title.substringBefore(" (").substringBefore(" [").trim()
        val artist = song.artist.split(",", "&", " x ", " X ").first().trim()
        if (title.isBlank() || artist.isBlank()) return null
        val results = YtMusicRepository.search("$artist $title", SearchFilter.VIDEOS, SearchKind.VIDEO_SWITCH)
            .getOrNull() ?: return null
        val seconds = TrackMatcher.secondsOf(song.durationText)
        fun fold(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
        val wantTitle = fold(title)
        val wantArtist = fold(artist)
        val pick = results.filterIsInstance<SearchResult.Track>().map { it.song }.firstOrNull { video ->
            val ownChannel = (song.artistId != null && video.artistId == song.artistId) ||
                fold(video.artist).let { it == wantArtist || it == wantArtist + "vevo" || it == wantArtist + "official" }
            val lower = video.title.lowercase()
            val titled = wantTitle.isNotEmpty() && wantTitle in fold(video.title)
            val notOther = NOT_THE_VIDEO.none { it in lower }
            val length = TrackMatcher.secondsOf(video.durationText)
            val lengthOk = seconds == null || length == null || length in (seconds - 15)..(seconds + 90)
            ownChannel && titled && notOther && lengthOk
        }?.videoId
        officialBySearch[song.videoId] = pick.orEmpty()
        return pick
    }

    private val NOT_THE_VIDEO = listOf(
        "lyric", "가사", "visuali", "audio", "live", "라이브", "cover", "커버", "reaction", "리액션",
        "stage", "\uBB34\uB300", "performance", "dance practice", "\uC548\uBB34", "fancam",
        "\uC9C1\uCEA0", "karaoke", "inst", "musicbank", "\uC778\uAE30\uAC00\uC694",
        "\uC5E0\uCE74\uC6B4\uD2B8\uB2E4\uC6B4", "m countdown", "mcountdown", "\uAD50\uCC28\uD3B8\uC9D1",
        "sped up", "slowed", "1 hour", "loop", "teaser", "\uD2F0\uC800", "behind", "making",
    )

}
