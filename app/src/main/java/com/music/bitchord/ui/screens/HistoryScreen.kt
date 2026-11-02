package com.music.bitchord.ui.screens

import com.music.bitchord.R

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.playingRowMatcher
import com.music.bitchord.ui.components.EmptyState
import com.music.bitchord.ui.components.LoadingTimeoutState
import com.music.bitchord.ui.components.MessageState
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
    var loadAttempt by remember { mutableIntStateOf(0) }
    val loadingTimedOut = rememberLoadingTimedOut(
        loading = state is UiState.Loading,
        tag = "history",
        requestKey = loadAttempt,
    )
    val retry = {
        loadAttempt += 1
        onRetry()
    }
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
                HistoryEmpty(
                    message = stringResource(R.string.history_sign_in_required),
                    onSignIn = onSignIn,
                    modifier = Modifier.fillParentMaxHeight(0.82f),
                )
            }
            return@LazyColumn
        }
        // A signed-in account that has not played anything yet used to get the
        // page's heading and then nothing at all. That is indistinguishable
        // from a request that never came back.
        if (historyHasNoSongs(state, signedIn)) {
            item(key = "history:empty") {
                HistoryEmpty(
                    message = stringResource(R.string.history_empty),
                    modifier = Modifier.fillParentMaxHeight(0.82f),
                )
            }
            return@LazyColumn
        }
        when (state) {
            is UiState.Loading -> if (loadingTimedOut) {
                item(key = "history:timeout") { LoadingTimeoutState(onRetry = retry) }
            } else {
                songListSkeleton(count = 10, keyPrefix = "skeleton:history")
            }

            is UiState.Error -> item(key = "history:message") {
                MessageState(
                    message = state.message,
                    actionLabel = stringResource(R.string.try_again),
                    onAction = retry,
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
 * What a guest sees instead of the list, and what a signed-in account with
 * nothing played sees too: a small clock, the page's name, the reason and,
 * for a guest, the account button. Deliberately no progress indicator —
 * nothing is loading, and a spinner over a settled answer reads as a request
 * that never finishes.
 */
@Composable
private fun HistoryEmpty(
    message: String,
    onSignIn: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = BitChordIcons.Clock,
        title = stringResource(R.string.listening_history),
        message = message,
        actionLabel = if (onSignIn != null) stringResource(R.string.sign_in) else null,
        onAction = onSignIn,
        modifier = modifier,
    )
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
