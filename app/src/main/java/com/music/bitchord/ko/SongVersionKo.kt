package com.music.bitchord.ko

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.videoRadioEnabledFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Video rows already offered the music-only swap in this process. The effect
 * that offers it re-runs whenever the activity's UI is rebuilt (returning to
 * the app), and a second offer would pause a song that is already playing.
 */
internal val musicOnlyTried: MutableSet<String> =
    java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

/**
 * Rows from a preview listing (a downloaded copy shown while the network
 * response is still on its way) do not know whether they are videos. Keep
 * them as audio rows but mark the type unknown, so the service and the
 * pre-audio lookup still ask for a song version — see [Song.typeUnknown].
 */
internal fun List<Song>.asTypeUnknown(): List<Song> =
    map {
        if (!it.isVideo &&
            !SongVersionPolicy.keepsLocalFile(it.localUri != null, it.isVideo, it.typeUnknown)
        ) {
            it.copy(typeUnknown = true)
        } else {
            it
        }
    }

/**
 * Resolves the catalogue release before audio starts when the listener asked
 * for music-only. A timeout or failure keeps the video row — never silence.
 */
internal suspend fun koSongBeforeAudio(song: Song): Song =
    if (AppSettings.preferMusicOnly.value && !videoRadioEnabledFor(song)) {
        try {
            withTimeout(SongVersionPolicy.BEFORE_AUDIO_TIMEOUT_MS) {
                YtMusicRepository.songVersion(song, beforeAudio = true)
            } ?: song
        } catch (timeout: TimeoutCancellationException) {
            TrackLog.i("BitChord", "song version: ${song.videoId} kept (timeout)")
            song
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            TrackLog.w("BitChord", "song version failed for radio: ${failure.message}")
            song
        }
    } else {
        song
    }

/** The same pre-audio resolution for a queue's current row and the rows shown after it. */
internal suspend fun koResolveQueueAhead(songs: List<Song>, index: Int): List<Song> =
    if (AppSettings.preferMusicOnly.value) {
        try {
            // Current row plus the bounded forward window, so a station or
            // playlist queue shows the song versions it is about to play
            // instead of their music-video cards.
            YtMusicRepository.resolveQueueAhead(
                songs,
                index,
                ahead = SongVersionPolicy.QUEUE_VERSION_AHEAD + 1,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            TrackLog.w("BitChord", "song version look-ahead failed: ${failure.message}")
            songs
        }
    } else {
        songs
    }
