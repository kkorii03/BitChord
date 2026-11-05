package com.music.bitchord.ko

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.CatalogSongMapper
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YouTubeDisplayCleaner
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.lyrics.LyricsMismatch
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/** The audio-version search kept out of the upstream file. */
internal object KoAudioSwitch {

    private const val TAG = "BitChord"

    suspend fun resolveAudioUncached(song: Song): Song {
        // A shared link or a row saved before the display rule existed can
        // still carry the uploader as its artist. Both the query and the
        // match are built from the credit the title states, or the search is
        // spent on a channel name no catalogue row carries (phone 11-06).
        // A cover is left exactly as it is: its own title is what the switch
        // must refuse, not the song it covers.
        val row = if (CoverTitles.isCover(song)) song else YouTubeDisplayCleaner.cleanUpload(song)
        // A row whose type is still unknown is treated as a video for timing:
        // its runtime may already include a visual intro/outro, and refusing
        // that drift is how the song version was written off.
        val target = TrackMatcher.audioSwitchTargetOf(row)
            .copy(isVideo = song.isVideo || song.typeUnknown)
        var queriesRun = 0
        var failedQueries = 0
        var videoRowsOnly = false
        val loggedRejects = HashSet<String>()
        for (query in TrackMatcher.audioSwitchQueries(row)) {
            queriesRun++
            Log.d(TAG, "audio switch: query=$query")
            val page = YtMusicRepository.search(query, SearchFilter.SONGS, SearchKind.AUDIO_SWITCH)
            if (page.isFailure) failedQueries++
            val rows = page.getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            val candidates = rows.filterNot { it.isVideo }
            if (rows.isNotEmpty() && candidates.isEmpty()) videoRowsOnly = true
            TrackMatcher.bestSameArtist(candidates, target)?.let { match ->
                Log.d(
                    TAG,
                    "audio switch: '${song.title}' -> '${match.title}' (queryLength=${query.length})",
                )
                YtMusicRepository.audioVersionCache[song.videoId] = match
                return match
            }
            // Music-video timing is visual timing, not the audio release's
            // timing. The manual switch may therefore use the exact official
            // song/artist match even when the video has a long intro or outro.
            TrackMatcher.bestOfficialAudioForVideo(candidates, target)?.let { match ->
                Log.d(
                    TAG,
                    "audio switch: accepted video/runtime drift '${song.title}' -> " +
                        "'${match.title}' (queryLength=${query.length})",
                )
                YtMusicRepository.audioVersionCache[song.videoId] = match
                return match
            }
            // A Korean release files the song under its own name while the
            // video is titled in English ("체포하겠어" / "You're Under
            // Arrest"), so the title cannot be compared at all. A fanchant or
            // stage upload on this same page prints both names, and that row is
            // what ties them together; see TrackMatcher.bestTitleBridged for
            // the artist and runtime checks that keep it from playing a
            // different song.
            TrackMatcher.bestTitleBridged(rows, candidates, target)?.let { (match, reading) ->
                Log.d(
                    TAG,
                    "audio switch: accept ${match.videoId} via title-bridge ($reading)",
                )
                YtMusicRepository.audioVersionCache[song.videoId] = match
                return match
            }
            // The page that would print both names is not always returned (the
            // phone's 11-06 page held only the MV and the Korean album row), so
            // the bridge above cannot fire. The credit and the runtime still
            // agree, and that is the grade the quality upgrade already trusts
            // for exactly this song — see
            // TrackMatcher.isAudioSwitchTitleUnverified. The nearest runtime
            // wins, and a cover, karaoke or remix row is refused inside it.
            val unverified = candidates
                .filter { TrackMatcher.isAudioSwitchTitleUnverified(it, target) }
                .minByOrNull { distanceFromTarget(it, target) }
            if (unverified != null) {
                adoptAudio(song, unverified, "title-unverified (artist+length)")?.let { return it }
            }
            // One line per candidate that did not become the answer, so a
            // device log names the failing check instead of only the outcome.
            for (candidate in rows) {
                if (!loggedRejects.add(candidate.videoId)) continue
                val reason = TrackMatcher.songVersionRejectReason(candidate, target) ?: continue
                Log.d(
                    TAG,
                    "audio switch: reject ${candidate.videoId} ${candidate.title} " +
                        "reason=$reason",
                )
            }
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
        if (catalogue != null && SongVersionPolicy.isSongVersionOf(song, catalogue)) {
            Log.d(TAG, "audio switch: '${song.title}' -> '${catalogue.title}' (catalogue)")
            YtMusicRepository.audioVersionCache[song.videoId] = catalogue
            return catalogue
        }
        // A row whose type never landed is looked up as a video, so the log has
        // to say so: it is not the same miss as a page that held no audio row.
        val shape = (if (videoRowsOnly) " (video rows only)" else "") +
            (if (song.typeUnknown) " (type unknown)" else "")
        Log.w(
            TAG,
            "audio switch: no official song match for '${song.title}' by '${song.artist}'" + shape,
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

    /**
     * Writes the swap, unless the sound it would play is already known to be
     * another song. The lyrics lookup throws a sheet away when what is being
     * sung is not in it, and two providers' sheets failing the same sound is
     * the sound — see `LyricsMismatch.wrongSound`. Reading that verdict costs
     * no request and no decode, which is the whole point: the check must not
     * listen to anything of its own. The original row is kept and the log says
     * why, in the same shape as the accept line.
     */
    private fun adoptAudio(song: Song, match: Song, via: String): Song? {
        LyricsMismatch.wrongSoundNote(match.videoId)?.let { note ->
            Log.w(TAG, note)
            return null
        }
        Log.d(TAG, "audio switch: accept ${match.videoId} via $via")
        YtMusicRepository.audioVersionCache[song.videoId] = match
        return match
    }

    /** How far a row's stated runtime sits from the video's, for picking between equals. */
    private fun distanceFromTarget(candidate: Song, target: TrackMatcher.Target): Int {
        val got = TrackMatcher.secondsOf(candidate.durationText) ?: return Int.MAX_VALUE
        return abs((target.durationSec ?: 0) - got)
    }

}
