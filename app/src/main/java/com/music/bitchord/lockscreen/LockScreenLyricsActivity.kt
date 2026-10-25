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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.TranslationAhead
import com.music.bitchord.playback.rememberMediaController
import com.music.bitchord.playback.rememberPlayerState
import com.music.bitchord.ui.player.StandaloneLyricsPanel
import com.music.bitchord.ui.player.ThinSlider
import com.music.bitchord.ui.rememberIsForeground
import com.music.bitchord.ui.theme.SFProDisplay
import com.music.bitchord.sharedui.resources.Res as SharedRes
import com.music.bitchord.sharedui.resources.ic_player_next
import com.music.bitchord.sharedui.resources.ic_player_pause
import com.music.bitchord.sharedui.resources.ic_player_play
import com.music.bitchord.sharedui.resources.ic_player_previous
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lyrics, large, over the lock screen: the time, the player's own lyric
 * sheet — words filling in as they are sung, the translation under each line —
 * and the transport, on the blurred cover. Shown by [LockScreenLyrics] while
 * something is loaded.
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

@Composable
private fun LockScreenLyricsScreen(onUnlock: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val controller = rememberMediaController()
    val state = rememberPlayerState(controller)
    val song = state.song
    val entry by NowPlayingLyrics.current.collectAsStateWithLifecycle()

    // Nothing loaded any more — the queue was cleared, the service stopped.
    LaunchedEffect(controller, song?.videoId) {
        if (controller != null && song == null) {
            delay(1_500)
            if (controller.currentMediaItem == null) onClose()
        }
    }

    val lines = entry?.takeIf { it.videoId == song?.videoId }?.lines.orEmpty()
    val hasWords = remember(lines) { lines.any { it.text.isNotBlank() } }

    // The translation, small under each line — the same one the player shows,
    // usually already made ahead of time and answered from the cache.
    // Kept per song, not per list: the lines re-timed onto the video arrive
    // as a new list, and the translation stays up while it follows them.
    val textKey = remember(lines) { lines.joinToString("\n") { it.text } }
    var translated by remember(song?.videoId, textKey) { mutableStateOf<List<LyricLine>?>(null) }
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
        (result as? LyricsTranslation.Result.Translated)?.lines
            ?.takeIf { it.size == lines.size }
            ?.let { translated = it }
    }

    // Swiping up lifts the whole screen with the finger, the way the system
    // lock screen does; far enough, and the system is asked to unlock.
    val scope = rememberCoroutineScope()
    val lift = remember { Animatable(0f) }
    val artwork = song?.thumbnailUrl
    val backdropRequest = remember(artwork) {
        ImageRequest.Builder(context).data(artwork.artworkAt(BACKDROP_ART_PX)).crossfade(600).build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .pointerInput(Unit) {
                val threshold = 120.dp.toPx()
                // Summed here rather than read back off [lift]: several moves
                // can land in one frame, before any snap has run, and each one
                // reading the same stale value lost the finger's distance.
                var offset = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset = lift.value },
                    onDragEnd = {
                        val unlocking = offset < -threshold
                        if (unlocking) onUnlock()
                        scope.launch {
                            lift.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                        }
                    },
                    onDragCancel = { scope.launch { lift.animateTo(0f) } },
                ) { change, delta ->
                    change.consume()
                    offset = (offset + delta).coerceIn(-size.height.toFloat(), 0f)
                    val target = offset
                    scope.launch { lift.snapTo(target) }
                }
            },
    ) {
        // The cover, blurred out to a wash of its colours; crossfades to the
        // next one as the track changes.
        if (!artwork.isNullOrBlank()) {
            AsyncImage(
                model = backdropRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                    }
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
                .graphicsLayer {
                    // 0 at rest, 1 once lifted a third of the screen: by
                    // then the content has faded out and settled back a little.
                    val progress = (-lift.value / (size.height * 0.33f)).coerceIn(0f, 1f)
                    translationY = lift.value
                    alpha = 1f - progress
                    scaleX = 1f - 0.08f * progress
                    scaleY = 1f - 0.08f * progress
                }
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Clock()
            Spacer(Modifier.height(12.dp))

            // The cover first, the lyrics a swipe to the side — the way the
            // listener left it last time. Just the cover when there are no lyrics.
            val prefs = remember { context.getSharedPreferences("bitchord_settings", android.content.Context.MODE_PRIVATE) }
            val pages = if (song != null && hasWords) 2 else 1
            val pager = rememberPagerState(
                initialPage = if (pages > 1) prefs.getInt(KEY_LOCK_PAGE, 0).coerceIn(0, 1) else 0,
            ) { pages }
            // A song's lyrics arrive after it starts: back to them, if they are
            // what the listener was reading.
            LaunchedEffect(pages) {
                if (pages > 1 && pager.currentPage == 0 && prefs.getInt(KEY_LOCK_PAGE, 0) == 1) pager.scrollToPage(1)
            }
            LaunchedEffect(pager, pages) {
                snapshotFlow { pager.settledPage }.collect { page ->
                    if (pages > 1) prefs.edit().putInt(KEY_LOCK_PAGE, page).apply()
                }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                Box(Modifier.fillMaxSize()) {
                    if (page == 1 && song != null && hasWords) {
                        StandaloneLyricsPanel(
                            lines = lines,
                            subLines = translated,
                            trackKey = song.videoId,
                            position = state.position,
                            isPlaying = state.isPlaying,
                            onSeek = { controller?.seekTo(it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (song != null) {
                        // As large as the space allows, and never more: on a
                        // short screen it shrinks rather than run up into the clock.
                        androidx.compose.foundation.layout.BoxWithConstraints(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val side = minOf(maxWidth, maxHeight)
                            AsyncImage(
                                model = artwork.artworkAt(COVER_ART_PX),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(side)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                            )
                        }
                    }
                }
            }
            if (pages > 1) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    repeat(pages) { index ->
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (pager.currentPage == index) 0.9f else 0.35f)),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            val likeOverrides by com.music.bitchord.data.LikeState.overrides.collectAsStateWithLifecycle()
            val liked = song?.videoId?.let { likeOverrides[it] } == com.music.bitchord.data.model.LikeStatus.LIKE
            val signedIn = com.music.bitchord.data.innertube.Innertube.cookie != null
            NowPlayingCard(
                title = song?.title.orEmpty(),
                artist = song?.artist.orEmpty(),
                artworkUrl = artwork,
                isPlaying = state.isPlaying,
                position = { state.position.positionMs },
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
                // The notification's own heart, through the same session
                // command: the like reaches YouTube, and liked-song downloads
                // follow from there as they do from anywhere else.
                liked = liked,
                onToggleLike = if (signedIn && song != null) {
                    {
                        controller?.sendCustomCommand(
                            androidx.media3.session.SessionCommand(
                                com.music.bitchord.playback.ACTION_TOGGLE_FAVORITE,
                                Bundle.EMPTY,
                            ),
                            Bundle.EMPTY,
                        )
                        Unit
                    }
                } else {
                    null
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Row(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 10.dp)
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

private const val BACKDROP_ART_PX = 360
private const val KEY_LOCK_PAGE = "lock_screen_lyrics_page"
private const val COVER_ART_PX = 720

@Composable
private fun Clock() {
    val context = LocalContext.current
    val foreground = rememberIsForeground()
    // Hours and minutes only: woken on the minute, not every second — this
    // screen sits under a dark display through whole albums. Parked entirely
    // while the screen is off, so a composition kept alive behind the lock
    // does not wake once a minute for a clock nobody can see.
    val now by produceState(System.currentTimeMillis(), foreground) {
        if (!foreground) return@produceState
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L - value % 60_000L)
        }
    }
    val locale = remember { Locale.getDefault() }
    val timePattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
    val datePattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE") }
    val time = remember(now / 60_000L, timePattern) { SimpleDateFormat(timePattern, locale).format(Date(now)) }
    val date = remember(now / 60_000L, datePattern) { SimpleDateFormat(datePattern, locale).format(Date(now)) }
    // The way the system lock screen sets it: the date small above, the time
    // large and tight beneath, in the same face as the rest of the player.
    Text(
        text = date,
        color = Color.White.copy(alpha = 0.82f),
        fontFamily = SFProDisplay,
        fontSize = 19.sp,
        fontWeight = FontWeight.W600,
    )
    Text(
        text = time,
        color = Color.White.copy(alpha = 0.94f),
        fontFamily = SFProDisplay,
        fontSize = 96.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = (-2.5).sp,
        lineHeight = 100.sp,
        style = androidx.compose.ui.text.TextStyle(
            fontFeatureSettings = "tnum",
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        ),
    )
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
    liked: Boolean,
    /** Null hides the heart — signed out, or nothing playing. */
    onToggleLike: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = if (durationMs > 0L) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = dragging ?: fraction
    Column(
        modifier = modifier
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
                    fontFamily = SFProDisplay,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    color = Color.White.copy(alpha = 0.65f),
                    fontFamily = SFProDisplay,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onToggleLike != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleLike),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (liked) com.music.bitchord.ui.icons.BitChordIcons.HeartFilled else com.music.bitchord.ui.icons.BitChordIcons.Heart,
                        contentDescription = stringResource(if (liked) R.string.remove_from_liked else R.string.like),
                        tint = if (liked) Color(0xFFFF4D66) else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        // The player's own scrubber: a thin line that thickens under the finger.
        ThinSlider(
            value = shown,
            onValueChange = { if (durationMs > 0L) dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            idleHeight = 4.dp,
            activeHeight = 9.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 6.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatTime((shown * durationMs).toLong()),
                color = Color.White.copy(alpha = 0.6f),
                fontFamily = SFProDisplay,
                fontSize = 12.sp,
                fontWeight = FontWeight.W600,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "-" + formatTime(durationMs - (shown * durationMs).toLong()),
                color = Color.White.copy(alpha = 0.6f),
                fontFamily = SFProDisplay,
                fontSize = 12.sp,
                fontWeight = FontWeight.W600,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(SharedRes.drawable.ic_player_previous, R.string.lock_screen_lyrics_previous, 36, onPrevious)
            TransportButton(
                if (isPlaying) SharedRes.drawable.ic_player_pause else SharedRes.drawable.ic_player_play,
                if (isPlaying) R.string.lock_screen_lyrics_pause else R.string.lock_screen_lyrics_play,
                40,
                onPlayPause,
            )
            TransportButton(SharedRes.drawable.ic_player_next, R.string.lock_screen_lyrics_next, 36, onNext)
        }
    }
}

@Composable
private fun TransportButton(
    icon: org.jetbrains.compose.resources.DrawableResource,
    label: Int,
    sizeDp: Int,
    onClick: () -> Unit,
) {
    // The player's own glyphs (◀◀ ▶▶, the rounded play), not the system's
    // skip-to-bar ones: the same transport as everywhere else in the app.
    Box(
        modifier = Modifier
            .size((sizeDp + 18).dp)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = org.jetbrains.compose.resources.painterResource(icon),
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
