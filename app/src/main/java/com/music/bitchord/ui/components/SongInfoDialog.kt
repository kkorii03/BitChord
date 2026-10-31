package com.music.bitchord.ui.components

import com.music.bitchord.ui.theme.AppType

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoDisplay
import com.music.bitchord.data.SongInfoField
import com.music.bitchord.data.SongInfoQuality
import com.music.bitchord.data.SongInfoRepository
import com.music.bitchord.data.model.Song
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh

private val INFO_BODY_MAX_HEIGHT = 360.dp

private fun SongInfoField.labelRes(): Int = when (this) {
    SongInfoField.ALBUM -> R.string.song_info_album
    SongInfoField.RELEASE_DATE -> R.string.song_info_release_date
    SongInfoField.GENRE -> R.string.song_info_genre
    SongInfoField.COMPOSER -> R.string.song_info_composer
    SongInfoField.LYRICIST -> R.string.song_info_lyricist
    SongInfoField.QUALITY -> R.string.song_info_quality
    SongInfoField.VIEWS -> R.string.song_info_views
    SongInfoField.LIKES -> R.string.song_info_likes
    SongInfoField.COMMENTS -> R.string.song_info_comments
}

/**
 * Drawn as the same UIAlertController-style card as [AlertScaffold] — centred
 * title, hairline [AlertRule]s, full-width [AlertAction]s — rather than a
 * Material [AlertDialog], so it reads as one of the app's own alerts.
 *
 * Kept in a window [Dialog] because it opens from the song actions menu, which
 * can sit inside the player's own window; an in-tree overlay would be drawn
 * underneath it. A
 * separate window can't sample the app's haze, so the card takes the plain
 * surface the other alerts fall back to under "reduce dynamic blur".
 */
@Composable
internal fun SongInfoDialog(song: Song, onDismiss: () -> Unit) {
    var retry by remember(song.videoId) { mutableIntStateOf(0) }
    val info by produceState<SongInfo?>(null, song, retry) {
        value = null
        value = SongInfoRepository.load(song)
    }
    val snapshot by NerdStats.current.collectAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(ALERT_WIDTH)
                .clip(RoundedCornerShape(ALERT_CORNER))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.song_info),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = AppType.Body, fontWeight = FontWeight.W600),
                    color = onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = song.title,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = AppType.Caption, lineHeight = 17.sp),
                    color = onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song.artist.isNotBlank()) {
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = AppType.Caption, lineHeight = 17.sp),
                        color = onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            AlertRule()
            Column(
                Modifier
                    .heightIn(max = INFO_BODY_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                val unknown = stringResource(R.string.song_info_unknown)
                val quality = SongInfoQuality.label(song, snapshot)
                val lines = info?.let { SongInfoDisplay.lines(it, song, quality, unknown) }
                    ?: SongInfoDisplay.loadingLines(song)
                lines.forEachIndexed { index, line ->
                    if (index > 0) AlertRule(Modifier.padding(start = 16.dp))
                    SongInfoRow(
                        label = stringResource(line.field.labelRes()),
                        value = line.value,
                        unknown = line.unknown,
                    )
                }
                if (info?.failed == true) {
                    Text(
                        text = stringResource(R.string.song_info_error),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = AppType.Caption, lineHeight = 15.sp),
                        color = onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            AlertRule()
            if (info?.failed == true) {
                AlertAction(label = stringResource(R.string.song_info_retry), emphasised = false, onClick = { retry++ })
                AlertRule()
            }
            AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = onDismiss)
        }
    }
}

/** Label on the left, value on the right; a small spinner stands in for the value while it loads. */
@Composable
private fun SongInfoRow(label: String, value: String?, unknown: Boolean = false) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = AppType.Secondary),
            color = onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = onSurface.copy(alpha = 0.5f),
                    strokeWidth = 1.75.dp,
                )
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = AppType.Secondary),
                    color = if (unknown) onSurface.copy(alpha = 0.6f) else onSurface,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
