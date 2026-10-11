package com.music.bitchord.data.lyrics

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The catalogue recording of a song — what a video's lyrics are timed to —
 * from an anonymous YouTube Music search: a lookup the app makes for itself,
 * kept out of the listener's search history.
 */
object OriginalRecording {

    /**
     * The first of the top song results that is the song named [title] by
     * [artist]. When the artist is unknown or written differently (a cover
     * read as "곡명 - 가수 Cover", a Hangul transliteration), a result whose
     * title alone matches is taken only when the search found nothing better.
     */
    suspend fun find(title: String, artist: String): Song? = withContext(Dispatchers.IO) {
        val cleanTitle = title.forLyricsSearch()
        val cleanArtist = artist.artistForLyricsSearch()
        val songs = search("$cleanTitle $cleanArtist")
        songs.firstOrNull { TrackMatcher.matches(it, cleanTitle, cleanArtist) }
            ?: songs.firstOrNull { TrackMatcher.matches(it, cleanTitle, it.artist) }
                ?.takeIf { cleanArtist.isBlank() || sameTitle(it.title, cleanTitle) }
    }

    private suspend fun search(query: String): List<Song> =
        InnertubeParser.parseSearchPage(Innertube.searchAnonymous(query, SearchFilter.SONGS.params), includeVideos = false)
            .rows
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }
            .filter { !it.isVideo }
            .take(5)

    private fun sameTitle(a: String, b: String): Boolean = squash(a.forLyricsSearch()) == squash(b)

    private fun squash(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
