package com.music.bitchord.lockscreen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.TranslationAhead
import com.music.bitchord.playback.rememberMediaController
import com.music.bitchord.playback.rememberPlayerState
import com.music.bitchord.ui.rememberIsForeground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The lyrics, large, over the lock screen: the time, the song's lines with the
 * one being sung lit and its translation under it, and the transport — on the
 * blurred cover. Shown by [LockScreenLyrics] while something is loaded.
 *
 * Never a way past the lock: swiping up asks the system to unlock (PIN,
 * fingerprint, face as usual) and back simply reveals the real lock screen.
 * It closes itself the moment the phone is unlocked any other way.
 */
class LockScreenLyricsActivity : ComponentActivity() {

    companion object {
        /** True while this screen is in front, so a screen-on does not start it twice. */
        @Volatile
        var showing: Boolean = false
            private set
    }

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) finish()
        }
    }
    private var unlockedRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        unlockedRegistered = runCatching {
            ContextCompat.registerReceiver(
                this,
                unlocked,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.isSuccess
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LockScreenLyricsScreen(
                    onUnlock = ::unlock,
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        showing = true
    }

    override fun onStop() {
        super.onStop()
        showing = false
    }

    override fun onDestroy() {
        if (unlockedRegistered) runCatching { unregisterReceiver(unlocked) }
        unlockedRegistered = false
        showing = false
        super.onDestroy()
    }

    /** Asks the system to unlock; this screen goes once it has. */
    private fun unlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    finish()
                }
            },
        )
    }
}

private data class ShownLine(val index: Int, val line: LyricLine)

