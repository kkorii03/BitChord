package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.utils.containSheetGestures
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import java.util.Locale
import kotlin.math.roundToInt

private val DrawerShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
private val ControlShape = RoundedCornerShape(16.dp)
private val ScrimColor = Color.Black.copy(alpha = 0.5f)
private val DrawerMaxWidth = 640.dp
private const val StepMs = 100
private const val DismissDragFraction = 0.25f

/**
 * What the sync drawer needs to correct the song playing on its own: its
 * correction now, the lines as they are showing (that correction included),
 * where the lyrics clock is, and where to send a new correction.
 */
internal class SongLyricsSync(
    val offsetMs: Long,
    /** How the lines were moved onto the video's clock, for the figures shown here. */
    val timing: LyricsTimingUi,
    /** Asks Gemini to time the lines (or takes its answer back); null where it can't. */
    val onAiTiming: (() -> Unit)?,
    val lines: List<LyricLine>,
    /** The lyrics clock now: the playhead with the all-songs offset applied, ms. */
    val lyricPositionMs: () -> Long,
    val onOffset: (Long) -> Unit,
)

/**
 * A player drawer for shifting synced lyrics against the playback clock: for
 * this song alone (by step, or by tapping as a line starts) and for every song.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun LyricsOffsetSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    song: SongLyricsSync? = null,
) {
    val offsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val drawerOffset by animateFloatAsState(
        targetValue = drag,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "lyricsOffsetDrawerOffset",
    )
    val scrimAlpha = if (height > 0) {
        (1f - drawerOffset / height).coerceIn(0f, 1f)
    } else {
        1f
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    // The body scrolls, so its scroll owns the gesture before the drag
    // detector below sees it. A pull past the top has to move the drawer
    // itself, the way every other sheet here behaves.
    val dismissOnRelease: () -> Unit = {
        if (height > 0 && drag > height * DismissDragFraction) onDismiss() else drag = 0f
    }
    val drawerScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (drag <= 0f || available.y >= 0f) return Offset.Zero
                val used = available.y.coerceAtLeast(-drag)
                drag += used
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) drag += available.y
                if (drag > 0f && available.y < 0f) {
                    val used = available.y.coerceAtLeast(-drag)
                    drag += used
                    return Offset(0f, used)
                }
                return available
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag <= 0f) return Velocity.Zero
                dismissOnRelease()
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .containSheetGestures()
            .background(ScrimColor.copy(alpha = ScrimColor.alpha * scrimAlpha))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(180)) { it },
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = DrawerMaxWidth)
                    .fillMaxWidth()
                    .onSizeChanged { height = it.height }
                    .offset { IntOffset(0, drawerOffset.roundToInt()) }
                    .clip(DrawerShape)
                    .then(
                        if (reduceDynamicBlur) {
                            Modifier.background(Color(0xFF121212))
                        } else {
                            Modifier
                                .optimizedHazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.regular(Color(0xFF141414)),
                                )
                                .background(Color(0xFF121212).copy(alpha = 0.9f))
                        },
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .nestedScroll(drawerScroll)
                    .pointerInput(height) {
                        detectVerticalDragGestures(
                            onDragEnd = dismissOnRelease,
                            onDragCancel = { drag = 0f },
                        ) { _, delta -> drag = (drag + delta).coerceAtLeast(0f) }
                    }
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(bottom = 12.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f)),
                )
                Text(
                    text = stringResource(Res.string.lyrics_offset),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                )
                Text(
                    text = stringResource(Res.string.lyrics_offset_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 3.dp, bottom = 14.dp),
                )

                if (song != null) {
                    SongOffsetSection(song)
                    SectionLabel(stringResource(Res.string.lyrics_offset_all))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ControlShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = formatOffset(offsetMs),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OffsetButton(
                            label = "−",
                            contentDescription = stringResource(Res.string.decrease_lyrics_offset),
                            enabled = offsetMs > MIN_LYRICS_OFFSET_MS,
                        ) {
                            haptics.play(Haptic.Select)
                            PlayerSettings.setLyricsOffsetMs(offsetMs - StepMs)
                        }
                        ThinSlider(
                            value = offsetToFraction(offsetMs),
                            onValueChange = { PlayerSettings.setLyricsOffsetMs(fractionToOffset(it)) },
                            idleHeight = 6.dp,
                            activeHeight = 10.dp,
                            modifier = Modifier.weight(1f),
                        )
                        OffsetButton(
                            label = "+",
                            contentDescription = stringResource(Res.string.increase_lyrics_offset),
                            enabled = offsetMs < MAX_LYRICS_OFFSET_MS,
                        ) {
                            haptics.play(Haptic.Select)
                            PlayerSettings.setLyricsOffsetMs(offsetMs + StepMs)
                        }
                    }
                }

                Text(
                    text = stringResource(Res.string.lyrics_offset_reset),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = if (offsetMs == 0) 0.35f else 0.8f),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clip(CircleShape)
                        .clickable(enabled = offsetMs != 0) {
                            haptics.play(Haptic.Tap)
                            PlayerSettings.setLyricsOffsetMs(0)
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 8.dp),
    )
}

/** Tapping registers a beat after the singer starts; this much is taken back off. */
private const val TapReactionMs = 120L

