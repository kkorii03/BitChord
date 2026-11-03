package com.music.bitchord.ko

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LocalPlayHistory
import com.music.bitchord.data.RecentShelf
import com.music.bitchord.data.StoredSongDisplay
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SharedLookups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

/** Home chart and locally-first Recents helpers kept out of the upstream file. */
internal object KoHomeFeed {

    private const val TAG = "BitChord"

    /** How many rows the Content-country chart contributes to a Home shelf. */
    private const val CHART_SONGS = 20
    private const val RECENT_TITLE = "Recents"
    private const val RECENT_LIMIT = 20

    internal sealed interface HomeAttempt
    internal class MusicHome(val root: JsonObject, val shelves: List<HomeShelf>) : HomeAttempt
    internal class FallbackHome(val feed: HomeFeed) : HomeAttempt

    /**
     * After a Music browse, a verdict that is still GATED means the answer was
     * the Premium upsell; it must be routed to the youtube.com fallback exactly
     * as if the browse had been refused. Otherwise the listener is shown an
     * empty feed whenever a re-check leaves the verdict unchanged.
     */
    fun shouldRenderMusicHome(gateActive: Boolean): Boolean = !gateActive

    /**
     * Starts [startChart] before [load], then keeps the chart only when the
     * loaded answer can use it. A failed load cancels a chart nobody will
     * await, so a fallback/error does not leave a request running.
     *
     * A lookup this call joined belongs to another caller: cancelling it would
     * fail that caller's await with a CancellationException, so only a lookup
     * this call started itself is cancelled.
     *
     * Extracted so the overlap rule is testable without a network: the old
     * Home path awaited its browse first and only then began the chart lookup.
     */
    suspend fun <T> loadWithChartBefore(
        startChart: () -> SharedLookups.Lookup<HomeShelf?>?,
        load: suspend () -> T,
        keepChart: (T) -> Boolean = { true },
    ): Pair<T, HomeShelf?> {
        val chart = startChart()
        val loaded = try {
            load()
        } catch (failure: Throwable) {
            chart?.cancelIfNotShared()
            throw failure
        }
        if (!keepChart(loaded)) {
            chart?.cancelIfNotShared()
            return loaded to null
        }
        return loaded to chart?.result?.await()
    }

    private fun SharedLookups.Lookup<HomeShelf?>.cancelIfNotShared() {
        if (!joined) result.cancel()
    }

    /**
     * The Recents shelf built from this device alone, followed by [previous]
     * rows already on screen that local history does not already cover.
     *
     * Used when a play is counted while Home is open: it never asks the
     * network, so the shelf follows the local record without re-downloading
     * the feed, and the account rows already shown stay below the local ones.
     */
    suspend fun localRecentShelf(previous: List<ShelfItem> = emptyList()): HomeShelf? {
        val local = RecentShelf.localSongs(LocalPlayHistory.recent(RECENT_LIMIT))
        val remote = previous.mapNotNull { item ->
            val videoId = item.videoId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Song(
                videoId = videoId,
                title = item.title,
                artist = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
                isVideo = item.isVideo,
            )
        }
        return recentsShelf(RecentShelf.merge(local, remote, RECENT_LIMIT))
    }

    /** [songs] as the Recents shelf, with covers shown the way the player shows them. */
    internal fun recentsShelf(songs: List<Song>): HomeShelf {
        // A cover reads as its song here too, as it does in the player, not as
        // the upload's own title. A row from an older build is cleaned first,
        // so its stored OST title and "Original Soundtrack" credit never reach
        // the shelf.
        val items = songs.map(StoredSongDisplay::clean)
            .map { CoverTitles.applyTo(it) }.map {
            ShelfItem(
                title = it.title,
                subtitle = it.artist,
                thumbnailUrl = it.thumbnailUrl,
                videoId = it.videoId,
                browseId = null,
                // Phone 11-03: without this a cover video tapped here played
                // as a plain song (no song version, music radio with no picks).
                isVideo = it.isVideo,
            )
        }
        return HomeShelf(title = RECENT_TITLE, items = items)
    }

    /**
     * Every chart-like song shelf — "인기곡", "인기 급상승", "Trending" — on Home,
     * its later pages or a supplement, filled from the content country's
     * chart: the feed itself picks them by the account's registered country.
     */
    internal suspend fun contentCountryCharts(shelves: List<HomeShelf>): List<HomeShelf> =
        withContentCountryChart(shelves, contentCountryChart())

    internal fun withContentCountryChart(
        shelves: List<HomeShelf>,
        chart: HomeShelf?,
    ): List<HomeShelf> = cleanVideoRows(withChart(shelves, chart))

    /**
     * Video rows read the way the player shows them (phone 11-03: "커버 및
     * 리믹스" showed raw uploads, "한결 - 마음짓기(心做し) Live Cover" credited
     * to the channel). Every Home page path goes through [withContentCountryChart].
     */
    internal fun cleanVideoRows(shelves: List<HomeShelf>): List<HomeShelf> = shelves.map { shelf ->
        if (shelf.items.none { it.isVideo && !it.videoId.isNullOrBlank() }) {
            shelf
        } else {
            shelf.copy(items = shelf.items.map(::cleanVideoRow))
        }
    }

    private fun cleanVideoRow(item: ShelfItem): ShelfItem {
        val videoId = item.videoId?.takeIf { item.isVideo && it.isNotBlank() } ?: return item
        val song = CoverTitles.applyTo(
            StoredSongDisplay.clean(
                Song(
                    videoId = videoId,
                    title = item.title,
                    artist = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    isVideo = true,
                ),
            ),
        )
        return item.copy(title = song.title, subtitle = song.artist.ifBlank { item.subtitle })
    }

