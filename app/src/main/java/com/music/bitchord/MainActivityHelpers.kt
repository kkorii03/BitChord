package com.music.bitchord

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.session.MediaController
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.SongSort
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

// playingYouTubesOwn and formatDurationText live in MainActivity.kt again:
// v1.9 keeps them there (its playingYouTubesOwn also reads the cache origin).
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
