package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.albumKind
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

/**
 * Turns a youtube.com fallback row into the catalogue song behind it.
 *
 * In Korea without Premium, `music.youtube.com` answers searches for songs with
 * no rows while its *albums* search (and every album page it links to) still
 * answers anonymously. Those album pages list real YouTube Music tracks with
 * square catalogue artwork, so a video upload can be replaced by the recording
 * the listener actually asked for instead of showing an MV title, an uploader
 * channel and a widescreen still.
 *
 * The lookup is deliberately lazy and bounded: [mapPage] only touches the rows
 * a shelf is about to show, [LookupBudget] caps the new lookups one screen
 * build may spend, [MAX_CONCURRENT_TOTAL] caps requests in flight, album pages live
 * in a small LRU, and every settled answer (including "not found") is persisted
 * per source video id. A miss is retried after [MISS_RETRY_MS], and only
 * answers the query that missed ([missCovers]); a hit is not re-asked.
 */
internal object CatalogSongMapper {

    /** A catalogue track that stands in for a youtube.com upload. */
    data class CatalogSong(
        val videoId: String,
        val title: String,
        val artist: String,
        val albumTitle: String,
        val albumId: String,
        val artworkUrl: String?,
        val durationText: String? = null,
    ) {
        /** The fallback row with the catalogue identity and metadata applied. */
        fun toSong(source: Song): Song {
            // A video's file is the MV itself, not this catalogue recording.
            // Carrying its localUri/localPath onto the catalogue id would make
            // playback install the song id but open the video's file, playing
            // the MV sound the mapping exists to replace. The resolved id
            // streams, or uses the file verified for that id, instead.
            val localVideo = isVideoSource(source) && source.localUri != null
            return source.copy(
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = artworkUrl ?: source.thumbnailUrl,
                durationText = durationText ?: source.durationText,
                albumId = albumId,
                albumName = albumTitle,
                artistId = null,
                artists = emptyList(),
                setVideoId = null,
                isVideo = false,
                typeUnknown = false,
                isVideoOrigin = false,
                localUri = if (localVideo) null else source.localUri,
                localPath = if (localVideo) null else source.localPath,
                // The release title above is what a listener reads, but the
                // upload's own title is the only row that prints both names of
                // the song ("체포하겠어 (You're Under Arrest)"). Dropping it is
                // what left a Korean release and its English music video with
                // nothing to meet on; see TrackMatcher.bestTitleBridged.
                uploadTitle = source.uploadTitle
                    ?: source.title.trim().takeIf { it.isNotBlank() && it != title },
            )
        }
    }

    /**
     * A row that is, or may still be, a music-video upload. Its local file is
     * the video, so it must not be mistaken for the catalogue recording.
     */
    internal fun isVideoSource(song: Song): Boolean = song.isVideo || song.typeUnknown

    /**
     * New lookups one screen build may start. Cached answers are free, so a
     * revisit never spends any of this; only genuinely new video ids do.
     */
    class LookupBudget(private val limit: Int = MAX_NEW_LOOKUPS) {
        private var remaining = limit.coerceAtLeast(0)

        /** Claims one lookup; false once the build's allowance is spent. */
        @Synchronized
        fun take(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }

        @Synchronized
        fun remaining(): Int = remaining

        @Synchronized
        fun used(): Int = limit - remaining
    }

    /** At most this many album pages are held in memory, least recently used first out. */
    const val ALBUM_PAGE_CACHE_SIZE = 40

    /** At most this many album pages are opened for one song. */
    const val MAX_ALBUMS_PER_SONG = 3

    /** Album results considered when a search query is enriched. */
    const val MAX_ALBUMS_PER_SEARCH = 2

    /** New song lookups one screen build may start. */
    const val MAX_NEW_LOOKUPS = 30

    /** Only the visible head of a shelf or search page is mapped. */
    const val MAX_ROWS_PER_PAGE = 20

    /** Requests in flight at once in the shared lane; battery over latency. */
    const val MAX_CONCURRENT = 2

    /**
     * Requests in flight at once across the process: the [MAX_CONCURRENT]
     * shared slots plus the current row's own single-permit lane.
     */
    const val MAX_CONCURRENT_TOTAL = MAX_CONCURRENT + 1

    /** How long a "not in the catalogue" answer is trusted. */
    const val MISS_RETRY_MS = 7L * 24L * 60L * 60L * 1000L

    /** Both runtimes have to agree this closely when both are stated. */
    const val MAX_DURATION_DRIFT_SEC = 10

