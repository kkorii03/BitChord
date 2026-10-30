package com.music.bitchord.data.videoradio

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YouTubeArtistResolver
import com.music.bitchord.data.YouTubeFallback
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.YouTubeWebParser
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/** Rows from one video-radio fetch, each tagged with the source that offered it. */
data class VideoRadioSourceRows(
    val songs: List<Song>,
    val sources: Map<String, VideoSource>,
)

/**
 * The YouTube-side material a video station can play besides the Music radio.
 *
 * The seed's own `RD…` mix is the video world YouTube builds from that upload,
 * and the uploader's channel is the one place guaranteed to hold more of the
 * same singer. Both are read through the existing youtube.com browse/next
 * routes, so the proxy and gate rules are the ones every other fallback page
 * already follows.
 *
 * Kept out of [com.music.bitchord.data.YtMusicRepository] so the upstream
 * repository keeps only the connection it already has, and cached because a
 * top-up is considered again after only a few tracks.
 */
object VideoRadioSources {

    /** The `next` watch queue for `list=RD<videoId>`, as youtube.com serves it. */
    suspend fun mix(seedVideoId: String): List<Song> {
        if (seedVideoId.isBlank()) {
            Log.w(TAG, "video-radio mix skipped: blank seed")
            return emptyList()
        }
        cached(mixCache, seedVideoId)?.let { return it }
        val songs = safe("video-radio mix") { mixLookup(seedVideoId) }.orEmpty()
        if (songs.isNotEmpty()) {
            store(mixCache, seedVideoId, songs)
        } else {
            // Not fatal: the channel below is the other leg of the same
            // top-up, and this line is what makes a silent RD failure visible.
            Log.w(TAG, "video-radio mix empty: RD$seedVideoId")
        }
        return songs
    }

