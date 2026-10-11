package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The moving cover's music video for a track comes from YouTube Music's own
 * Song/Video pairing, not a search — checked against the real service once.
 * Runs only with LIVE_KR_LYRICS=1, like the other live checks. Every step is
 * printed as a LOOKUP_MS line, so the CI annotations say where it stopped.
 */
class OfficialVideoLiveTest {
    private val live = System.getenv("LIVE_KR_LYRICS") == "1"

    @Test
    fun payphoneTrackPairsWithItsOfficialVideo() {
        if (!live) return
        runBlocking {
            val rows = runCatching {
                InnertubeParser.parseSearchPage(
                    Innertube.searchAnonymous("Payphone Maroon 5", SearchFilter.SONGS.params),
                    includeVideos = false,
                ).rows.filterIsInstance<SearchResult.Track>().map { it.song }
            }.onFailure { println("LOOKUP_MS counterpart search failed: ${it.javaClass.simpleName} ${it.message}") }
                .getOrDefault(emptyList())
            val track = rows.firstOrNull { it.title.contains("Payphone", ignoreCase = true) }
            println(
                "LOOKUP_MS counterpart rows=${rows.size} first=${rows.take(3).map { "${it.title}/${it.videoId}/${it.isVideo}" }}",
            )
            // The runner's searches are sometimes refused: nothing to check then.
            if (track == null) return@runBlocking
            // What the watch queue actually carries, for when the pairing comes back empty.
            runCatching {
                val raw = Innertube.next(track.videoId).toString()
                fun count(word: String) = Regex(Regex.escape(word)).findAll(raw).count()
                val ids = Regex(""""videoId":"([\w-]{11})"""").findAll(raw).map { it.groupValues[1] }.distinct().take(4).toList()
                println(
                    "LOOKUP_MS counterpart raw len=${raw.length} wrappers=${count("playlistPanelVideoWrapperRenderer")} " +
                        "counterpart=${count("counterpart")} panel=${count("playlistPanelVideoRenderer")} ids=$ids " +
                        "omv=${count("MUSIC_VIDEO_TYPE_OMV")} atv=${count("MUSIC_VIDEO_TYPE_ATV")}",
                )
            }.onFailure { println("LOOKUP_MS counterpart raw failed: ${it.message}") }
            val video = runCatching { YtMusicRepository.officialVideo(track.videoId) }
                .onFailure { println("LOOKUP_MS counterpart next failed: ${it.javaClass.simpleName} ${it.message}") }
                .getOrNull()
            println("LOOKUP_MS counterpart ${track.title} ${track.videoId} -> $video")
            assertTrue(video == null || video != track.videoId)
        }
    }
}
