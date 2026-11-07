package com.music.bitchord.ko.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.player.LyricPlayhead
import com.music.bitchord.ui.player.PLAYER_GUTTER
import com.music.bitchord.ui.player.PlayerSettings
import org.jetbrains.compose.resources.stringResource

/**
 * Tap-to-record: the owner taps the lyrics the moment each line starts being
 * sung, and every tap is one numbers-only log line, so a song gets an answer key
 * for lyric sync that no measurement of ours wrote.
 *
 * Memory only, never saved: a restart always comes back with it off, so it can
 * not be left on by accident. Everything here runs on the main thread (menu
 * clicks, composition, pointer input), so nothing is locked.
 */
object KoTapSync {
    /** Whether lyric taps record instead of seeking. */
    var on by mutableStateOf(false)
        private set

    /** Taps recorded for the song in [session]; drawn in the lyric view. */
    var taps by mutableIntStateOf(0)
        private set

    /** The lyrics source label the player last logged, for the tap lines. */
    var source: String? = null

    private var session: String? = null
    private var sheetKey = 0

    fun set(value: Boolean) {
        if (value == on) return
        if (!value) endSession()
        on = value
    }

    /** Opens the log for [videoId] when the mode is on, closing any other song's first. */
    internal fun ensureSession(videoId: String, lines: List<LyricLine>, offsetMs: Int) {
        if (!on || videoId.isBlank()) return
        if (session != videoId) {
            endSession()
            session = videoId
            taps = 0
            sheetKey = 0
            log(videoId, "$videoId start off=$offsetMs")
        }
        // Again whenever the line times change (a refit, another source); not on a
        // translation toggle, which keeps every time.
        val key = koTapSheetKey(lines)
        if (key != sheetKey) {
            sheetKey = key
            koTapSheetLines(videoId, lines).forEach { log(videoId, it) }
        }
    }

    internal fun record(videoId: String, lines: List<LyricLine>, offsetMs: Int, tap: KoTapReading) {
        ensureSession(videoId, lines, offsetMs)
        if (session != videoId) return
        taps += 1
        log(videoId, koTapLine(videoId, taps, tap, source))
    }

    private fun endSession() {
        val id = session ?: return
        log(id, "$id stop taps=$taps")
        session = null
        taps = 0
        sheetKey = 0
    }

    private fun log(videoId: String, body: String) {
        TrackLog.i("BitChord", "tap-sync: $body", about = videoId)
    }
}

/** What a tap saw, all taken at the moment the finger came down. */
internal data class KoTapReading(
    val posMs: Long,
    val drawnMs: Long,
    val line: Int,
    val sampledMs: Long,
)

/**
 * The tap's own time on the [System.nanoTime] clock. A pointer event is stamped
 * in uptime milliseconds, which is that clock on the phone; anywhere it is not
 * (the two seconds apart), the time the event was handled is the best there is.
 */
internal fun koTapEventNanos(eventUptimeMs: Long, nowNanos: Long): Long {
    val event = eventUptimeMs * 1_000_000L
    return if (event in (nowNanos - SAME_CLOCK_NANOS)..nowNanos) event else nowNanos
}

/**
 * Where the song was at [tapNanos]: the reading, carried forward from when it was
 * taken at the playing speed, the way the lyric clock dates its readings. A
 * stopped song is where the reading says, however old it is. A reading taken
 * after the tap is carried back the same way.
 */
internal fun koTapSongMs(
    positionMs: Long,
    sampledAtNanos: Long,
    tapNanos: Long,
    moving: Boolean,
    speed: Float,
): Long {
    if (!moving || sampledAtNanos <= 0L) return positionMs
    val rate = if (speed.isNaN() || speed <= 0f) 1.0 else speed.toDouble()
    val sinceMs = (tapNanos - sampledAtNanos) / 1_000_000.0
    return (positionMs + sinceMs * rate).toLong().coerceAtLeast(0L)
}

/** How old the reading was at the tap, or -1 when there was none. */
internal fun koTapReadingAgeMs(sampledAtNanos: Long, tapNanos: Long): Long =
    if (sampledAtNanos <= 0L) -1L else (tapNanos - sampledAtNanos) / 1_000_000L

