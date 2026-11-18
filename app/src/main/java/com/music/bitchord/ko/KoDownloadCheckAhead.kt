package com.music.bitchord.ko

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadHealth
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.BatteryPolicy
import com.music.bitchord.playback.EXTRA_UNVERIFIED_DOWNLOAD
import com.music.bitchord.playback.MusicOnlyPlayback
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.canonicalIndex
import com.music.bitchord.playback.contextCycleId
import com.music.bitchord.playback.queueEntryId
import com.music.bitchord.playback.queueTier
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.HqVerificationStore
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Download check ahead: the next track's saved file, while still unchecked, is
 * heard [LEAD_MS] before its transition instead of after it starts.
 *
 * 11-18 16:10-16:31 log (vc213): Opalite JCcUifiu3UU, Sucker 73ouhDibImA, Bones
 * V2SVRJ6dcE4 and There's Nothing Holdin' Me Back Ztdc7TcPdAA each had a saved,
 * unverified file. PlayerConnection's `withheldForCheck` built the row on its
 * stream, the start's [DownloadHealth.onPlayback] heard the file 5.6-22 s after
 * the transition, and [koUseVerifiedDownload] moved the song onto it mid-verse.
 *
 * Here the same check runs off the playing tick a minute ahead; when it accepts
 * the file, the next row is rebuilt as the file ([koMoveNextOntoFile]) so the
 * standby or ExoPlayer opens it from the first note. Not while a blend is armed
 * or running (the standby already holds the stream; the start moves it, now
 * without a decode, see [koApplyDownloadHealth]), and not for a file off
 * YouTube's clock ([KoLosslessAhead.cueSafe]): the mix cue was measured on the
 * stream.
 *
 * No timer: driven from PlaybackService.driveDirectWindow's tick, before its
 * Direct-only returns, so it runs with Direct Source Stream off too. Nothing
 * while paused, inside the seek settle, or for a row that is not waiting on its
 * check. The check can read the network (the YouTube reference when it is not
 * kept, a words hearing when one is owed), so it follows read-ahead's rule
 * ([BatteryPolicy.cachePrefetchBytesAllowed]: not saving power, not metered);
 * otherwise the start's check runs as before.
 */
internal object KoDownloadCheckAhead {

    /** The direct lead's span: search, proof and a decode fit in it on the 11-18 log. */
    const val LEAD_MS = KoDirectLead.LEAD_MS

    /** The pair already checked ahead: once only, a skip back to it asks the start's check. */
    private var pair: Pair<String, String>? = null

    /** Any tick inside [LEAD_MS] of the transition. A check begun late still answers the start. */
    internal fun inLead(remainingMs: Long): Boolean = remainingMs in 1..LEAD_MS

    /** Whether this tick starts the check for [nextId] after [currentId]: once per pair. */
    @Synchronized
    internal fun due(currentId: String, nextId: String): Boolean {
        val now = currentId to nextId
        if (pair == now) return false
        pair = now
        return true
    }

    internal fun allowed(metered: Boolean, lowPower: Boolean): Boolean =
        BatteryPolicy.cachePrefetchBytesAllowed(metered = metered, lowPower = lowPower)

    /**
     * Whether a verified next row may be rebuilt as its file before it plays.
     * [youTubeBytes]: the file was saved from YouTube itself, so it is on the
     * stream's clock by construction ([koUseVerifiedDownload] reads it the same way).
     */
    internal fun mayMoveNext(
        transitioning: Boolean,
        youTubeBytes: Boolean,
        verified: HqVerificationStore.Verified?,
    ): Boolean = !transitioning && (youTubeBytes || KoLosslessAhead.cueSafe(verified))

    /** Test hook. */
    @Synchronized
    internal fun reset() {
        pair = null
    }
}

