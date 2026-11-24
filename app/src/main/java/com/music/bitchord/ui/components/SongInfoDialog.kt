package com.music.bitchord.ui.components

import com.music.bitchord.ui.icons.SfIcons
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.music.bitchord.data.SongCredit
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.ko.SongInfoDisplay
import com.music.bitchord.ko.SongInfoField
import com.music.bitchord.ko.SongInfoQuality
import com.music.bitchord.ui.theme.ArtworkPalette
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
 * Kept in a window [Dialog] because it opens from the song actions sheet, which
 * is itself a window; an in-tree overlay would be drawn underneath it. A
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
    val creditsScroll = rememberScrollState()
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
            // [KO] up110: the body still hugs its rows up to the cap (the fork's row
            // count varies), so the credits and the error never hide below the fold.
            val surface = MaterialTheme.colorScheme.surface
            Box(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .heightIn(max = INFO_BODY_MAX_HEIGHT)
                        .drawWithContent {
                            drawContent()
                            if (creditsScroll.canScrollForward) {
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        1f to surface,
                                        startY = size.height - 28.dp.toPx(),
                                        endY = size.height,
                                    )
                                )
                            }
                        }
                        .verticalScroll(creditsScroll),
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
                    extraCredits(info).forEach { credit ->
                        AlertRule(Modifier.padding(start = 16.dp))
                        SongInfoCreditRow(role = credit.role, names = credit.text)
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
                if (creditsScroll.canScrollForward) {
                    Icon(
                        imageVector = SfIcons.ChevronDown,
                        contentDescription = null,
                        tint = onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).size(16.dp),
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

/**
 * The sheet's own Song Info page, laid out like the sleep timer picker: a back
 * arrow and title over a hairline, then full-width rows on the artwork tint.
 * [SongInfoDialog] is kept for the held-row menu, which has no pages to swap.
 */
@Composable
internal fun SongInfoPage(song: Song, palette: ArtworkPalette, onBack: () -> Unit) {
    var retry by remember(song.videoId) { mutableIntStateOf(0) }
    val info by produceState<SongInfo?>(null, song, retry) {
        value = null
        value = SongInfoRepository.load(song)
    }
    val snapshot by NerdStats.current.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    SfIcons.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.song_info),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        val unknown = stringResource(R.string.song_info_unknown)
        val quality = SongInfoQuality.label(song, snapshot)
        val lines = info?.let { SongInfoDisplay.lines(it, song, quality, unknown) }
            ?: SongInfoDisplay.loadingLines(song)
        lines.forEach { line ->
            SongInfoPageRow(
                label = stringResource(line.field.labelRes()),
                value = line.value,
                unknown = line.unknown,
                palette = palette,
            )
        }
        extraCredits(info).forEach { credit ->
            SongInfoPageRow(label = credit.role, value = credit.text, unknown = false, palette = palette)
        }

        if (info?.failed == true) {
            ActionRow(SfIcons.Cached, stringResource(R.string.song_info_retry), accent = palette.accent) {
                retry++
            }
            Text(
                text = stringResource(R.string.song_info_error),
                modifier = Modifier.padding(horizontal = 22.dp),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onBackgroundVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Same metrics as the sleep timer's options; the value takes the sheet's accent, spinner while loading. */
@Composable
private fun SongInfoPageRow(label: String, value: String?, unknown: Boolean, palette: ArtworkPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value == null) {
                CircularProgressIndicator(
                    color = palette.onBackgroundVariant,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (unknown) palette.onBackgroundVariant else palette.accent,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** The credits YouTube lists that the composer and lyricist lines do not already say. */
private fun extraCredits(info: SongInfo?): List<SongCredit> =
    info?.credits.orEmpty().filter { it.text != info?.composer && it.text != info?.lyricist }

@Composable
private fun SongInfoCreditRow(role: String, names: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = role,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = AppType.Caption, lineHeight = 17.sp),
            color = onSurface.copy(alpha = 0.6f),
        )
        Text(
            text = names,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = AppType.Secondary),
            color = onSurface,
        )
    }
}
