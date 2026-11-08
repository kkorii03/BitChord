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
import com.music.bitchord.data.net.UnprovenVersions
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException

/** The audio-version search kept out of the upstream file. */
internal object KoAudioSwitch {

    private const val TAG = "BitChord"

    /** Video rows one AUDIO_SWITCH query may spend a catalogue mapping on. */
    private const val MAX_RECOVERED_ROWS = 2

    suspend fun resolveAudioUncached(song: Song): Song {
        val row = audioSwitchRow(song)
        // A row whose type is still unknown is treated as a video for timing:
        // its runtime may already include a visual intro/outro, and refusing
        // that drift is how the song version was written off.
        val target = TrackMatcher.audioSwitchTargetOf(row)
            .copy(isVideo = song.isVideo || song.typeUnknown)
        var queriesRun = 0
        var failedQueries = 0
        var videoRowsOnly = false
        val loggedRejects = HashSet<String>()
        // Rows that print this title with a second name, kept for the
        // catalogue lookup below: its album page may file the release only
        // under that other name (NPZtKA6343o / 체포하겠어).
        val bridges = LinkedHashMap<String, Song>()
        val budget = CatalogSongMapper.newBudget()
        for (query in TrackMatcher.audioSwitchQueries(row)) {
            queriesRun++
            Log.d(TAG, "audio switch: query=$query")
            val page = YtMusicRepository.search(query, SearchFilter.SONGS, SearchKind.AUDIO_SWITCH)
            if (page.isFailure) failedQueries++
            val rows = page.getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            var candidates = rows.filterNot { it.isVideo }
            KoTrackMatcher.titleBridges(rows, target).forEach { bridges.getOrPut(it.videoId) { it } }
            // The free audio rows first: a page that already answers costs no
            // mapping.
            adoptBest(rows, candidates, target, song, query.length)?.let { return it }
            // A row that only a catalogue mapping can turn into audio (an
            // official-audio upload with a 16:9 still, an MV mapped to its
            // track) used to be dropped when AUDIO_SWITCH stopped mapping
            // every row. Map only the few video rows that could be this song,
            // and only after the free rows failed.
            val recovered = recoverMappedRows(rows, row, target, budget)
            if (recovered.isNotEmpty()) {
                candidates = candidates + recovered
                adoptBest(rows, candidates, target, song, query.length)?.let { return it }
            }
            if (rows.isNotEmpty() && candidates.isEmpty()) videoRowsOnly = true
            // One line per candidate that did not become the answer, so a
            // device log names the failing check instead of only the outcome.
            for (candidate in rows) {
                if (!loggedRejects.add(candidate.videoId)) continue
                val reason = TrackMatcher.songVersionRejectReason(candidate, target, rows) ?: continue
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
        // priority: this is the row the before-audio budget is waiting on, so
        // it does not queue behind radio and shelf work (triage 2026-11-08).
        val catalogueLookup = try {
            CatalogSongMapper.mapResult(
                song,
                budget,
                allowLocalVideo = true,
                priority = true,
                bridges = bridges.values.toList(),
            )
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
        Log.w(TAG, noMatchLine(row, shape))
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
     * The first row among [candidates] the switch may adopt, or null. [rows]
     * is the whole page, which the title bridge reads as evidence.
     */
    private fun adoptBest(
        rows: List<Song>,
        candidates: List<Song>,
        target: TrackMatcher.Target,
        song: Song,
        queryLength: Int,
    ): Song? {
        TrackMatcher.bestSameArtist(candidates, target)?.let { match ->
            Log.d(
                TAG,
                "audio switch: '${song.title}' -> '${match.title}' (queryLength=$queryLength)",
            )
            YtMusicRepository.audioVersionCache[song.videoId] = match
            return match
        }
        // Music-video timing is visual timing, not the audio release's timing.
        // The manual switch may therefore use the exact official song/artist
        // match even when the video has a long intro or outro.
        TrackMatcher.bestOfficialAudioForVideo(candidates, target)?.let { match ->
            Log.d(
                TAG,
                "audio switch: accepted video/runtime drift '${song.title}' -> " +
                    "'${match.title}' (queryLength=$queryLength)",
            )
            YtMusicRepository.audioVersionCache[song.videoId] = match
            return match
        }
        // A Korean release files the song under its own name while the video
        // is titled in English, so a fanchant or stage upload printing both
        // names is what ties them together. The bridge goes first because it is
        // direct evidence; title-unverified below rests on credit and runtime.
        // Both pass through adoptAudio, so a swap the lyrics proved wrong is
        // refused on either path.
        TrackMatcher.bestTitleBridged(rows, candidates, target)?.let { (match, reading) ->
            adoptAudio(song, match, "title-bridge ($reading)")?.let { return it }
        }
        val unverified = TrackMatcher.titleUnverifiedAdoption(candidates, target)
        if (unverified != null) {
            adoptAudio(song, unverified, "title-unverified (artist+length)")?.let { adopted ->
                UnprovenVersions.note(song, adopted.videoId)
                return adopted
            }
        }
        return null
    }

    /**
     * Video rows a catalogue lookup could turn into this song's audio. Only
     * rows that already name the same title and act are tried, so a page of
     * unrelated uploads spends nothing; [MAX_RECOVERED_ROWS] bounds the rest
     * against the before-audio budget.
     */
    internal suspend fun recoverMappedRows(
        rows: List<Song>,
        row: Song,
        target: TrackMatcher.Target,
        budget: CatalogSongMapper.LookupBudget,
    ): List<Song> {
        val recoverable = rows.asSequence()
            .filter { it.videoId != row.videoId }
            .filter { recoverableForAudioSwitch(it, row, target) }
            .take(MAX_RECOVERED_ROWS)
            .toList()
        if (recoverable.isEmpty()) return emptyList()
        return recoverable.mapNotNull { candidate ->
            try {
                CatalogSongMapper.map(candidate, budget, priority = true)?.toSong(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "audio switch: mapping ${candidate.videoId} failed: ${failure.message}")
                null
            }
        }
    }

    /**
     * A video row that a catalogue mapping could make audio, and that could be
     * this song: same act and same title/version. A cover, a live take or
     * another act is never recovered.
     */
    internal fun recoverableForAudioSwitch(
        candidate: Song,
        row: Song,
        target: TrackMatcher.Target,
    ): Boolean =
        candidate.isVideo &&
            !CatalogSongMapper.isCover(candidate) &&
            (target.artist.isBlank() || TrackMatcher.sharesArtist(candidate.artist, target.artist)) &&
            TrackMatcher.sameTitle(candidate, row)

    /**
     * The miss line, with the video id so a device log names the row without
     * the title and artist alone having to be unique.
     */
    internal fun noMatchLine(row: Song, shape: String): String =
        "audio switch: no official song match videoId=${row.videoId} " +
            "for '${row.title}' by '${row.artist}'" + shape

    /**
     * The row a song-version search is built from: the upload's title and
     * channel read as the song and the artist a listener sees (see
     * [YouTubeDisplayCleaner]).
     *
     * A shared link or a row saved before the display rule existed can still
     * carry the uploader as its artist, and a deep-linked watch row can carry
     * its release's album id — which `cleanUpload` reads as "already
     * display-ready" and skips. The search then asked for 'HYPE UNIVERSE'
     * while the player showed 'ILLIT' (phone 11-06, vc148). The display rule
     * is asked directly here instead; the row's other fields (album id, queue
     * identity, runtime) are kept, because the match still compares them.
     *
     * Every check below — the query, the bridge, the title-unverified adoption
     * and the miss line — is made against this row, so they all agree on who
     * the artist is.
     *
     * A cover is left exactly as it is: its own title is what the switch must
     * refuse, not the song it covers.
     */
    internal fun audioSwitchRow(song: Song): Song {
        if (CoverTitles.isCover(song)) return song
        val credit = song.artist.trim()
        return YouTubeDisplayCleaner.clean(song, if (credit.isEmpty()) emptySet() else setOf(credit))
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
}
