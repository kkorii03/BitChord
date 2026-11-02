package com.music.bitchord.ko.ui

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.UiState
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.album
import com.music.bitchord.sharedui.resources.albums
import com.music.bitchord.sharedui.resources.artist
import com.music.bitchord.sharedui.resources.artists
import com.music.bitchord.sharedui.resources.playlist
import com.music.bitchord.sharedui.resources.playlists
import com.music.bitchord.sharedui.resources.search_filter_all
import com.music.bitchord.sharedui.resources.search_filter_episodes
import com.music.bitchord.sharedui.resources.search_filter_videos
import com.music.bitchord.sharedui.resources.song
import com.music.bitchord.sharedui.resources.songs
import org.jetbrains.compose.resources.StringResource

/**
 * The type noun a search row falls back to when YouTube gave it no subtitle.
 *
 * The row still has to name what it is in the app's language; using the enum
 * name made every blank-subtitle album or playlist read "Album"/"Playlist"
 * even in Korean. OTHER is a plain song/video card, so it takes the song noun.
 */
fun koBrowseFallbackLabel(type: BrowseType): StringResource = when (type) {
    BrowseType.ALBUM -> Res.string.album
    BrowseType.ARTIST -> Res.string.artist
    BrowseType.PLAYLIST -> Res.string.playlist
    BrowseType.OTHER -> Res.string.song
}

/**
 * A completed search with no rows is an answer, not a request still in flight.
 * Null, Loading and Error all mean "still asking" or "failed", never "nothing
 * matched".
 */
fun <T> koHasNoResults(state: UiState<List<T>>?): Boolean =
    state is UiState.Success && state.data.isEmpty()

/**
 * The search filter tab's name in the app's language, not the enum's hardcoded
 * Korean. Restored with the fork's pill row, which v1.9 replaced with the
 * enum label and left every other locale reading Korean.
 */
fun koSearchFilterLabel(filter: SearchFilter): StringResource = when (filter) {
    SearchFilter.ALL -> Res.string.search_filter_all
    SearchFilter.SONGS -> Res.string.songs
    SearchFilter.VIDEOS -> Res.string.search_filter_videos
    SearchFilter.EPISODES -> Res.string.search_filter_episodes
    SearchFilter.ALBUMS -> Res.string.albums
    SearchFilter.ARTISTS -> Res.string.artists
    SearchFilter.PLAYLISTS -> Res.string.playlists
}
