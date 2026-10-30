package com.music.bitchord.ui.screens

import com.music.bitchord.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.playingRowMatcher
import com.music.bitchord.ui.components.LoadingTimeoutState
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.rememberLoadingTimedOut
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.songListSkeleton
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * What the account has been listening to, most recent first.
 *
 * A plain list rather than a page with a header: no single cover stands for a
 * history, and borrowing one — the newest track's, say — would claim the page
 * belonged to it.
 *
 * Tapping a row plays it with the rest of the history behind it, so the list
 * doubles as a queue that has already been approved once. That is the useful
 * reading of a history: not a receipt, but everything worth playing again.
 */
@Composable
fun HistoryScreen(
    state: UiState<List<Song>>,
    listState: LazyListState,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val loadingTimedOut = rememberLoadingTimedOut(state is UiState.Loading, "history")
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        // Signed out is not a failed request: there is no account to have a
        // history on, so a retry button and a loading gesture would both be
        // lying about what the page is waiting for. Offer the way in instead.
        if (!signedIn) {
            item(key = "history:signed-out") {
                HistorySignedOut(
                    message = stringResource(R.string.history_sign_in_required),
                    onSignIn = onSignIn,
                )
            }
            return@LazyColumn
        }
        // A signed-in account that has not played anything yet used to get the
        // page's heading and then nothing at all. That is indistinguishable
        // from a request that never came back.
        if (historyHasNoSongs(state, signedIn)) {
            item(key = "history:empty") {
                MessageState(stringResource(R.string.history_empty))
            }
            return@LazyColumn
        }
        when (state) {
            is UiState.Loading -> if (loadingTimedOut) {
                item(key = "history:timeout") { LoadingTimeoutState(onRetry = onRetry) }
            } else {
                songListSkeleton(count = 10, keyPrefix = "skeleton:history")
            }

            is UiState.Error -> item(key = "history:message") {
                MessageState(
                    message = state.message,
                    actionLabel = stringResource(R.string.try_again),
                    onAction = onRetry,
                )
            }

            is UiState.Success -> {
                val songs = state.data
                val isCurrentRow = playingRowMatcher(songs, currentSong)
                // The videoId alone is not a key here: the feed is deduplicated
                // on it, but a list keyed on something that could repeat is one
                // bad response away from a crash. The index makes it total.
                itemsIndexed(songs) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { onSongClick(songs, index) },
                        onLongPress = { onSongLongPress(song) },
                        onSwipeToQueue = { onSongSwipe(song) },
                        isCurrent = isCurrentRow(song),
                        isPlaying = isCurrentRow(song) && isPlaying,
                        searchPlayingStyle = true,
                        activeTint = PlayingAccent,
                    )
                    if (index < songs.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a guest sees instead of the list: a still clock, the reason, and the
 * account button. Deliberately no progress indicator — nothing is loading, and
 * a spinner over a settled answer reads as a request that never finishes.
 */
@Composable
private fun HistorySignedOut(
    message: String,
    onSignIn: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = BitChordIcons.Clock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(42.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onSignIn != null) {
            Button(onClick = onSignIn) { Text(stringResource(R.string.sign_in)) }
        }
    }
}

/** Keyed on both, for the reason given at the call site. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    songs: List<Song>,
    row: @Composable (Int, Song) -> Unit,
) = items(
    count = songs.size,
    key = { "${songs[it].videoId}:$it" },
) { index -> row(index, songs[index]) }

/** Whether a completed, signed-in history has nothing to list. */
internal fun historyHasNoSongs(state: UiState<List<Song>>, signedIn: Boolean): Boolean =
    signedIn && state is UiState.Success && state.data.isEmpty()