    private const val CACHE_ENTRIES = 3_000
    // v4: a stored "not in the catalogue" answer written before a lookup's
    // deadline was respected may have been produced by a request that never
    // finished, so it must be revalidated once instead of being trusted for a
    // week. v3 retired answers from the old 10-second song window, and v2 the
    // same class of verdict when the video-source runtime window changed (The
    // Hills). The v3 store is migrated below: its settled hits are kept and only
    // its misses are looked up again, so the bump does not spend a lookup on
    // every track the user had already seen resolved.
    private const val STORE_KEY = "catalog_song_mapping_v4"
    private const val LEGACY_STORE_KEY = "catalog_song_mapping_v3"
    private const val NONE = "none"
    private const val FIELD = "\u001F"
    private const val TAG = "BitChord"

    /**
     * The mapper only ever runs on the gated fallback. Keeping the semaphore
     * here rather than in each caller means two shelves built at once still
     * share the same two-slot ceiling.
     */
    private val inFlight = Semaphore(MAX_CONCURRENT)

    /**
     * The row the listener is on gets its own lane: its answer is what the
     * before-audio budget waits for, and the shared [inFlight] slots are held
     * by radio and shelf mapping for tens of seconds (triage 2026-11-08). The
     * lane is one separate permit, so the process-wide ceiling is
     * [MAX_CONCURRENT_TOTAL] ([MAX_CONCURRENT] + 1), not [MAX_CONCURRENT].
     */
    private val currentRowInFlight = Semaphore(1)

    private val cache = StoredVerdictCache(
        STORE_KEY,
        CACHE_ENTRIES,
        legacyKey = LEGACY_STORE_KEY,
        legacyDropValue = NONE,
    )