    /**
     * The uploader channel's videos.
     *
     * [channelId] is the `UC…` id the playback/watch response stated. When it
     * is present the uploader name is never searched, because a name is not an
     * identity and another channel with the same name would answer. The name
     * remains only as the last resort for an older row that never recorded the
     * id; that fallback is logged so the missing id is not silent.
     *
     * The videos tab is asked first; a channel whose page ignores that params
     * still answers its home rows, so that is the single fallback. One page is
     * enough for a queue top-up.
     */
    suspend fun channelSongs(channelName: String, channelId: String? = null): List<Song> {
        val name = channelName.trim()
        val explicitId = channelId?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitId == null && name.isEmpty()) {
            Log.w(TAG, "video-radio channel skipped: no id and no uploader name")
            return emptyList()
        }
        val id = explicitId ?: run {
            // A row without the owner id still has to play. Say so, because
            // this is the one path where a same-named channel can be opened.
            Log.w(TAG, "video-radio channel id absent; searching uploader name=$name")
            safe("video-radio channel id") {
                artistResolver(name)?.browseId?.takeIf { it.isNotBlank() }
            }
        }
        if (id == null) {
            Log.w(TAG, "video-radio channel id missing: name=${name.ifEmpty { "-" }}")
            return emptyList()
        }
        val key = "id:$id"
        cached(channelCache, key)?.let { return it }
        val songs = safe("video-radio channel") {
            val videosTab = channelBrowse(id, CHANNEL_VIDEOS_PARAMS)
            val fromVideosTab = channelParser(videosTab)
            fromVideosTab.ifEmpty { channelParser(channelBrowse(id, null)) }
        }.orEmpty()
        if (songs.isNotEmpty()) {
            store(channelCache, key, songs)
        } else {
            Log.w(TAG, "video-radio channel empty: id=$id name=$name")
        }
        return songs
    }

    /**
     * Both sources for one top-up, fetched together so the two requests do not
     * add their latencies, then de-duplicated in source order (mix first).
     *
     * The channel id rides on the seed [Song] from the player response; when it
     * is missing, this one lookup asks the player before falling back to the
     * uploader name inside [channelSongs].
     */
    suspend fun collect(
        seedVideoId: String,
        channelName: String,
        channelId: String? = null,
    ): VideoRadioSourceRows = coroutineScope {
        val mixRows = async { mix(seedVideoId) }
        val channelRows = async {
            channelSongs(channelName, resolveChannelId(seedVideoId, channelId))
        }
        val songs = LinkedHashMap<String, Song>()
        val sources = HashMap<String, VideoSource>()
        mixRows.await().forEach { song ->
            if (song.videoId.isNotBlank() && songs.putIfAbsent(song.videoId, song) == null) {
                sources[song.videoId] = VideoSource.MIX
            }
        }
        channelRows.await().forEach { song ->
            if (song.videoId.isNotBlank() && songs.putIfAbsent(song.videoId, song) == null) {
                sources[song.videoId] = VideoSource.CHANNEL
            }
        }
        VideoRadioSourceRows(songs.values.toList(), sources)
    }

    /** The seed's own channel id, from the row or else from its player response. */
    private suspend fun resolveChannelId(seedVideoId: String, channelId: String?): String? {
        channelId?.trim()?.takeIf { it.isNotEmpty() }?.let { explicit ->
            if (seedVideoId.isNotBlank()) storeChannelId(seedVideoId, explicit)
            return explicit
        }
        if (seedVideoId.isBlank()) return null
        cachedChannelId(seedVideoId)?.let { return it }
        return safe("video-radio channel id from player") {
            videoChannelLookup(seedVideoId)?.trim()?.takeIf { it.isNotEmpty() }
        }?.also { storeChannelId(seedVideoId, it) }
    }

    private suspend fun <T> safe(label: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "$label failed: ${failure.message}")
            null
        }

    private fun cached(cache: SongCache, key: String): List<Song>? = synchronized(cache) {
        val hit = cache[key] ?: return@synchronized null
        if (System.currentTimeMillis() - hit.at > CACHE_MS) {
            cache.remove(key)
            return@synchronized null
        }
        hit.songs
    }

    private fun store(cache: SongCache, key: String, songs: List<Song>) {
        synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), songs) }
    }

    /**
     * The channel id resolved for a seed, so a later top-up does not ask the
     * player again just because the row was written before the id existed.
     */
    private fun cachedChannelId(seedVideoId: String): String? = synchronized(channelIdCache) {
        val hit = channelIdCache[seedVideoId] ?: return@synchronized null
        if (System.currentTimeMillis() - hit.at > CACHE_MS) {
            channelIdCache.remove(seedVideoId)
            return@synchronized null
        }
        hit.channelId
    }

    private fun storeChannelId(seedVideoId: String, channelId: String) {
        synchronized(channelIdCache) {
            channelIdCache[seedVideoId] = CachedChannelId(System.currentTimeMillis(), channelId)
        }
    }

    private data class Cached(val at: Long, val songs: List<Song>)

    private data class CachedChannelId(val at: Long, val channelId: String)

    private class SongCache(private val limit: Int) :
        LinkedHashMap<String, Cached>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) =
            size > limit
    }

    private class ChannelIdCache(private val limit: Int) :
        LinkedHashMap<String, CachedChannelId>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedChannelId>) =
            size > limit
    }

    private val mixCache = SongCache(CACHE_ENTRIES)
    private val channelCache = SongCache(CACHE_ENTRIES)
    private val channelIdCache = ChannelIdCache(CACHE_ENTRIES)

    /** Replaceable so the id-before-name rule can be exercised without a network. */
    @Volatile
    internal var mixLookup: suspend (String) -> List<Song> = { seed ->
        YouTubeFallback.playlistPage("RD$seed").songs
    }

    @Volatile
    internal var videoChannelLookup: suspend (String) -> String? = { videoId ->
        InnertubeParser.videoChannelId(Innertube.playerResponse(videoId))
    }

    @Volatile
    internal var artistResolver: suspend (String) -> YouTubeArtistResolver.Hit? = {
        YouTubeArtistResolver.resolve(it)
    }

    @Volatile
    internal var channelBrowse: suspend (String, String?) -> JsonObject = { id, params ->
        Innertube.browseYouTube(id, params)
    }

    @Volatile
    internal var channelParser: (JsonObject) -> List<Song> = { root ->
        YouTubeWebParser.parsePlaylist(root).songs
    }

    @Synchronized
    internal fun resetForTest() {
        synchronized(mixCache) { mixCache.clear() }
        synchronized(channelCache) { channelCache.clear() }
        synchronized(channelIdCache) { channelIdCache.clear() }
        mixLookup = { seed -> YouTubeFallback.playlistPage("RD$seed").songs }
        videoChannelLookup = { videoId ->
            InnertubeParser.videoChannelId(Innertube.playerResponse(videoId))
        }
        artistResolver = { YouTubeArtistResolver.resolve(it) }
        channelBrowse = { id, params -> Innertube.browseYouTube(id, params) }
        channelParser = { root -> YouTubeWebParser.parsePlaylist(root).songs }
    }

    /** Channel videos tab; ignored by pages that only answer their home rows. */
    private const val CHANNEL_VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"

    private const val CACHE_ENTRIES = 8
    private const val CACHE_MS = 10L * 60L * 1000L
    private const val TAG = "BitChord"
}
