package com.music.bitchord.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.player.PlayerSettings
import kotlin.math.roundToInt

/**
 * An Apple-style segmented control: equal-width labels on a rounded track, the
 * selected segment drawn as a raised pill that slides between positions.
 *
 * Chosen over Material's [androidx.compose.material3.TabRow] because the two
 * places this app still used one — a Library folder's Songs / Artists / Albums
 * — are content filters inside a page, not navigation, and the underlined tab
 * row reads as the latter. Labels carry no icons: the words are the whole
 * control, as they are on iOS.
 *
 * The slide honours both of the app's motion switches. With reduce-animation
 * on, or the phone in low-power mode, the pill jumps straight to its new
 * segment instead of animating.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val lowPower by PlayerSettings.lowPower.collectAsStateWithLifecycle()
    val still = reduceAnimation || lowPower
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.coerceIn(0, options.lastIndex).toFloat(),
        animationSpec = if (still) snap() else tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "segmentedSelection",
    )
    val haptics = rememberHaptics()

    val track = MaterialTheme.colorScheme.surfaceVariant
    // The selected segment has to read as raised over the track in either
    // theme. In light mode that is the page's own near-white surface; in dark
    // mode nothing in the scheme is lighter than surfaceVariant, so it is the
    // track lifted a little toward white instead.
    val pill = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        lerp(track, Color.White, 0.16f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val selectedContent = MaterialTheme.colorScheme.onSurface
    val unselectedContent = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(CircleShape)
            .background(track)
            .padding(3.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val segmentWidth = maxWidth / options.size.toFloat()
            Box(
                modifier = Modifier
                    .offset { IntOffset((segmentWidth.toPx() * animatedIndex).roundToInt(), 0) }
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .shadow(elevation = 1.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(pill),
            )
            Row(Modifier.fillMaxSize()) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = {
                                    if (!selected) {
                                        haptics.play(Haptic.Select)
                                        onSelect(index)
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) selectedContent else unselectedContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
