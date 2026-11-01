package com.music.bitchord.ko

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.AlbumSubtitle
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.firstArtworkOrStill
import com.music.bitchord.data.model.parseAlbumSubtitle
import com.music.bitchord.data.stats.SmartPlaylist
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.components.BROWSE_LOADING_TIMEOUT_MS
import com.music.bitchord.ui.components.EmptyState
import com.music.bitchord.ui.components.HqLibraryProgressLine
import com.music.bitchord.ui.components.HqLibraryUpgradeAllRow
import com.music.bitchord.ui.components.LoadingTimeoutState
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.detailSkeleton
import com.music.bitchord.ui.components.rememberLoadingTimedOut
import com.music.bitchord.ui.components.topBarContentPadding
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.screens.MvSoundSuspectBanner
import com.music.bitchord.ui.screens.deviceFolderIcon
import com.music.bitchord.ui.screens.matching
import com.music.bitchord.ui.screens.playtime
import com.music.bitchord.ui.theme.ArtworkPalette

/** State the detail page's first load needs to offer its own retry. */
internal class KoDetailLoadingState(
    val timedOut: Boolean,
    val onRetry: (() -> Unit)?,
)

@Composable
internal fun koDetailLoadingState(
    loading: Boolean,
    browseId: String,
    onRetryLoading: (() -> Unit)?,
): KoDetailLoadingState {
    var loadAttempt by remember { mutableIntStateOf(0) }
    val timedOut = rememberLoadingTimedOut(
        loading = loading,
        tag = "detail",
        timeoutMs = BROWSE_LOADING_TIMEOUT_MS,
        requestKey = "$browseId:$loadAttempt",
    )
    val retry: (() -> Unit)? = onRetryLoading?.let { action ->
        {
            loadAttempt += 1
            action()
        }
    }
    return KoDetailLoadingState(timedOut, retry)
}

/** The page cover, or the first row's picture/still when the header had none. */
@Composable
internal fun koDetailHeaderArtwork(
    thumbnailUrl: String?,
    songs: List<Song>,
    suggestedSongs: List<Song>,
): String? = remember(thumbnailUrl, songs, suggestedSongs) {
    thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: (songs + suggestedSongs).firstArtworkOrStill()
}

/** An album's header and footer share one reading of the server's subtitle. */
@Composable
internal fun koDetailAlbumSubtitle(subtitle: String, type: BrowseType): AlbumSubtitle? =
    remember(subtitle, type) {
        if (type == BrowseType.ALBUM) parseAlbumSubtitle(subtitle) else null
    }

/** The empty-shelf flags: smart playlists, Liked Music, and device folders. */
internal class KoDetailEmptyFlags(
    val likedMusicPage: Boolean,
    val smartEmpty: Boolean,
    val folderEmpty: Boolean,
    val smartNeedsSignIn: Boolean,
)

internal fun koDetailEmptyFlags(
    browseId: String,
    songsError: Boolean,
    folderPresent: Boolean,
    signedIn: Boolean,
): KoDetailEmptyFlags {
    val likedMusicPage = browseId == YtMusicRepository.LIKED_MUSIC
    val smartEmpty = (SmartPlaylist.of(browseId) != null || likedMusicPage) && songsError
    return KoDetailEmptyFlags(
        likedMusicPage = likedMusicPage,
        smartEmpty = smartEmpty,
        folderEmpty = folderPresent && songsError,
        smartNeedsSignIn = smartEmpty && !signedIn &&
            (SmartPlaylist.of(browseId) == SmartPlaylist.LIKED_UNPLAYED || likedMusicPage),
    )
}

/** Opening Downloads refreshes the length verdicts from what is already recorded. */
@Composable
internal fun KoDetailDownloadsScan(browseId: String) {
    val context = LocalContext.current
    LaunchedEffect(browseId) {
        if (browseId == "local:downloads") {
            Downloads.scanForMvSoundSuspects(context)
        }
    }
}