/** One tap, numbers only. */
internal fun koTapLine(videoId: String, n: Int, tap: KoTapReading, source: String?): String =
    "$videoId n=$n pos=${tap.posMs} drawn=${tap.drawnMs} line=${tap.line} " +
        "src=${koTapSourceLabel(source)} sampled=${tap.sampledMs}"

/** The source label as one token, so a label with a space cannot break the line apart. */
internal fun koTapSourceLabel(source: String?): String =
    source?.trim()?.replace(Regex("\\s+"), "_")?.ifEmpty { null } ?: "none"

/** The sheet as numbers: its line count, then each line's index and own start time. Never its text. */
internal fun koTapSheetLines(videoId: String, lines: List<LyricLine>): List<String> =
    listOf("$videoId sheet lines=${lines.size}") +
        lines.mapIndexed { i, line ->
            "$videoId sheet n=$i t=${line.timeMs}" + if (line.isGap) " gap" else ""
        }

internal fun koTapSheetKey(lines: List<LyricLine>): Int {
    var key = lines.size
    lines.forEach { key = key * 31 + it.timeMs.hashCode() }
    // 0 is "not logged yet".
    return if (key == 0) 1 else key
}

/**
 * The lyric list's hook: while the mode is on, a tap anywhere on the list is a
 * recorded tap and does nothing else. Taken at the initial pass and only the
 * lifting finger is consumed, so the rows never see a click (no seek), while the
 * down and every move go through untouched and the list still scrolls. A finger
 * that moved past the touch slop, or a second finger, is a scroll, not a tap.
 * Off, this adds nothing: no detector, no drawing.
 */
@Composable
internal fun Modifier.koTapSync(
    trackKey: String,
    lines: List<LyricLine>,
    playhead: LyricPlayhead,
    clock: LongState,
    currentLine: () -> Int,
    songMoving: Boolean,
    active: Boolean,
): Modifier {
    val on = KoTapSync.on && active
    val haptics = rememberHaptics()
    val measurer = rememberTextMeasurer()
    val label = stringResource(Res.string.tap_sync_count)
    val latestLine = rememberUpdatedState(currentLine)
    val latestMoving = rememberUpdatedState(songMoving)
    val latestLines = rememberUpdatedState(lines)
    LaunchedEffect(on, trackKey, lines) {
        if (on) KoTapSync.ensureSession(trackKey, lines, PlayerSettings.lyricsOffsetMs.value)
    }
    if (!on) return this
    return this
        .pointerInput(trackKey, playhead, clock) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val tapNanos = koTapEventNanos(down.uptimeMillis, System.nanoTime())
                val position = playhead.position
                val sampledAt = position.sampledAtNanos
                val reading = KoTapReading(
                    posMs = koTapSongMs(position.positionMs, sampledAt, tapNanos, latestMoving.value, position.speed),
                    drawnMs = clock.longValue,
                    line = latestLine.value(),
                    sampledMs = koTapReadingAgeMs(sampledAt, tapNanos),
                )
                var dragged = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.size > 1 ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) {
                        dragged = true
                    }
                    if (!change.pressed) {
                        if (!dragged) {
                            change.consume()
                            KoTapSync.record(trackKey, latestLines.value, PlayerSettings.lyricsOffsetMs.value, reading)
                            haptics.play(Haptic.Tick)
                        }
                        break
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()
            // Read here, not in composition: a tap redraws this corner and nothing else.
            val text = "$label ${KoTapSync.taps}"
            val laid = measurer.measure(text, COUNTER_STYLE)
            drawText(
                laid,
                topLeft = Offset(
                    x = size.width - laid.size.width - PLAYER_GUTTER.toPx(),
                    y = COUNTER_TOP.toPx(),
                ),
            )
        }
}

private val COUNTER_STYLE = TextStyle(
    color = Color.White.copy(alpha = 0.7f),
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
)
private val COUNTER_TOP = 8.dp

/** A pointer stamp this close behind [System.nanoTime] is on the same clock. */
private const val SAME_CLOCK_NANOS = 1_000_000_000L