@Composable
private fun LockScreenLyricsScreen(onUnlock: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val controller = rememberMediaController()
    val state = rememberPlayerState(controller)
    val song = state.song
    val foreground = rememberIsForeground()
    val offsetMs by AppSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val entry by NowPlayingLyrics.current.collectAsStateWithLifecycle()

    // Nothing loaded any more — the queue was cleared, the service stopped.
    LaunchedEffect(controller, song?.videoId) {
        if (controller != null && song == null) {
            delay(1_500)
            if (controller.currentMediaItem == null) onClose()
        }
    }

    // Read often while playing, so the lit line keeps up with the singer; only
    // while this screen is actually in front.
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller, state.isPlaying, foreground, song?.videoId) {
        val player = controller ?: return@LaunchedEffect
        positionMs = player.currentPosition.coerceAtLeast(0L)
        while (foreground && state.isPlaying) {
            delay(120)
            positionMs = player.currentPosition.coerceAtLeast(0L)
        }
    }

    val lines = entry?.takeIf { it.videoId == song?.videoId }?.lines.orEmpty()
    val shown = remember(lines) {
        lines.mapIndexedNotNull { index, line ->
            ShownLine(index, line).takeIf { line.text.isNotBlank() }
        }
    }

    // The translation, small under each line — the same one the player shows,
    // usually already made ahead of time and answered from the cache.
    var translated by remember(song?.videoId, lines) { mutableStateOf<List<LyricLine>?>(null) }
    LaunchedEffect(song?.videoId, lines) {
        val id = song?.videoId ?: return@LaunchedEffect
        if (lines.isEmpty()) return@LaunchedEffect
        val target = TranslationAhead.targetLanguage(context)
        val result = try {
            withContext(Dispatchers.IO) { LyricsTranslation.translate(id, lines, target) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        translated = (result as? LyricsTranslation.Result.Translated)?.lines
            ?.takeIf { it.size == lines.size }
    }

    // Paused, the player's own reading follows every seek; playing, the poll
    // above is the fresher of the two. Read through a lambda and a derived
    // state, so a tick redraws the scrubber and, only when the line changes,
    // the lyrics — not the whole screen.
    val isPlaying = state.isPlaying
    val playhead: () -> Long = { if (isPlaying) positionMs else state.position.positionMs }
    // Plain lyrics carry no timings: nothing is lit and nothing scrolls.
    val synced = remember(shown) { shown.any { it.line.timeMs > 0L } }
    val current by remember(shown, offsetMs, isPlaying, synced) {
        derivedStateOf {
            if (!synced) return@derivedStateOf -1
            val lyricMs = playhead() - offsetMs
            shown.indexOfLast { it.line.timeMs <= lyricMs }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .pointerInput(Unit) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        if (travelled < -with(density) { 120.dp.toPx() }) onUnlock()
                        travelled = 0f
                    },
                    onDragCancel = { travelled = 0f },
                ) { _, delta -> travelled += delta }
            },
    ) {
        // The cover, blurred out to a wash of its colours.
        if (!song?.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = song?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(70.dp) else Modifier),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.55f)),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Clock()
            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (shown.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = song?.title.orEmpty(),
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = song?.artist.orEmpty(),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    LyricsColumn(
                        shown = shown,
                        translated = translated,
                        current = current,
                        synced = synced,
                        onSeek = { line ->
                            controller?.seekTo((line.timeMs + offsetMs).coerceAtLeast(0L))
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            NowPlayingCard(
                title = song?.title.orEmpty(),
                artist = song?.artist.orEmpty(),
                artworkUrl = song?.thumbnailUrl,
                isPlaying = state.isPlaying,
                position = playhead,
                durationMs = state.durationMs,
                onSeek = { controller?.seekTo(it) },
                onPrevious = { controller?.seekToPrevious() },
                onPlayPause = {
                    val player = controller
                    if (player != null) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                },
                onNext = { controller?.seekToNext() },
            )
            Row(
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 10.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onUnlock)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.lock_screen_lyrics_unlock),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    val locale = remember { Locale.getDefault() }
    val timePattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
    val datePattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE") }
    val time = remember(now / 60_000L, timePattern) { SimpleDateFormat(timePattern, locale).format(Date(now)) }
    val date = remember(now / 60_000L, datePattern) { SimpleDateFormat(datePattern, locale).format(Date(now)) }
    Text(
        text = time,
        color = Color.White,
        fontSize = 84.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 88.sp,
    )
    Text(
        text = date,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun LyricsColumn(
    shown: List<ShownLine>,
    translated: List<LyricLine>?,
    current: Int,
    synced: Boolean,
    onSeek: (LyricLine) -> Unit,
) {
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    // A line the reader scrolled to stays put for a few seconds before the
    // list goes back to following the song.
    var heldUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) {
        if (dragged) heldUntil = Long.MAX_VALUE
        else if (heldUntil == Long.MAX_VALUE) heldUntil = System.currentTimeMillis() + 4_000L
    }
    var tick by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(heldUntil) {
        if (heldUntil != Long.MAX_VALUE && heldUntil > System.currentTimeMillis()) {
            delay(heldUntil - System.currentTimeMillis())
            tick += 1f
        }
    }
    LaunchedEffect(current, tick, shown) {
        if (!synced || System.currentTimeMillis() < heldUntil) return@LaunchedEffect
        val target = current.coerceAtLeast(0)
        if (target < shown.size) listState.animateScrollToItem(target)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = maxHeight * 0.32f
        val bottom = maxHeight * 0.6f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = top, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.14f to Color.Black,
                            0.82f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(shown, key = { _, item -> item.index }) { position, item ->
                val lit = position == current
                val alpha by animateFloatAsState(
                    targetValue = when {
                        !synced -> 0.9f
                        lit -> 1f
                        else -> 0.38f
                    },
                    animationSpec = tween(260),
                    label = "lockLyricAlpha",
                )
                val sub = translated?.getOrNull(item.index)?.text
                    ?.takeIf { it.isNotBlank() && it != item.line.text }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { this.alpha = alpha }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = synced,
                        ) { onSeek(item.line) },
                ) {
                    Text(
                        text = item.line.text,
                        color = Color.White,
                        fontSize = 27.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (sub != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = sub,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    position: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val positionMs = position()
    val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            enabled = durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatTime(((dragging ?: fraction) * durationMs).toLong()),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTime(durationMs),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(Icons.Rounded.SkipPrevious, R.string.lock_screen_lyrics_previous, 34, onPrevious)
            TransportButton(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (isPlaying) R.string.lock_screen_lyrics_pause else R.string.lock_screen_lyrics_play,
                46,
                onPlayPause,
            )
            TransportButton(Icons.Rounded.SkipNext, R.string.lock_screen_lyrics_next, 34, onNext)
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    sizeDp: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size((sizeDp + 18).dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(label),
            tint = Color.White,
            modifier = Modifier.size(sizeDp.dp),
        )
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    return "%d:%02d".format(total / 60, total % 60)
}
