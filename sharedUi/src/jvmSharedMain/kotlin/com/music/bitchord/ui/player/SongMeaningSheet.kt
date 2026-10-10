package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/**
 * The "what this song means" control: a disc beside the translate one, drawn
 * the same way, shown only while a Gemini key is set up.
 */
@Composable
internal fun SongMeaningButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color.White.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.42f)
    val discAlpha by animateFloatAsState(
        targetValue = if (enabled) 0.18f else 0.12f,
        label = "meaningDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = stringResource(Res.string.song_meaning),
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Gemini's explanation of the playing song, in a [PlayerDrawer]: one line on
 * what it is about, a few paragraphs, and the lines worth a note. Asked once
 * per song and language — the answer is kept, so opening it again is instant.
 */
@Composable
internal fun SongMeaningSheet(
    hazeState: HazeState,
    song: Song,
    lines: List<LyricLine>,
    targetLanguage: String,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    val currentLines by rememberUpdatedState(lines)
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(song.videoId, targetLanguage) {
        mutableStateOf<LyricsTranslation.MeaningResult?>(null)
    }
    LaunchedEffect(song.videoId, targetLanguage, attempt) {
        result = null
        result = PlayerPlatform.host.songMeaning(song, currentLines, targetLanguage)
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.song_meaning),
        onDismiss = onDismiss,
        titleGap = 4.dp,
    ) {
        Text(
            text = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 16.dp),
        )
        when (val state = result) {
            null -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(Res.string.song_meaning_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            is LyricsTranslation.MeaningResult.Ready -> MeaningBody(state.meaning)
            LyricsTranslation.MeaningResult.NoKey -> MeaningNotice(
                text = stringResource(Res.string.song_meaning_no_key),
                onRetry = null,
            )
            LyricsTranslation.MeaningResult.Unavailable -> MeaningNotice(
                text = stringResource(Res.string.song_meaning_unavailable),
                onRetry = {
                    haptics.play(Haptic.Tap)
                    attempt++
                },
            )
        }
    }
}

@Composable
private fun MeaningBody(meaning: LyricsTranslation.SongMeaning) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = meaning.summary,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                lineHeight = 24.sp,
            ),
            color = Color.White,
        )
        meaning.meaning
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        if (meaning.highlights.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.song_meaning_highlights),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp),
            )
            meaning.highlights.forEach { highlight ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ROW_SHAPE)
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "“${highlight.line}”",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = highlight.note,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }
        }
        Text(
            text = stringResource(Res.string.song_meaning_footer),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MeaningNotice(text: String, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.75f),
        )
        if (onRetry != null) {
            Text(
                text = stringResource(Res.string.song_meaning_retry),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRetry,
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}
