package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.isExactArtistMatch
import com.music.bitchord.data.model.normalizedArtistName
import kotlinx.coroutines.CancellationException

/**
 * The channel and picture behind an artist name, resolved once and kept.
 *
 * The listening record can name an artist without ever having stored the
 * channel that page opens, which is common for rows read from a home shelf.
 * An anonymous YouTube Music artist search answers it, and only an exact
 * (folded) name match is accepted: searching `2115` finds four different
 * people, and opening the first one is worse than opening nothing.
 *
 * A hit is persisted with the same bounded store as the classifier's verdicts
 * and the square-cover cache, so the lookup is paid once per artist rather than
 * once per Home load.
 */
internal object YouTubeArtistResolver {

    data class Hit(val browseId: String, val thumbnailUrl: String?)

    private const val STORE_KEY = "youtube_artist_ids_v1"

    private const val MAX_ENTRIES = 500

    private const val SEPARATOR = "\n"

    private val cache = StoredVerdictCache(STORE_KEY, MAX_ENTRIES)

    /** Replaceable so the exact-match rule can be exercised without a network. */
    @Volatile
    internal var search: suspend (String) -> List<BrowseItem> = ::searchAnonymously

    /** A previously resolved artist, or null when this name has not been found. */
    fun cached(name: String): Hit? {
        val key = normalizedArtistName(name)
        if (key.isEmpty()) return null
        cache.ensureLoaded(MusicGateFallback.sharedStore())
        val entry = cache.get(key)?.value ?: return null
        val browseId = entry.substringBefore(SEPARATOR).takeIf { it.isNotBlank() } ?: return null
        val thumbnail = entry.substringAfter(SEPARATOR, "").takeIf { it.isNotBlank() }
        return Hit(browseId, thumbnail)
    }

    /**
     * The exact artist page for [name], from the cache or one anonymous search
     * for artists. Returns null when no result is exactly that name.
     */
    suspend fun resolve(name: String): Hit? {
        if (normalizedArtistName(name).isEmpty()) return null
        cached(name)?.let { return it }
        val results = try {
            search(name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Artist lookup failed length=${name.length}: ${failure.message}")
            return null
        }
        val hit = results.firstOrNull {
            it.type == BrowseType.ARTIST && isExactArtistMatch(it.title, name)
        } ?: return null
        val browseId = hit.browseId.takeIf { it.isNotBlank() } ?: return null
        cache.ensureLoaded(MusicGateFallback.sharedStore())
        cache.put(normalizedArtistName(name), browseId + SEPARATOR + hit.thumbnailUrl.orEmpty())
        return Hit(browseId, hit.thumbnailUrl)
    }

    private suspend fun searchAnonymously(name: String): List<BrowseItem> =
        InnertubeParser.parseSearchPage(
            Innertube.searchAnonymous(
                query = name,
                params = SearchFilter.ARTISTS.params,
                hl = "ko",
                gl = "KR",
                kind = SearchKind.ARTIST,
            ),
        ).rows.filterIsInstance<SearchResult.Browse>().map { it.item }

    @Synchronized
    internal fun resetForTest(store: MusicGateFallback.Store? = null) {
        cache.resetForTest()
        store?.let(cache::ensureLoaded)
        search = ::searchAnonymously
    }

    private const val TAG = "BitChordArtist"
}
