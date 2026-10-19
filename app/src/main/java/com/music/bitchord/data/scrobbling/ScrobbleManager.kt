package com.music.bitchord.data.scrobbling

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ListeningSink
import com.music.bitchord.data.settings.PrivateListening
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToLong

class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 180,
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false
    var useNowPlaying = true
    var usePrimaryArtistOnly = false

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
    }

    fun onSongStart(
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null || !recordingAllowed()) return
        songStartedAt = System.currentTimeMillis() / 1000
        songStarted = true
        startScrobbleTimer(song, durationMs)
        if (useNowPlaying) {
            updateNowPlaying(song)
        }
    }

    fun onSongResume(song: Song) {
        if (!recordingAllowed()) return
        resumeScrobbleTimer(song)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {
        stopScrobbleTimer()
        songStarted = false
    }

    private fun startScrobbleTimer(
        song: Song,
        durationMs: Long? = null,
    ) {
        scrobbleJob?.cancel()
        scrobbleJob = null
        // A new track must never inherit the previous one's remaining time:
        // pausing/resuming a track with no known duration would otherwise
        // scrobble it on the old track's schedule.
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        val resolvedDurationSeconds = durationMs?.toInt()?.div(1000)
            ?: durationSeconds(song.durationText)
            ?: return

        if (resolvedDurationSeconds <= minSongDuration) return

        val thresholdMs = (resolvedDurationSeconds * 1000L * scrobbleDelayPercent).roundToLong()
        scrobbleRemainingMillis = min(thresholdMs, scrobbleDelaySeconds * 1000L)

        if (scrobbleRemainingMillis <= 0) {
            scrobbleSong(song, resolvedDurationSeconds)
            return
        }
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(song, resolvedDurationSeconds)
                scrobbleJob = null
            }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0) scrobbleRemainingMillis = 0
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun resumeScrobbleTimer(song: Song) {
        if (scrobbleRemainingMillis <= 0) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                val durationSec = durationSeconds(song.durationText) ?: 0
                scrobbleSong(song, durationSec)
                scrobbleJob = null
            }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0
    }

    private fun scrobbleSong(song: Song, durationSeconds: Int) {
        if (!recordingAllowed()) return
        // A cover is scrobbled to who sings it — the byline "원곡자 · 가수 커버"
        // is how the player shows it, not an artist Last.fm knows.
        val scrobbleArtist = com.music.bitchord.data.cover.CoverTitles.creditedArtist(song).forScrobble()
        scope.launch {
            LastFM
                .scrobble(
                    artist = scrobbleArtist,
                    track = song.title,
                    duration = durationSeconds,
                    timestamp = songStartedAt,
                    album = song.albumName,
                ).onSuccess {
                    Log.d(TAG, "Scrobbled: ${song.title} by ${song.artist}")
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Log.e(TAG, "Failed to scrobble: ${song.title}", throwable)
                }
        }
    }

    private fun updateNowPlaying(song: Song) {
        if (!recordingAllowed()) return
        // A cover is scrobbled to who sings it — the byline "원곡자 · 가수 커버"
        // is how the player shows it, not an artist Last.fm knows.
        val scrobbleArtist = com.music.bitchord.data.cover.CoverTitles.creditedArtist(song).forScrobble()
        scope.launch {
            LastFM
                .updateNowPlaying(
                    artist = scrobbleArtist,
                    track = song.title,
                    album = song.albumName,
                    duration = durationSeconds(song.durationText),
                ).onSuccess {
                    Log.d(TAG, "Updated now playing: ${song.title}")
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Log.e(TAG, "Failed to update now playing: ${song.title}", throwable)
                }
        }
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        if (isPlaying) {
            if (!songStarted) {
                onSongStart(song, durationMs)
            } else {
                onSongResume(song)
            }
        } else {
            onSongPause()
        }
    }

    private fun String.forScrobble(): String = if (usePrimaryArtistOnly) primaryArtist() else this

    /**
     * Private listening was switched on: drop the pending scrobble and the
     * "now playing" state. Nothing is submitted for the interrupted play, and
     * turning the mode back off starts a new decision at the next track.
     */
    fun onPrivateModeEnabled() = destroy()

    private fun recordingAllowed(): Boolean =
        PrivateListening.allows(ListeningSink.SCROBBLING, AppSettings.privateListening.value)

    companion object {
        private const val TAG = "ScrobbleManager"

        /**
         * A row's display duration as whole seconds, or null when it states
         * none. [durationMillis] reads `M:SS` and `H:MM:SS`, so an hour-plus
         * track no longer parses to zero and stops being scrobbled.
         */
        internal fun durationSeconds(text: String?): Int? =
            (text.durationMillis() / 1000L).toInt().takeIf { it > 0 }
    }
}
