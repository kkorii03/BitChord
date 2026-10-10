package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.playback.PlaybackPosition

/**
 * The player's own lyric sheet — the word-by-word sweep, the lift and glow of
 * the line being sung, the translation small under each line — for a screen
 * that is not the player, such as the lock screen.
 *
 * Exactly the panel the player draws, with the player's controls taken out of
 * it: there is no transport behind these lines for a tap to bring back.
 */
@Composable
fun StandaloneLyricsPanel(
    lines: List<LyricLine>,
    /** Translated copies of [lines], index for index; null for none. */
    subLines: List<LyricLine>?,
    trackKey: String,
    position: PlaybackPosition,
    isPlaying: Boolean,
    /** Seeks the player, in song time (the lyrics offset already applied). */
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val playhead = rememberLyricPlayhead(position)
    LyricsPanel(
        lines = lines,
        subLines = subLines?.takeIf { it.size == lines.size },
        trackKey = trackKey,
        playhead = playhead,
        looking = false,
        isPlaying = isPlaying,
        onSeekToLine = { lineTimeMs -> onSeek(adjustedLyricsSeekTarget(lineTimeMs, offsetMs)) },
        controlsOpen = true,
        onRevealControls = {},
        onHideControls = {},
        // The panel bleeds out by the player's gutter and pads its lines back
        // in by the same amount, so it expects to sit inside that gutter.
        modifier = modifier.padding(horizontal = PLAYER_GUTTER),
    )
}
