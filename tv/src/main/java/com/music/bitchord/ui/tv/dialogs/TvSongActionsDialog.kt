package com.music.bitchord.ui.tv.dialogs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.SleepTimer
import com.music.bitchord.playback.beginRadioQueue
import com.music.bitchord.playback.commitRadioQueue
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.player.coverSingerChannelId
import com.music.bitchord.ui.tv.components.TvActivityIndicator
import com.music.bitchord.ui.tv.components.TvArtwork
import com.music.bitchord.ui.tv.components.TvDialog
import com.music.bitchord.ui.tv.components.TvDialogButton
import com.music.bitchord.ui.tv.components.TvListRow
import com.music.bitchord.ui.tv.components.TvSongRow
import com.music.bitchord.ui.tv.components.TvTextField
import com.music.bitchord.ui.tv.components.tvInitialFocus
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvType
import kotlinx.coroutines.launch

/**
 * A song's actions from anywhere outside the player: works out whether it's
 * liked and where its album and artist pages are, then shows the menu.
 */
@Composable
fun TvSongMenu(
    song: Song,
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onDismiss: () -> Unit,
) {
    val overrides by LikeState.overrides.collectAsState()
    val isLiked = overrides[song.videoId] == LikeStatus.LIKE
    TvSongActionMenuDialog(
        song = song,
        isLiked = isLiked,
        viewModel = viewModel,
        mediaController = mediaController,
        onToggleLike = { viewModel.toggleLike(song.videoId) },
        onOpenAlbum = song.albumId?.let { id ->
            { _: String -> onNavigateToDetail(id, song.albumName.orEmpty(), song.artist, song.thumbnailUrl, BrowseType.ALBUM) }
        },
        onOpenArtist = coverSingerChannelId(song)?.let { id ->
            {
                val artistName = if (CoverTitles.isCover(song)) {
                    CoverTitles.creditedArtist(song)
                } else {
                    song.artist
                }
                onNavigateToDetail(id, artistName, "", null, BrowseType.ARTIST)
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * The tvOS action sheet for a track: the song itself at the top, then a short
 * list of plain actions — one line each, a glyph on the right, the white
 * platter on whichever has focus.
 */
@Composable
fun TvSongActionMenuDialog(
    song: Song,
    isLiked: Boolean,
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onToggleLike: () -> Unit,
    onOpenAlbum: ((String) -> Unit)? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(SongSheet.Actions) }
    val sleepDeadline by SleepTimer.deadline.collectAsState()
    val sleepAfterTrack by SleepTimer.afterTrack.collectAsState()
    val nerdStats by AppSettings.showNerdStats.collectAsState()

    when (sheet) {
        SongSheet.Playlist -> {
            TvAddToPlaylistDialog(song = song, viewModel = viewModel, onDismiss = onDismiss)
            return
        }
        SongSheet.Sleep -> {
            TvSleepTimerDialog(onDismiss = onDismiss)
            return
        }
        SongSheet.Equalizer -> {
            TvEqualizerDialog(onDismiss = onDismiss)
            return
        }
        SongSheet.Actions -> Unit
    }

    TvDialog(title = song.title, message = song.artist.ifBlank { null }, onDismissRequest = onDismiss) {
        TvArtwork(
            url = song.thumbnailUrl,
            px = 320,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .padding(bottom = 18.dp)
                .size(77.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 288.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item {
                TvListRow(
                    title = stringResource(R.string.tv_play_next),
                    trailingIcon = Icons.AutoMirrored.Rounded.QueueMusic,
                    modifier = Modifier.tvInitialFocus(),
                    onClick = {
                        mediaController?.let { it.addMediaItem(it.currentMediaItemIndex + 1, song.toMediaItem()) }
                        onDismiss()
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_play_last),
                    trailingIcon = Icons.Rounded.PlaylistPlay,
                    onClick = {
                        mediaController?.addMediaItem(song.toMediaItem())
                        onDismiss()
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_start_station),
                    trailingIcon = Icons.Rounded.Radio,
                    onClick = {
                        val controller = mediaController
                        if (controller != null) scope.launch { controller.startStation(song) }
                        onDismiss()
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(if (isLiked) R.string.tv_unlove else R.string.tv_love),
                    trailingIcon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    onClick = {
                        onToggleLike()
                        onDismiss()
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_add_to_playlist),
                    trailingIcon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    onClick = { sheet = SongSheet.Playlist },
                )
            }
            if (onOpenAlbum != null) {
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_go_to_album),
                        trailingIcon = Icons.Rounded.Album,
                        onClick = {
                            onDismiss()
                            onOpenAlbum(song.albumId.orEmpty())
                        },
                    )
                }
            }
            if (onOpenArtist != null) {
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_go_to_artist),
                        trailingIcon = Icons.Rounded.Person,
                        onClick = {
                            onDismiss()
                            onOpenArtist(song.artistId.orEmpty())
                        },
                    )
                }
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_equalizer),
                    trailingIcon = Icons.Rounded.Equalizer,
                    onClick = { sheet = SongSheet.Equalizer },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_sleep_timer),
                    value = stringResource(if (sleepDeadline != null || sleepAfterTrack) R.string.tv_on else R.string.tv_off),
                    trailingIcon = Icons.Rounded.Timer,
                    onClick = { sheet = SongSheet.Sleep },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_stats_for_nerds),
                    value = stringResource(if (nerdStats) R.string.tv_on else R.string.tv_off),
                    trailingIcon = Icons.Rounded.Info,
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }
        }
    }
}

