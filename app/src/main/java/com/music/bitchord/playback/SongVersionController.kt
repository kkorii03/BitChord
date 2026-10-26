package com.music.bitchord.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackDiagnostics
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.net.DownloadedMvPolicy
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.MvSoundRedownload
import com.music.bitchord.playback.smart.LyricsTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The song-version half of playback: deciding whether a music video should
 * play as its catalogue release, holding the first sound while that is
 * decided, and measuring lyrics against a late answer.
 *
 * Extracted from [PlaybackService] without changing behaviour, log lines or
 * timing. The service owns the player and the queue; this class owns only the
 * version lookups and the hold state around them, and reaches back through
 * the small callbacks below.
 */
internal class SongVersionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentPlayer: () -> ExoPlayer?,
    private val currentAudibleMediaId: () -> String?,
    private val setSwappingMediaId: (String?) -> Unit,
    private val onVersionInstalled: () -> Unit,
) {

    /** Resolves the song versions of the next few queue rows before they play. */
    private var songVersionJob: Job? = null

    /** The track [songVersionJob]'s look-ahead pass was started for. */
    private var songVersionJobFor: String? = null

    /** The pre-audio lookup for the row that just became current. */
    private var beforeAudioVersionJob: Job? = null

    /** True while a before-audio version lookup is still running. */
    val isResolvingBeforeAudio: Boolean
        get() = beforeAudioVersionJob?.isActive == true

    /** The last id whose mismatched download was announced, so one track logs once. */
    private var streamingSongFileLoggedFor: String? = null

    /** The last id whose length verdict was announced, so one track logs once. */
    private var mvSoundFileLoggedFor: String? = null

    /**
     * Waits for a version lookup that outlived the 1.5 s budget. The queue
     * plays the video meanwhile; when the answer arrives, its lyrics are
     * measured against the catalogue release. The recording itself is never
     * swapped once it is audible.
     */
    private var lateVersionMeasureJob: Job? = null
    private var versionHoldWantsPlay = false
    private var versionHoldHeld = false

    /**
     * The version lookup outlived the first-sound budget. This video is
     * playing and stays playing; if the shared lookup still lands while it is
     * current, its lyrics are measured against that release.
     */
    fun watchForLateVersion(exoPlayer: ExoPlayer, mediaId: String, song: Song) {
        if (!AppSettings.preferMusicOnly.value) return
        if (YtMusicRepository.isSongVersionMissing(song.videoId)) return
        lateVersionMeasureJob = scope.launch {
            val late = try {
                YtMusicRepository.songVersion(song)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                TrackLog.w("BitChord", "late song version failed: ${failure.message}", about = song.videoId)
                null
            } ?: return@launch
            if (late.videoId == song.videoId) return@launch
            if (currentPlayer() !== exoPlayer || exoPlayer.currentMediaItem?.mediaId != mediaId) return@launch
            // Nothing to move if the lyrics never arrived; the normal lyrics
            // path then measures against the answer already in the cache.
            if (TrackDiagnostics.lyricsFor(song.videoId) == null) return@launch
            LyricsTiming.measureLateVersion(
                context,
                song.copy(isVideo = true),
                late,
                song.durationMillis(),
            )
        }
    }

    /**
     * With music-only on, the rows either side of the current one are replaced
     * by their catalogue releases as the queue approaches them: the next three
     * and the previous one, so a skip backwards is as prepared as a skip
     * forwards. The current row was resolved when the queue was built (or held
     * before its first sound); replacing it here would restart a track already
     * playing, so this never touches it.
     */
    fun scheduleUpcomingSongVersions(exoPlayer: ExoPlayer) {
        if (!AppSettings.preferMusicOnly.value) return
        if (ListenTogether.state.value.inParty) return
        // Nothing is looked ahead until this track is audible. Run at the
        // transition this put five version searches beside the current row's
        // own resolve and the first sound went from about 2 s to 5.5 s on a
        // cold queue. [onIsPlayingChanged] schedules it the moment sound
        // starts, and the in-flight guard keeps a resume from starting a
        // second pass over the same rows.
        val currentId = exoPlayer.currentMediaItem?.mediaId ?: return
        if (currentId != currentAudibleMediaId()) return
        // Only a pass for this same track is a duplicate. A pass left over from
        // the previous track is walking the wrong rows and is replaced; the
        // lookups it started live in the repository's own scope, so cancelling
        // the pass does not throw their answers away.
        if (songVersionJob?.isActive == true && songVersionJobFor == currentId) return
        songVersionJob?.cancel()
        songVersionJobFor = currentId
        songVersionJob = scope.launch {
            val current = exoPlayer.currentMediaItemIndex
            if (current !in 0 until exoPlayer.mediaItemCount) return@launch
            val first = (current - 1).coerceAtLeast(0)
            val last = (current + 3).coerceAtMost(exoPlayer.mediaItemCount - 1)
            for (index in first..last) {
                if (!isActive) return@launch
                if (index == current) continue
                if (index >= exoPlayer.mediaItemCount) return@launch
                val item = exoPlayer.getMediaItemAt(index)
                val song = item.toSong()
                if (!SongVersionPolicy.wantsSongVersion(song.isVideo, song.typeUnknown)) continue
                // A downloaded video is still a video and is resolved like any
                // other. Offline, its file is the only thing that can play, so
                // the look-ahead does not spend a request that cannot finish.
                if (song.localUri != null && AudioCache.isOffline()) continue
                val resolved = try {
                    YtMusicRepository.songVersion(song, beforeAudio = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    TrackLog.w("BitChord", "song version failed: ${failure.message}", about = song.videoId)
                    null
                } ?: continue
                if (resolved.videoId == song.videoId) continue
                if (!SongVersionPolicy.mayInstallSongVersion(song)) continue
                if (index == exoPlayer.currentMediaItemIndex) continue
                if (index >= exoPlayer.mediaItemCount) continue
                val now = exoPlayer.getMediaItemAt(index)
                if (now.mediaId != song.videoId) continue
                val replacement = QueueShuffle.withQueueMetadata(
                    resolved.toMediaItem(),
                    newEntryId = now.queueEntryId,
                    newCanonicalIndex = now.canonicalIndex,
                    newTier = now.queueTier,
                    newContextCycleId = now.contextCycleId,
                )
                runCatching { exoPlayer.replaceMediaItem(index, replacement) }
                    .onFailure {
                        TrackLog.w(
                            "BitChord",
                            "song version replace failed at $index: ${it.message}",
                            about = song.videoId,
                        )
                    }
            }
        }
    }

    /**
     * Says that [playId]'s saved file is the video's sound, not the song's.
     *
     * The file stays on the device as the offline fallback; this is only the
     * explanation for why the online playback is a stream.
     */
    private fun logStreamingSongFile(playId: String, about: String) {
        if (streamingSongFileLoggedFor == playId) return
        streamingSongFileLoggedFor = playId
        TrackLog.i(
            "BitChord",
            "song version: $playId file is the video's sound; streaming the song",
            about = about,
        )
    }

    private fun logMvSoundFile(playId: String, deltaMs: Long) {
        if (mvSoundFileLoggedFor == playId) return
        mvSoundFileLoggedFor = playId
        TrackLog.i(
            "BitChord",
            "song version: $playId file longer than the song by ${deltaMs}ms; streaming the song",
            about = playId,
        )
    }

    /**
     * Records that verification found a different recording behind this file
     * and queues the song version for download. Idempotent, so a file already
     * marked by [DownloadedMvPolicy] is not announced or queued twice.
     */
    private fun markVerifiedMvSound(song: Song) {
        val playId = Downloads.songIdForFile(song.videoId)
        if (Downloads.mvSoundSuspectMs(playId) != null) return
        Downloads.markMvSoundSuspect(playId, 0L)
        MvSoundRedownload.scheduleIfEnabled(context)
        TrackLog.i(
            "BitChord",
            "song version: $playId verified as the video's sound; scheduling the song download",
            about = song.videoId,
        )
    }

    /**
     * Fills in a pre-field download's upload type after the catalogue agreed it
     * is the song, so the same file is trusted without another lookup.
     */
    private fun backfillVerifiedSong(song: Song) {
        Downloads.backfillUploadType(song.videoId, isVideo = false, typeUnknown = false)
    }

    /**
     * Treats a downloaded song whose file is a music video's sound as one.
     *
     * The verdict comes from [DownloadedMvPolicy], so a legacy download with no
     * recorded upload type is still caught by its title or by its length beside
     * the catalogue release. The verdict is remembered on the device the first
     * time it is reached, so the same file is not re-measured and its song
     * version is not re-requested on every play. Online the song is streamed
     * through the same path as a recorded provenance mismatch; offline the file
     * stays, because it is the only sound there is.
     *
     * @return true when the row was handled and the caller must not fall
     *   through to the ordinary song-version lookup. False after marking a file
     *   whose catalogue release is not known yet: the ordinary verification
     *   lookup resolves it, with the repair already scheduled.
     */
    fun streamLongDownloadedSong(
        exoPlayer: ExoPlayer,
        mediaId: String,
        song: Song,
        offline: Boolean,
        fileDurationMs: Long?,
    ): Boolean {
        if (song.isVideo || song.typeUnknown || song.localUri == null) return false
        if (!Downloads.isDownloaded(song.videoId)) return false
        // A downloaded video and its song share one file; the shorter id is
        // the recording the app calls the song.
        val playId = Downloads.songIdForFile(song.videoId)
        val remembered = Downloads.mvSoundSuspectMs(playId)
        val cached = YtMusicRepository.cachedSongVersionFor(song)
        val catalogDuration = listOfNotNull(
            Downloads.catalogDurationMs(song.videoId, song.durationMillis()),
            cached?.durationMillis()?.takeIf { it > 0L },
        ).minOrNull()
        val file = fileDurationMs?.takeIf { it > 0L }
        val soundSource = Downloads.savedSoundSourceFor(song.videoId)
        val catalogId = cached?.videoId?.takeIf { it != song.videoId }
            ?: playId.takeIf { it != song.videoId }
        val mvSound = DownloadedMvPolicy.isMvSound(
            rowVideoId = song.videoId,
            title = song.title,
            fileDurationMs = file,
            storedIsVideo = Downloads.savedIsVideoFor(song.videoId),
            storedTypeUnknown = Downloads.savedTypeUnknownFor(song.videoId),
            soundSourceVideoId = soundSource,
            catalogVideoId = catalogId,
            catalogDurationMs = catalogDuration,
        )
        // The older two-and-a-half-second rule still guards a file with no
        // recorded source; it is the rule the downloads scan uses and must not
        // be weakened here.
        val lengthDelta = SongVersionPolicy.mvSoundDeltaMs(
            fileDurationMs = file,
            catalogDurationMs = catalogDuration,
            isVideo = false,
            localFile = true,
            soundSourceId = soundSource,
        )
        if (remembered == null && !mvSound && lengthDelta == null) return false
        val delta = remembered ?: lengthDelta ?: run {
            val catalog = catalogDuration
            if (file != null && catalog != null) (file - catalog).coerceAtLeast(0L) else 0L
        }
        if (remembered == null) {
            Downloads.markMvSoundSuspect(playId, delta)
            MvSoundRedownload.scheduleIfEnabled(context)
        }
        if (offline) return true
        if (delta > 0L) logMvSoundFile(playId, delta) else logStreamingSongFile(playId, song.videoId)
        val streamId = cached?.videoId?.takeIf { it != song.videoId }
            ?: playId.takeIf { it != song.videoId }
        if (streamId == null) {
            // The catalogue release is not known yet; the ordinary verification
            // lookup resolves it, with the file already marked for repair.
            return false
        }
        installStreamedRecording(exoPlayer, mediaId, song, streamId)
        return true
    }

    /**
     * Decides the song version of the row that just became current, before it
     * has made a sound.
     *
     * Queue builds resolve the current row up front (see
     * [YtMusicRepository.resolveQueueAhead]); this is the safety net for every
     * way a row can become current without that — a skip, a previous, a
     * restored queue. The first sound is held for at most
     * [SongVersionPolicy.BEFORE_AUDIO_TIMEOUT_MS]; if no version is found in
     * that window the video plays as it is and is never swapped mid-song. A
     * cached answer returns without a wait.
     *
     * A row playing from a downloaded file is no exception when it is a video:
     * the file is the video, so the catalogue release is still what should
     * play. [SongVersionPolicy.keepsLocalFile] is what leaves a downloaded
     * song, and the listener's own music, alone. Offline the downloaded video
     * file stays instead of being replaced by a stream that cannot open.
     */
    fun resolveSongVersionBeforeAudio(
        exoPlayer: ExoPlayer,
        mediaItem: MediaItem?,
        alreadyAudible: Boolean,
    ) {
        lateVersionMeasureJob?.cancel()
        val item = mediaItem
        val song = item?.toSong()
        val localFile = song?.localUri != null
        val offline = AudioCache.isOffline()
        // A file saved before provenance or upload type was recorded cannot say
        // which stream produced it. Online it is verified as a possible video
        // copy: the catalogue release is streamed and the file stays as the
        // offline fallback. Offline the file is the only sound, so it is kept.
        val soundSourceId = song?.let { Downloads.savedSoundSourceFor(it.videoId) }
        val storedIsVideo = song?.let { Downloads.savedIsVideoFor(it.videoId) }
        val storedTypeUnknown = song?.let { Downloads.savedTypeUnknownFor(it.videoId) } == true
        val effectiveIsVideo: Boolean? = song?.let {
            when {
                it.isVideo || storedIsVideo == true -> true
                storedIsVideo == null -> null
                else -> false
            }
        }
        val verifyUnknownDownload = song != null &&
            SongVersionPolicy.verifiesUnknownDownload(
                localFile = localFile,
                downloaded = Downloads.isDownloaded(song.videoId),
                provenanceKnown = soundSourceId != null,
                provenanceIsOwnId = soundSourceId == song.videoId,
                isVideo = effectiveIsVideo,
                typeUnknown = song.typeUnknown || storedTypeUnknown,
                offline = offline,
            )
        val versionSong = if (verifyUnknownDownload) {
            song?.copy(typeUnknown = true, localUri = null, localPath = null)
        } else {
            // What the download recorded about the upload counts even when the
            // queue row lost it, or a saved video/unknown row skips the lookup.
            song?.copy(
                isVideo = song.isVideo || storedIsVideo == true,
                typeUnknown = song.typeUnknown || storedTypeUnknown,
            )
        }
        // A catalogue row whose file was recorded from another upload's stream
        // must not play that sound. Online, the row's own recording is
        // streamed and the file stays only as the offline fallback. A file with
        // no provenance or no upload type is judged by [DownloadedMvPolicy]
        // here instead of being trusted blindly; when its catalogue release is
        // not known yet, the verification lookup below resolves it after the
        // file has already been marked for repair.
        if (!alreadyAudible && item != null && song != null && localFile &&
            !SongVersionPolicy.hasMadeFirstSound(item.mediaId, currentAudibleMediaId()) &&
            !SongVersionPolicy.wantsSongVersion(song.isVideo, song.typeUnknown)
        ) {
            // The player's own duration is preferred; the recorded durations
            // are what the Downloads page compares without opening anything.
            val playerDuration = exoPlayer.duration
                .takeIf { exoPlayer.playbackState == Player.STATE_READY && it > 0L }
            if (streamLongDownloadedSong(exoPlayer, item.mediaId, song, offline, playerDuration)) {
                return
            }
            if (!verifyUnknownDownload &&
                SongVersionPolicy.downloadedFileUse(
                    song.videoId,
                    soundSourceId,
                    online = !offline,
                ) == SongVersionPolicy.DownloadedFileUse.STREAM_SONG
            ) {
                logStreamingSongFile(song.videoId, about = song.videoId)
                installStreamedRecording(exoPlayer, item.mediaId, song)
                return
            }
        }
        if (item == null || song == null) {
            releaseVersionHold(exoPlayer)
            return
        }
        val lookupSong = versionSong ?: song
        val skipReason = when {
            alreadyAudible -> "already audible"
            !AppSettings.preferMusicOnly.value -> "music only off"
            ListenTogether.state.value.inParty -> "listen together"
            !SongVersionPolicy.wantsSongVersion(
                lookupSong.isVideo,
                lookupSong.typeUnknown,
            ) -> "not a video"
            SongVersionPolicy.keepsLocalFile(
                lookupSong.localUri != null,
                lookupSong.isVideo,
                lookupSong.typeUnknown,
            ) -> "local file"
            SongVersionPolicy.hasMadeFirstSound(item.mediaId, currentAudibleMediaId()) -> "first sound"
            else -> null
        }
        if (skipReason != null) {
            val id = song.videoId
            if (SongVersionPolicy.wantsSongVersion(song.isVideo, song.typeUnknown)) {
                TrackLog.i(
                    "BitChord",
                    "song version: $id hold skipped ($skipReason)",
                    about = id,
                )
            }
            releaseVersionHold(exoPlayer)
            return
        }
        val mediaId = item.mediaId
        // A definitive answer from the queue build needs no network wait, but
        // the first sound is still held for the single dispatch that installs
        // it, so the replacement cannot land after audio. Deferring it also
        // keeps the timeline change out of the transition callback.
        val cached = YtMusicRepository.cachedSongVersionFor(lookupSong)
        val cachedUse = cached?.let {
            SongVersionPolicy.downloadedFileUse(
                it.videoId,
                Downloads.savedSoundSourceFor(it.videoId),
                online = !offline,
            )
        }
        val cachedFile = if (cached != null &&
            cachedUse != SongVersionPolicy.DownloadedFileUse.STREAM_SONG
        ) {
            Downloads.verifiedSavedUri(cached.videoId)
        } else {
            null
        }
        if (cached != null && cachedFile != null &&
            SongVersionPolicy.mayInstallSongVersion(lookupSong)
        ) {
            // A cached answer whose recording is already on the device plays
            // from that file, with no network, even offline.
            installPreResolvedVersion(
                exoPlayer,
                mediaId,
                song,
                cached.copy(localUri = cachedFile),
                viaDownload = true,
            )
            return
        }
        if (localFile) {
            // The download record can name this file's song version even when
            // this run has not looked it up ? see Downloads.remember. Playing
            // that id needs no network at all.
            downloadedSongVersionFor(lookupSong, offline)
                ?.takeIf { SongVersionPolicy.mayInstallSongVersion(lookupSong) }
                ?.let { downloaded ->
                    val streamOnly = downloaded.localUri == null
                    installPreResolvedVersion(
                        exoPlayer,
                        mediaId,
                        song,
                        downloaded,
                        viaDownload = !streamOnly,
                        forceStream = streamOnly,
                    )
                    return
                }
        }
        if (cached != null && !offline &&
            SongVersionPolicy.mayInstallSongVersion(lookupSong)
        ) {
            // Offline, a cached answer is only usable when its recording is
            // already on the device. Otherwise the downloaded video file keeps
            // playing rather than being replaced by a stream that cannot open.
            val streamOnly = cachedUse == SongVersionPolicy.DownloadedFileUse.STREAM_SONG
            if (streamOnly && !localFile) logStreamingSongFile(cached.videoId, song.videoId)
            installPreResolvedVersion(
                exoPlayer,
                mediaId,
                song,
                cached,
                viaDownload = false,
                forceStream = streamOnly,
            )
            return
        }
        if (localFile && offline) {
            TrackLog.i(
                "BitChord",
                "song version: ${song.videoId} kept (local file, offline)",
                about = song.videoId,
            )
            releaseVersionHold(exoPlayer)
            return
        }
        if (YtMusicRepository.isSongVersionMissing(lookupSong.videoId)) {
            // The queue build already established this row has no song version.
            TrackLog.i(
                "BitChord",
                if (localFile) {
                    "song version: ${song.videoId} kept (local file, none)"
                } else {
                    "song version: ${song.videoId} kept (none)"
                },
                about = song.videoId,
            )
            releaseVersionHold(exoPlayer)
            return
        }
        // A hold a transition interrupted keeps the listener's play intent, so
        // a skip made during the wait does not leave the player paused.
        beforeAudioVersionJob?.cancel()
        beginVersionHold(exoPlayer)
        beforeAudioVersionJob = scope.launch {
            var lookupFailed = false
            val resolved = try {
                withTimeout(SongVersionPolicy.BEFORE_AUDIO_TIMEOUT_MS) {
                    YtMusicRepository.songVersion(lookupSong, beforeAudio = true)
                }
            } catch (timeout: TimeoutCancellationException) {
                lookupFailed = true
                TrackLog.i(
                    "BitChord",
                    if (localFile) {
                        "song version: ${song.videoId} kept (local file, timeout)"
                    } else {
                        "song version: ${song.videoId} kept (timeout)"
                    },
                    about = song.videoId,
                )
                // The lookup itself keeps running in the repository's shared
                // scope. If it lands while this video is still playing, use it
                // as the lyrics reference (never as a mid-song swap).
                watchForLateVersion(exoPlayer, mediaId, lookupSong)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lookupFailed = true
                TrackLog.w(
                    "BitChord",
                    "song version failed before audio: ${failure.message}",
                    about = song.videoId,
                )
                if (localFile) {
                    TrackLog.i(
                        "BitChord",
                        "song version: ${song.videoId} kept (local file, failed)",
                        about = song.videoId,
                    )
                }
                null
            }
            try {
                if (resolved != null &&
                    SongVersionPolicy.mayInstallSongVersion(lookupSong)
                ) {
                    // Verification of an old download resolved a different
                    // recording: the file is the video's sound even when its
                    // title and length did not prove it up front. Mark it and
                    // let the repair queue fetch the catalogue release.
                    if (verifyUnknownDownload && resolved.videoId != song.videoId) {
                        markVerifiedMvSound(song)
                    } else if (verifyUnknownDownload) {
                        backfillVerifiedSong(song)
                    }
                    val resolvedUse = SongVersionPolicy.downloadedFileUse(
                        resolved.videoId,
                        Downloads.savedSoundSourceFor(resolved.videoId),
                        online = !offline,
                    )
                    val streamOnly =
                        resolvedUse == SongVersionPolicy.DownloadedFileUse.STREAM_SONG
                    if (streamOnly) logStreamingSongFile(resolved.videoId, song.videoId)
                    val resolvedFile = if (streamOnly) {
                        null
                    } else {
                        Downloads.verifiedSavedUri(resolved.videoId)
                    }
                    val version = if (resolvedFile != null) {
                        resolved.copy(localUri = resolvedFile)
                    } else {
                        resolved
                    }
                    installSongVersionBeforeAudio(
                        exoPlayer,
                        mediaId,
                        song,
                        version,
                        forceStream = streamOnly,
                    )
                } else if (resolved == null && localFile && !lookupFailed) {
                    // The lookup answered "there is no version" rather than
                    // failing; keep the downloaded video with a reason that
                    // says why it stayed. Nothing is recorded about the file
                    // here: [YtMusicRepository.songVersion] also answers null
                    // when its search failed, and a legacy MV must not be filed
                    // as a verified song on a network error. Only a lookup that
                    // positively named this very id (above) records that.
                    TrackLog.i(
                        "BitChord",
                        "song version: ${song.videoId} kept (local file, none)",
                        about = song.videoId,
                    )
                }
            } finally {
                // The replacement itself changes the media id, so the hold is
                // still ours when the current item is either the original
                // video or the song version just installed.
                val currentId = exoPlayer.currentMediaItem?.mediaId
                if (currentPlayer() === exoPlayer &&
                    (currentId == mediaId || (resolved != null && currentId == resolved.videoId))
                ) {
                    resumeVersionHold(exoPlayer)
                }
            }
        }
    }

    /**
     * Installs a song version that was already in hand — a cached answer, or a
     * downloaded release found in the download record — holding the first
     * sound only for the dispatch that performs the replacement.
     */
    private fun installPreResolvedVersion(
        exoPlayer: ExoPlayer,
        mediaId: String,
        song: Song,
        version: Song,
        viaDownload: Boolean,
        forceStream: Boolean = false,
    ) {
        beforeAudioVersionJob?.cancel()
        beginVersionHold(exoPlayer)
        beforeAudioVersionJob = scope.launch {
            val via = when {
                forceStream -> "stream"
                viaDownload -> "download"
                else -> "cache"
            }
            TrackLog.i(
                "BitChord",
                "song version: ${song.videoId} -> ${version.videoId} via $via before-audio",
                about = song.videoId,
            )
            installSongVersionBeforeAudio(exoPlayer, mediaId, song, version, forceStream)
            if (currentPlayer() === exoPlayer) resumeVersionHold(exoPlayer)
        }
    }

    /**
     * Replaces a row whose saved file is another upload's sound with the same
     * recording streamed from the network. The file is kept as the offline
     * fallback; only this play avoids it.
     */
    private fun installStreamedRecording(
        exoPlayer: ExoPlayer,
        mediaId: String,
        song: Song,
        streamId: String? = null,
    ) {
        val id = streamId ?: Downloads.songIdForFile(song.videoId)
        installPreResolvedVersion(
            exoPlayer,
            mediaId,
            song,
            song.copy(videoId = id, localUri = null, localPath = null),
            viaDownload = false,
            forceStream = true,
        )
    }

    /**
     * A downloaded catalogue release of [song], when the download record kept
     * the video and the release it resolved to against one file — see
     * [Downloads.remember]. Null for a file saved on its own, which is every
     * ordinary download; the caller then falls back to the network lookup.
     */
    private fun downloadedSongVersionFor(song: Song, offline: Boolean): Song? {
        val alias = Downloads.savedAliasFor(song.videoId) ?: return null
        val uri = Downloads.verifiedSavedUri(alias) ?: return null
        val meta = Downloads.savedMetadataFor(alias)
        val streamOnly = SongVersionPolicy.downloadedFileUse(
            alias,
            Downloads.savedSoundSourceFor(alias),
            online = !offline,
        ) == SongVersionPolicy.DownloadedFileUse.STREAM_SONG
        return Song(
            videoId = alias,
            title = meta?.title?.takeIf { it.isNotBlank() } ?: song.title,
            artist = meta?.artist?.takeIf { it.isNotBlank() } ?: song.artist,
            thumbnailUrl = meta?.thumbnailUrl ?: song.thumbnailUrl,
            durationText = meta?.durationText ?: song.durationText,
            artistId = song.artistId,
            artists = song.artists,
            albumId = song.albumId,
            albumName = meta?.albumName ?: song.albumName,
            setVideoId = song.setVideoId,
            queueTier = song.queueTier,
            queueEntryId = song.queueEntryId,
            radioName = song.radioName,
            playbackSource = song.playbackSource,
            playbackSourceType = song.playbackSourceType,
            playbackSourceId = song.playbackSourceId,
            localUri = if (streamOnly) null else uri,
            isVideoOrigin = true,
        )
    }

    /**
     * Installs [resolved] in place of the current video row without seeking or
     * touching the playhead. The caller has established that the track has not
     * made a sound and is still the current item.
     */
    private fun installSongVersionBeforeAudio(
        exoPlayer: ExoPlayer,
        mediaId: String,
        song: Song,
        resolved: Song,
        forceStream: Boolean = false,
    ) {
        if (currentPlayer() !== exoPlayer ||
            exoPlayer.currentMediaItem?.mediaId != mediaId ||
            SongVersionPolicy.hasMadeFirstSound(mediaId, currentAudibleMediaId()) ||
            (resolved.videoId == song.videoId && !forceStream) ||
            resolved.isVideo
        ) return
        val index = exoPlayer.currentMediaItemIndex
        if (index !in 0 until exoPlayer.mediaItemCount) return
        val now = exoPlayer.getMediaItemAt(index)
        val replacement = QueueShuffle.withQueueMetadata(
            resolved.toMediaItem(useDownloadedFile = !forceStream),
            newEntryId = now.queueEntryId,
            newCanonicalIndex = now.canonicalIndex,
            newTier = now.queueTier,
            newContextCycleId = now.contextCycleId,
        )
        // The same recording is already current; replacing it is a version
        // swap, not a new track. Marking the replacement keeps the transition
        // from booking a second history entry or scrobble for it.
        setSwappingMediaId(resolved.videoId)
        runCatching { exoPlayer.replaceMediaItem(index, replacement) }
            .onSuccess {
                // The transition callback returns early for a version swap, so
                // the per-id work it would have done is done here instead.
                onVersionInstalled()
            }
            .onFailure {
                setSwappingMediaId(null)
                TrackLog.w(
                    "BitChord",
                    "song version replace failed before audio: ${it.message}",
                    about = song.videoId,
                )
            }
    }

    /** Gives back a hold without touching the track it was taken for. */
    private fun releaseVersionHold(exoPlayer: ExoPlayer) {
        beforeAudioVersionJob?.cancel()
        beforeAudioVersionJob = null
        resumeVersionHold(exoPlayer)
    }

    /**
     * Holds the first sound while the song version is decided. A hold a
     * transition interrupted keeps the listener's play intent, so a skip made
     * during the wait does not leave the player paused.
     */
    private fun beginVersionHold(exoPlayer: ExoPlayer) {
        if (!versionHoldHeld) versionHoldWantsPlay = exoPlayer.playWhenReady
        if (versionHoldWantsPlay) {
            exoPlayer.pause()
            versionHoldHeld = true
        }
    }

    /** Resumes a hold's pause without cancelling the job that owns it. */
    private fun resumeVersionHold(exoPlayer: ExoPlayer) {
        if (versionHoldWantsPlay && !exoPlayer.playWhenReady) exoPlayer.play()
        versionHoldWantsPlay = false
        versionHoldHeld = false
    }
}