/** Per-song steps are finer to reach and coarser to need: a tap does the big moves. */
private const val SongStepMs = 100L

/**
 * This song's own correction: a step either way, and "tap to sync" — pick a
 * line, tap as it is heard, and that line lands on the tap.
 */
@Composable
private fun SongOffsetSection(song: SongLyricsSync) {
    val haptics = rememberHaptics()
    val current by rememberUpdatedState(song)
    val sung = remember(song.lines) { song.lines.filter { it.text.isNotBlank() } }
    var tapping by remember { mutableStateOf(false) }
    // The line the next tap is for, as an index into [sung].
    var target by remember { mutableIntStateOf(-1) }

    SectionLabel(stringResource(Res.string.lyrics_offset_song))
    VideoTimingBlock(song.timing, song.onAiTiming)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OffsetButton(
                label = "−",
                contentDescription = stringResource(Res.string.decrease_lyrics_offset),
                enabled = song.offsetMs > -MAX_SONG_OFFSET_MS,
            ) {
                haptics.play(Haptic.Select)
                current.onOffset(current.offsetMs - SongStepMs)
            }
            Text(
                text = formatOffset(song.offsetMs),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            OffsetButton(
                label = "+",
                contentDescription = stringResource(Res.string.increase_lyrics_offset),
                enabled = song.offsetMs < MAX_SONG_OFFSET_MS,
            ) {
                haptics.play(Haptic.Select)
                current.onOffset(current.offsetMs + SongStepMs)
            }
        }
        Text(
            text = stringResource(Res.string.lyrics_offset_song_description),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
        )

        if (tapping && sung.isNotEmpty()) {
            val index = target.coerceIn(0, sung.lastIndex)
            Text(
                text = stringResource(Res.string.lyrics_tap_sync_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { target = (index - 1).coerceAtLeast(0) }, enabled = index > 0) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(Res.string.lyrics_tap_previous_line),
                        tint = Color.White.copy(alpha = if (index > 0) 0.85f else 0.25f),
                    )
                }
                Text(
                    text = sung[index].text,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { target = (index + 1).coerceAtMost(sung.lastIndex) }, enabled = index < sung.lastIndex) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(Res.string.lyrics_tap_next_line),
                        tint = Color.White.copy(alpha = if (index < sung.lastIndex) 0.85f else 0.25f),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(ControlShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable {
                        val now = current
                        val line = sung.getOrNull(index) ?: return@clickable
                        // The line lands where the tap was, less the time a
                        // tap takes to follow what was heard.
                        val moved = now.lyricPositionMs() - TapReactionMs - line.timeMs
                        val next = (now.offsetMs + moved).coerceIn(-MAX_SONG_OFFSET_MS, MAX_SONG_OFFSET_MS)
                        haptics.play(Haptic.Tap)
                        now.onOffset((next / 10) * 10)
                        target = (index + 1).coerceAtMost(sung.lastIndex)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.lyrics_tap_now),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                text = stringResource(if (sung.isEmpty()) Res.string.lyrics_tap_no_lines else Res.string.lyrics_tap_sync),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = if (sung.isEmpty()) 0.35f else if (tapping) 1f else 0.8f),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (tapping) 0.14f else 0f))
                    .clickable(enabled = sung.isNotEmpty()) {
                        haptics.play(Haptic.Tap)
                        if (!tapping) {
                            // Start from the next line due by the lyrics clock.
                            val at = current.lyricPositionMs()
                            target = sung.indexOfFirst { it.timeMs > at }.let { if (it < 0) sung.lastIndex else it }
                        }
                        tapping = !tapping
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
            Text(
                text = stringResource(Res.string.lyrics_offset_reset),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = if (song.offsetMs == 0L) 0.35f else 0.8f),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = song.offsetMs != 0L) {
                        haptics.play(Haptic.Tap)
                        current.onOffset(0L)
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

/**
 * What the app did to put the lines on this video's clock — the measurement's
 * or Gemini's answer, with its figures — and the AI timing control, kept here
 * rather than over the lyrics where a near miss jumped the song.
 */
@Composable
private fun VideoTimingBlock(timing: LyricsTimingUi, onAiTiming: (() -> Unit)?) {
    val haptics = rememberHaptics()
    val line = when {
        timing.aiRunning -> stringResource(Res.string.lyrics_timing_running)
        timing.aiFailed -> stringResource(Res.string.lyrics_timing_failed)
        else -> lyricsTimingDetail(timing) ?: stringResource(
            if (timing.videoOwn) Res.string.lyrics_timing_video_own else Res.string.lyrics_timing_untouched,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(ControlShape)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.lyrics_timing_section),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
            )
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The measurement's own figures: which recording, how close. Small
            // and dim — it is there for a screenshot when a video won't time.
            timing.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.38f),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (onAiTiming != null) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (timing.byAi) 0.22f else 0.12f))
                    .clickable(enabled = !timing.aiRunning) {
                        haptics.play(Haptic.Tap)
                        onAiTiming()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (timing.aiRunning) {
                    // A spinner alone in the pill read as a stray mark; it
                    // says what it is doing, the way the button says what it does.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = Color.White.copy(alpha = 0.8f),
                            strokeWidth = 1.5.dp,
                        )
                        Text(
                            text = stringResource(Res.string.lyrics_timing_ai_button_running),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(
                            if (timing.byAi) Res.string.ai_lyrics_timing_undo else Res.string.lyrics_timing_ai_button,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** How far a song's own correction may go either way — the same bound the app keeps. */
internal const val MAX_SONG_OFFSET_MS = 60_000L

@Composable
private fun OffsetButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White.copy(alpha = if (enabled) 0.85f else 0.25f),
        )
    }
}

private fun offsetToFraction(offsetMs: Int): Float =
    (offsetMs - MIN_LYRICS_OFFSET_MS).toFloat() /
        (MAX_LYRICS_OFFSET_MS - MIN_LYRICS_OFFSET_MS)

private fun fractionToOffset(fraction: Float): Int {
    val raw = MIN_LYRICS_OFFSET_MS +
        fraction.coerceIn(0f, 1f) *
        (MAX_LYRICS_OFFSET_MS - MIN_LYRICS_OFFSET_MS)
    return (raw / StepMs).roundToInt() * StepMs
}

private fun formatOffset(offsetMs: Int): String = formatOffset(offsetMs.toLong())

private fun formatOffset(offsetMs: Long): String = String.format(
    Locale.getDefault(),
    "%+.1f s",
    offsetMs / 1_000f,
)
