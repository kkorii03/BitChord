package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.NextPrepPolicy.Step
import com.music.bitchord.playback.smart.HqVerificationStore
import com.music.bitchord.playback.smart.LyricsTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Prepares exactly one queue row — the next — while the current one plays.
 *
 * The steps are ordered because each needs the answer before it: the song
 * version decides which recording will play, the lyrics are looked up for that
 * recording, the high-quality candidate is compared against it, the song sync
 * check listens to the audio that will actually play, and finally the mix
 * analysis is requested for the transition into it.
 *
 * Nothing here polls or schedules itself. It is started by the first-sound
 * event and by a queue change, runs once per next row, and is cancelled the
 * moment that row is replaced. Results go to the stores that already own them
 * (the lyrics memo/disk cache, [LyricsTiming]'s store, the analysis store and
 * [StreamChoice]/[HqVerificationStore]), so a replay skips every step that is
 * already answered and makes no request at all.
 */
internal class NextTrackPreparation(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentPlayer: () -> ExoPlayer?,
    private val prepareMix: (ExoPlayer) -> Unit,
    private val mixReady: (String) -> Boolean,
    private val awaitMix: suspend (String) -> Boolean,
) {

    private var job: Job? = null

    /** The queue row this bundle belongs to and the pair it was started for. */
    private var rowId: String? = null
    private var queueKey: String? = null

    /** The next row's video whose lossless hq-prefetch this bundle started. */
    private var hqPrefetchVideoId: String? = null
    private var completedRowId: String? = null
    private var completedQueueKey: String? = null

    /**
     * Steps already tried for a row in this session, keyed by the row's sound
     * (see [Snapshot.queueKey]). A failed network step is not retried for the
     * same sound: the ordinary post-start path takes over, which is where the
     * user's battery would otherwise be spent twice. A download finishing or
     * a file/stream swap is a different sound and starts a fresh set.
     */
    private val attempted = object : LinkedHashMap<String, MutableSet<Step>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<Step>>) =
            size > MAX_ROWS_REMEMBERED
    }

    /** Lyrics already fetched for a row's sound, so a resumed bundle keeps its answer. */
    private val lyricsByRow = object : LinkedHashMap<String, TranslationAhead.AheadLyrics>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TranslationAhead.AheadLyrics>,
        ) = size > MAX_ROWS_REMEMBERED
    }

    private data class Snapshot(
        val rowId: String,
        val index: Int,
        val song: Song,
        val queueKey: String,
        /**
         * The lyrics settings sampled when the bundle was scheduled. Used for
         * the whole run so a change mid-run cannot label lines chosen under one
         * settings set with another, and so the next schedule sees a new sound.
         */
        val lyricsSettings: String,
    )

    /**
     * Called after the current track's first sound and on every queue change.
     * A different next row cancels the running bundle; the same row already
     * prepared is left alone.
     */
    fun schedule(player: ExoPlayer) {
        val snapshot = snapshot(player)
        if (snapshot == null) {
            cancel()
            return
        }
        if (NextPrepPolicy.alreadyHandled(
                startedRowId = rowId,
                startedQueueKey = queueKey,
                rowId = snapshot.rowId,
                queueKey = snapshot.queueKey,
                running = job?.isActive == true,
                completedQueueKey = completedQueueKey,
            )
        ) return
        if (NextPrepPolicy.shouldCancel(rowId, queueKey, snapshot.rowId, snapshot.queueKey)) {
            job?.cancel()
            cancelPreparedDownloads()
            NextPrepCache.clear()
        }
        rowId = snapshot.rowId
        queueKey = snapshot.queueKey
        completedRowId = null
        completedQueueKey = null
        job?.cancel()
        job = scope.launch { run(snapshot) }
    }

    /** Stops the bundle and forgets what was prepared for the old next row. */
    fun cancel() {
        job?.cancel()
        job = null
        cancelPreparedDownloads()
        rowId = null
        queueKey = null
        completedRowId = null
        completedQueueKey = null
        NextPrepCache.clear()
    }

    /** Stops the lossless fetch this bundle started for a row that is no longer next. */
    private fun cancelPreparedDownloads() {
        AudioCache.cancelLosslessPrefetchFor(hqPrefetchVideoId)
        hqPrefetchVideoId = null
    }

    private fun snapshot(player: ExoPlayer): Snapshot? {
        val index = player.nextMediaItemIndex
        if (index == C.INDEX_UNSET || index !in 0 until player.mediaItemCount) return null
        val current = player.currentMediaItemIndex.takeIf { it in 0 until player.mediaItemCount }
        if (!NextPrepPolicy.hasDistinctNextRow(index, current)) return null
        val item = player.getMediaItemAt(index)
        val song = runCatching { item.toSong() }.getOrNull() ?: return null
        val rowId = item.queueEntryId ?: item.mediaId
        if (rowId.isBlank()) return null
        // The row's sound is part of its identity: a download finishing (or a
        // file being replaced by its stream) must cancel a bundle prepared for
        // the other source, or its first-frame cache is for the wrong audio.
        // The lyrics settings are part of it too: lines chosen under an old
        // source set/order are not the answer for the new one, and a completed
        // bundle must not stop the row being prepared again after a change.
        val lyricsSettings = NextPrepCache.settingsKey(
            sources = AppSettings.lyricsSources.value,
            order = AppSettings.lyricsSourceOrder.value,
            prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
            syncedLyrics = AppSettings.syncedLyrics.value,
            nextPrepEnabled = AppSettings.syncSafetyNextPrep.value,
        )
        return Snapshot(
            rowId,
            index,
            song,
            NextPrepPolicy.soundKey(rowId, song.videoId, song.localUri != null, lyricsSettings),
            lyricsSettings,
        )
    }

    private fun attemptsFor(stateKey: String): MutableSet<Step> = synchronized(attempted) {
        attempted.getOrPut(stateKey) { mutableSetOf() }
    }

    private suspend fun run(snapshot: Snapshot) {
        val startedAt = SystemClock.elapsedRealtime()
        val switches = AppSettings.syncSafetySwitches
        var song = snapshot.song
        var expectedQueueKey = snapshot.queueKey
        // Attempts and lyrics belong to the sound that will play — the queue row
        // with its source and lyrics settings — and to the recording itself: a
        // video resolved to its song version must not inherit the video's
        // failed steps or its lyrics.
        var stateKey = NextPrepPolicy.stateKey(snapshot.queueKey, song.videoId)
        var steps = attemptsFor(stateKey)
        var syncTiming: LyricsTiming.Timing? = null
        var deadlineHit = false
        // Steps this particular run actually reached, for the log: [steps] is
        // the session-wide set, so a step disabled now could still appear there
        // from an earlier sound and be reported as a miss instead of "off".
        val triedThisRun = mutableSetOf<Step>()

        fun stillCurrent(): Boolean {
            val player = currentPlayer() ?: return false
            val index = player.nextMediaItemIndex
            if (index == C.INDEX_UNSET || index !in 0 until player.mediaItemCount) return false
            val item = player.getMediaItemAt(index)
            val liveRow = item.queueEntryId ?: item.mediaId
            return liveRow == snapshot.rowId && item.mediaId == song.videoId
        }

        // (a) Which recording will play.
        if (versionWanted(song) && Step.VERSION !in steps && !versionAnswered(song)) {
            steps += Step.VERSION
            val resolved = resolveVersion(song, snapshot.rowId) { expected ->
                expectedQueueKey = NextPrepPolicy.soundKey(
                    snapshot.rowId,
                    expected.videoId,
                    expected.localUri != null,
                    snapshot.lyricsSettings,
                )
                queueKey = expectedQueueKey
            }
            if (resolved != null) {
                song = resolved
                val resolvedKey = NextPrepPolicy.stateKey(expectedQueueKey, resolved.videoId)
                if (resolvedKey != stateKey) {
                    stateKey = resolvedKey
                    steps = attemptsFor(stateKey)
                    NextPrepCache.clear()
                }
            }
        }
        if (!stillCurrent()) return

        // (b) The lyrics for that recording, then (c) its high-quality copy.
        // The lyrics switch is off by default: the cache may hold lines from
        // an earlier run, so it is not even read while the switch is off.
        var lyrics: TranslationAhead.AheadLyrics? = if (switches.nextPrepLyrics) {
            synchronized(lyricsByRow) { lyricsByRow[stateKey] }
        } else {
            null
        }
        var hq = existingHq(song)
        var mix = mixReady(song.videoId)

        val wanted = SyncSafetyPolicy.nextPrepSteps(
            buildSet {
                if (AppSettings.syncedLyrics.value) {
                    add(Step.LYRICS)
                    if (syncWanted(song)) add(Step.SYNC)
                }
                if (hqWanted(song)) add(Step.HQ)
                if (mixWanted(song)) add(Step.MIX)
            },
            switches,
        )
        val prepared = preparedSteps(song, lyrics = lyrics)
        val plan = NextPrepPolicy.plan(
            NextPrepPolicy.Request(
                rowId = snapshot.rowId,
                queueKey = expectedQueueKey,
                wanted = wanted,
                prepared = prepared,
                attempted = steps,
            ),
        )

        for (step in plan.steps) {
            if (step == Step.MIX) continue
            if (!stillCurrent()) return
            val player = currentPlayer() ?: return
            val left = remainingMs(player)
            if (!NextPrepPolicy.canStartStep(left)) {
                // The current track is inside its last twenty seconds. Every
                // later step would compete with the handoff, so keep only what
                // has already finished and say why in the one log line below.
                deadlineHit = true
                break
            }
            steps += step
            triedThisRun += step
            // The deadline is not just a gate before the step: a measurement,
            // an AI wait or a search can outlast the time it was given. The
            // step runs under the same remaining allowance, so the handoff is
            // never entered with the bundle still working, and a timeout is
            // reported as `(deadline)` rather than as a failed step.
            val completed: Boolean? = try {
                withRemaining(left) {
                    when (step) {
                        Step.VERSION -> Unit
                        Step.LYRICS -> {
                            lyrics = TranslationAhead.lyricsForPrep(
                                context,
                                song,
                                allowHeavy = AppSettings.heavyWorkAllowed,
                            )
                            synchronized(lyricsByRow) {
                                lyrics?.let { lyricsByRow[stateKey] = it }
                            }
                        }
                        Step.HQ -> hq = prepareHq(song) ?: hq
                        Step.SYNC -> {
                            val found = lyrics
                            val lines = found?.lines?.takeIf { list ->
                                list.any { it.text.isNotBlank() }
                            }
                            // A substitute stream is a different recording under
                            // the same id; its clock is not the upload's, so no
                            // sync is measured or recorded for it.
                            val query = CoverTitles.lyricsQuery(song)
                            val substituted = StreamChoice.isSubstitute(song.videoId)
                            if (lines != null && !substituted) {
                                // Free measurement and timing first: they need
                                // no upload and are what times most covers.
                                val prepared = LyricsTiming.prepareNextFree(
                                    context = context,
                                    song = song,
                                    lines = lines,
                                    fromFile = found.fromFile,
                                    source = found.source,
                                    searchedAs = found.searchedAs,
                                )
                                // Keep the free answer even if the paid gate or
                                // the step's deadline cancels the rest below.
                                syncTiming = prepared
                                // Only the paid engines wait for the current
                                // song to pass the early-skip window. The free
                                // half has already run, so a short track still
                                // keeps its measurement and does not push MIX
                                // behind the 30 s gate.
                                if (AppSettings.aiTimingAhead.value && Deepgram.usable) {
                                    val leftNow = remainingMs(player)
                                    val gateOpen = NextPrepPolicy.aiGateOpen(player.currentPosition) ||
                                        (NextPrepPolicy.canStartStep(leftNow) &&
                                            awaitAiGate(player, leftNow))
                                    if (gateOpen) {
                                        // The paid answer replaces the free one:
                                        // it is the correction the next row must
                                        // open with, and what the log reports.
                                        syncTiming = LyricsTiming.prepareNextPaid(
                                            context = context,
                                            song = song,
                                            lines = lines,
                                            fromFile = found.fromFile,
                                            source = found.source,
                                            timing = prepared,
                                        )
                                    }
                                }
                            }
                        }
                        Step.MIX -> Unit
                    }
                    true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // A failed step is skipped, never fatal: the post-start path
                // still does the work for this track if it is still wanted.
                TrackLog.d(
                    "BitChord",
                    "next-prep ${step.name.lowercase(Locale.ROOT)} failed: ${failure.message}",
                    about = song.videoId,
                )
                false
            }
            if (completed == null) {
                deadlineHit = true
                break
            }
        }

        if (!stillCurrent()) return

        val found = lyrics
        val lines = found?.lines?.takeIf { line -> line.any { it.text.isNotBlank() } }
        val timing = syncTiming ?: try {
            lines?.let { timed(it, found, song) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            TrackLog.d(
                "BitChord",
                "next-prep timing failed: ${failure.message}",
                about = song.videoId,
            )
            null
        }
        val shown = if (timing != null && lines != null) {
            LyricsTiming.apply(lines, timing)
        } else {
            lines
        }
        if (lines != null && shown != null && SyncSafetyPolicy.firstFrameCacheAllowed(switches)) {
            val query = CoverTitles.lyricsQuery(song)
            NextPrepCache.put(
                NextPrepCache.Ready(
                    videoId = song.videoId,
                    raw = lines,
                    shown = shown,
                    source = found.source,
                    searchedAs = found.searchedAs,
                    isVideo = query.isVideo,
                    fromFile = found.fromFile,
                    localFile = song.localUri != null,
                    lyricsSettings = snapshot.lyricsSettings,
                    audioSource = if (query.isVideo) {
                        null
                    } else {
                        LyricsTiming.audioSourceKey(song.videoId, song.localUri != null)
                    },
                    substituted = !query.isVideo && StreamChoice.isSubstitute(song.videoId),
                ),
            )
        }

        // The sync answer is stored above, so the lossless download can now
        // use whatever is left without delaying the lyrics. It is still
        // bounded by the same end-of-track deadline as every other step.
        if (hq != null && !deadlineHit) {
            if (!stillCurrent()) return
            val beforeHq = currentPlayer()?.let { remainingMs(it) } ?: Long.MAX_VALUE
            if (NextPrepPolicy.canStartStep(beforeHq)) {
                try {
                    if (awaitLosslessDownload(song, beforeHq)) deadlineHit = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    TrackLog.d(
                        "BitChord",
                        "next-prep hq download failed: ${failure.message}",
                        about = song.videoId,
                    )
                }
            }
        }

        // The mix analysis is last because it can take a while; the lyrics and
        // their correction are already in the cache above, so a track that
        // starts early still gets them on its first frame. Re-check the
        // deadline here too: an earlier step (the sync measurement or the
        // lossless download) can consume the rest of the track's allowance.
        if (Step.MIX in plan.steps) {
            if (!stillCurrent()) return
            val beforeMix = currentPlayer()?.let { remainingMs(it) } ?: Long.MAX_VALUE
            if (deadlineHit || !NextPrepPolicy.canStartStep(beforeMix)) {
                deadlineHit = true
            } else {
                steps += Step.MIX
                triedThisRun += Step.MIX
                val completed: Boolean? = try {
                    withRemaining(beforeMix) {
                        val player = currentPlayer()
                        if (player != null) {
                            prepareMix(player)
                            mix = awaitMix(song.videoId)
                        }
                        true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    TrackLog.d(
                        "BitChord",
                        "next-prep mix failed: ${failure.message}",
                        about = song.videoId,
                    )
                    false
                }
                if (completed == null) deadlineHit = true
            }
        }

        completedRowId = snapshot.rowId
        completedQueueKey = expectedQueueKey
        val playerNow = currentPlayer()
        val playedMs = playerNow?.currentPosition ?: 0L
        val leftNow = playerNow?.let { remainingMs(it) } ?: Long.MAX_VALUE
        val query = CoverTitles.lyricsQuery(song)
        val fromFile = found?.fromFile == true
        val timeable = !fromFile || query.isVideo
        val timedToVideo = LyricsTiming.timedToVideo(found?.source)
        val recordTimed = timeable && (!fromFile || found?.source != null) && !timedToVideo
        val fileTimed = fromFile && !query.isVideo
        val syncFacts = NextPrepPolicy.SyncFacts(
            lyricsOff = !switches.nextPrepLyrics,
            syncedLyrics = AppSettings.syncedLyrics.value,
            hasLyrics = lines != null,
            hasTimedLines = lines?.any { it.timeMs > 0L } == true,
            fileTimed = fileTimed,
            recordTimed = recordTimed,
            timedToVideo = timedToVideo,
            heavyWorkAllowed = AppSettings.heavyWorkAllowed,
            aiTimingAhead = AppSettings.aiTimingAhead.value,
            aiUsable = Deepgram.usable,
            currentPlayedMs = playedMs,
            remainingMs = if (deadlineHit) 0L else leftNow,
            hasTiming = timing != null,
        )
        val lyricsLabel = found?.source?.name?.lowercase(Locale.ROOT)
            ?: if (Step.LYRICS !in triedThisRun) {
                "off"
            } else if (lines != null) {
                "none"
            } else {
                NextPrepPolicy.noneWithReason(lyricsSkipReason(switches, lines))
            }
        val syncLabel = if (Step.SYNC !in triedThisRun) {
            "off"
        } else {
            NextPrepPolicy.syncStatus(syncFacts)
        }
        val hqLabel = hq?.name?.lowercase(Locale.ROOT)
            ?: NextPrepPolicy.noneWithReason(
                if (deadlineHit) "deadline" else hqSkipReason(song),
            )
        val mixLabel = if (mix) {
            "ready"
        } else {
            NextPrepPolicy.noneWithReason(
                if (deadlineHit) "deadline" else mixSkipReason(song),
            )
        }
        val tookMs = SystemClock.elapsedRealtime() - startedAt
        TrackLog.i(
            "BitChord",
            "next-prep: ${song.title} version=${song.videoId} " +
                "lyrics=$lyricsLabel " +
                "hq=$hqLabel " +
                "sync=$syncLabel " +
                "mix=$mixLabel in ${tookMs}ms" +
                if (deadlineHit) " (deadline)" else "",
            about = song.videoId,
        )
    }

    /**
     * How long the current track can still give the bundle before its handoff.
     *
     * Only the player's own duration counts: falling back to the next row's
     * length measured the wrong track and could open a deadline that had
     * already passed (or hide one that had not). An unknown duration is
     * treated as unlimited, as it was before.
     */
    private fun remainingMs(player: ExoPlayer): Long {
        val duration = player.duration
        if (duration <= 0L) return Long.MAX_VALUE
        return NextPrepPolicy.remainingMs(duration, player.currentPosition)
    }

    /**
     * Waits, on the player's own clock rather than a polling loop, until the
     * current track has played [NextPrepPolicy.AI_MIN_PLAYED_MS]. Returns false
     * when the deadline would arrive first, so the free steps still run and the
     * paid ones are skipped with `sync=skipped(ai-too-early)`.
     */
    private suspend fun awaitAiGate(player: ExoPlayer, remainingMs: Long): Boolean {
        val played = player.currentPosition
        if (NextPrepPolicy.aiGateOpen(played)) return true
        val waitMs = NextPrepPolicy.AI_MIN_PLAYED_MS - played
        if (remainingMs != Long.MAX_VALUE && remainingMs <= waitMs) return false
        val reached = CompletableDeferred<Unit>()
        val message = runCatching {
            player.createMessage { _, _ -> reached.complete(Unit) }
                .setPosition(NextPrepPolicy.AI_MIN_PLAYED_MS)
                .setDeleteAfterDelivery(true)
                .send()
        }.getOrNull() ?: return false
        return try {
            withTimeoutOrNull(waitMs + AI_GATE_GRACE_MS) { reached.await() } != null
        } finally {
            message.cancel()
        }
    }

    private fun lyricsSkipReason(
        switches: SyncSafetyPolicy.Switches,
        lines: List<LyricLine>?,
    ): String? = when {
        lines != null -> null
        !switches.nextPrepLyrics -> "off"
        !AppSettings.syncedLyrics.value -> "synced-off"
        else -> "no-answer"
    }

    private fun hqSkipReason(song: Song): String? = when {
        song.isVideo -> "video"
        song.localUri != null -> "local"
        SourceRegistry.parseTrackKey(song.videoId) != null -> "source"
        !SourceResolver.canSubstituteForYouTube() -> "no-source"
        else -> "no-answer"
    }

    private fun mixSkipReason(song: Song): String? = when {
        !AppSettings.smartFadeEnabled.value -> "off"
        ListenTogether.state.value.inParty -> "party"
        !AppSettings.automixVideos.value &&
            (currentPlayer()?.currentMediaItem?.isVideoOrigin == true || song.isVideo) -> "video"
        else -> "not-ready"
    }

    // ---- (a) version ---------------------------------------------------------

    private fun versionWanted(song: Song): Boolean {
        if (!AppSettings.preferMusicOnly.value) return false
        val lookup = lookupSong(song)
        if (lookup.videoRadio) return false
        if (!SongVersionPolicy.wantsSongVersion(lookup.isVideo, lookup.typeUnknown)) return false
        if (lookup.localUri != null && AudioCache.isOffline()) return false
        return true
    }

    /**
     * A downloaded file with no recorded provenance may be a video copy under a
     * song-looking row. Online it is looked up like a video (the song version
     * is streamed, the file stays for offline); offline the file is kept.
     */
    private fun lookupSong(song: Song): Song {
        val verify = SongVersionPolicy.verifiesUnknownDownload(
            localFile = song.localUri != null,
            downloaded = Downloads.isDownloaded(song.videoId),
            provenanceKnown = Downloads.savedSoundSourceFor(song.videoId) != null,
            isVideo = song.isVideo,
            typeUnknown = song.typeUnknown,
            offline = AudioCache.isOffline(),
        )
        return if (verify) {
            song.copy(typeUnknown = true, localUri = null, localPath = null)
        } else {
            song
        }
    }

    /** A definitive answer already exists, so no lookup is needed — only an install. */
    private fun versionAnswered(song: Song): Boolean =
        YtMusicRepository.cachedSongVersionFor(song) == null &&
            YtMusicRepository.isSongVersionMissing(song.videoId)

    private suspend fun resolveVersion(
        song: Song,
        rowId: String,
        installed: (Song) -> Unit,
    ): Song? {
        val lookup = lookupSong(song)
        val cached = YtMusicRepository.cachedSongVersionFor(lookup)
        val resolved = cached ?: if (YtMusicRepository.isSongVersionMissing(lookup.videoId)) {
            null
        } else {
            try {
                YtMusicRepository.songVersion(lookup, beforeAudio = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                TrackLog.w(
                    "BitChord",
                    "next-prep version failed: ${failure.message}",
                    about = song.videoId,
                )
                null
            }
        } ?: return null
        if (resolved.videoId == song.videoId) return null
        val player = currentPlayer() ?: return null
        val index = player.nextMediaItemIndex
        if (index == C.INDEX_UNSET || index !in 0 until player.mediaItemCount) return null
        val item = player.getMediaItemAt(index)
        if ((item.queueEntryId ?: item.mediaId) != rowId) return null
        if (item.mediaId != song.videoId) return null
        val replacement = QueueShuffle.withQueueMetadata(
            resolved.toMediaItem(),
            newEntryId = item.queueEntryId,
            newCanonicalIndex = item.canonicalIndex,
            newTier = item.queueTier,
            newContextCycleId = item.contextCycleId,
        )
        // Announce the new id before the timeline callback fires, so this
        // bundle is not mistaken for one the queue replaced.
        installed(resolved)
        val replaced = runCatching { player.replaceMediaItem(index, replacement) }.isSuccess
        if (!replaced) return null
        return resolved
    }

    // ---- (b) lyrics, (c) high quality ---------------------------------------

    private fun preparedSteps(song: Song, lyrics: TranslationAhead.AheadLyrics?): Set<Step> =
        buildSet {
            if (!versionWanted(song) || versionAnswered(song)) add(Step.VERSION)
            if (lyrics?.lines != null) add(Step.LYRICS)
            if (!hqWanted(song) || existingHq(song) != null) add(Step.HQ)
            if (!mixWanted(song) || mixReady(song.videoId)) add(Step.MIX)
        }

    private fun hqWanted(song: Song): Boolean = NextPrepPolicy.hqStepWanted(
        isVideo = song.isVideo,
        videoRadio = song.videoRadio,
        localFile = song.localUri != null,
        sourceKey = SourceRegistry.parseTrackKey(song.videoId) != null,
        canSubstitute = SourceResolver.canSubstituteForYouTube(),
    )

    private fun existingHq(song: Song): HqTier? {
        val chosen = StreamChoice.of(song.videoId)
        val sourceId = chosen?.sourceConfigId
        if (chosen != null && sourceId != null &&
            SourceRegistry.config(sourceId)?.kind != SourceKind.YOUTUBE
        ) {
            return HqQuality.tierOf(chosen.format)
        }
        return HqVerificationStore.bestTier(context, song.videoId)
    }

    private suspend fun prepareHq(song: Song): HqTier? {
        if (!hqWanted(song)) return null
        existingHq(song)?.let { return it }
        // Read-ahead started the same search at the first-sound event. Wait
        // for its answer instead of running a second search; it has already
        // pinned whatever passed the sound comparison.
        val found = try {
            AudioCache.awaitHqHead(song.videoId, NEXT_PREP_HQ_WAIT_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            TrackLog.d(
                "BitChord",
                "next-prep hq failed: ${failure.message}",
                about = song.videoId,
            )
            null
        }
        val tier = found?.let { stream ->
            val sourceId = stream.sourceConfigId
            if (sourceId != null &&
                SourceRegistry.config(sourceId)?.kind != SourceKind.YOUTUBE
            ) {
                HqQuality.tierOf(stream.format)
            } else {
                null
            }
        }
        if (found != null && tier != null && HqQuality.isLosslessTier(found.format)) {
            // The same hq-prefetch path a mid-song swap uses: pin the verified
            // copy so the standby's losslessAheadItem() opens it. The download
            // itself starts after the sync step, so it never competes with the
            // measurement or the AI call for the current track's bandwidth.
            runCatching {
                StreamChoice.remember(
                    song.videoId,
                    found,
                    substituted = found.sourceConfigId != null,
                )
            }
        }
        return tier ?: existingHq(song)
    }

    /**
     * Starts the hq-prefetch for the pinned lossless rendition and waits for
     * the whole of it to land on disk. Bounded by the current track's deadline;
     * a timeout leaves the background job running, and the standby still opens
     * the pinned rendition, so the next row is lossless either way.
     *
     * The background job is started here, after the sync step, rather than in
     * [prepareHq]: the bundle runs one step at a time, and a whole FLAC must
     * not compete with the measurement/AI/refine it was launched beside.
     */
    private suspend fun awaitLosslessDownload(song: Song, remainingMs: Long): Boolean {
        if (remainingMs <= 0L) return true
        val stream = StreamChoice.of(song.videoId) ?: return false
        if (!HqQuality.isLosslessTier(stream.format)) return false
        val uri = currentNextItemUri(song.videoId) ?: return false
        val upgraded = runCatching {
            Uri.parse(QualityUpgrade.upgradedUri(uri.toString()))
        }.getOrNull() ?: return false
        // Starting the background job first makes the awaited call join it
        // instead of opening a second writer on the same entry.
        hqPrefetchVideoId = song.videoId
        AudioCache.prefetchVerifiedRendition(upgraded, song.title, stream)
        // A null here is the deadline cutting the download short; the pinned
        // rendition and the background job are still there, but the bundle
        // must say it ran out of time rather than claim the step finished.
        return withRemaining(remainingMs) {
            AudioCache.downloadVerifiedRendition(upgraded, song.title, stream)
        } == null
    }

    /**
     * Runs [block] under [remainingMs] unless the duration is unknown, in
     * which case it is unlimited. A literal `Long.MAX_VALUE` timeout overflows
     * the delay arithmetic, so "no deadline" is expressed by not arming one.
     */
    private suspend fun <T> withRemaining(remainingMs: Long, block: suspend () -> T): T? =
        if (remainingMs == Long.MAX_VALUE) block() else withTimeoutOrNull(remainingMs) { block() }

    private fun currentNextItemUri(videoId: String): Uri? {
        val player = currentPlayer() ?: return null
        val index = player.nextMediaItemIndex
        if (index == C.INDEX_UNSET || index !in 0 until player.mediaItemCount) return null
        val item = player.getMediaItemAt(index)
        if (item.mediaId != videoId) return null
        return item.localConfiguration?.uri
    }

    // ---- (d) song sync -------------------------------------------------------

    private fun syncWanted(song: Song): Boolean {
        // A substitute stream is a different recording under the same id; its
        // clock is not the upload's, so it never runs a song sync.
        if (StreamChoice.isSubstitute(song.videoId)) return false
        if (!AppSettings.heavyWorkAllowed) return false
        // A video's free chroma measurement needs no paid engine; the AI
        // fallback inside prepareNextPaid still respects aiTimingAhead/Deepgram.
        // The lyrics query can know a row is a video even when its flag is
        // missing, so use the same source the rest of the bundle does.
        if (LyricsTimingSource.isVideo(song)) return true
        if (!AppSettings.aiTimingAhead.value) return false
        return Deepgram.usable
    }

    private suspend fun timed(
        lines: List<LyricLine>,
        found: TranslationAhead.AheadLyrics,
        song: Song,
    ): LyricsTiming.Timing? {
        // A file's own timing stands unless it is a video; a re-check override
        // carries a fresh sync for this file and is applied too, exactly as the
        // player and the lyrics screen do. A substitute stream is a different
        // recording under the same id, so its clock is never written.
        val query = CoverTitles.lyricsQuery(song)
        val sourceTimedToVideo = LyricsTiming.timedToVideo(found.source)
        val timeable = LocalTimingPolicy.downloadedTimingTimeable(
            timed = true,
            fromFile = found.fromFile,
            isVideo = query.isVideo,
            recheckOverride = found.recheckOverride,
            sourceTimedToVideo = sourceTimedToVideo,
        )
        val substituted = StreamChoice.isSubstitute(song.videoId)
        val recordTimed = timeable && (!found.fromFile || found.source != null) && !sourceTimedToVideo
        return LyricsTiming.timing(
            context = context,
            videoId = song.videoId,
            lines = lines,
            baseAllowed = timeable && !substituted,
            measuredAllowed = recordTimed && !substituted,
            isVideo = query.isVideo,
            audioSource = if (query.isVideo) {
                null
            } else {
                LyricsTiming.audioSourceKey(song.videoId, song.localUri != null)
            },
        )
    }

    // ---- (e) mix -------------------------------------------------------------

    private fun mixWanted(song: Song): Boolean {
        if (!AppSettings.smartFadeEnabled.value) return false
        if (ListenTogether.state.value.inParty) return false
        val current = currentPlayer()?.currentMediaItem
        if (!AppSettings.automixVideos.value && (current?.isVideoOrigin == true || song.isVideo)) {
            return false
        }
        return true
    }

    private companion object {
        const val MAX_ROWS_REMEMBERED = 12
        const val NEXT_PREP_HQ_WAIT_MS = 15_000L

        /** A player message at 30 s is the gate; this only covers a seek/pause race. */
        const val AI_GATE_GRACE_MS = 5_000L
    }
}

/**
 * The next row's lyrics, ready before its first frame.
 *
 * The service and the lyrics screen both read it when the track opens; it is
 * cleared as soon as the next row changes, so it can never outlive the queue
 * it was prepared for.
 */
internal object NextPrepCache {

    class Ready(
        val videoId: String,
        /** The provider's lines, before [LyricsTiming] moved them. */
        val raw: List<LyricLine>,
        /** The same lines with the stored correction already applied. */
        val shown: List<LyricLine>,
        val source: LyricsSource?,
        val searchedAs: Pair<String, String>?,
        val isVideo: Boolean,
        val fromFile: Boolean,
        /** Whether the row played a downloaded file rather than a stream. */
        val localFile: Boolean,
        /**
         * The source set, order and syllable preference the lines were chosen
         * with. A later change must not let the old settings' answer be shown
         * as if it were the new settings' choice.
         */
        val lyricsSettings: String,
        /** The file/stream clock these lines were timed for; null for a video's own upload. */
        val audioSource: String?,
        /**
         * Whether the sound was a substitute stream when these lines were
         * timed. A substitute has a different clock from the YouTube upload a
         * stored measurement or AI answer was made against, so its cache entry
         * must not be served after the choice changes.
         */
        val substituted: Boolean,
    )

    @Volatile
    private var ready: Ready? = null

    fun put(value: Ready) {
        ready = value
    }

    fun get(
        videoId: String,
        localFile: Boolean,
        isVideo: Boolean,
        lyricsSettings: String,
        audioSource: String?,
        substituted: Boolean,
    ): Ready? =
        ready?.takeIf {
            it.videoId == videoId && it.localFile == localFile &&
                it.isVideo == isVideo && it.lyricsSettings == lyricsSettings &&
                it.audioSource == audioSource && it.substituted == substituted
        }

    /** One string for the lyrics choices that change what a lookup returns. */
    fun settingsKey(
        sources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        syncedLyrics: Boolean = false,
        nextPrepEnabled: Boolean = true,
    ): String = buildString {
        sources.map { it.name }.sorted().forEach { append(it).append('\u0000') }
        order.forEach { append(it.name).append('\u0001') }
        append(prioritizeSyllableSync)
        // Turning synced lyrics (or the next-prep switch) on after a no-lyrics
        // bundle completed must re-prepare the next row, so both are identity.
        // PlaybackService also reads this switch when it opens a track, and the
        // next-prep safety switch decides whether the bundle does lyrics work
        // at all.
        append('\u0002').append(syncedLyrics)
        append('\u0003').append(nextPrepEnabled)
    }

    fun clear() {
        ready = null
    }
}
