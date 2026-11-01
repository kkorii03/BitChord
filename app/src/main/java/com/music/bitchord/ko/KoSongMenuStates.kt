package com.music.bitchord.ko

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.player.LyricsTimingUi

internal data class KoSongMenuStates(
    val likeStatuses: Map<String, LikeStatus>,
    val pinnedToOriginal: Set<String>,
    val qualityUpgradesInFlight: Set<String>,
    val lyrics: List<LyricLine>?,
    val lyricsTiming: LyricsTimingUi,
    val playlists: List<UserPlaylist>,
)

/**
 * Menu-only answers, read with the track menu itself so they do not invalidate
 * the app shell. Kept out of the upstream file — see docs/ko-fork-layout.md.
 */
@Composable
internal fun koSongMenuStates(viewModel: MainViewModel): KoSongMenuStates {
    val likeStatuses by viewModel.likeStatuses.collectAsStateWithLifecycle()
    val pinnedToOriginal by OriginalVersion.pinned.collectAsStateWithLifecycle()
    val qualityUpgradesInFlight by NerdStats.racingLossless.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsTiming by viewModel.lyricsTiming.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    return KoSongMenuStates(
        likeStatuses = likeStatuses,
        pinnedToOriginal = pinnedToOriginal,
        qualityUpgradesInFlight = qualityUpgradesInFlight,
        lyrics = lyrics,
        lyricsTiming = lyricsTiming,
        playlists = playlists,
    )
}