/** The album's last-track note: release date when stated, then tracks and time. */
@Composable
internal fun KoAlbumFooter(
    songs: List<Song>,
    albumSubtitle: AlbumSubtitle?,
    palette: ArtworkPalette,
) {
    val date = albumSubtitle?.let { parts ->
        val year = parts.year
        val month = parts.month
        val day = parts.day
        when {
            year == null -> null
            month != null && day != null ->
                stringResource(R.string.album_release_date, year, month, day)
            else -> stringResource(R.string.album_release_year, year.toString())
        }
    }
    val summary = listOfNotNull(
        pluralStringResource(R.plurals.track_count_plural, songs.size, songs.size),
        songs.playtime(),
    ).joinToString(", ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                top = 18.dp,
                bottom = 30.dp,
            ),
    ) {
        if (date != null) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelMedium,
                color = palette.onBackgroundVariant,
            )
            Spacer(Modifier.height(3.dp))
        }
        if (summary.isNotBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = palette.onBackgroundVariant,
            )
        }
    }
}

/** The artist page's popular-songs strip, narrowed by the page's own filter first. */
internal fun List<Song>.koArtistTopSongs(query: String, limit: Int): List<Song> =
    matching(query).take(limit).map { it.value }

/** Downloads' high-quality progress and MV-sound rows above a folder's list. */
internal fun LazyListScope.koDetailDownloadExtras(
    browseId: String,
    songCount: Int,
    palette: ArtworkPalette,
    gutter: Dp,
) {
    if (browseId != "local:downloads" || songCount == 0) return
    item(key = "hq-library-progress") {
        HqLibraryProgressLine(
            Modifier.padding(horizontal = gutter, vertical = 6.dp),
        )
    }
    item(key = "hq-library-upgrade-all") {
        HqLibraryUpgradeAllRow(
            Modifier.padding(horizontal = gutter, vertical = 2.dp),
        )
    }
    item(key = "mv-sound-suspects") {
        MvSoundSuspectBanner(palette)
    }
}

/** A smart playlist with no songs: its name as the bold line and the reason below. */
@Composable
internal fun SmartPlaylistEmptyState(
    title: String,
    message: String,
    onSignIn: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = BitChordIcons.MusicNote,
        title = title.ifBlank { message },
        message = message.takeIf { title.isNotBlank() },
        actionLabel = if (onSignIn != null) stringResource(R.string.sign_in) else null,
        onAction = onSignIn,
        modifier = modifier,
    )
}

/** The detail page's first load, or its retry once the timeout has passed. */
internal fun LazyListScope.koDetailLoadingItems(
    timedOut: Boolean,
    onRetry: (() -> Unit)?,
    isArtist: Boolean,
) {
    if (timedOut && onRetry != null) {
        item(key = "detail:timeout") { LoadingTimeoutState(onRetry = onRetry) }
    } else {
        detailSkeleton(isArtist)
    }
}

/** The detail page's error state: empty shelf, folder, or a retryable message. */
internal fun LazyListScope.koDetailErrorItems(
    browseId: String,
    pageTitle: String,
    message: String,
    folderEmpty: Boolean,
    smartEmpty: Boolean,
    smartNeedsSignIn: Boolean,
    onSignIn: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    when {
        folderEmpty -> item(key = "folder-empty") {
            EmptyState(
                icon = deviceFolderIcon(browseId) ?: BitChordIcons.MusicNote,
                title = pageTitle.ifBlank { message },
                message = message.takeIf { pageTitle.isNotBlank() },
                modifier = Modifier
                    .fillParentMaxHeight(0.86f)
                    .padding(top = topBarContentPadding()),
            )
        }
        smartEmpty -> item(key = "smart-empty") {
            SmartPlaylistEmptyState(
                title = pageTitle,
                message = message,
                onSignIn = onSignIn.takeIf { smartNeedsSignIn },
                modifier = Modifier
                    .fillParentMaxHeight(0.86f)
                    .padding(top = topBarContentPadding()),
            )
        }
        onRetry != null -> item {
            MessageState(
                message,
                actionLabel = stringResource(R.string.try_again),
                onAction = onRetry,
            )
        }
        else -> item { MessageState(message) }
    }
}
