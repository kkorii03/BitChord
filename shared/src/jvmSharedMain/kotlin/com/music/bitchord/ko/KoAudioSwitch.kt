package com.music.bitchord.ko

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.CatalogSongMapper
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/** The audio-version search kept out of the upstream file. */
internal object KoAudioSwitch {

    private const val TAG = "BitChord"

    suspend fun resolveAudioUncached(song: Song): Song =
        resolveAudioUncached(song) { query ->
            YtMusicRepository.search(query, SearchFilter.SONGS, SearchKind.AUDIO_SWITCH)
        }

    /** The search seam a plain JVM test uses to check the query pass without a network. */
    internal suspend fun resolveAudioUncached(
        song: Song,
        search: suspend (String) -> Result<List<SearchResult>>,
    ): Song {
        // A row whose type is still unknown is treated as a video for timing:
        // its runtime may already include a visual intro/outro, and refusing
        // that drift is how the song version was written off.
        val target = TrackMatcher.audioSwitchTargetOf(song)
            .copy(isVideo = song.isVideo || song.typeUnknown)
        // Run every query at once and judge each answer as it arrives.
        // Sequentially, a second query that would have found the official
        // song started only after the first one's answer, so the pre-playback
        // budget expired while the useful search was still queued
        // ("kept (timeout)"). Whichever query answers with an accepted
        // recording first wins; every accepted row has already cleared the
        // same title/artist/cover gates, so this changes only the wait, not
        // what may play.
        val queries = TrackMatcher.audioSwitchQueries(song)
        var queriesRun = 0
        var failedQueries = 0
        var videoRowsOnly = false
        val loggedRejects = HashSet<String>()
        val match = coroutineScope {
            val answers = Channel<Pair<String, Result<List<SearchResult>>>>(Channel.UNLIMITED)
            val jobs = queries.map { query ->
                launch {
                    Log.d(TAG, "audio switch: query=$query")
                    answers.send(query to search(query))
                }
            }
            try {
                repeat(queries.size) {
                    val (query, page) = answers.receive()
                    queriesRun++
                    if (page.isFailure) failedQueries++
                    val rows = page.getOrNull()
                        ?.filterIsInstance<SearchResult.Track>()
                        ?.map { it.song }
                        .orEmpty()
                    val candidates = rows.filterNot { it.isVideo }
                    if (rows.isNotEmpty() && candidates.isEmpty()) videoRowsOnly = true
                    TrackMatcher.bestSameArtist(candidates, target)?.let { found ->
                        Log.d(
                            TAG,
                            "audio switch: '${song.title}' -> '${found.title}' " +
                                "(queryLength=${query.length})",
                        )
                        return@coroutineScope found
                    }
                    // Music-video timing is visual timing, not the audio
                    // release's timing. The manual switch may therefore use
                    // the exact official song/artist match even when the video
                    // has a long intro or outro.
                    TrackMatcher.bestOfficialAudioForVideo(candidates, target)?.let { found ->
                        Log.d(
                            TAG,
                            "audio switch: accepted video/runtime drift '${song.title}' -> " +
                                "'${found.title}' (queryLength=${query.length})",
                        )
                        return@coroutineScope found
                    }
                    // One line per candidate that did not become the answer,
                    // so a device log names the failing check instead of only
                    // the outcome.
                    for (candidate in rows) {
                        if (!loggedRejects.add(candidate.videoId)) continue
                        val reason = TrackMatcher.songVersionRejectReason(candidate, target)
                            ?: continue
                        Log.d(
                            TAG,
                            "audio switch: reject ${candidate.videoId} ${candidate.title} " +
                                "reason=$reason",
                        )
                    }
                }
                null
            } finally {
                // A match or a failure ends the pass; no query needs to finish
                // once its answer cannot change the outcome.
                jobs.forEach { it.cancel() }
                answers.close()
            }
        }
        if (match != null) {
            YtMusicRepository.audioVersionCache[song.videoId] = match
            return match
        }
        // The search answered with uploads only, or nothing at all: the
        // catalogue's own album listing still names the release behind a
        // video, even where Korea's Songs filter refuses the anonymous
        // search. This is the same mapping the gated search uses, asked
        // directly for this one row. A downloaded video still qualifies: the
        // lookup reads album metadata only and never touches its file.
        val catalogueLookup = try {
            CatalogSongMapper.mapResult(song, allowLocalVideo = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "audio switch: catalogue lookup failed for '${song.title}'", failure)
            CatalogSongMapper.MapResult.Failed
        }
        val catalogue =
            (catalogueLookup as? CatalogSongMapper.MapResult.Found)?.song?.toSong(song)
        if (catalogue != null && catalogue.videoId != song.videoId && !catalogue.isVideo) {
            Log.d(TAG, "audio switch: '${song.title}' -> '${catalogue.title}' (catalogue)")
            YtMusicRepository.audioVersionCache[song.videoId] = catalogue
            return catalogue
        }
        Log.w(
            TAG,
            "audio switch: no official song match for '${song.title}' by '${song.artist}'" +
                if (videoRowsOnly) " (video rows only)" else "",
        )
        // A search that failed outright is no verdict: leave it retryable
        // instead of remembering the video as having no catalogue release.
        // The same applies when only some of the queries answered — a later
        // failure may have hidden the release an earlier query missed.
        if (
            SongVersionPolicy.audioSwitchSearchIsConclusive(
                queriesRun,
                failedQueries,
                catalogueFailed = catalogueLookup is CatalogSongMapper.MapResult.Failed,
            )
        ) {
            YtMusicRepository.audioVersionCache[song.videoId] = song
        }
        return song
    }

}