    private fun withChart(
        shelves: List<HomeShelf>,
        chart: HomeShelf?,
    ): List<HomeShelf> {
        if (shelves.none { TRENDING_TITLE.matches(it.title) }) return shelves
        chart ?: return shelves
        return shelves.map { shelf ->
            if (TRENDING_TITLE.matches(shelf.title)) {
                shelf.copy(items = chart.items, moreBrowseId = chart.moreBrowseId, moreParams = chart.moreParams)
            } else {
                shelf
            }
        }
    }

    internal val TRENDING_TITLE = Regex("""(?i)^\s*(?:인기곡|인기\s*급상승(?:\s*곡)?|급상승|trending(?:\s+(?:songs|now))?|top\s+songs)\s*$""")

    @Volatile
    private var chartCache: Triple<String, Long, HomeShelf>? = null

    internal fun cachedContentCountryChart(country: String): HomeShelf? =
        chartCache?.let { (c, at, shelf) ->
            shelf.takeIf { c == country && System.currentTimeMillis() - at < 30 * 60_000L }
        }

    /**
     * Starts the content-country chart lookup without waiting for Home's own
     * browse. The lookup is shared, so the feed and any supplement that asks
     * while it is in flight wait on the same request.
     */
    internal fun startContentCountryChart(): SharedLookups.Lookup<HomeShelf?>? {
        val country = Innertube.contentCountry()?.takeIf { it.isNotBlank() } ?: return null
        if (cachedContentCountryChart(country) != null) return null
        return chartLookups.lookup(country) { loadContentCountryChart(country) }
    }

    /** The content country's top song shelf, kept half an hour (Home and its pages all ask). */
    private suspend fun contentCountryChart(): HomeShelf? {
        val country = Innertube.contentCountry()?.takeIf { it.isNotBlank() } ?: return null
        cachedContentCountryChart(country)?.let { return it }
        return chartLookups.lookup(country) { loadContentCountryChart(country) }.result.await()
    }

    private suspend fun loadContentCountryChart(country: String): HomeShelf? {
        fun songs(shelf: HomeShelf) = shelf.items.isNotEmpty() && shelf.items.count { it.videoId != null } * 2 > shelf.items.size
        val page = runCatching { InnertubeParser.parseHome(Innertube.charts(country)) }.getOrNull().orEmpty()
        // Some answers carry the songs themselves; signed in, the page is chart
        // *playlists* ("한국 인기곡 Top100", "일간 인기 뮤직비디오 - 한국" …), so the
        // top songs one is opened and its first rows taken.
        val chart = page.firstOrNull { songs(it) && it.items.size >= 5 }
            ?: chartPlaylistSongs(page)
            ?: return null
        chartCache = Triple(country, System.currentTimeMillis(), chart)
        return chart
    }

    private val TOP_SONGS_PLAYLIST = Regex("""(?i)인기곡\s*top|top\s*(?:100\s*)?songs|top songs""")
    private val TOP_VIDEOS_PLAYLIST = Regex("""(?i)인기\s*뮤직\s*비디오|top\s*music\s*videos|급상승|trending""")

    private suspend fun chartPlaylistSongs(page: List<HomeShelf>): HomeShelf? {
        val playlists = page.flatMap { it.items }.filter { it.browseId != null && it.videoId == null }
        val pick = playlists.firstOrNull { TOP_SONGS_PLAYLIST.containsMatchIn(it.title) && !it.title.contains("Shorts", true) }
            ?: playlists.firstOrNull { TOP_VIDEOS_PLAYLIST.containsMatchIn(it.title) && !it.title.contains("Shorts", true) }
            ?: return null
        val browseId = pick.browseId!!.let { if (it.startsWith("VL") || !it.startsWith("PL")) it else "VL$it" }
        val songs = runCatching { InnertubeParser.parsePlaylistShelf(Innertube.browse(browseId))?.songs }.getOrNull()
            ?.take(CHART_SONGS)
            ?.takeIf { it.size >= 5 }
            ?: return null
        return HomeShelf(
            title = pick.title,
            items = songs.map {
                ShelfItem(
                    title = it.title,
                    subtitle = it.artist,
                    thumbnailUrl = it.thumbnailUrl,
                    videoId = it.videoId,
                    browseId = null,
                    isVideo = it.isVideo,
                )
            },
            moreBrowseId = browseId,
        )
    }

    /**
     * Explore's song shelf ("인기곡") follows the account's own country, not
     * the gl a request carries: an account registered in India and used in
     * Korea is shown India's trending songs whatever the content country
     * says. The charts page does take a country, so that shelf's songs are
     * taken from the content country's chart instead — the shelf as it was if
     * the chart doesn't answer.
     */
    internal suspend fun contentCountrySongs(shelves: List<HomeShelf>): List<HomeShelf> {
        val country = Innertube.contentCountry()?.takeIf { it.isNotBlank() } ?: return shelves
        fun songs(shelf: HomeShelf) = shelf.items.isNotEmpty() && shelf.items.count { it.videoId != null } * 2 > shelf.items.size
        val index = shelves.indexOfFirst(::songs)
        if (index < 0) return shelves
        if (country.isBlank()) return shelves
        val chart = contentCountryChart() ?: return shelves
        return shelves.toMutableList().apply {
            set(index, shelves[index].copy(items = chart.items, moreBrowseId = chart.moreBrowseId, moreParams = chart.moreParams))
        }
    }

    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chartLookups = SharedLookups<HomeShelf?>(lookupScope)
}