private enum class SongSheet { Actions, Playlist, Sleep, Equalizer }

/**
 * A station seeded from [song], as the phone builds one: YouTube's radio for
 * it, with the song itself first. If it's already the one playing, it keeps
 * playing and only the rest of the queue is replaced.
 */
private suspend fun MediaController.startStation(song: Song) {
    val related = YtMusicRepository.radio(song.videoId).getOrNull()
        ?.filterNot { it.videoId == song.videoId }
        ?: return
    beginRadioQueue()
    val index = currentMediaItemIndex
    if (index >= 0 && currentMediaItem?.mediaId == song.videoId) {
        if (index + 1 < mediaItemCount) removeMediaItems(index + 1, mediaItemCount)
        if (index > 0) removeMediaItems(0, index)
        addMediaItems(1, related.map { it.toMediaItem() })
    } else {
        playSongs(listOf(song) + related, 0)
    }
    commitRadioQueue()
}

/** Picks one of the account's playlists for the song, or names a new one for it. */
@Composable
fun TvAddToPlaylistDialog(
    song: Song,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsState()
    val loading by viewModel.playlistsLoading.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (playlists.isEmpty()) viewModel.loadPlaylists() }

    val done = result
    when {
        done != null -> TvDialog(title = done, onDismissRequest = onDismiss) {
            TvDialogButton(text = stringResource(R.string.tv_ok), onClick = onDismiss, initialFocus = true)
        }
        creating -> TvDialog(
            title = stringResource(R.string.tv_new_playlist),
            message = stringResource(R.string.tv_new_playlist_message),
            onDismissRequest = { creating = false },
        ) {
            val create = {
                if (name.isNotBlank()) {
                    viewModel.createPlaylist(name.trim(), PlaylistPrivacy.PRIVATE, song)
                    result = context.getString(R.string.tv_added_to_playlist, name.trim())
                }
            }
            TvTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.tv_playlist_name),
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
                onImeAction = create,
                modifier = Modifier.tvInitialFocus(),
            )
            Spacer(modifier = Modifier.size(16.dp))
            TvDialogButton(text = stringResource(R.string.tv_create), enabled = name.isNotBlank(), onClick = create)
            Spacer(modifier = Modifier.size(6.dp))
            TvDialogButton(text = stringResource(R.string.tv_cancel), onClick = { creating = false })
        }
        else -> TvDialog(title = stringResource(R.string.tv_add_to_playlist), message = song.title, onDismissRequest = onDismiss) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 272.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_new_playlist),
                        trailingIcon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        modifier = Modifier.tvInitialFocus(),
                        onClick = { creating = true },
                    )
                }
                if (loading && playlists.isEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            TvActivityIndicator(size = 21.dp)
                        }
                    }
                }
                items(playlists, key = { it.playlistId }) { playlist ->
                    TvListRow(
                        title = playlist.title,
                        value = playlist.subtitle.takeIf { it.isNotBlank() },
                        onClick = {
                            viewModel.addToPlaylists(listOf(playlist), song) { added, already, _ ->
                                result = when {
                                    added > 0 -> context.getString(R.string.tv_added_to_playlist, playlist.title)
                                    already > 0 -> context.getString(R.string.tv_already_in_playlist, playlist.title)
                                    else -> context.getString(R.string.tv_couldnt_add_to_playlist, playlist.title)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Stop playback after a while, or at the end of this song. */
@Composable
fun TvSleepTimerDialog(onDismiss: () -> Unit) {
    val deadline by SleepTimer.deadline.collectAsState()
    val afterTrack by SleepTimer.afterTrack.collectAsState()
    val active = deadline != null || afterTrack

    TvDialog(title = stringResource(R.string.tv_sleep_timer), message = stringResource(R.string.tv_sleep_timer_message), onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(15, 30, 45, 60).forEachIndexed { index, minutes ->
                TvListRow(
                    title = stringResource(R.string.tv_minutes, minutes),
                    modifier = if (index == 0) Modifier.tvInitialFocus() else Modifier,
                    onClick = {
                        SleepTimer.start(minutes)
                        onDismiss()
                    },
                )
            }
            TvListRow(
                title = stringResource(R.string.tv_end_of_current_song),
                trailingIcon = if (afterTrack) Icons.Rounded.Check else null,
                onClick = {
                    SleepTimer.startAfterTrack()
                    onDismiss()
                },
            )
            if (active) {
                TvListRow(
                    title = stringResource(R.string.tv_turn_off_timer),
                    destructive = true,
                    onClick = {
                        SleepTimer.cancel()
                        onDismiss()
                    },
                )
            }
        }
    }
}
