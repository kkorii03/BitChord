package com.music.bitchord.ko

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The one switch palette every settings toggle uses.
 *
 * Material's off track is the card's own container colour, so a row's switch
 * all but disappears against it. The outline grey keeps the track visible at
 * rest, the way iOS's off state does, while the checked colours stay the
 * app's accent.
 */
@Composable
internal fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = IosSwitchGreen,
    checkedBorderColor = IosSwitchGreen,
    checkedThumbColor = Color.White,
    uncheckedTrackColor = IosSwitchGrey,
    uncheckedBorderColor = IosSwitchGrey,
    uncheckedThumbColor = Color.White,
)

/** iOS's switch palette: green when on, graphite when off, white knob in both. */
private val IosSwitchGreen = Color(0xFF34C759)
private val IosSwitchGrey = Color(0xFF39393D)

/**
 * A row's explanation, a clear step below its title: four-fifths of the title
 * size with a tight leading, so one-line subtitles stay compact.
 */
@Composable
internal fun settingsSubtitleStyle() = MaterialTheme.typography.bodyLarge.copy(
    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 0.96f,
)
