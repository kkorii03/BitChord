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
 * build may spend, [MAX_CONCURRENT] caps requests in flight, album pages live
 * in a small LRU, and every settled answer (including "not found") is persisted
 * per source video id. A miss is retried after [MISS_RETRY_MS]; a hit is not
 * re-asked.
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
        fun toSong(source: Song): Song = source.copy(
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
            isVideoOrigin = false,
        )
    }

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

    /** Requests in flight at once; battery over latency. */
    const val MAX_CONCURRENT = 2

    /** How long a "not in the catalogue" answer is trusted. */
    const val MISS_RETRY_MS = 7L * 24L * 60L * 60L * 1000L

    /** Both runtimes have to agree this closely when both are stated. */
    const val MAX_DURATION_DRIFT_SEC = 10

    private const val CACHE_ENTRIES = 3_000
    // v2: the video-source runtime window changed, so an old "not in the
    // catalogue" verdict (The Hills) must not outrank a fresh lookup.
    private const val STORE_KEY = "catalog_song_mapping_v2"
    private const val NONE = "none"
    private const val FIELD = "\u001F"
    private const val TAG = "BitChord"

    /**
     * The mapper only ever runs on the gated fallback. Keeping the semaphore
     * here rather than in each caller means two shelves built at once still
     * share the same two-slot ceiling.
     */
    private val inFlight = Semaphore(MAX_CONCURRENT)

    private val cache = StoredVerdictCache(STORE_KEY, CACHE_ENTRIES)

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
     */
    suspend fun map(song: Song, budget: LookupBudget = newBudget()): CatalogSong? {
        if (song.videoId.isBlank()) return null
        if (song.localUri != null) return null
        if (isCover(song) || isCoverTitle(song.title)) return null
        // A row already carrying a release identity is catalogue metadata, not
        // a youtube.com upload; there is nothing to map and no reason to spend
        // a lookup on it.
        if (song.albumId != null && !song.isVideo) return null

        val cleaned = YouTubeDisplayCleaner.clean(song)
        val title = cleaned.title.trim()
        val artist = cleaned.artist.trim()
        if (title.isBlank() || artist.isBlank()) return null
        if (isCoverTitle(title)) return null

        cache.ensureLoaded(MusicGateFallback.sharedStore())
        val now = System.currentTimeMillis()
        val stored = cache.get(song.videoId)
        if (stored != null) {
            if (stored.value != NONE) {
                decode(stored.value)?.let { return it }
            } else if (!shouldRetryMiss(stored.at, now)) {
                return null
            }
        }

        if (!budget.take()) return null

        val outcome = try {
            inFlight.withPermit { resolve(song.videoId, title, artist, song.durationText, song.isVideo) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "catalogue mapping failed for ${song.videoId}: ${failure.message}")
            Outcome.Failed
        }

        return when (outcome) {
            is Outcome.Found -> {
                cache.put(song.videoId, encode(outcome.song), now)
                outcome.song
            }
            Outcome.NotFound -> {
                cache.put(song.videoId, NONE, now)
                null
            }
            Outcome.Failed -> null
        }
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
     */
    suspend fun forSearch(
        query: String,
        youtubeSongs: List<Song>,
        limit: Int = MAX_ROWS_PER_PAGE,
    ): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isBlank() && youtubeSongs.isEmpty()) return youtubeSongs
        val budget = newBudget()
        val out = LinkedHashMap<String, Song>()

        if (trimmed.isNotBlank() && budget.take()) {
            val albums = try {
                inFlight.withPermit { searchAlbums(trimmed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "album search failed length=${trimmed.length}: ${failure.message}")
                null
            }.orEmpty()

            for (album in albums.take(MAX_ALBUMS_PER_SEARCH)) {
                if (budget.remaining() <= 0) break
                budget.take()
                val tracks = try {
                    inFlight.withPermit { albumTracks(album.browseId) }
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

        if (youtubeSongs.isNotEmpty()) {
            for (song in youtubeSongs.take(limit)) {
                val mapped = try {
                    map(song, budget)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
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

    // ---- Lookup -------------------------------------------------------------

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
    ): Outcome {
        val query = "$artist $title".replace(Regex("""\s+"""), " ").trim()
        val albums = searchAlbums(query) ?: return Outcome.Failed
        val matching = albums.asSequence()
            .filter { albumArtistMatches(artist, it.subtitle) }
            .take(MAX_ALBUMS_PER_SONG)
            .toList()
        if (matching.isEmpty()) return Outcome.NotFound

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
        var opened = false
        var failed = false
        for (album in matching) {
            val tracks = albumTracks(album.browseId)
            if (tracks == null) {
                failed = true
                continue
            }
            opened = true
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
            val match = targets.firstNotNullOfOrNull { wanted ->
                // The ordinary matcher first, so every mapping that works today
                // keeps working unchanged. A video's runtime is visual timing,
                // though: its album track may be minutes shorter
                // (intro/skit/outro) and is still the song, which the ordinary
                // matcher rightly refuses. Only that gap uses the video-switch
                // rule, and only when title, versions and artist agree.
                TrackMatcher.best(candidates, wanted)
                    ?: if (sourceIsVideo) {
                        TrackMatcher.bestOfficialAudioForVideo(candidates, wanted)
                    } else {
                        null
                    }
            } ?: continue
            val catalog = catalogOf(match, album, artist) ?: continue
            if (catalog.videoId.isBlank()) continue
            return Outcome.Found(catalog)
        }
        if (opened) {
            Log.i(
                TAG,
                "catalogue mapping: no matching track for '$title' / '$artist'" +
                    if (sourceIsVideo) " (video source)" else "",
            )
        }
        return if (opened) Outcome.NotFound else if (failed) Outcome.Failed else Outcome.NotFound
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
        val wanted = TrackMatcher.secondsOf(source) ?: return true
        val got = TrackMatcher.secondsOf(candidate) ?: return true
        if (allowVideoLongerThanSong && got <= wanted) return true
        return abs(wanted - got) <= limitSec
    }

    /** A stored miss is re-asked only after [MISS_RETRY_MS]. */
    internal fun shouldRetryMiss(storedAt: Long, now: Long): Boolean =
        storedAt <= 0L || now - storedAt >= MISS_RETRY_MS

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
        if (value == NONE) return null
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
