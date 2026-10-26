package com.music.bitchord.ui

import android.app.Application
import com.music.bitchord.data.TrackDiagnostics
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsAiGuard
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.NextPrepCache
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.SyncSafetyPolicy
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.playback.smart.SyncTestMode
import com.music.bitchord.ui.player.LyricsTimingUi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loading, provider selection and timing for the lyrics of the playing track.
 *
 * Moved out of [MainViewModel] so the view model only exposes the resulting
 * state and delegates commands; the logic, logs and timing are unchanged.
 */
class LyricsController(
    private val app: Application,
    private val scope: CoroutineScope,
) {

    /** Synced lyrics for whatever is playing; null while unknown or absent. */
    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    /** How long timed lines wait on a higher-priority source still answering — see [showFirstUsable]. */
    private val PATIENCE_MS = 1_500L

    /** Measurements of one video while its lyrics are showing, at most — see [startTiming]. */
    private val MAX_MEASURE_ATTEMPTS = 10

    /** How often to look whether more of a video has arrived to measure on. */
    private val REMEASURE_CHECK_MS = 8_000L

    /** How long to keep looking — longer than any video worth measuring takes to arrive. */
    private val MEASURE_WINDOW_MS = 15 * 60_000L

    /** How the lyrics on screen were moved onto the video's clock — see [LyricsTiming]. */
    private val _lyricsTiming = MutableStateFlow(LyricsTimingUi())
    val lyricsTiming: StateFlow<LyricsTimingUi> = _lyricsTiming.asStateFlow()

    /** The lyrics as their source timed them, before [LyricsTiming] moved them. */
    private var rawLyricsResult: LyricsRepository.Result? = null

    /**
     * The order the running lookup takes answers in — see [showFirstUsable].
     * Empty while nothing is being looked up.
     */
    private var lyricsRaceOrder: List<LyricsSource> = emptyList()
    private var lyricsTimingJob: Job? = null
    private var aiTimingJob: Job? = null

    /**
     * The song whose check was started in this playback. It keeps a check that
     * found no audio from being tried again on every lyrics delivery in the
     * same playback; a different track (or coming back to this one later)
     * clears it, so the next playback may have the audio.
     */
    private var songCheckStartedFor: String? = null

    /** Which of the four databases [lyrics] came from, for the panel's credit. */
    private val _lyricsSource = MutableStateFlow<LyricsSource?>(null)
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsSource.asStateFlow()

    /**
     * Whether the lookup for the current track has finished. [lyrics] alone
     * can't tell "still looking" apart from "looked, found nothing" — both
     * are null — and the player needs that distinction to show "Lyrics not
     * available" only once it actually means that.
     */
    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()

    private var lyricsJob: Job? = null
    private val manualLyricsJobs = mutableMapOf<LyricsSource, Job>()

    private val _lyricsProviderStates = MutableStateFlow(
        LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED },
    )
    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        _lyricsProviderStates.asStateFlow()

    /** Completed hits are retained for the playing track so choosing one is instant. */
    private val lyricsProviderResults = ConcurrentHashMap<LyricsSource, LyricsRepository.Result>()

    private data class LyricsRequest(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
        val isVideo: Boolean = false,
        val alternatives: List<Pair<String, String>> = emptyList(),
        val artistKnown: Boolean = true,
        val artistAliases: List<String> = emptyList(),
        /** Played from a file: there is no stream on disk to measure against. */
        val localUri: String? = null,
        /**
         * The file's lyrics were timed for another recording (a music video
         * this catalogue track was swapped from). They still need measuring.
         */
        val fileTimingForeign: Boolean = false,
        /** The file's header names its provider and the recording; false is the `@unknown` case. */
        val fileProviderKnown: Boolean = false,
    )

    private var currentLyricsRequest: LyricsRequest? = null
    private var lyricsGeneration = 0L
    private var selectedLyricsSource: LyricsSource? = null

    /**
     * `file:<provider>@<videoId>` when the lyrics on screen came out of a
     * downloaded file, so the sync line names the recording they were timed
     * for. Null for every network answer.
     */
    private var fileLyricsLabel: String? = null

    /**
     * What the loaded lyrics are for. Both the track *and* the settings that
     * chose them, so switching a source on or off re-runs the lookup rather
     * than leaving the last answer sitting on a player that would now find a
     * different one. The audio source is part of it too: a song row can switch
     * from its downloaded file to the song stream, and the check of the old
     * recording must not stand for the new one.
     */
    private var lyricsFor: Triple<String, String?, Set<LyricsSource>>? = null

    init {
        // Kept lyrics a background check has since bettered (word timing where
        // only lines were kept): swapped in for the song on screen, unless a
        // source was picked by hand.
        scope.launch {
            LyricsRepository.upgrades.collect { (videoId, result) ->
                if (currentLyricsRequest?.videoId == videoId && selectedLyricsSource == null && _lyrics.value != null) {
                    publishLyrics(result)
                }
            }
        }
    }

    /**
     * Called as the playing track changes; cheap no-op when already loaded.
     *
     * [localUri] is the file this track plays from when it is on the device,
     * and it is tried before the network: a downloaded track had its lyrics
     * fetched once already and written into its own file (see `LyricsTag`), so
     * asking the same servers again is a round trip to arrive at a string that
     * is on disk — and one that fails outright with the connection off, which
     * is what made a downloaded song show nothing offline.
     */
    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
        /** A video upload: its captions are asked first. */
        isVideo: Boolean = false,
        /** Other readings of a cover's title to search under — see CoverTitles. */
        alternatives: List<Pair<String, String>> = emptyList(),
        /** False when [artist] is only a cover's singer — see CoverTitles.LyricsQuery. */
        artistKnown: Boolean = true,
        /** Other names the row's artist is known by — see CoverTitles.LyricsQuery. */
        artistAliases: List<String> = emptyList(),
    ) {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        val key = Triple(
            videoId,
            if (isVideo) null else LyricsTiming.audioSourceKey(videoId, localUri != null),
            sources,
        )
        if (lyricsFor == key) return
        // The duration lands a beat after the track, and a database match needs
        // it. Turned away here rather than inside the job below: claiming the
        // lookup first and giving it up asynchronously means the very re-trigger
        // that carries the duration can arrive while the claim still stands, be
        // dropped as a duplicate, and leave the track marked as being looked up
        // by nobody — which is what left a paused track loading for ever, since
        // pausing is when the duration is most likely to arrive a frame late.
        if (localUri == null && durationMs <= 0L) return
        if (currentLyricsRequest?.videoId != videoId ||
            (currentLyricsRequest?.localUri != null) != (localUri != null)
        ) {
            songCheckStartedFor = null
        }
        lyricsFor = key
        lyricsGeneration += 1
        val generation = lyricsGeneration
        currentLyricsRequest = LyricsRequest(
            videoId, title, artist, durationMs, album, isVideo, alternatives, artistKnown,
            artistAliases, localUri,
        )
        selectedLyricsSource = null
        fileLyricsLabel = null
        manualLyricsJobs.values.forEach(Job::cancel)
        manualLyricsJobs.clear()
        lyricsProviderResults.clear()
        _lyricsProviderStates.value =
            LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED }
        val order = (AppSettings.lyricsSourceOrder.value + LyricsSource.offered).distinct()
            .filter { it in sources && !it.hidden }
        lyricsRaceOrder = if (isVideo && LyricsSource.YOUTUBE_TRANSCRIPT in order) {
            listOf(LyricsSource.YOUTUBE_TRANSCRIPT) + (order - LyricsSource.YOUTUBE_TRANSCRIPT)
        } else {
            order
        }
        _lyrics.value = null
        _lyricsSource.value = null
        rawLyricsResult = null
        timingLines = null
        timingBaseAllowed = false
        timingMeasureAllowed = true
        lyricsTimingJob?.cancel()
        aiTimingJob?.cancel()
        _lyricsTiming.value = LyricsTimingUi()
        lyricsJob?.cancel()
        startEarlyMeasure(videoId, title, artist, durationMs, localUri, isVideo, alternatives, artistKnown)
        if (sources.isEmpty()) {
            // Switched off, or every source unticked. Nothing to look up, and
            // nothing to say about it — the player drops the lyric strip
            // rather than reporting a track with no lyrics.
            TrackLog.d("BitChord", "lyrics: no sources enabled", videoId)
            _lyricsChecked.value = true
            return
        }
        _lyricsChecked.value = false
        // The next-song bundle already looked these up. Put them on screen
        // before the lookup below even starts, with the stored correction
        // applied by startTiming; the lookup then confirms the same memoised
        // answer rather than making a request. Off by default: the bundle's
        // lyrics path is a post-ko-101 addition and must not reach this frame.
        if (SyncSafetyPolicy.firstFrameCacheAllowed(AppSettings.syncSafetySwitches)) {
            NextPrepCache.get(videoId)?.let { prepared ->
                if (prepared.source != null) {
                    publishLyrics(
                        LyricsRepository.Result(
                            source = prepared.source,
                            lines = prepared.raw,
                            searchedAs = prepared.searchedAs,
                        ),
                    )
                    _lyricsChecked.value = true
                }
            }
        }
        lyricsJob = scope.launch {
            // A per-song override is the last word: it comes before the
            // downloaded file's tags or `lyrics.lrc`, and before any provider
            // lookup. It also keeps a re-checked sheet out of the audio file,
            // which may be the one playing — see LyricsOverrideFile.
            val override = LyricsOverrideStore.read(app, videoId)
            if (override != null) {
                val lines = override.lines
                val timed = lines.any { it.timeMs > 0 }
                _lyrics.value = lines
                _lyricsChecked.value = true
                currentLyricsRequest = currentLyricsRequest?.copy(
                    fileProviderKnown = override.source != null,
                )
                if (localUri != null) {
                    _lyricsSource.value = null
                    fileLyricsLabel = com.music.bitchord.data.lyrics.FileLyricsOrigin.label(
                        override.source?.label,
                        videoId,
                    )
                    val onVideo = isVideo && timed
                    val recordTimed = onVideo && override.source != null &&
                        !LyricsTiming.timedToVideo(override.source)
                    val downloadRecheck = !isVideo && timed &&
                        override.kind == LyricsOverrideFile.Kind.RECHECK &&
                        !LyricsTiming.timedToVideo(override.source)
                    startTiming(
                        lines,
                        adjustable = onVideo || downloadRecheck,
                        searchedAs = null,
                        measure = recordTimed,
                        measureNow = true,
                        videoOwn = onVideo && !recordTimed,
                        downloadRecheck = downloadRecheck,
                    )
                } else {
                    _lyricsSource.value = override.source
                    fileLyricsLabel = null
                    val videoOwn = LyricsTiming.timedToVideo(override.source)
                    startTiming(
                        lines,
                        adjustable = timed,
                        searchedAs = null,
                        measure = !videoOwn,
                        measureNow = true,
                        videoOwn = timed && videoOwn,
                    )
                }
                return@launch
            }
            // The file first, and without the duration gate below: a length is
            // only needed to *match* a track against a stranger's database, and
            // nothing is being matched here — these lyrics were written into
            // this exact file, for this exact recording.
            if (localUri != null) {
                EmbeddedLyrics.withSource(app, localUri)?.let { file ->
                    val embedded = file.lines
                    _lyrics.value = embedded
                    // No source to name: what the file records is the lyrics,
                    // not which of the eight services they came from months ago.
                    // The download wrote that source and the id it was timed for
                    // into the file header; name both in the sync line.
                    _lyricsSource.value = null
                    fileLyricsLabel = com.music.bitchord.data.lyrics.FileLyricsOrigin.label(
                        file.source?.label,
                        file.videoId,
                    )
                    _lyricsChecked.value = true
                    // A song's own file whose header names it is timed for it
                    // already: only a correction by hand. One with no id (an
                    // older download) is checked once below. A downloaded video
                    // (a cover) carries its lyrics as they were found — the
                    // record's, which the video's timing kept for it still moves,
                    // unless they were the video's own; and whatever they are,
                    // Gemini can be asked to place them.
                    val timed = embedded.any { it.timeMs > 0 }
                    val foreign = timed && com.music.bitchord.data.lyrics.FileLyricsOrigin
                        .timingBelongsToAnotherRecording(file.videoId, videoId)
                    if (foreign) {
                        currentLyricsRequest = currentLyricsRequest?.copy(fileTimingForeign = true)
                    }
                    currentLyricsRequest = currentLyricsRequest?.copy(
                        fileProviderKnown = file.source != null && !file.videoId.isNullOrBlank(),
                    )
                    val onVideo = isVideo && timed
                    val recordTimed = onVideo && file.source != null && !LyricsTiming.timedToVideo(file.source)
                    // A song's file header names the recording its times were
                    // written for. Without that id — an older download — nothing
                    // ever checked these times, so the song check may run once.
                    // (A video keeps its existing foreign/measure treatment.)
                    val unverified = !isVideo && timed && !LyricsTiming.timedToVideo(file.source) &&
                        com.music.bitchord.data.lyrics.FileLyricsOrigin.timingIsUnverified(file.videoId, videoId)
                    // A downloaded song is re-checked once with today's rules,
                    // whatever its header says: the file was written at save
                    // time and may have kept a wrong sync ever since.
                    val downloadRecheck = !isVideo && timed && !foreign &&
                        !LyricsTiming.timedToVideo(file.source)
                    startTiming(
                        embedded,
                        adjustable = onVideo || foreign || unverified || downloadRecheck,
                        searchedAs = null,
                        measure = recordTimed || foreign,
                        measureNow = true,
                        videoOwn = onVideo && !foreign && file.source != null && !recordTimed,
                        downloadRecheck = downloadRecheck,
                    )
                    return@launch
                }
            }
            if (durationMs <= 0L) {
                // Duration arrives a beat after the track does; wait for it.
                lyricsFor = null
                return@launch
            }
            val found = LyricsRepository.lyrics(
                videoId, title, artist, durationMs, album, sources,
                AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
                onSourceStarted = { source -> providerStarted(generation, source) },
                onSourceResult = { source, result, failure ->
                    providerFinished(generation, source, result, failure)
                },
                onSourceCancelled = { source -> providerCancelled(generation, source) },
                isVideo = isVideo,
                alternatives = alternatives,
                artistKnown = artistKnown,
                artistAliases = artistAliases,
                transcribe = localUri == null,
            )
            val selected = selectedLyricsSource?.let(lyricsProviderResults::get) ?: found
            publishLyrics(selected)
            _lyricsChecked.value = true
        }
    }

    /**
     * Selects a provider from the player drawer. Completed hits are applied
     * from memory; misses are inert; only an untouched provider goes online.
     */
    fun selectLyricsProvider(source: LyricsSource) {
        val request = currentLyricsRequest ?: return
        if (source.hidden || source !in AppSettings.lyricsSources.value) return
        TrackLog.i("BitChord", "cmd: lyrics-provider ${source.label} from ui", about = request.videoId)
        when (_lyricsProviderStates.value[source]) {
            LyricsProviderState.FOUND -> {
                selectedLyricsSource = source
                lyricsProviderResults[source]?.let(::showLyricsResult)
            }
            LyricsProviderState.FETCHING -> {
                // Apply it as soon as the already-running automatic attempt completes.
                selectedLyricsSource = source
            }
            LyricsProviderState.NOT_FETCHED,
            null,
            LyricsProviderState.TIMEOUT,
            LyricsProviderState.NETWORK_ERROR,
            LyricsProviderState.BLOCKED,
            -> {
                selectedLyricsSource = source
                fetchLyricsProvider(request, lyricsGeneration, source)
            }
            LyricsProviderState.NOT_FOUND -> Unit
        }
    }

    private fun fetchLyricsProvider(
        request: LyricsRequest,
        generation: Long,
        source: LyricsSource,
    ) {
        // A provider the listener disabled in Settings is never fetched, even
        // if a stale row or an old tap still names it.
        if (source.hidden || source !in AppSettings.lyricsSources.value) return
        if (manualLyricsJobs[source]?.isActive == true) return
        manualLyricsJobs[source] = scope.launch {
            LyricsRepository.lyrics(
                videoId = request.videoId,
                title = request.title,
                artist = request.artist,
                durationMs = request.durationMs,
                album = request.album,
                sources = setOf(source),
                order = listOf(source),
                prioritizeSyllableSync = false,
                onSourceStarted = { provider -> providerStarted(generation, provider) },
                onSourceResult = { provider, result, failure ->
                    providerFinished(generation, provider, result, failure)
                },
                onSourceCancelled = { provider -> providerCancelled(generation, provider) },
                alternatives = request.alternatives,
                artistKnown = request.artistKnown,
                artistAliases = request.artistAliases,
            )
        }
    }

    private fun providerStarted(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FETCHING) }
    }

    private fun providerFinished(
        generation: Long,
        source: LyricsSource,
        result: LyricsRepository.Result?,
        failure: com.music.bitchord.data.lyrics.LyricsFailure? = null,
    ) {
        if (generation != lyricsGeneration) return
        if (result == null) {
            // A miss and a disabled/blocked source look the same in the
            // player's one-line log; name the source and the reason here.
            TrackLog.d(
                "BitChord",
                "lyrics: ${source.label} ${failure?.name?.lowercase() ?: "not found"}",
            )
            _lyricsProviderStates.update {
                it + (source to LyricsProviderState.fromFailure(failure))
            }
            return
        }
        lyricsProviderResults[source] = result
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FOUND) }
        // Provider answers arrive on the lookup's IO threads; what is shown is
        // changed on the main thread only, so two of them landing together
        // can't interleave with the lookup's own result.
        scope.launch(Dispatchers.Main.immediate) {
            if (generation != lyricsGeneration) return@launch
            if (selectedLyricsSource == source) showLyricsResult(result) else showFirstUsable(generation)
        }
    }

    /**
     * Shows timed lyrics the moment the lookup has the answer it would settle
     * on without waiting any further — the highest-priority source with timed
     * lines, every source ahead of it having missed — rather than after the
     * grace period the lookup gives word-synced sources to do better. If one
     * does, the lookup's own answer replaces these when it lands.
     */
    private fun showFirstUsable(generation: Long, stopWaiting: Boolean = false) {
        if (generation != lyricsGeneration || _lyrics.value != null || selectedLyricsSource != null) return
        if (lyricsJob?.isActive != true) return
        val states = _lyricsProviderStates.value
        var blocked = false
        for (source in lyricsRaceOrder) {
            when (states[source]) {
                LyricsProviderState.FOUND -> {
                    val result = lyricsProviderResults[source] ?: continue
                    if (result.lines.none { it.timeMs > 0 }) continue
                    if (!blocked || stopWaiting) {
                        publishLyrics(result)
                    } else {
                        // Timed lines are in hand, but a source ahead of them is
                        // still answering. It gets [PATIENCE_MS], not the whole
                        // lookup: a slow mirror shouldn't keep good lines off the
                        // screen. If it does better, the lookup's own answer
                        // replaces these when it lands.
                        scope.launch {
                            delay(PATIENCE_MS)
                            showFirstUsable(generation, stopWaiting = true)
                        }
                    }
                    return
                }
                LyricsProviderState.NOT_FOUND,
                LyricsProviderState.TIMEOUT,
                LyricsProviderState.NETWORK_ERROR,
                LyricsProviderState.BLOCKED,
                -> continue
                else -> blocked = true
            }
        }
    }

    private fun providerCancelled(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { states ->
            if (states[source] == LyricsProviderState.FETCHING) {
                states + (source to LyricsProviderState.NOT_FETCHED)
            } else {
                states
            }
        }
        // A tap may have selected a provider while the priority race was still
        // using it. If that race then cancels the loser, honour the tap with a
        // dedicated request instead of leaving the row stuck at "Fetching".
        if (selectedLyricsSource == source) {
            currentLyricsRequest?.let { fetchLyricsProvider(it, generation, source) }
        }
    }

    private fun showLyricsResult(result: LyricsRepository.Result) {
        publishLyrics(result)
        _lyricsChecked.value = true
    }

    /**
     * Puts [result] on screen as its source timed it, then — for timed lyrics
     * that aren't the video's own — on the video's clock. See [startTiming].
     */
    private fun publishLyrics(result: LyricsRepository.Result?) {
        // The same lines already showing — the lookup confirming what
        // [showFirstUsable] put up early: keep them and their timing work.
        if (result != null && rawLyricsResult?.lines === result.lines) {
            rawLyricsResult = result
            return
        }
        rawLyricsResult = result
        _lyrics.value = result?.lines
        _lyricsSource.value = result?.source
        val request = currentLyricsRequest
        // Any timed lines can be put on the video's clock by Gemini; only the
        // record's are measured against the record.
        val adjustable = request != null && result != null && result.lines.any { it.timeMs > 0 }
        val videoOwn = LyricsTiming.timedToVideo(result?.source)
        startTiming(
            result?.lines,
            adjustable,
            searchedAs = result?.searchedAs,
            measure = !videoOwn,
            measureNow = request?.localUri == null,
            videoOwn = adjustable && videoOwn,
        )
    }

    /** The lines on screen before any timing moved them; what [LyricsTiming] works from. */
    private var timingLines: List<LyricLine>? = null

    /** Whether [timingLines] may be moved onto the video's clock, or only corrected by hand. */
    private var timingBaseAllowed = false

    /** Whether [timingLines] are the record's, to be measured against it — not the video's own captions. */
    private var timingMeasureAllowed = true

    /** What [refreshTiming] last put on [timingLines]. */
    private var appliedTiming: LyricsTiming.Timing? = null

    /**
     * Applies what is known about this track's timing to [lines] at once —
     * an AI answer or measurement kept for the video, the listener's own
     * correction — then, for a video whose lyrics are the record's
     * ([adjustable]), measures it: now on what has reached the disk, and
     * again as more of it arrives, until the whole video has been measured.
     * With "가사 타이밍 자동 보정" on, a measurement that comes up empty
     * hands over to Gemini.
     */
    private fun startTiming(
        lines: List<LyricLine>?,
        adjustable: Boolean,
        searchedAs: Pair<String, String>?,
        cancelAi: Boolean = true,
        measure: Boolean = true,
        /** Measure the video now, as it streams in — not for a file, which has no stream on disk. */
        measureNow: Boolean = true,
        /** The lines are timed to the video already (its captions, Gemini's transcript). */
        videoOwn: Boolean = adjustable && !measure,
        /** A downloaded song whose stored sync may need one check with today's rules. */
        downloadRecheck: Boolean = false,
    ) {
        lyricsTimingJob?.cancel()
        if (cancelAi) aiTimingJob?.cancel()
        timingLines = lines
        val request = currentLyricsRequest
        // A substituted recording is a different file under the same video id:
        // its clock is not the one any measurement or AI answer for the YouTube
        // upload was made against. Neither apply it nor spend an engine
        // checking it; the plain provider timing is what this play shows.
        val substituted = request != null && !request.isVideo &&
            StreamChoice.isSubstitute(request.videoId)
        timingBaseAllowed = adjustable && !substituted
        timingMeasureAllowed = measure && !substituted
        val timed = lines != null && lines.any { it.timeMs > 0 }
        // As the source timed them, until [refreshTiming] says otherwise.
        appliedTiming = null
        if (lines != null) _lyrics.value = lines
        _lyricsTiming.value = LyricsTimingUi(
            adjustable = adjustable,
            manualAvailable = timed,
            videoOwn = videoOwn,
        )
        if (!timed || request == null || lines == null) return
        val generation = lyricsGeneration
        val context = app
        lyricsTimingJob = scope.launch {
            // Whatever else changes this video's timing — the lock screen, the
            // next-song preparation measuring it first — shows here too.
            launch {
                LyricsTiming.changes.collect { videoId ->
                    if (videoId == request.videoId) refreshTiming(generation, lines)
                }
            }
            refreshTiming(generation, lines)
            if (downloadRecheck && request.localUri != null) {
                // Event-driven only: the check runs because this file started
                // playing, once per playback and one file at a time. A version
                // already recorded makes it a cheap no-op.
                if (!adjustable || videoOwn || !cancelAi || substituted) return@launch
                if (!SyncSafetyPolicy.recheckAllowed(AppSettings.syncSafetySwitches) ||
                    !AppSettings.heavyWorkAllowed || !AppSettings.aiTimingAhead.value ||
                    !com.music.bitchord.data.lyrics.Deepgram.usable
                ) return@launch
                val checkKey = LyricsTiming.audioSourceKey(request.videoId, localFile = true)
                if (songCheckStartedFor == checkKey) return@launch
                songCheckStartedFor = checkKey
                val replaced = LyricsTiming.recheckDownloaded(
                    context,
                    Song(
                        videoId = request.videoId,
                        title = request.title,
                        artist = request.artist,
                        thumbnailUrl = null,
                        isVideo = false,
                        localUri = request.localUri,
                    ),
                    lines,
                    providerUnknown = !request.fileProviderKnown,
                    title = request.title,
                    artist = request.artist,
                    durationMs = request.durationMs,
                    album = request.album,
                    alternatives = request.alternatives,
                    artistKnown = request.artistKnown,
                    artistAliases = request.artistAliases,
                )
                if (replaced != null) {
                    // The re-check found a better sheet and wrote it into the
                    // file. Show those lines now, with the sync just saved
                    // under their key, rather than the old words the job began
                    // with. A new timing job also makes the provider label
                    // name the source the fresh sheet came from.
                    fileLyricsLabel = com.music.bitchord.data.lyrics.FileLyricsOrigin.label(
                        replaced.source.label,
                        request.videoId,
                    )
                    currentLyricsRequest = currentLyricsRequest?.copy(fileProviderKnown = true)
                    startTiming(
                        replaced.lines,
                        adjustable = true,
                        searchedAs = null,
                        cancelAi = false,
                        measure = false,
                        measureNow = false,
                        videoOwn = false,
                        downloadRecheck = false,
                    )
                    return@launch
                }
                refreshTiming(generation, lines)
                return@launch
            }
            if (request.isVideo || request.fileTimingForeign) {
                if (!adjustable || !measure || !measureNow) return@launch
                // AI after the free measurement, not alongside it: each request
                // uploads the whole song (twice — Deepgram, then Gemini), and the
                // measurement times most covers on its own. A few videos still ask
                // alongside it, so how late each engine runs keeps being learned.
                // Each video is sampled at most once ever, so a replay or a lyrics
                // re-delivery does not buy the same sample again.
                var calibration = LyricsTiming.calibrationSample(request.videoId) &&
                    AppSettings.aiTimingAhead.value &&
                    (LyricsTranslation.geminiKeyUsable || com.music.bitchord.data.lyrics.Deepgram.usable) &&
                    LyricsTiming.wantsAi(context, request.videoId, lines)
                if (calibration && !LyricsTiming.claimCalibrationSample(context, request.videoId)) {
                    calibration = false
                }
                if (calibration) launch { aiAhead(context, request.videoId, lines) }
                // The record is searched for under every name the lyrics know:
                // what they were found as first, then the video's own readings.
                val names = buildList {
                    // A cover that names the original's artist is searched under
                    // that first; one that doesn't, under what its lyrics were found as.
                    if (request.artistKnown) add(request.title to request.artist)
                    // Found under the singer's name is no lead to the original.
                    searchedAs?.takeIf { request.artistKnown || it.second != request.artist }?.let(::add)
                    // A cover that never names the original: its title alone.
                    if (!request.artistKnown) add(request.title to "")
                    add(request.title to request.artist)
                    addAll(request.alternatives)
                }.distinct()
                val video = Song(
                    videoId = request.videoId,
                    title = request.title,
                    artist = request.artist,
                    thumbnailUrl = null,
                    isVideo = true,
                    // A file's lyrics timed for another recording are measured
                    // against the file that is actually playing.
                    localUri = request.localUri.takeIf { request.fileTimingForeign },
                )
                var attempts = 0
                val giveUpAt = System.currentTimeMillis() + MEASURE_WINDOW_MS
                while (attempts < MAX_MEASURE_ATTEMPTS && System.currentTimeMillis() < giveUpAt &&
                    !LyricsTiming.measured(context, request.videoId)
                ) {
                    if (LyricsTiming.worthMeasuring(context, request.videoId)) {
                        attempts++
                        LyricsTiming.measure(context, video, names, request.durationMs)
                        refreshTiming(generation, lines)
                        autoAiIfNeeded(generation, lines)
                        if (LyricsTiming.measured(context, request.videoId)) break
                    }
                    delay(REMEASURE_CHECK_MS)
                }
                if (!calibration && !LyricsTiming.measuredTiming(context, request.videoId)) {
                    aiAhead(context, request.videoId, lines)
                    refreshTiming(generation, lines)
                }
                autoAiIfNeeded(generation, lines)
                return@launch
            }
            // A song version plays the record itself, so there is no other
            // recording to measure against; check the provider's times directly
            // against the words heard, once per playback, only when the song is
            // one a check can help (not the video's own captions, not already
            // verified, English and not instrumental, timed) and the phone is
            // not saving power. [refreshTiming] then shows a kept answer.
            // [cancelAi] false is the AI button taking its answer back: the
            // listener asked for it to go, so the automatic check must not
            // buy it again on the way out.
            if (!adjustable || videoOwn || !cancelAi || substituted) return@launch
            val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
            if (!LyricsAiGuard.songTimingWanted(
                    isVideo = request.isVideo,
                    timed = timed,
                    sungLines = sung.size,
                    title = request.title,
                    artist = request.artist,
                    texts = sung.map { it.value.text },
                )
            ) return@launch
            if (!AppSettings.heavyWorkAllowed || !AppSettings.aiTimingAhead.value ||
                !com.music.bitchord.data.lyrics.Deepgram.usable
            ) return@launch
            // Missing audio is not kept on disk (the next playback may have
            // it), so this session keeps it to one try per audio source rather
            // than one per lyrics delivery. A file-to-stream swap is a new
            // source and is checked again.
            val checkKey = if (request.isVideo) {
                request.videoId
            } else {
                LyricsTiming.audioSourceKey(request.videoId, request.localUri != null)
            }
            if (songCheckStartedFor == checkKey) return@launch
            songCheckStartedFor = checkKey
            LyricsTiming.verifySong(
                context,
                Song(
                    videoId = request.videoId,
                    title = request.title,
                    artist = request.artist,
                    thumbnailUrl = null,
                    isVideo = false,
                    localUri = request.localUri,
                ),
                lines,
            )
            refreshTiming(generation, lines)
        }
    }

    private var earlyMeasureJob: Job? = null

    /**
     * Starts measuring a cover against its original as the track starts,
     * rather than once its lyrics are in: the measurement needs only the
     * names, and is kept per video for whichever lyrics turn up. Only for a
     * cover that names its original's artist — one that doesn't is searched
     * better under the name its lyrics are found as, so it waits for them.
     */
    private fun startEarlyMeasure(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        localUri: String?,
        isVideo: Boolean,
        alternatives: List<Pair<String, String>>,
        artistKnown: Boolean,
    ) {
        earlyMeasureJob?.cancel()
        if (!isVideo || !artistKnown || localUri != null || durationMs <= 0L) return
        val context = app
        val video = Song(videoId = videoId, title = title, artist = artist, thumbnailUrl = null, isVideo = true)
        val names = (listOf(title to artist) + alternatives).distinct()
        earlyMeasureJob = scope.launch {
            if (LyricsTiming.worthMeasuring(context, videoId)) {
                LyricsTiming.measure(context, video, names, durationMs)
            }
        }
    }

    /** Puts what [LyricsTiming] knows for the current video onto [lines] and the line under them. */
    private suspend fun refreshTiming(generation: Long, lines: List<LyricLine>) {
        val request = currentLyricsRequest ?: return
        // A substituted file has a different clock from the YouTube upload the
        // saved measurement/AI answer was made against. Checked here as well as
        // at [startTiming] because the stream may be chosen after the lyrics
        // were first shown.
        val substituted = !request.isVideo && StreamChoice.isSubstitute(request.videoId)
        val baseAllowed = timingBaseAllowed && !substituted
        val audioSource = if (request.isVideo) {
            null
        } else {
            LyricsTiming.audioSourceKey(request.videoId, request.localUri != null)
        }
        val timing = LyricsTiming.timing(
            app,
            request.videoId,
            lines,
            baseAllowed,
            measuredAllowed = baseAllowed && timingMeasureAllowed,
            isVideo = request.isVideo,
            audioSource = audioSource,
        )
        if (generation != lyricsGeneration || timingLines !== lines) return
        // Every change to this video's timing lands here, its own included;
        // the lines are only rebuilt when what applies actually changed.
        var shown = _lyrics.value
        if (timing != appliedTiming || _lyrics.value == null) {
            appliedTiming = timing
            shown = if (timing == null) lines else LyricsTiming.apply(lines, timing)
            _lyrics.value = shown
        }
        val base = timing?.base
        val note = if (timingBaseAllowed && timingMeasureAllowed) LyricsTiming.note(app, request.videoId) else null
        if (generation != lyricsGeneration || timingLines !== lines) return
        val shiftMs = base?.let { adjustment ->
            LyricsTiming.shownShiftMs(lines, adjustment) - if (adjustment.byAi) (timing?.aiBiasMs ?: 0L) else 0L
        }
        val matchPercent = (base as? LyricsTiming.Adjustment.Mapped)?.matchPercent
        val manualMs = timing?.manualMs ?: 0L
        val baseLabel = when {
            base == null -> "none"
            base.byAi -> "ai"
            else -> "measured"
        }
        val provider = selectedLyricsSource?.label
            ?: _lyricsSource.value?.label
            ?: fileLyricsLabel
            ?: if (shown?.isNotEmpty() == true) "file" else "none"
        val (storedSync, recheck) = LyricsTiming.syncDiagnostics(
            app,
            request.videoId,
            lines,
            audioSource,
            downloaded = request.localUri != null && !request.isVideo,
            providerUnknown = !request.fileProviderKnown,
        )
        TrackDiagnostics.noteLyricsSync(
            videoId = request.videoId,
            provider = provider,
            base = baseLabel,
            sync = TrackDiagnostics.syncLabel(baseLabel, manualMs),
            shiftMs = shiftMs,
            matchPercent = matchPercent,
            biasMs = timing?.aiBiasMs ?: 0L,
            manualOffsetMs = manualMs,
            video = request.isVideo,
            stored = storedSync,
            recheck = recheck,
        )
        // Sync-test mode only: the lines as they are now on screen, so a phone
        // scoring run can compare every displayed start with the audio.
        SyncTestMode.report(
            context = app,
            videoId = request.videoId,
            source = provider,
            base = baseLabel,
            shiftMs = shiftMs ?: 0L,
            matchPercent = matchPercent,
            lines = shown ?: lines,
        )
        _lyricsTiming.update {
            it.copy(
                shiftMs = shiftMs,
                byAi = base?.byAi == true,
                rate = (base as? LyricsTiming.Adjustment.Mapped)?.rate?.takeIf { rate -> rate != 1.0 },
                matchPercent = matchPercent,
                manualMs = manualMs,
                note = note,
                aiEngine = timing?.aiEngine,
                // Empty unless the answer showing is the transcript's, and
                // already shifted onto the same clock as [LyricsTiming.apply].
                heardWords = timing?.heardWords.orEmpty(),
            )
        }
    }

    private suspend fun aiAhead(context: Application, videoId: String, lines: List<LyricLine>) {
        // Automatic only — the manual button calls [runAiTiming] directly and
        // still works while the phone saves power.
        if (!AppSettings.heavyWorkAllowed) return
        if (!AppSettings.aiTimingAhead.value) return
        if (!LyricsTranslation.geminiKeyUsable && !com.music.bitchord.data.lyrics.Deepgram.usable) return
        if (!LyricsTiming.wantsAi(context, videoId, lines)) return
        val request = currentLyricsRequest
        runCatching {
            LyricsTiming.placeWithAi(
                context,
                videoId,
                lines,
                automatic = true,
                title = request?.title.orEmpty(),
                artist = request?.artist.orEmpty(),
                isVideo = request?.isVideo == true,
            )
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    /**
     * "가사 타이밍 자동 보정": when the free measurement is done with this video
     * and found nothing, Gemini is asked — once per video and lyrics.
     */
    private suspend fun autoAiIfNeeded(generation: Long, lines: List<LyricLine>) {
        // An upload's worth of work for a track already playing: low power
        // leaves it for a later playback on a phone that is not saving power.
        if (!AppSettings.heavyWorkAllowed) return
        // "AI 싱크 미리 맞추기" already asks the quick engines on its own; once the
        // measurement gives up it also gets Whisper's better ear.
        if (!AppSettings.autoLyricsTiming.value && !AppSettings.aiTimingAhead.value) return
        val request = currentLyricsRequest ?: return
        if (generation != lyricsGeneration || timingLines !== lines || !timingBaseAllowed || !request.isVideo) return
        if (!timingMeasureAllowed) return
        if (aiTimingJob?.isActive == true) return
        if (!LyricsTranslation.geminiKeyUsable && !com.music.bitchord.data.lyrics.Deepgram.usable) return
        val context = app
        if (!LyricsTiming.measurementGaveUp(context, request.videoId)) return
        // One policy decides what is left to spend: an accepted answer, an
        // engine already paid for these lyrics, or Whisper once it is due.
        if (!LyricsTiming.wantsAi(context, request.videoId, lines)) return
        runAiTiming(generation, lines, automatic = true)
    }

    private fun runAiTiming(generation: Long, lines: List<LyricLine>, automatic: Boolean) {
        val request = currentLyricsRequest ?: return
        val context = app
        _lyricsTiming.update { it.copy(aiRunning = true, aiFailed = false) }
        aiTimingJob = scope.launch {
            val adjustment = try {
                LyricsTiming.placeWithAi(
                    context,
                    request.videoId,
                    lines,
                    automatic,
                    title = request.title,
                    artist = request.artist,
                    isVideo = request.isVideo,
                    audioSource = if (request.isVideo) {
                        null
                    } else {
                        LyricsTiming.audioSourceKey(request.videoId, request.localUri != null)
                    },
                )
            } finally {
                if (generation == lyricsGeneration && timingLines === lines) {
                    _lyricsTiming.update { it.copy(aiRunning = false) }
                }
            }
            if (generation != lyricsGeneration || timingLines !== lines) return@launch
            if (adjustment == null) {
                // Asked for and not given: say so. Tried on its own and not
                // given: the measurement's answer (none) simply stands.
                if (!automatic) _lyricsTiming.update { it.copy(aiFailed = true) }
            } else {
                refreshTiming(generation, lines)
            }
        }
    }

    /**
     * The AI timing button: asks Gemini to place the lines in this video, or —
     * when its answer is what is showing — takes it back, leaving the
     * automatic measurement (if any) in place.
     */
    fun toggleAiLyricsTiming() {
        val request = currentLyricsRequest ?: return
        val lines = timingLines ?: return
        val state = _lyricsTiming.value
        if (!state.adjustable || state.aiRunning) return
        val generation = lyricsGeneration
        val context = app
        aiTimingJob?.cancel()
        if (state.byAi) {
            aiTimingJob = scope.launch {
                LyricsTiming.clearAi(context, request.videoId)
                if (generation != lyricsGeneration || timingLines !== lines) return@launch
                startTiming(
                    lines,
                    adjustable = true,
                    searchedAs = rawLyricsResult?.searchedAs,
                    cancelAi = false,
                    measure = timingMeasureAllowed,
                    measureNow = request.localUri == null,
                    videoOwn = _lyricsTiming.value.videoOwn,
                )
            }
            return
        }
        runAiTiming(generation, lines, automatic = false)
    }

    /**
     * The sync drawer's "this song" correction, in ms (positive shows the
     * lyrics later), kept for this video and applied on top of any other
     * timing — here and on the lock screen.
     */
    fun setSongLyricsOffset(ms: Long) {
        val request = currentLyricsRequest ?: return
        val lines = timingLines ?: return
        val generation = lyricsGeneration
        val value = ms.coerceIn(-LyricsTiming.MAX_MANUAL_MS, LyricsTiming.MAX_MANUAL_MS)
        if (_lyricsTiming.value.manualMs != value) {
            TrackLog.i("BitChord", "cmd: lyrics-offset ${value}ms from ui", about = request.videoId)
        }
        // Shown at once, so a run of taps on "+" counts from each other.
        _lyricsTiming.update { it.copy(manualMs = value) }
        scope.launch {
            LyricsTiming.setManual(app, request.videoId, value)
            refreshTiming(generation, lines)
        }
    }
}
