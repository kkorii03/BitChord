package com.music.bitchord.ko

import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoParser
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Song Info request, kept out of the upstream repository so upgrades only
 * need the `// [KO] song-info` hook lines in SongInfoRepository.kt.
 */
internal object KoSongInfoRepository {
    private const val TAG = "BitChord"

    /** A source row's YouTube match is a nicety; the panel must not wait on it. */
    private const val CATALOGUE_LOOKUP_MS = 8_000L

    /** The album link is also a nicety; the watch description already has the facts. */
    private const val LINK_LOOKUP_MS = 5_000L

    /**
     * How long a settled panel answer is reused.
     *
     * Song Info is opened, closed and opened again from the same menu, and the
     * two entry points (the held-row dialog and the sheet's page) ask for the
     * same track. Each load used to repeat the player request, the watch
     * `next` request, the album link and the header parse. The facts here —
     * release date, credits, album — do not change between two looks at the
     * same upload; the counts can, which is why the window is short.
     */
    private const val CACHE_TTL_MS = 300_000L
    private const val CACHE_MAX = 64

    private data class Cached(val info: SongInfo, val at: Long)

    private val cache = object : LinkedHashMap<String, Cached>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) =
            size > CACHE_MAX
    }

    suspend fun load(song: Song): SongInfo? {
        val key = song.videoId
        synchronized(cache) { cache[key] }
            ?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL_MS }
            ?.let { return it.info }
        val info = loadUncached(song)
        // A failure is not an answer: the retry button and the next open must
        // be able to ask again. A blank key would collide with every other
        // row that has no identity, so it is never cached either.
        if (!info.failed && key.isNotBlank()) {
            synchronized(cache) { cache[key] = Cached(info, System.currentTimeMillis()) }
        }
        return info
    }

    private suspend fun loadUncached(song: Song): SongInfo = withContext(Dispatchers.IO) {
        val direct = SongInfoTarget.youtubeId(song)
        val resolved = if (direct == null && SongInfoTarget.needsCatalogueLookup(song)) {
            withTimeoutOrNull(CATALOGUE_LOOKUP_MS) { resolveCatalogueTarget(song) }
        } else {
            null
        }
        val target = direct ?: resolved?.videoId
        val via = when {
            direct != null -> "id"
            resolved != null -> "search"
            else -> "none"
        }
        if (target == null) {
            // No YouTube identity behind this row. The metadata already on the
            // row is still worth showing; the missing parts are simply absent.
            TrackLog.i(TAG, "song-info: no youtube id via=$via")
            return@withContext SongInfo(album = song.albumName?.takeIf { it.isNotBlank() })
        }

        val player = async { attempt { Innertube.playerResponse(target) } }
        val watch = async { attempt { Innertube.watchNext(target) } }
        val links = async {
            attempt {
                if (song.albumId == null) {
                    YtMusicRepository.trackLinks(target).getOrThrow()
                } else {
                    song
                }
            }
        }

        val playerResult = player.await()
        val watchResult = watch.await()
        val parsed = attempt {
            SongInfoParser.parseWatch(
                player = playerResult.getOrNull(),
                data = watchResult.getOrNull(),
                videoId = target,
            )
        }

        val linked = withTimeoutOrNull(LINK_LOOKUP_MS) { links.await() }
        if (linked == null) links.cancel()
        var album = song.albumName?.takeIf { it.isNotBlank() }
            ?: linked?.getOrNull()?.albumName?.takeIf { it.isNotBlank() }
        val albumId = linked?.getOrNull()?.albumId ?: song.albumId
        val header = albumId?.let { id ->
            attempt { InnertubeParser.parseBrowseHeader(Innertube.browse(id)) }.getOrNull()
        }
        album = album ?: header?.title?.takeIf { it.isNotBlank() }
        // Korean headers say "앨범 • 2010년", so the year is a token inside the
        // line rather than the whole line. Matching the line exactly dropped it
        // and left a release with no date whenever the description had none.
        val year = releaseYearFromSubtitle(header?.subtitle)

        val stats = parsed.getOrNull()
        val failed = stats == null || stats.failed
        if (failed) {
            val cause = reason(
                parsed.exceptionOrNull()
                    ?: playerResult.exceptionOrNull()
                    ?: watchResult.exceptionOrNull(),
            )
            TrackLog.w(
                TAG,
                "song-info: id=$target via=$via failed ($cause) " +
                    "links=${if (linked?.isSuccess == true) "ok" else reason(linked?.exceptionOrNull())}",
            )
        }

        SongInfo(
            album = album,
            releaseYear = year,
            releaseDate = stats?.releaseDate,
            genre = stats?.genre,
            composer = stats?.composer,
            lyricist = stats?.lyricist,
            views = stats?.views,
            likes = stats?.likes,
            comments = stats?.comments,
            failed = failed,
        )
    }

    /**
     * The YouTube song behind a source-backed row (`src:…`). Matched with the
     * same rules the substitute path uses, so a cover or a live take cannot
     * lend the panel another recording's album and credits. A local file with
     * no YouTube id is deliberately left alone: a personal recording is not
     * something a title search should guess at.
     */
    private suspend fun resolveCatalogueTarget(song: Song): Song? {
        if (song.title.isBlank() || song.artist.isBlank()) return null
        val target = TrackMatcher.targetOf(song)
        for (query in TrackMatcher.queries(target)) {
            val result = YtMusicRepository.search(query, SearchFilter.SONGS, SearchKind.CATALOG)
            if (result.isFailure) continue
            val candidates = result.getOrNull().orEmpty()
                .filterIsInstance<SearchResult.Track>()
                .map { it.song }
                .filterNot { it.isVideo }
            TrackMatcher.bestSameArtist(candidates, target)?.let { return it }
        }
        return null
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** A log-safe cause: no URL, query or key ever reaches the line. */
    private fun reason(error: Throwable?): String = when (error) {
        null -> "none"
        is io.ktor.client.plugins.ResponseException -> "http ${error.response.status.value}"
        is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
        else -> error::class.simpleName ?: "error"
    }
}
