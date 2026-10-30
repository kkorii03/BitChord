package com.music.bitchord.data.videoradio

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YouTubeArtistResolver
import com.music.bitchord.data.YouTubeFallback
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.YouTubeWebParser
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
        if (seedVideoId.isBlank()) return emptyList()
        cached(mixCache, seedVideoId)?.let { return it }
        val songs = safe("video-radio mix") {
            YouTubeFallback.playlistPage("RD$seedVideoId").songs
        }.orEmpty()
        if (songs.isNotEmpty()) store(mixCache, seedVideoId, songs)
        return songs
    }

    /**
     * The uploader channel's videos.
     *
     * The channel id is resolved from its name because a youtube.com row does
     * not carry the owner id. The videos tab is asked first; a channel whose
     * page ignores that params still answers its home rows, so that is the
     * single fallback. One page is enough for a queue top-up.
     */
    suspend fun channelSongs(channelName: String): List<Song> {
        val name = channelName.trim()
        if (name.isEmpty()) return emptyList()
        cached(channelCache, name)?.let { return it }
        val channelId = safe("video-radio channel id") {
            YouTubeArtistResolver.resolve(name)?.browseId
        } ?: return emptyList()
        val songs = safe("video-radio channel") {
            val videosTab = Innertube.browseYouTube(channelId, params = CHANNEL_VIDEOS_PARAMS)
            YouTubeWebParser.parsePlaylist(videosTab).songs.ifEmpty {
                YouTubeWebParser.parsePlaylist(Innertube.browseYouTube(channelId)).songs
            }
        }.orEmpty()
        if (songs.isNotEmpty()) store(channelCache, name, songs)
        return songs
    }

    /**
     * Both sources for one top-up, fetched together so the two requests do not
     * add their latencies, then de-duplicated in source order (mix first).
     */
    suspend fun collect(seedVideoId: String, channelName: String): VideoRadioSourceRows =
        coroutineScope {
            val mixRows = async { mix(seedVideoId) }
            val channelRows = async { channelSongs(channelName) }
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

    private data class Cached(val at: Long, val songs: List<Song>)

    private class SongCache(private val limit: Int) :
        LinkedHashMap<String, Cached>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) =
            size > limit
    }

    private val mixCache = SongCache(CACHE_ENTRIES)
    private val channelCache = SongCache(CACHE_ENTRIES)

    /** Channel videos tab; ignored by pages that only answer their home rows. */
    private const val CHANNEL_VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"

    private const val CACHE_ENTRIES = 8
    private const val CACHE_MS = 10L * 60L * 1000L
    private const val TAG = "BitChord"
}