    /**
     * Album pages already read in this process. Access order, so the page
     * opened first is the one dropped when the fortieth new one arrives.
     */
    private val albumPages = object : LinkedHashMap<String, List<Song>>(
        ALBUM_PAGE_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>) =
            size > ALBUM_PAGE_CACHE_SIZE
    }

    fun newBudget(): LookupBudget = LookupBudget()

    /**
     * The catalogue recording behind [song], or null when the catalogue does
     * not confirm one (or the build has no lookup left).
     *
     * Covers are never mapped to the song they cover: their upload is the
     * performance the listener chose.
     *
     * [allowLocalVideo] is for the song-version lookup of a downloaded video
     * row (including one whose type has not landed yet): the mapping only
     * reads album metadata, never the local file, so a file on the device must
     * not hide the catalogue release behind it. Local *audio* is still left
     * alone — it is already the recording to play.
     */
    suspend fun map(
        song: Song,
        budget: LookupBudget = newBudget(),
        allowLocalVideo: Boolean = false,
        priority: Boolean = false,
    ): CatalogSong? =
        when (val result = mapResult(song, budget, allowLocalVideo, priority)) {
            is MapResult.Found -> result.song
            else -> null
        }

    /**
     * [map] with the lookup's outcome kept. A caller that remembers "no song
     * version" must tell a completed miss from a transient failure: only the
     * former may be cached for the rest of the run.
     *
     * [priority] is for the row the listener is on: its lookup takes
     * [currentRowInFlight] instead of queueing behind radio and shelf work.
     */
    internal suspend fun mapResult(
        song: Song,
        budget: LookupBudget = newBudget(),
        allowLocalVideo: Boolean = false,
        priority: Boolean = false,
        /**
         * Rows the caller already holds that print this song's title together
         * with a second name for it (see `KoTrackMatcher.titleBridges`). An
         * album page may file the release only under that other name, which
         * no title comparison can reach (NPZtKA6343o: the MV is "You're Under
         * Arrest", BREAK EVEN lists only "체포하겠어").
         */
        bridges: List<Song> = emptyList(),
    ): MapResult {
        if (song.videoId.isBlank()) return MapResult.NotFound
        if (song.localUri != null &&
            !(allowLocalVideo && isVideoSource(song))
        ) {
            return MapResult.NotFound
        }
        if (isCover(song) || isCoverTitle(song.title)) return MapResult.NotFound
        // A row already carrying a release identity is catalogue metadata, not
        // a youtube.com upload; there is nothing to map and no reason to spend
        // a lookup on it.
        if (song.albumId != null && !song.isVideo) return MapResult.NotFound

        val cleaned = YouTubeDisplayCleaner.clean(song)
        val title = cleaned.title.trim()
        val artist = cleaned.artist.trim()
        if (title.isBlank() || artist.isBlank()) return MapResult.NotFound
        if (isCoverTitle(title)) return MapResult.NotFound

        cache.ensureLoaded(MusicGateFallback.sharedStore())
        val now = System.currentTimeMillis()
        val query = lookupQuery(artist, title)
        val stored = cache.get(song.videoId)
        if (stored != null) {
            if (!isMiss(stored.value)) {
                decode(stored.value)?.let { return MapResult.Found(it) }
            } else if (
                bridges.isEmpty() &&
                missCovers(stored.value, query) &&
                !shouldRetryMiss(stored.at, now)
            ) {
                // A stored miss was read without bridging rows, so it says
                // nothing about the other name they bring; only that case
                // re-asks. The audio switch's own per-run cache keeps it to
                // once per row.
                // It also answers only the album search that missed: the same
                // upload read under another title asks a different question
                // (see [missCovers]).
                return MapResult.NotFound
            }
        }

        // No lookup was spent, so this is not a completed miss: keep the
        // verdict retryable rather than caching it as "no catalogue release".
        if (!budget.take()) return MapResult.Failed

        val outcome = try {
            withLookupPermit(priority) {
                resolve(
                    song.videoId,
                    title,
                    artist,
                    song.durationText,
                    sourceIsVideo = isVideoSource(song),
                    bridges = bridges,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "catalogue mapping failed for ${song.videoId}: ${failure.message}")
            Outcome.Failed
        }

        return when (outcome) {
            is Outcome.Found -> {
                cache.put(song.videoId, encode(outcome.song), now)
                MapResult.Found(outcome.song)
            }
            Outcome.NotFound -> {
                // Earlier queries' misses are kept while they are still
                // trusted, so two titles that both miss do not re-ask in turn.
                val earlier = stored?.takeIf { !shouldRetryMiss(it.at, now) }?.value
                cache.put(song.videoId, missValue(earlier, query), now)
                MapResult.NotFound
            }
            Outcome.Failed -> MapResult.Failed
        }
    }

    /** [mapResult]'s answer: a mapped song, a completed miss, or a retryable failure. */
    internal sealed interface MapResult {
        data class Found(val song: CatalogSong) : MapResult
        data object NotFound : MapResult
        data object Failed : MapResult
    }

    /**
     * Maps the first [limit] rows of a shelf/page and returns every row in the
     * same order. Unmapped rows keep the display metadata they came in with.
     */
    suspend fun mapPage(
        songs: List<Song>,
        budget: LookupBudget = newBudget(),
        limit: Int = MAX_ROWS_PER_PAGE,
    ): List<Song> {
        if (songs.isEmpty() || limit <= 0) return songs
        val shown = songs.take(limit)
        val out = ArrayList<Song>(songs.size)
        for (song in shown) {
            val mapped = try {
                map(song, budget)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "catalogue mapping skipped ${song.videoId}: ${failure.message}")
                null
            }
            out += mapped?.toSong(song) ?: song
        }
        out += songs.drop(shown.size)
        return out
    }

    /**
     * [mapPage] for a shelf whose rows are already collection items. Collection
     * cards (no video id) are left alone, and mapped rows take the catalogue
     * track's id so playback never lands back on the upload.
     */
    suspend fun mapShelf(
        shelf: HomeShelf,
        budget: LookupBudget = newBudget(),
        limit: Int = MAX_ROWS_PER_PAGE,
        sources: Map<String, Song> = emptyMap(),
    ): HomeShelf {
        val indexes = shelf.items.indices
            .filter { shelf.items[it].videoId != null }
            .take(limit)
        if (indexes.isEmpty()) return shelf
        val songs = indexes.map { index ->
            val item = shelf.items[index]
            val videoId = item.videoId.orEmpty()
            // The shelf row only carries display strings; the local play it
            // came from still knows whether this was a video upload or an
            // already-catalogue track, and that decides whether a lookup is
            // worth spending.
            sources[videoId]?.copy(
                videoId = videoId,
                title = item.title,
                artist = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            ) ?: Song(
                videoId = videoId,
                title = item.title,
                artist = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
        }
        val mapped = mapPage(songs, budget, songs.size)
        val byIndex = indexes.zip(mapped).toMap()
        return shelf.copy(
            items = shelf.items.mapIndexed { index, item ->
                val song: Song = byIndex[index] ?: return@mapIndexed item
                item.copy(
                    title = song.title,
                    subtitle = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = song.videoId,
                )
            },
        )
    }

    /**
     * A gated youtube.com search answer with catalogue rows in front.
     *
     * The search's own album results are the shortcut a "songs" filter cannot
     * provide in Korea: the top [MAX_ALBUMS_PER_SEARCH] albums are opened and
     * every track whose title matches the typed query is prepended, then the
     * youtube.com rows are mapped as usual. Callers still decide how many rows
     * to show and may run the cover resolver over whatever is left unmapped.
     *
     * A [SearchKind.AUDIO_SWITCH] page is a different question: the caller is
     * reading the one row it already holds, so the uploads beside it must not
     * each spend an album search (twenty of them, triage 2026-11-08). Its
     * album shortcut also takes [currentRowInFlight], so the row the listener
     * is waiting on does not queue behind radio and shelf work.
     */
    suspend fun forSearch(
        query: String,
        youtubeSongs: List<Song>,
        limit: Int = MAX_ROWS_PER_PAGE,
        kind: SearchKind = SearchKind.TYPED,
    ): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isBlank() && youtubeSongs.isEmpty()) return youtubeSongs
        val budget = newBudget()
        val priority = kind == SearchKind.AUDIO_SWITCH
        val out = LinkedHashMap<String, Song>()

        if (trimmed.isNotBlank() && budget.take()) {
            val albums = try {
                withLookupPermit(priority) { searchAlbums(trimmed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "album search failed length=${trimmed.length}: ${failure.message}")
                null
            }.orEmpty()

            for (album in searchAlbumsFor(albums)) {
                if (budget.remaining() <= 0) break
                budget.take()
                val tracks = try {
                    withLookupPermit(priority) { albumTracks(album.browseId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    null
                }.orEmpty()
                var taken = 0
                for (track in tracks) {
                    if (taken >= MAX_TRACKS_PER_SEARCH_ALBUM) break
                    if (track.videoId.isBlank() || isCoverTitle(track.title)) continue
                    if (!trackMatchesQuery(trimmed, track.title)) continue
                    val catalog = catalogOf(track, album, null) ?: continue
                    out.putIfAbsent(catalog.videoId, catalog.toSong(track))
                    taken++
                }
            }
        }

        val mapsRows = mapsPageRows(kind)
        if (youtubeSongs.isNotEmpty()) {
            for (song in youtubeSongs.take(limit)) {
                val mapped = if (mapsRows) {
                    try {
                        map(song, budget, priority = priority)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        null
                    }
                } else {
                    null
                }
                val row = mapped?.toSong(song) ?: YouTubeDisplayCleaner.clean(song)
                out.putIfAbsent(row.videoId, row)
            }
            for (song in youtubeSongs.drop(limit)) {
                val row = YouTubeDisplayCleaner.clean(song)
                out.putIfAbsent(row.videoId, row)
            }
        }

        return out.values.toList()
    }

    /**
     * Whether a page's own upload rows are looked up one by one. The
     * song-version search reads the row it was given; the uploads on the page
     * around it are somebody else's songs, and each one cost an album search.
     */
    internal fun mapsPageRows(kind: SearchKind): Boolean = kind != SearchKind.AUDIO_SWITCH

    /**
     * Whether a gated Songs page has square covers looked up for its uploads
     * before it is returned. A song-version page is read by the matcher and
     * never shown, and that iTunes + Deezer pass (two requests per upload, up
     * to twenty uploads, behind the app's shared four-slot cover gate) was
     * 4-19 s of a 7-45 s lookup on the rs18-rs20 emulators: the song version
     * then landed after the music video was already audible.
     */
    internal fun enrichesCovers(kind: SearchKind): Boolean = kind != SearchKind.AUDIO_SWITCH

    // ---- Lookup -------------------------------------------------------------

    /** One album search or page read, in the shared ceiling or in the current row's lane. */
    private suspend fun <T> withLookupPermit(priority: Boolean, block: suspend () -> T): T =
        if (priority) currentRowInFlight.withPermit { block() } else inFlight.withPermit { block() }

    /**
     * One line per catalogue miss: which exit it took, and how far the lookup
     * got. The old line (`no matching track`) printed only when an album page
     * had opened, so a miss with no album naming the artist said nothing at
     * all, and neither line could say where the chain stopped
     * (triage 2026-11-08).
     *
     * Only identities and counts reach this line — the source video id, the
     * query's length and hash, and the exit reason. The fork's log carries no
     * typed text, so neither the song's title, its artist nor the query may be
     * written; the id is what a device log can be joined back to.
     */
    internal fun missLine(
        sourceVideoId: String,
        sourceIsVideo: Boolean,
        reason: String,
        query: String,
        albums: Int,
        matching: Int,
        pages: Int,
        tracks: Int,
        candidates: Int,
    ): String =
        "catalogue mapping: no matching track videoId=$sourceVideoId" +
            (if (sourceIsVideo) " (video source)" else "") +
            " reason=$reason albums=$albums matching=$matching pages=$pages" +
            " tracks=$tracks candidates=$candidates" +
            " queryLength=${query.length} queryHash=${Integer.toHexString(query.hashCode())}"

    private sealed interface Outcome {
        data class Found(val song: CatalogSong) : Outcome
        data object NotFound : Outcome
        data object Failed : Outcome
    }

    private suspend fun resolve(
        sourceVideoId: String,
        title: String,
        artist: String,
        duration: String?,
        /**
         * The source is a music-video upload. Its stated runtime includes a
         * visual intro/outro, so the album track may legitimately be a minute
         * shorter; a song-to-song comparison keeps the tight window.
         */
        sourceIsVideo: Boolean = false,
        bridges: List<Song> = emptyList(),
    ): Outcome {
        val query = lookupQuery(artist, title)
        val albums = searchAlbums(query)
        if (albums == null) {
            Log.i(
                TAG,
                missLine(sourceVideoId, sourceIsVideo, "album-search-failed", query, 0, 0, 0, 0, 0),
            )
            return Outcome.Failed
        }
        val matching = albumPagesFor(albums, artist)
        if (matching.isEmpty()) {
            Log.i(
                TAG,
                missLine(
                    sourceVideoId,
                    sourceIsVideo,
                    "no-album-artist",
                    query,
                    albums.size,
                    0,
                    0,
                    0,
                    0,
                ),
            )
            return Outcome.NotFound
        }

        val target = TrackMatcher.Target(
            title = title,
            artist = artist,
            durationSec = TrackMatcher.secondsOf(duration),
            isVideo = sourceIsVideo,
        )
        // A Korean release often files the same song under the other spelling:
        // the upload says "Friday(금요일에 만나요)" and the album row says only
        // "금요일에 만나요". Those bracketed spellings are real titles, not
        // packaging, so each is tried with the same artist and runtime.
        val targets = buildList {
            add(target)
            alternateTitles(title).forEach { alternative ->
                add(target.copy(title = alternative))
            }
        }
        var opened = 0
        var failed = false
        var tracksRead = 0
        var candidatesRead = 0
        for (album in matching) {
            val tracks = albumTracks(album.browseId)
            if (tracks == null) {
                failed = true
                continue
            }
            opened++
            tracksRead += tracks.size
            val candidates = tracks
                .asSequence()
                .filter { it.videoId != sourceVideoId }
                .filter { !isCoverTitle(it.title) }
                .filter {
                    durationCompatible(
                        duration,
                        it.durationText,
                        if (sourceIsVideo) TrackMatcher.VIDEO_DURATION_LIMIT_SEC else MAX_DURATION_DRIFT_SEC,
                        allowVideoLongerThanSong = sourceIsVideo,
                    )
                }
                .toList()
            candidatesRead += candidates.size
            val match = albumTrackFor(candidates, targets, sourceIsVideo)
                ?: bridgedAlbumTrack(candidates, target, bridges)?.also {
                    Log.i(TAG, "catalogue mapping: bridged videoId=$sourceVideoId -> ${it.videoId}")
                }
                ?: continue
            val catalog = catalogOf(match, album, artist) ?: continue
            if (catalog.videoId.isBlank()) continue
            return Outcome.Found(catalog)
        }
        val reason = when {
            opened == 0 && failed -> "page-failed"
            opened == 0 -> "no-page"
            candidatesRead == 0 -> "no-candidates"
            else -> "no-track"
        }
        Log.i(
            TAG,
            missLine(
                sourceVideoId,
                sourceIsVideo,
                reason,
                query,
                albums.size,
                matching.size,
                opened,
                tracksRead,
                candidatesRead,
            ),
        )
        return if (opened > 0) Outcome.NotFound else if (failed) Outcome.Failed else Outcome.NotFound
    }

    /** Anonymous WEB_REMIX album search, or null when the request itself failed. */
    private suspend fun searchAlbums(query: String): List<BrowseItem>? = try {
        val root = Innertube.searchAnonymous(
            query = query,
            params = SearchFilter.ALBUMS.params,
            hl = "ko",
            gl = "KR",
            kind = SearchKind.CATALOG,
        )
        InnertubeParser.parseSearch(root)
            .filterIsInstance<SearchResult.Browse>()
            .map { it.item }
            .filter { it.type == BrowseType.ALBUM }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w(TAG, "album search failed: ${failure.message}")
        null
    }

    /** One anonymous album page's tracks, from the LRU when it has been read. */
    private suspend fun albumTracks(browseId: String): List<Song>? {
        if (browseId.isBlank()) return null
        synchronized(albumPages) { albumPages[browseId] }?.let { return it }
        val root = try {
            Innertube.browseAnonymous(browseId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "album page $browseId failed: ${failure.message}")
            return null
        }
        val tracks = InnertubeParser.collectSongsDeep(root)
        if (tracks.isEmpty()) return null
        synchronized(albumPages) { albumPages[browseId] = tracks }
        return tracks
    }

    private fun catalogOf(track: Song, album: BrowseItem, fallbackArtist: String?): CatalogSong? {
        if (track.videoId.isBlank() || track.title.isBlank()) return null
        val artist = track.artist.trim()
            .takeUnless { it.isBlank() || it.equals("Unknown artist", ignoreCase = true) }
            ?: fallbackArtist?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return CatalogSong(
            videoId = track.videoId,
            title = track.title.trim(),
            artist = artist,
            albumTitle = album.title.trim(),
            albumId = album.browseId,
            artworkUrl = squareArtwork(album.thumbnailUrl) ?: squareArtwork(track.thumbnailUrl),
            durationText = track.durationText,
        )
    }

    // ---- Pure rules (tested without a network) ------------------------------

    /**
     * The track on one album page that is the song, by title: [targets] holds
     * the source's title and its bracketed spellings, all with the same artist
     * and runtime.
     *
     * The ordinary matcher first, so every mapping that works today keeps
     * working unchanged. A video's runtime is visual timing, though: its album
     * track may be minutes shorter (intro/skit/outro) and is still the song,
     * which the ordinary matcher rightly refuses. Only that gap uses the
     * video-switch rule, and only when title, versions and artist agree.
     */
    internal fun albumTrackFor(
        candidates: List<Song>,
        targets: List<TrackMatcher.Target>,
        sourceIsVideo: Boolean,
    ): Song? = targets.firstNotNullOfOrNull { wanted ->
        TrackMatcher.best(candidates, wanted)
            ?: if (sourceIsVideo) {
                TrackMatcher.bestOfficialAudioForVideo(candidates, wanted)
            } else {
                null
            }
    }

    /**
     * The track on one album page that is [target]'s song under its other
     * name, proven by a [bridges] row that prints both names, or null.
     *
     * Asked only after [albumTrackFor] found nothing. It is the audio switch's
     * own title bridge ([TrackMatcher.bestTitleBridged]) with the album page's
     * tracks as the candidates, so the same take, act and runtime checks stand
     * between the wider title and a wrong track. With no bridging row nothing
     * is guessed.
     */
    internal fun bridgedAlbumTrack(
        candidates: List<Song>,
        target: TrackMatcher.Target,
        bridges: List<Song>,
    ): Song? {
        if (bridges.isEmpty() || candidates.isEmpty()) return null
        return TrackMatcher.bestTitleBridged(bridges, candidates, target)?.first
    }

    /** "cover by X", a bracketed cover tag, or the Korean word. */
    private val COVER_MARKER = Regex(
        """(?i)(?<![a-z])cover(?:ed)?\s*by|[(\[（【]\s*cover(?:ed)?\s*[)\]）】]|커버""",
    )

    /** Whether a title says it is a cover rather than the original recording. */
    internal fun isCoverTitle(title: String): Boolean =
        title.isNotBlank() && COVER_MARKER.containsMatchIn(title)

    /** A structural cover (already read by [CoverTitles]) or a cover-shaped title. */
    internal fun isCover(song: Song): Boolean =
        CoverTitles.isCover(song) || isCoverTitle(song.title)

    /** Both runtimes stated and no further apart than [limitSec]. */
    internal fun durationCompatible(
        source: String?,
        candidate: String?,
        limitSec: Int = MAX_DURATION_DRIFT_SEC,
        /**
         * The source is a music-video upload. Its stated runtime includes a
         * visual intro/outro, so the album track may be shorter by any amount;
         * only an album cut much *longer* than the video is a different edit.
         * The title/artist identity is checked afterwards by [TrackMatcher].
         */
        allowVideoLongerThanSong: Boolean = false,
    ): Boolean {
        val wantedText = source?.trim()
        val gotText = candidate?.trim()
        val wanted = TrackMatcher.secondsOf(wantedText)
        val got = TrackMatcher.secondsOf(gotText)
        // Absent durations are unknown and compatible; a non-blank value that
        // does not parse ("3:60") is malformed, not absent. Treating it as
        // unknown let such a row pass the drift check, so fail closed.
        if (wanted == null) return wantedText.isNullOrBlank()
        if (got == null) return gotText.isNullOrBlank()
        if (allowVideoLongerThanSong && got <= wanted) return true
        return abs(wanted - got) <= limitSec
    }

    /** A stored miss is re-asked only after [MISS_RETRY_MS]. */
    internal fun shouldRetryMiss(storedAt: Long, now: Long): Boolean =
        storedAt <= 0L || now - storedAt >= MISS_RETRY_MS

    /** The album search one lookup asks: the row's artist and title. */
    internal fun lookupQuery(artist: String, title: String): String =
        "$artist $title".replace(Regex("""\s+"""), " ").trim()

    /** A stored "not in the catalogue" value, bare (older builds) or query-scoped. */
    internal fun isMiss(value: String): Boolean =
        value == NONE || value.startsWith(NONE + FIELD)

    /**
     * Whether a stored miss answers [query]. A miss is the album search's
     * answer to one query, not a fact about the upload: rs20 171b
     * (XbGs_qK2PQA) asked "Eminem Rap God" while the tunnel had taken the
     * youtube.com rows away, got the twenty tribute and karaoke albums that
     * query always gets (rs10-rs21), and the bare `none` then refused for a
     * week the row titled "Rap God (Explicit)", whose search does list
     * The Marshall Mathers LP2. A bare value from an older build names no
     * query, so it is asked once more and rewritten scoped.
     */
    internal fun missCovers(value: String, query: String): Boolean =
        isMiss(value) && missKey(query) in value.split(FIELD).drop(1)

    /** The miss to store for [query], keeping the queries [previous] already missed. */
    internal fun missValue(previous: String?, query: String): String {
        val earlier = previous?.takeIf { isMiss(it) }?.split(FIELD)?.drop(1).orEmpty()
        val keys = (earlier - missKey(query) + missKey(query)).takeLast(MAX_MISS_QUERIES)
        return (listOf(NONE) + keys).joinToString(FIELD)
    }

    /** The same hash the miss line prints, so a stored miss joins its log line. */
    private fun missKey(query: String): String = Integer.toHexString(query.hashCode())

    /**
     * Whether [candidate] names [wanted] once case, brackets and the known
     * Korean/English aliases are folded away.
     */
    internal fun artistsMatch(wanted: String, got: String): Boolean {
        if (wanted.isBlank() || got.isBlank()) return false
        if (TrackMatcher.sharesArtist(wanted, got)) return true
        return artistVariants(wanted).any { it in artistVariants(got) }
    }

    /** The album row's subtitle ("Album • IU • 2023") naming the song's artist. */
    internal fun albumArtistMatches(artist: String, albumSubtitle: String): Boolean =
        artistsMatch(artist, albumSubtitle)

    /**
     * Which album pages one lookup opens, in the order it opens them.
     *
     * Albums first, singles and EPs after: the album filter ranks a song's
     * *releases* by relevance, so a sped-up take, a tribute cover or a remix
     * EP by the same artist is listed above the album that actually holds the
     * song ("Feather (Sped Up)", "Short n' Sweet", "Treasure Remix EP"), and
     * taking the first [MAX_ALBUMS_PER_SONG] rows spent every page on a
     * release that cannot answer the lookup — the logged 280 misses, none of
     * them a hit (catalogue mapping, 2026-11-08). A song released only as a
     * single keeps working: singles are still opened when no album matched.
     * The sort is stable, so the search's own order decides within each kind.
     *
     * shortcut: a song that exists only on an EP, while the artist has three or
     * more other releases ranked above that EP, still spends all three slots on
     * albums; <widen when a device `reason=no-track matching=3` line names it>.
     */
    internal fun albumPagesFor(albums: List<BrowseItem>, artist: String): List<BrowseItem> =
        albums.asSequence()
            .filter { albumArtistMatches(artist, it.subtitle) }
            .sortedByDescending { !isShortRelease(it.subtitle) }
            .take(MAX_ALBUMS_PER_SONG)
            .toList()

    /**
     * The album results a search shortcut opens: the album that holds the
     * song before the singles and EPs relevance ranks above it. The same
     * order as [albumPagesFor], but a free-text search is not scoped to one
     * artist, so no artist filter is applied.
     */
    internal fun searchAlbumsFor(albums: List<BrowseItem>): List<BrowseItem> =
        albums.sortedByDescending { !isShortRelease(it.subtitle) }
            .take(MAX_ALBUMS_PER_SEARCH)

    /**
     * A one- or two-track release ("싱글", "EP"). Its page lists the take that
     * was released, not the album cut a lookup is after. The subtitle is read
     * with [albumKind], so comma and middle-dot shapes count too. [albumKind]
     * returns the word as written ("EP"), so it is lowercased before the lookup.
     */
    internal fun isShortRelease(albumSubtitle: String): Boolean =
        albumKind(albumSubtitle)?.lowercase() in SHORT_RELEASE_WORDS

    /**
     * A track title answering a free-text search query. Both sides go through
     * [TrackMatcher]'s packaging removal, so "IU 금요일에 만나요" can match the
     * album's plain "금요일에 만나요".
     */
    internal fun trackMatchesQuery(query: String, title: String): Boolean {
        val asked = TrackMatcher.parseTitle(query)
        val got = TrackMatcher.parseTitle(title)
        if (asked.core.isBlank() || got.core.isBlank()) return false
        if (asked.versions != got.versions) return false
        if (asked.core == got.core) return true
        if (asked.core.length > 2 && got.core.length > 2) {
            if (TrackMatcher.romajiOf(asked.core) == TrackMatcher.romajiOf(got.core)) return true
            return asked.core.contains(got.core) || got.core.contains(asked.core)
        }
        return false
    }

    /**
     * The bracketed spellings of a title that are another name for the song
     * rather than packaging. "Friday(금요일에 만나요)" yields
     * "금요일에 만나요"; "(Feat. …)", "(Live)" and "(Official Video)" do not.
     */
    internal fun alternateTitles(title: String): List<String> =
        BRACKET.findAll(title)
            .map { it.groupValues[1].trim() }
            .filter { it.length >= 2 && !it.all { ch -> ch.isDigit() } }
            .filter { !ALTERNATE_NOISE.containsMatchIn(it) }
            .filter { TrackMatcher.parseTitle(it).core.isNotBlank() }
            .distinct()
            .toList()

    /**
     * Every spelling a credit may be filed under: the whole name, the
     * bracketed alias ("IU(아이유)"), the part outside the brackets, each
     * bullet-separated field, and whichever aliases belong to them.
     */
    internal fun artistVariants(raw: String): Set<String> {
        val text = TrackMatcher.normalize(raw)
        val out = LinkedHashSet<String>()
        fun add(value: String) {
            val compact = value.filter { it.isLetterOrDigit() }
            if (compact.isEmpty()) return
            ALIAS_GROUPS.forEach { group -> if (compact in group) out += group }
            out += compact
        }
        add(text)
        BRACKET.findAll(text).forEach { add(it.groupValues[1]) }
        add(BRACKET.replace(text, " "))
        text.split(ARTIST_SPLIT).forEach { add(it.trim()) }
        return out
    }

    // ---- Storage ------------------------------------------------------------

    private fun encode(song: CatalogSong): String = listOf(
        song.videoId,
        song.title,
        song.artist,
        song.albumTitle,
        song.albumId,
        song.artworkUrl.orEmpty(),
        song.durationText.orEmpty(),
    ).joinToString(FIELD)

    private fun decode(value: String): CatalogSong? {
        // A scoped miss with four queries has five fields; it is not a song.
        if (isMiss(value)) return null
        val parts = value.split(FIELD)
        if (parts.size < 5 || parts[0].isBlank()) return null
        return CatalogSong(
            videoId = parts[0],
            title = parts[1],
            artist = parts[2],
            albumTitle = parts[3],
            albumId = parts[4],
            artworkUrl = parts.getOrNull(5)?.takeIf { it.startsWith("http") },
            durationText = parts.getOrNull(6)?.takeIf { it.isNotBlank() },
        )
    }

    /** Square catalogue art; a widescreen ytimg frame is not one. */
    private fun squareArtwork(url: String?): String? = url
        ?.trim()
        ?.takeIf {
            it.startsWith("http") &&
                !it.contains("ytimg.com") &&
                !it.contains("youtube.com/vi/")
        }

    internal fun cacheSizeForTest(): Int = cache.size()

    internal fun cachePutForTest(videoId: String, value: String, at: Long = 0L) {
        cache.ensureLoaded(MusicGateFallback.sharedStore())
        cache.put(videoId, value, at)
    }

    internal fun cacheValueForTest(videoId: String): String? =
        cache.get(videoId)?.value

    internal fun encodeForTest(song: CatalogSong): String = encode(song)

    internal fun decodeForTest(value: String): CatalogSong? = decode(value)

    @Synchronized
    internal fun resetForTest(store: MusicGateFallback.Store? = null) {
        cache.resetForTest()
        synchronized(albumPages) { albumPages.clear() }
        store?.let { cache.ensureLoaded(it) }
    }

    // ---- Folding data -------------------------------------------------------

    private const val MAX_TRACKS_PER_SEARCH_ALBUM = 2

    /** Queries one stored miss remembers; the oldest is forgotten first. */
    private const val MAX_MISS_QUERIES = 4

    /** The subtitle words that name a one- or two-track release; see [isShortRelease]. */
    private val SHORT_RELEASE_WORDS = setOf("싱글", "single", "ep")

    private val BRACKET = Regex("""[(\[（【]([^()\[\]（）【】]*)[)\]）】]""")

    /** Bracketed text that describes the upload rather than naming the song. */
    private val ALTERNATE_NOISE = Regex(
        """(?i)\b(?:feat|ft|featuring|with|official|video|audio|lyric|lyrics|""" +
            """mv|m/v|remix|live|acoustic|instrumental|karaoke|cover|version|edit)\b|""" +
            """커버|라이브|리믹스""",
    )

    // Only whole credits are folded, never one word of a multi-word name: two
    // different artists sharing a first name are not the same act.
    private val ARTIST_SPLIT = Regex("""[•·|/,&;、，×]+|\s+\b(?:and|x)\b\s+""")

    /**
     * The Korean/English pairs a label or a store may file one act under.
     * Compared after folding, so only the compact spellings belong here.
     */
    private val ALIAS_GROUPS: List<Set<String>> = listOf(
        setOf("iu", "아이유"),
        setOf("bts", "방탄소년단"),
        setOf("blackpink", "블랙핑크"),
        setOf("twice", "트와이스"),
        setOf("newjeans", "뉴진스"),
        setOf("redvelvet", "레드벨벳"),
        setOf("aespa", "에스파"),
        setOf("ive", "아이브"),
        setOf("itzy", "있지"),
        setOf("nmixx", "엔믹스"),
        setOf("lesserafim", "르세라핌"),
        setOf("gidle", "여자아이들"),
        setOf("straykids", "스트레이키즈"),
        setOf("seventeen", "세븐틴"),
        setOf("enhypen", "엔하이픈"),
        setOf("txt", "투모로우바이투게더"),
        setOf("exo", "엑소"),
        setOf("snsd", "소녀시대", "girlsgeneration"),
        setOf("bigbang", "빅뱅"),
        setOf("shinee", "샤이니"),
        setOf("tvxq", "동방신기"),
        setOf("superjunior", "슈퍼주니어"),
        setOf("2ne1", "투애니원"),
        setOf("psy", "싸이"),
    )
}
