package com.music.bitchord.ko.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.lyrics.withEstimatedWords
import com.music.bitchord.data.lyrics.withHeardWords
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.player.LyricsSkeleton
import com.music.bitchord.ui.player.PLAYER_GUTTER
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun koTranscribingPlaceholder(modifier: Modifier) {
    Column(modifier) {
        Text(
            text = stringResource(Res.string.lyrics_transcribing),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                top = 40.dp,
                start = PLAYER_GUTTER,
                end = PLAYER_GUTTER,
            ),
        )
        LyricsSkeleton(topPadding = 18.dp)
    }
}

@Composable
internal fun koChooseProviderButton(onChooseProvider: (() -> Unit)?) {    if (onChooseProvider != null) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onChooseProvider) {
            Text(
                text = stringResource(Res.string.choose_lyrics_provider),
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

internal fun koShownLine(
    line: LyricLine,
    nextStamp: Long?,
    isSynced: Boolean,
    heardWords: List<LyricsWordAlign.Heard>,
): LyricLine =
    when {
        !isSynced -> line
        else -> line.withHeardWords(heardWords, nextStamp)
            ?: line.withEstimatedWords(nextStamp)
    }

internal fun koSweptLine(
    lineItem: LyricLine?,
    instrumental: Boolean,
    nextStamp: Long?,
    heardWords: List<LyricsWordAlign.Heard>,
): LyricLine? =
    lineItem?.takeIf { !instrumental }
        ?.let { it.withHeardWords(heardWords, nextStamp) ?: it.withEstimatedWords(nextStamp) }
        ?.takeIf { it.isWordSynced }

@Composable
internal fun koStatusChevron() {
    Icon(
        imageVector = BitChordIcons.ChevronRight,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.45f),
        modifier = Modifier
            .padding(start = 4.dp)
            .size(14.dp),
    )
}
