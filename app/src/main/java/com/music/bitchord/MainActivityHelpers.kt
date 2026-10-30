package com.music.bitchord

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.session.MediaController
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.QualityUpgrade
import java.util.Locale

internal fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

@Composable
internal fun LibrarySort.localizedLabel(): String = when (this) {
    LibrarySort.DEFAULT -> stringResource(R.string.sort_default)
    LibrarySort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    LibrarySort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
}

@Composable
internal fun SongSort.localizedLabel(): String = when (this) {
    SongSort.DEFAULT -> stringResource(R.string.sort_default)
    SongSort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    SongSort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
    SongSort.DATE_ADDED_ASC -> stringResource(R.string.sort_date_added_oldest)
    SongSort.DATE_ADDED_DESC -> stringResource(R.string.sort_date_added)
}

/**
 * Whether this page id is one of the two device folders — `local:downloads` and
 * `local:all`, the tabbed Songs / Artists / Albums view.
 *
 * Asked rather than `startsWith("local:")` because that prefix now covers two
 * unlike pages: a folder, and one downloaded playlist, which is a plain track
 * listing under its own cover and wants the same chrome every other release page
 * gets. See [Downloads.PLAYLIST_PREFIX] for why they share a namespace at all.
 */
internal fun String?.isDeviceFolder(): Boolean =
    this != null && startsWith("local:") && !startsWith(Downloads.PLAYLIST_PREFIX)

/**
 * Whether [videoId] is the track playing, and is known to be playing YouTube's
 * own copy — which decides whether the player menu leads with "Revert to
 * original" or with "Upgrade quality".
 *
 * See [QualityUpgrade.isKnownToBePlayingYouTubesOwn] for why "known" is doing
 * real work here: an unresolved track playing off the disk cache answers false,
 * and keeps the revert on offer.
 */
internal fun playingYouTubesOwn(videoId: String, controller: MediaController?): Boolean {
    val item = controller?.currentMediaItem?.takeIf { it.mediaId == videoId } ?: return false
    return QualityUpgrade.isKnownToBePlayingYouTubesOwn(
        videoId,
        item.localConfiguration?.uri?.let(QualityUpgrade::cacheTag),
    )
}

/** `M:SS`/`H:MM:SS`, the same shape [String?.durationMillis] parses back. */
internal fun formatDurationText(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

internal fun shouldSkipAfterDislike(
    previousStatus: LikeStatus,
    targetVideoId: String,
    currentVideoId: String?,
): Boolean = previousStatus != LikeStatus.DISLIKE && targetVideoId == currentVideoId

/**
 * The keys that decide whether the before-audio music-only swap runs.
 *
 * [videoRadio] is part of the decision (see `videoRadioEnabledFor`), so it has
 * to be part of the effect key too: changing the switch while a video row is
 * playing must re-evaluate the swap instead of waiting for the next track.
 */
internal fun autoMusicOnlyEffectKey(
    songId: String?,
    preferMusicOnly: Boolean,
    videoRadio: Boolean,
): List<Any?> = listOf(songId, preferMusicOnly, videoRadio)