/** [KO] dl-check-ahead: one tick of driveDirectWindow, see [KoDownloadCheckAhead]. */
internal fun PlaybackService.koCheckNextDownloadAhead(exoPlayer: ExoPlayer, lastSeekAt: Long) {
    if (!exoPlayer.isPlaying) return
    if (SystemClock.elapsedRealtime() - lastSeekAt < PlaybackService.SEEK_SETTLE_MS) return
    val current = exoPlayer.currentMediaItem ?: return
    val nextIndex = exoPlayer.nextMediaItemIndex
    if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until exoPlayer.mediaItemCount) return
    val next = exoPlayer.getMediaItemAt(nextIndex)
    // Cheapest first: only a row built behind its stream for the check.
    if (next.mediaMetadata.extras?.getBoolean(EXTRA_UNVERIFIED_DOWNLOAD) != true) return
    if (MusicOnlyPlayback.wants(next)) return
    val duration = exoPlayer.duration
    if (duration == C.TIME_UNSET || duration <= 0L) return
    // The window's own arithmetic: the planned blend start, else the end.
    val boundary = crossfade?.plannedTransitionFor(current.mediaId) ?: duration
    val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(PlaybackService.MIN_WINDOW_SPEED)
    val remaining = ((boundary - exoPlayer.currentPosition) / speed).toLong()
    if (!KoDownloadCheckAhead.inLead(remaining)) return
    val id = next.mediaId
    // Once per pair, the move too: a row refused here (a blend holds it, or a
    // file off YouTube's clock) is the start's to move, not every tick's.
    if (!KoDownloadCheckAhead.due(current.mediaId, id)) return
    // Verified already (by an earlier play): no decode, only the move. No
    // network either, so saving power does not stop it.
    if (Downloads.isRecordingVerified(id)) {
        koMoveNextOntoFile(id)
        return
    }
    if (!KoDownloadCheckAhead.allowed(
            metered = AppSettings.meteredConnection.value == true,
            lowPower = AppSettings.lowPower.value,
        )
    ) return
    val song = next.toSong()
    TrackLog.d("BitChord", "download-check ahead: $id ${remaining}ms before the transition", about = id)
    // The row is not playing yet: its decode waits behind the playing track's.
    scope.launch(TrackLog.about(id) + AudioDecoder.aheadOfTime.asContextElement(true)) {
        val outcome = DownloadHealth.onPlayback(this@koCheckNextDownloadAhead, song)
        withContext(Dispatchers.Main) {
            if (koMoveNextOntoFile(id)) return@withContext
            // Started meanwhile: the start's own check found this one in flight.
            koApplyDownloadHealth(song, outcome)
        }
    }
}

/**
 * Rebuilds the next row [id], built behind its stream, as its verified file,
 * the way [koUseVerifiedDownload] rebuilds a playing one. False when it is not
 * the next row any more, is not waiting, is not verified, or may not move yet.
 */
internal fun PlaybackService.koMoveNextOntoFile(id: String): Boolean {
    val live = player ?: return false
    val index = live.nextMediaItemIndex
    if (index == C.INDEX_UNSET || index !in 0 until live.mediaItemCount) return false
    val next = live.getMediaItemAt(index)
    if (next.mediaId != id) return false
    if (next.mediaMetadata.extras?.getBoolean(EXTRA_UNVERIFIED_DOWNLOAD) != true) return false
    if (!Downloads.isRecordingVerified(id)) return false
    val meta = Downloads.savedMetadataFor(id)
    val source = meta?.hqSourceConfigId?.takeIf { it.isNotBlank() }
    val verdict = source?.let { HqVerificationStore.get(this, id, it, meta?.hqTrackId) }
    if (!KoDownloadCheckAhead.mayMoveNext(
            transitioning = crossfade?.isTransitioning() == true,
            youTubeBytes = source == null,
            verified = verdict,
        )
    ) return false
    val file = Downloads.verifiedSavedUri(id) ?: return false
    val rebuilt = next.toSong().copy(localUri = file, localPath = null).toMediaItem()
    if (rebuilt.mediaMetadata.extras?.getBoolean(EXTRA_UNVERIFIED_DOWNLOAD) == true) return false
    if (rebuilt.localConfiguration?.uri == next.localConfiguration?.uri) return false
    val replacement = QueueShuffle.withQueueMetadata(
        rebuilt,
        newEntryId = next.queueEntryId,
        newCanonicalIndex = next.canonicalIndex,
        newTier = next.queueTier,
        newContextCycleId = next.contextCycleId,
    )
    if (runCatching { live.replaceMediaItem(index, replacement) }.isFailure) return false
    // A pin made for the stream (next-prep's lossless copy) must not reopen
    // the row as that copy; the file is what plays.
    StreamChoice.forget(id)
    TrackLog.i("BitChord", "download-check ahead: $id verified; the next track opens its downloaded file", about = id)
    return true
}
