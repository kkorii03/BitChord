package com.music.bitchord.playback

import android.content.Context
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
import com.music.bitchord.data.model.durationMillis
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    private var completedRowId: String? = null
    private var completedQueueKey: String? = null

    /**
     * Steps already tried for a row in this session. A failed network step is
     * not retried for the same row: the ordinary post-start path takes over,
     * which is where the user's battery would otherwise be spent twice.
     */
    private val attempted = object : LinkedHashMap<String, MutableSet<Step>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<Step>>) =
            size > MAX_ROWS_REMEMBERED
    }

    /** Lyrics already fetched for a row, so a resumed bundle keeps its answer. */
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
        if (snapshot.rowId == rowId) {
            if (job?.isActive == true) return
            if (completedRowId == snapshot.rowId && completedQueueKey == snapshot.queueKey) return
        }
        if (NextPrepPolicy.shouldCancel(rowId, queueKey, snapshot.rowId, snapshot.queueKey)) {
            job?.cancel()
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
        rowId = null
        queueKey = null
        completedRowId = null
        completedQueueKey = null
        NextPrepCache.clear()
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
        return Snapshot(rowId, index, song, "$rowId|${song.videoId}")
    }

    private fun attemptsFor(rowId: String): MutableSet<Step> = synchronized(attempted) {
        attempted.getOrPut(rowId) { mutableSetOf() }
    }

    private suspend fun run(snapshot: Snapshot) {
        val startedAt = SystemClock.elapsedRealtime()
        val switches = AppSettings.syncSafetySwitches
        var song = snapshot.song
        var expectedQueueKey = snapshot.queueKey
        val steps = attemptsFor(snapshot.rowId)

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
                expectedQueueKey = "${snapshot.rowId}|${expected.videoId}"
                queueKey = expectedQueueKey
            }
            if (resolved != null) song = resolved
        }
        if (!stillCurrent()) return

        // (b) The lyrics for that recording, then (c) its high-quality copy.
        // The lyrics switch is off by default: the cache may hold lines from
        // an earlier run, so it is not even read while the switch is off.
        var lyrics: TranslationAhead.AheadLyrics? = if (switches.nextPrepLyrics) {
            synchronized(lyricsByRow) { lyricsByRow[snapshot.rowId] }
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
        val prepared = preparedSteps(song, lyrics = null)
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
            steps += step
            try {
                when (step) {
                    Step.VERSION -> Unit
                    Step.LYRICS -> {
                        lyrics = TranslationAhead.lyricsForPrep(
                            context,
                            song,
                            allowHeavy = AppSettings.heavyWorkAllowed,
                        )
                        synchronized(lyricsByRow) {
                            lyrics?.let { lyricsByRow[snapshot.rowId] = it }
                        }
                    }
                    Step.HQ -> hq = prepareHq(song) ?: hq
                    Step.SYNC -> {
                        val found = lyrics
                        val lines = found?.lines
                        if (lines != null && lines.any { it.timeMs > 0 } && !song.isVideo) {
                            if (!LyricsTiming.songVerified(context, song, lines)) {
                                LyricsTiming.verifySong(context, song, lines)
                            }
                        }
                    }
                    Step.MIX -> Unit
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
            }
        }

        if (!stillCurrent()) return

        val found = lyrics
        val lines = found?.lines?.takeIf { it.any { line -> line.text.isNotBlank() } }
        val timing = try {
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
        val syncSkipReason = if (timing != null) {
            null
        } else {
            SyncSafetyPolicy.nextPrepSyncSkipReason(
                switches = switches,
                syncedLyrics = AppSettings.syncedLyrics.value,
                isVideo = song.isVideo,
                heavyWorkAllowed = AppSettings.heavyWorkAllowed,
                aiTimingAhead = AppSettings.aiTimingAhead.value,
                deepgramUsable = Deepgram.usable,
                hasLyrics = lines != null,
                hasTimedLines = lines?.any { it.timeMs > 0 } == true,
            )
        }
        if (lines != null && shown != null && SyncSafetyPolicy.firstFrameCacheAllowed(switches)) {
            NextPrepCache.put(
                NextPrepCache.Ready(
                    videoId = song.videoId,
                    raw = lines,
                    shown = shown,
                    source = found.source,
                    searchedAs = found.searchedAs,
                    isVideo = song.isVideo,
                    fromFile = found.fromFile,
                ),
            )
        }

        // The mix analysis is last because it can take a while; the lyrics and
        // their correction are already in the cache above, so a track that
        // starts early still gets them on its first frame.
        if (Step.MIX in plan.steps) {
            if (!stillCurrent()) return
            steps += Step.MIX
            try {
                val player = currentPlayer()
                if (player != null) {
                    prepareMix(player)
                    mix = awaitMix(song.videoId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                TrackLog.d(
                    "BitChord",
                    "next-prep mix failed: ${failure.message}",
                    about = song.videoId,
                )
            }
        }

        completedRowId = snapshot.rowId
        completedQueueKey = expectedQueueKey
        val tookMs = SystemClock.elapsedRealtime() - startedAt
        TrackLog.i(
            "BitChord",
            "next-prep: ${song.title} version=${song.videoId} " +
                "lyrics=${found?.source?.name?.lowercase(Locale.ROOT) ?: "none"} " +
                "hq=${hq?.name?.lowercase(Locale.ROOT) ?: "none"} " +
                "sync=${if (timing != null) "applied" else "skipped:${syncSkipReason ?: "not-applied"}"} " +
                "mix=${if (mix) "ready" else "none"} in ${tookMs}ms",
            about = song.videoId,
        )
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

    private fun hqWanted(song: Song): Boolean {
        if (song.isVideo || song.localUri != null) return false
        if (SourceRegistry.parseTrackKey(song.videoId) != null) return false
        return SourceResolver.canSubstituteForYouTube()
    }

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
        return tier ?: existingHq(song)
    }

    // ---- (d) song sync -------------------------------------------------------

    private fun syncWanted(song: Song): Boolean {
        if (song.isVideo) return false
        if (!AppSettings.heavyWorkAllowed) return false
        if (!AppSettings.aiTimingAhead.value) return false
        return Deepgram.usable
    }

    private suspend fun timed(
        lines: List<LyricLine>,
        found: TranslationAhead.AheadLyrics,
        song: Song,
    ): LyricsTiming.Timing? {
        val query = CoverTitles.lyricsQuery(song)
        val timeable = !found.fromFile || query.isVideo
        val recordTimed = timeable && (!found.fromFile || found.source != null) &&
            !LyricsTiming.timedToVideo(found.source)
        return LyricsTiming.timing(
            context = context,
            videoId = song.videoId,
            lines = lines,
            baseAllowed = timeable,
            measuredAllowed = recordTimed,
            isVideo = song.isVideo,
            audioSource = if (song.isVideo) {
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
    )

    @Volatile
    private var ready: Ready? = null

    fun put(value: Ready) {
        ready = value
    }

    fun get(videoId: String): Ready? = ready?.takeIf { it.videoId == videoId }

    fun clear() {
        ready = null
    }
}
