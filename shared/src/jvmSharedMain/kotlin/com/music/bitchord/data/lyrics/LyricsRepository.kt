package com.music.bitchord.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the player gets its lyrics.
 *
 * Sources tried in [order] — the user's own priority list in Settings,
 * defaulting to [LyricsSource.offered]; a [hidden][LyricsSource.hidden] one is
 * never asked, whatever the saved settings say:
 *
 *  - [LrcRed] — Apple Music TTML filed by ISRC, and the catalogue
 *    [BiniLyrics] itself answers from.
 *  - [BetterLyrics], [PaxSenix] and [BiniLyrics] — Apple Music TTML,
 *    per-syllable, from three independent hosts so one having a bad day
 *    doesn't cost the timing.
 *  - [LyricsPlus] — the YouLy+ backend; finest timing of the lot, flakiest hosting.
 *  - [SimpMusicLyrics] — keyed on the video id, so it can't fetch the wrong edit.
 *  - [Unison] — contributed rather than licensed; thin, and occasionally the
 *    only place a track exists.
 *  - [LrcLib], [Musixmatch], [KuGou] — line-synced only, but between them
 *    almost always up, and [KuGou] carries a lot that the others don't.
 *
 * Every enabled source is asked *at the same time*, but their answers are
 * taken in [order]: the loop awaits them one at a time in that sequence, so a
 * lower-priority source finishing first never preempts one still pending
 * ahead of it. Asked one after another instead, a miss on each source would
 * cost its own round trip before the next was even tried, and a track with no
 * lyrics anywhere would spend the best part of a minute finding that out with
 * eight of them. Run together, a miss costs whatever the slowest one needed
 * to still be waited on took.
 *
 * A word-timed answer wins outright. Failing that, a line-timed one is taken
 * from the highest-priority source that had it — better a whole line lighting
 * up in sync than the right animation on lyrics that don't exist.
 *
 * ### Matching on the recording
 *
 * Everything above matches a *name*, and a name is ambiguous in a way that
 * matters: a single and its album cut share a title, an artist and very nearly
 * a length, and routinely differ in the words. An ISRC names one recording and
 * settles it. Two sources accept one — [BiniLyrics] and [LyricsPlus] — and
 * [BiniLyrics] is also the one that hands them out, reporting the ISRC of
 * whatever its own search matched.
 *
 * So the recording is settled *before* anybody is asked for words: one small
 * search against [BiniLyrics], whose answer is a name every other source can
 * use. It costs a round trip at the head of the lookup, which is why it is
 * capped at [IDENTIFY_TIMEOUT_MS] and why its result is kept against the video
 * id — a track asked about twice pays for this once. A downloaded or local file
 * can skip it entirely: its own tags name the recording, which is what the
 * [isrc] parameter is for.
 *
 * Only run when the user has [LyricsSource.BINI_LYRICS] enabled. It is a
 * request to a third party like any other, and a source somebody has turned off
 * is a source this app does not contact — not even for something it would only
 * use to help the sources they left on.
 */
object LyricsRepository {

    /** Lyrics, and which source they turned out to come from. */
    data class Result(
        val source: LyricsSource,
        val lines: List<LyricLine>,
        /**
         * The (title, artist) the search that found these was made under —
         * the original's name for a cover, or the name an original-language
         * retry found. Their timings belong to that recording.
         */
        val searchedAs: Pair<String, String>? = null,
    )

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off. [order] is tried first-to-last; a source
     * missing from it (an upgrade that added one after the order was last
     * saved) falls in after everything named, in [LyricsSource]'s own order.
     *
     * [prioritizeSyllableSync] decides what happens once *something* has come
     * back: off, the highest-priority source's own answer is taken as-is,
     * word-synced or not — priority is priority, and second-guessing it with
     * more network calls after it has already answered is not what "first"
     * was supposed to mean. On, a merely line-synced answer is kept only as a
     * fallback, and the search keeps going through the rest of [order] for a
     * word-synced one, taking the top-priority source that has one.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = false,
        isrc: String? = null,
        /** Called from the provider job itself, including for lazily-started providers. */
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        /**
         * Reports a completed provider attempt. A null result is a miss, and
         * [LyricsFailure] says why — network, timeout, blocked or genuinely
         * absent. A cancelled race loser is deliberately not reported as one.
         */
        onSourceResult: ((LyricsSource, Result?, LyricsFailure?) -> Unit)? = null,
        /** Lets callers turn a cancelled race loser back into "not fetched". */
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
        /**
         * A video upload rather than a catalogue track: its own captions are
         * the likeliest lyrics to fit it, so [LyricsSource.YOUTUBE_TRANSCRIPT]
         * is asked first, where the listener has it switched on.
         */
        isVideo: Boolean = false,
        /**
         * Other (title, artist) readings to try, in order, when [title] and
         * [artist] find nothing — a cover whose title could be read two ways.
         */
        alternatives: List<Pair<String, String>> = emptyList(),
        /**
         * False when [artist] is only a cover's singer — the title never named
         * the original's artist. Failing everything else, the song is then
         * looked for by its title alone (see [OriginalNames.fromTitle]).
         */
        artistKnown: Boolean = true,
        /**
         * With nothing found anywhere, have [transcriber] write the lyrics
         * down off the video. Only for what is playing or about to — never
         * for one provider tried by hand, or a download filling its tags.
         */
        transcribe: Boolean = false,
    ): Result? {
        val found = lookUpAll(
            videoId, title, artist, durationMs, album, sources, order, prioritizeSyllableSync, isrc,
            onSourceStarted, onSourceResult, onSourceCancelled, isVideo, alternatives, artistKnown,
        )
        // An upload labelled an instrumental has no words to write down: skip
        // the paid Gemini request before it is made.
        if (found != null || !transcribe || sources.isEmpty() || LyricsAiGuard.isInstrumentalTitle(title)) return found
        return transcribed(videoId, durationMs)?.also { heard ->
            // Kept like any answer, so the next play — today or after a
            // restart — shows it without listening again.
            val key = questionKey(
                videoId, title, artist, prioritizeSyllableSync, isrc, isVideo, artistKnown, alternatives, order, sources,
            )
            answers.put(key, Answer(heard))
            kotlinx.coroutines.withContext(Dispatchers.IO) { LyricsDiskCache.write(key, heard) }
        }
    }

    private fun questionKey(
        videoId: String,
        title: String,
        artist: String,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
        isVideo: Boolean,
        artistKnown: Boolean,
        alternatives: List<Pair<String, String>>,
        order: List<LyricsSource>,
        sources: Set<LyricsSource>,
    ): String = buildString {
        append(videoId).append('\u0000').append(title).append('\u0000').append(artist)
        append('\u0000').append(prioritizeSyllableSync).append('\u0000').append(isrc.orEmpty())
        append('\u0000').append(isVideo).append('\u0000').append(artistKnown)
        alternatives.forEach { (t, a) -> append('\u0001').append(t).append('\u0000').append(a) }
        order.forEach { append('\u0002').append(it.name) }
        sources.map { it.name }.sorted().forEach { append('\u0003').append(it) }
    }

    private suspend fun lookUpAll(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
        onSourceStarted: ((LyricsSource) -> Unit)?,
        onSourceResult: ((LyricsSource, Result?, LyricsFailure?) -> Unit)?,
        onSourceCancelled: ((LyricsSource) -> Unit)?,
        isVideo: Boolean,
        alternatives: List<Pair<String, String>>,
        artistKnown: Boolean,
    ): Result? {
        // The whole answer — retries and original-language names included —
        // kept against the whole question, so the lookup the next-track
        // prefetch made is the one the player gets, rather than the player
        // walking the same chain of misses again. A lookup still running for
        // the same question is joined rather than started twice.
        val key = questionKey(
            videoId, title, artist, prioritizeSyllableSync, isrc, isVideo, artistKnown, alternatives, order, sources,
        )
        answers.get(key)?.let { kept ->
            kept.result?.let { onSourceResult?.invoke(it.source, it, null) }
            return kept.result
        }
        // Heard before, on any earlier day: on the device already. Shown at
        // once, and looked for again in the background only while something
        // better than what is kept could still turn up.
        if (durationMs > 0L) {
            val kept = kotlinx.coroutines.withContext(Dispatchers.IO) { LyricsDiskCache.read(key) }
            val keptResult = kept?.result
            if (kept != null && keptResult != null) {
                answers.put(key, Answer(keptResult))
                onSourceResult?.invoke(keptResult.source, keptResult, null)
                if (LyricsDiskCache.worthCheckingAgain(kept)) {
                    checkAgain(key, videoId, keptResult) {
                        search(
                            videoId, title, artist, durationMs, album, sources, order, prioritizeSyllableSync, isrc,
                            null, null, null, isVideo, alternatives, artistKnown,
                        )
                    }
                }
                return keptResult
            }
        }
        if (durationMs <= 0L) {
            return search(
                videoId, title, artist, durationMs, album, sources, order, prioritizeSyllableSync, isrc,
                onSourceStarted, onSourceResult, onSourceCancelled, isVideo, alternatives, artistKnown,
            )
        }
        // Everyone asking hears every provider as it starts and answers — not
        // only whoever asked first. The playback service (for the lock screen)
        // and the next-song preparation ask without listening, and usually
        // ask first; the lyrics screen joining their lookup used to hear
        // nothing until the whole of it was over, and so could not put up the
        // first usable lines while slower sources were still being waited for.
        val listener = Listener(onSourceStarted, onSourceResult, onSourceCancelled)
        val fresh = Lookup()
        val lookup = inFlight.putIfAbsent(key, fresh) ?: fresh
        lookup.join(listener)
        try {
            if (lookup === fresh) {
                val started = background.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    search(
                        videoId, title, artist, durationMs, album, sources, order, prioritizeSyllableSync, isrc,
                        lookup::started, lookup::answered, lookup::cancelled, isVideo, alternatives, artistKnown,
                    ).also {
                        answers.put(key, Answer(it))
                        // Only answers go on disk: "nothing found" may only
                        // mean the phone was offline.
                        if (it != null) LyricsDiskCache.write(key, it)
                    }
                }
                started.invokeOnCompletion { inFlight.remove(key, lookup) }
                lookup.start(started)
                started.start()
            }
            val result = lookup.await()
            // Joined at the very end, after the last provider had reported:
            // the answer itself is still news to this caller.
            if (!listener.heard && result != null) onSourceResult?.invoke(result.source, result, null)
            return result
        } finally {
            lookup.leave(listener)
        }
    }

    /**
     * Lyrics a background check found better than the ones shown for a video
     * — word-synced where only lines were kept, say: (video id, lyrics).
     */
    val upgrades: kotlinx.coroutines.flow.SharedFlow<Pair<String, Result>> get() = _upgrades
    private val _upgrades = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Result>>(extraBufferCapacity = 4)

    /** Keys checked again this session: once is enough. */
    private val checkedAgain = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun checkAgain(key: String, videoId: String, kept: Result, search: suspend () -> Result?) {
        if (!checkedAgain.add(key)) return
        background.launch {
            val found = try {
                search()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // Anything a source has beats what Gemini heard, timed as well or not.
            val replacesHeard = kept.source == LyricsSource.GEMINI_TRANSCRIPT &&
                found != null && found.source != LyricsSource.GEMINI_TRANSCRIPT
            if (found != null && (replacesHeard || LyricsDiskCache.rank(found) > LyricsDiskCache.rank(kept))) {
                answers.put(key, Answer(found))
                LyricsDiskCache.write(key, found)
                _upgrades.tryEmit(videoId to found)
            } else {
                // Nothing better: not asked again for a while.
                LyricsDiskCache.touchChecked(key)
            }
        }
    }

    /** One caller's provider callbacks. */
    private class Listener(
        val onStarted: ((LyricsSource) -> Unit)?,
        val onResult: ((LyricsSource, Result?, LyricsFailure?) -> Unit)?,
        val onCancelled: ((LyricsSource) -> Unit)?,
    ) {
        @Volatile var heard = false
    }

    /**
     * A lookup in progress, shared by everyone asking the same question: its
     * provider events go to each of them, and one joining late is first told
     * what has happened so far.
     */
    private class Lookup {
        private val job = kotlinx.coroutines.CompletableDeferred<Deferred<Result?>>()

        /** Hands over the search itself, once, right after the lookup is published. */
        fun start(search: Deferred<Result?>) {
            job.complete(search)
        }
        private val listeners = ArrayList<Listener>()
        private val running = LinkedHashSet<LyricsSource>()
        private val finished = LinkedHashMap<LyricsSource, ProviderAnswer>()

        fun join(listener: Listener) {
            val (wasRunning, wasFinished) = synchronized(this) {
                listeners += listener
                running.toList() to finished.toList()
            }
            wasRunning.forEach { listener.onStarted?.invoke(it) }
            wasFinished.forEach { (source, answer) ->
                listener.heard = true
                listener.onResult?.invoke(source, answer.result, answer.failure)
            }
        }

        fun leave(listener: Listener) = synchronized(this) { listeners -= listener }

        fun started(source: LyricsSource) {
            val now = synchronized(this) {
                running += source
                listeners.toList()
            }
            now.forEach { it.onStarted?.invoke(source) }
        }

        fun answered(source: LyricsSource, result: Result?, failure: LyricsFailure?) {
            val now = synchronized(this) {
                running -= source
                finished[source] = ProviderAnswer(result, failure)
                listeners.toList()
            }
            now.forEach {
                it.heard = true
                it.onResult?.invoke(source, result, failure)
            }
        }

        fun cancelled(source: LyricsSource) {
            val now = synchronized(this) {
                running -= source
                listeners.toList()
            }
            now.forEach { it.onCancelled?.invoke(source) }
        }

        suspend fun await(): Result? = job.await().await()
    }

    /** A whole lookup's answer, a miss included, and when it was made. */
    private class Answer(val result: Result?, val at: Long = System.currentTimeMillis())

    /** One provider's finished attempt: its answer, or why there is none. */
    private data class ProviderAnswer(val result: Result?, val failure: LyricsFailure?)

    /**
     * Answers by question. A miss is kept for [MISS_KEPT_MS] only — a source
     * may have been down — while a hit lasts until it is pushed out.
     */
    private val answers = object {
        private val entries = object : LinkedHashMap<String, Answer>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Answer>) = size > 40
        }

        @Synchronized fun get(key: String): Answer? {
            val answer = entries[key] ?: return null
            if (answer.result == null && System.currentTimeMillis() - answer.at > MISS_KEPT_MS) {
                entries.remove(key)
                return null
            }
            return answer
        }

        @Synchronized fun put(key: String, answer: Answer) {
            entries[key] = answer
        }
    }

    private const val MISS_KEPT_MS = 3L * 60L * 1000L

    private val inFlight = ConcurrentHashMap<String, Lookup>()

    /**
     * Where whole lookups run: not the asking coroutine's, so a listener
     * skipping a track doesn't throw away a search the prefetch (or going
     * back) would have used. Every source inside it is time-boxed, so nothing
     * here runs for long.
     */
    private val background = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * Set by the app: the lyrics heard off a video when nothing else has any
     * (Gemini, see [LyricsTranslation.transcribeVideo]) — null when that is
     * off. Lines, an empty list for "nothing is sung", or null for no answer.
     */
    @Volatile
    var transcriber: (suspend (videoId: String, durationMs: Long) -> List<LyricLine>?)? = null

    /**
     * Videos the app is having lyrics written down for right now — filled in
     * by [transcriber]'s implementation as it starts and stops listening.
     *
     * The player shows a skeleton from the moment a lookup starts, and a video
     * with no lyrics anywhere spends another minute in Gemini before anything
     * comes back. Without this the panel kept drawing placeholder lines for
     * all of it and looked stuck; the UI reads this to say what is actually
     * happening. A set rather than a Boolean because a prefetch and the
     * playing track can be listened to at once, and only the track on screen
     * should be reported.
     */
    private val _transcribing = MutableStateFlow<Set<String>>(emptySet())
    val transcribing: StateFlow<Set<String>> = _transcribing.asStateFlow()

    /** Called by the app's [transcriber] as it starts and stops writing [videoId] down. */
    fun markTranscribing(videoId: String, running: Boolean) {
        _transcribing.update { if (running) it + videoId else it - videoId }
    }

    /** Transcriptions running, by video: the player, the lock screen and the next-song preparation share one. */
    private val transcriptionRuns = ConcurrentHashMap<String, Deferred<Result?>>()

    /** What was heard this session, by video — kept on disk too, under each question that led to it. */
    private val heard = object : LinkedHashMap<String, Result>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Result>) = size > 32
    }

    /**
     * What [transcriber] hears in [videoId], as an answer; null for nothing.
     * Run once per video however many ask, in the background so a skip
     * doesn't throw away a minute of listening the next play would want.
     */
    private suspend fun transcribed(videoId: String, durationMs: Long): Result? {
        val ask = transcriber ?: return null
        if (videoId.isBlank() || videoId.contains(':') || durationMs <= 0L) return null
        synchronized(heard) { heard[videoId] }?.let { return it }
        val fresh = background.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val lines = try {
                ask(videoId, durationMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            lines?.takeIf { it.isNotEmpty() }?.let { Result(LyricsSource.GEMINI_TRANSCRIPT, it) }
                ?.also { synchronized(heard) { heard[videoId] = it } }
        }
        val running = transcriptionRuns.putIfAbsent(videoId, fresh) ?: fresh
        if (running === fresh) {
            fresh.invokeOnCompletion { transcriptionRuns.remove(videoId, fresh) }
            fresh.start()
        }
        return running.await()
    }

    private suspend fun search(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
        onSourceStarted: ((LyricsSource) -> Unit)?,
        onSourceResult: ((LyricsSource, Result?, LyricsFailure?) -> Unit)?,
        onSourceCancelled: ((LyricsSource) -> Unit)?,
        isVideo: Boolean,
        alternatives: List<Pair<String, String>>,
        artistKnown: Boolean,
    ): Result? = coroutineScope {
        // The original-language name is one cheap search away and needed the
        // moment the first round comes back empty, so it is asked for now,
        // alongside that round, rather than after it.
        val nameAhead: Deferred<OriginalNames.Name?>? = when {
            !artistKnown -> async { runCatching { OriginalNames.fromTitle(title, durationMs) }.getOrNull() }
            OriginalNames.worthTrying(title, artist) ->
                async { runCatching { OriginalNames.fromYouTubeMusic(title, artist, durationMs) }.getOrNull() }
            else -> null
        }
        try {
            searchRounds(
                videoId, title, artist, durationMs, album, sources, order, prioritizeSyllableSync, isrc,
                onSourceStarted, onSourceResult, onSourceCancelled, isVideo, alternatives, artistKnown, nameAhead,
            )
        } finally {
            nameAhead?.cancel()
        }
    }

    private suspend fun searchRounds(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
        onSourceStarted: ((LyricsSource) -> Unit)?,
        onSourceResult: ((LyricsSource, Result?, LyricsFailure?) -> Unit)?,
        onSourceCancelled: ((LyricsSource) -> Unit)?,
        isVideo: Boolean,
        alternatives: List<Pair<String, String>>,
        artistKnown: Boolean,
        nameAhead: Deferred<OriginalNames.Name?>?,
    ): Result? {
        val ordered = if (isVideo && LyricsSource.YOUTUBE_TRANSCRIPT in sources) {
            listOf(LyricsSource.YOUTUBE_TRANSCRIPT) + order.filterNot { it == LyricsSource.YOUTUBE_TRANSCRIPT }
        } else {
            order
        }
        val first = lyricsFor(
            videoId, title, artist, durationMs, album, sources, ordered, prioritizeSyllableSync, isrc,
            onSourceStarted, onSourceResult, onSourceCancelled,
        )
        if (first != null) return first
        // The sources keyed on the video rather than on a name were already
        // asked: they do not change with the name the search is made under.
        val byName = sources - KEYED_ON_VIDEO
        if (byName.isEmpty()) return null
        val tried = mutableSetOf(title to artist)
        suspend fun retry(name: Pair<String, String>): Result? {
            if (!tried.add(name)) return null
            return lyricsFor(
                videoId, name.first, name.second, durationMs, album,
                byName, ordered, prioritizeSyllableSync, isrc,
                onSourceStarted, onSourceResult, onSourceCancelled,
            )
        }
        for (alternative in alternatives) {
            retry(alternative)?.let { return it }
        }
        // A cover with no original artist named: the singer was never going to
        // find it. Find the song by its title, then search under its real name.
        if (!artistKnown) {
            nameAhead?.await()?.let { name ->
                retry(name.title to name.artist)?.let { return it }
            }
            return null
        }
        // Still nothing, and the name is written in Hangul: very often a Korean
        // spelling of a Japanese, Chinese or English song, which the catalogues
        // file under its own name. Ask for that name and search once more —
        // YouTube Music first, Gemini only if that doesn't do it.
        if (!OriginalNames.worthTrying(title, artist)) return null
        nameAhead?.await()?.let { name ->
            retry(name.title to name.artist)?.let { return it }
        }
        OriginalNames.fromGemini(title, artist)?.let { name ->
            retry(name.title to name.artist)?.let { return it }
        }
        return null
    }

    /** Sources that look a track up by its video id, whatever it is called. */
    private val KEYED_ON_VIDEO = setOf(
        LyricsSource.YOUTUBE_TRANSCRIPT,
        LyricsSource.YOUTUBE_MUSIC,
        LyricsSource.SIMP_MUSIC,
    )

    private suspend fun lyricsFor(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
        onSourceStarted: ((LyricsSource) -> Unit)?,
        onSourceResult: ((LyricsSource, Result?, LyricsFailure?) -> Unit)?,
        onSourceCancelled: ((LyricsSource) -> Unit)?,
    ): Result? {
        // The last few answers, so the next track's lyrics looked up ahead of
        // time (see LyricsPrefetch) are the very lines the player then shows —
        // the same text is what lets the translation fetched with them be found.
        val memoKey = buildString {
            append(videoId).append('\u0000').append(title).append('\u0000').append(artist)
            append('\u0000').append(prioritizeSyllableSync)
            append('\u0000').append(isrc.orEmpty())
            order.forEach { append('\u0000').append(it.name) }
            append('\u0001')
            sources.map { it.name }.sorted().forEach { append('\u0000').append(it) }
        }
        synchronized(recent) { recent[memoKey] }?.let { hit ->
            onSourceResult?.invoke(hit.source, hit, null)
            return hit
        }
        val found = lookUp(
            videoId, title, artist, durationMs, album, sources, order, prioritizeSyllableSync, isrc,
            onSourceStarted, onSourceResult, onSourceCancelled,
        )?.copy(searchedAs = title to artist)
        // Only an answer matched against a known length is worth reusing: one
        // made without it may be a looser match than the player would make.
        if (found != null && durationMs > 0L) synchronized(recent) { recent[memoKey] = found }
        return found
    }

    /** The sources that can use a recording id, and so wait for [identify]. */
    private val USES_RECORDING = setOf(LyricsSource.LRC_RED, LyricsSource.LYRICS_PLUS, LyricsSource.BINI_LYRICS)

    /**
     * The most a source may take, all its requests together. One request is
     * already capped at six seconds; a source that chains several (search,
     * then details, then the file) is held to this as a whole. PaxSenix's
     * keyed routes wait on an upstream catalogue and get longer.
     */
    private fun budgetMs(source: LyricsSource): Long = when (source) {
        LyricsSource.PAXSENIX_SPOTIFY, LyricsSource.PAXSENIX_MUSIXMATCH -> 18_000L
        else -> 9_000L
    }

    /** Where provider work runs, so a stuck request never holds a lookup open. */
    private val detached = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** How long word-synced sources may keep a line-synced answer waiting. */
    private const val SYLLABLE_GRACE_MS = 8_000L

    /** How often the grace period looks over the sources still running. */
    private const val GRACE_POLL_MS = 80L

    private val recent = object : LinkedHashMap<String, Result>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Result>) = size > 32
    }

    private suspend fun lookUp(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
        onSourceStarted: ((LyricsSource) -> Unit)?,
        onSourceResult: ((LyricsSource, Result?, LyricsFailure?) -> Unit)?,
        onSourceCancelled: ((LyricsSource) -> Unit)?,
    ): Result? = coroutineScope {
        val sequence = (order + LyricsSource.offered)
            .distinct()
            .filter { it in sources && !it.hidden }

        // Every source but [SimpMusicLyrics] is asked for a name, and
        // YouTube's is not the name anyone catalogued. Cleaned once, here,
        // rather than by whichever source thought to do it for itself.
        val searchTitle = title.forLyricsSearch()
        val searchArtist = artist.artistForLyricsSearch()

        // Settled before anyone is asked for words, so every source that can
        // name the recording does. What the caller knows beats what we worked
        // out last time, and both beat asking again.
        val known = isrc?.takeIf { it.isNotBlank() } ?: isrcs[videoId]
        // Only the sources that can use a recording id wait for it; everyone
        // else starts at once instead of sitting out [IDENTIFY_TIMEOUT_MS].
        val identifying: Deferred<BiniLyrics.Hit?>? = if (known == null) {
            async(Dispatchers.IO) { identify(videoId, searchTitle, searchArtist, durationMs, album, sequence) }
        } else {
            null
        }
        val documents = SharedDocuments(this)

        // Genius is a plain text web scraper. To preserve bandwidth and avoid rate-limiting,
        // it starts lazily and is only contacted if all higher-priority synced sources miss.
        val racing: List<Pair<LyricsSource, Deferred<Result?>>> = sequence.map { source ->
            val startMode = if (source == LyricsSource.GENIUS) kotlinx.coroutines.CoroutineStart.LAZY else kotlinx.coroutines.CoroutineStart.DEFAULT
            source to async(Dispatchers.IO, start = startMode) {
                onSourceStarted?.invoke(source)
                try {
                    val hit = if (source in USES_RECORDING) identifying?.await() else null
                    val recording = known ?: hit?.isrc?.takeIf { it.isNotBlank() }
                    // Run apart and waited on with a deadline: a provider making
                    // several blocking requests in a row can't be interrupted
                    // mid-request, so it is left to finish on its own instead of
                    // holding the whole lookup past [budgetMs].
                    val work = detached.async {
                        fetch(source, videoId, searchTitle, searchArtist, durationMs, album, recording, hit, documents::get)
                    }
                    val lines = try {
                        withTimeoutOrNull(budgetMs(source)) { work.await() }
                    } finally {
                        work.cancel()
                    }
                    val found = lines?.let { result(source, it) }
                    // A null body with a cancelled worker is the budget running
                    // out, not the provider saying it has nothing.
                    val failure = when {
                        found != null -> null
                        lines == null && work.isCancelled -> LyricsFailure.TIMEOUT
                        else -> LyricsFailure.NOT_FOUND
                    }
                    onSourceResult?.invoke(source, found, failure)
                    found
                } catch (cancelled: CancellationException) {
                    onSourceCancelled?.invoke(source)
                    throw cancelled
                } catch (error: Exception) {
                    onSourceResult?.invoke(source, null, LyricsFailure.from(error))
                    null
                }
            }
        }

        try {
            var fallback: Result? = null
            for ((index, entry) in racing.withIndex()) {
                val (source, job) = entry
                // With something in hand already, Genius (plain text) is not worth asking.
                if (fallback != null && source == LyricsSource.GENIUS) continue
                val found = runCatching { job.await() }.getOrNull() ?: continue
                if (found.lines.any { it.isWordSynced }) return@coroutineScope found
                val timed = found.lines.any { it.timeMs > 0 }
                if (!prioritizeSyllableSync && timed) return@coroutineScope found
                if (fallback == null || (timed && fallback.lines.none { it.timeMs > 0 })) fallback = found
                if (timed) {
                    // A line-synced answer is in hand. The sources still running
                    // get [SYLLABLE_GRACE_MS] between them to beat it with a
                    // word-synced one — all of them at once, so one slow server
                    // early in the order no longer ends the wait for a faster one
                    // further down that has already answered.
                    // Only sources that *can* answer word by word are worth
                    // the wait; a line-only one finishing later changes nothing.
                    val rest = racing.drop(index + 1)
                        .filter { it.first != LyricsSource.GENIUS && it.first.wordSynced }
                    return@coroutineScope wordSyncedWithin(rest, SYLLABLE_GRACE_MS) ?: fallback
                }
            }
            fallback
        } finally {
            // Whoever lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they are still running.
            racing.forEach { it.second.cancel() }
            identifying?.cancel()
            documents.cancel()
        }
    }

    /**
     * The first word-synced answer among [rest], in their order, waiting at
     * most [graceMs] for it. A source higher in the order that is still
     * running is waited for before a lower one that has answered is taken;
     * once time is up, the best that has answered wins.
     */
    private suspend fun wordSyncedWithin(
        rest: List<Pair<LyricsSource, Deferred<Result?>>>,
        graceMs: Long,
    ): Result? {
        if (rest.isEmpty()) return null
        val deadline = System.currentTimeMillis() + graceMs
        while (true) {
            val timeUp = System.currentTimeMillis() >= deadline
            var waitingAbove = false
            for ((_, job) in rest) {
                if (!job.isCompleted) {
                    waitingAbove = true
                    if (!timeUp) break
                    continue
                }
                val answer = if (job.isCancelled) null else runCatching { job.await() }.getOrNull()
                if (answer != null && answer.lines.any { it.isWordSynced }) {
                    if (!waitingAbove || timeUp) return answer
                }
            }
            if (!waitingAbove || timeUp) return null
            delay(GRACE_POLL_MS)
        }
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
        /** What [identify] already found, where it ran; saves a second search. */
        hit: BiniLyrics.Hit?,
        /** Fetches a document once per lookup, however many sources want it. */
        get: suspend (String) -> String?,
    ): List<LyricLine>? {
        val found = when (source) {
            LyricsSource.LRC_RED -> LrcRed.lyrics(title, artist, durationMs, isrc, get)
            LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
            LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)
            LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)
            LyricsSource.BINI_LYRICS ->
                (hit?.let { BiniLyrics.lyricsFor(it, get) }
                    ?: BiniLyrics.lyrics(title, artist, durationMs, album, isrc, get))
                    ?.also { remember(videoId, it.isrc) }
                    ?.lines
            LyricsSource.UNISON -> Unison.lyrics(title, artist, durationMs, album)
            LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)
            LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)
            LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(videoId)
            LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)
            LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)
            LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
            LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)
            LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)
            LyricsSource.GENIUS -> Genius.lyrics(title, artist)
            LyricsSource.VIBE -> VibeLyrics.lyrics(title, artist, durationMs)
            LyricsSource.BUGS -> BugsLyrics.lyrics(title, artist, durationMs)
            // Never raced: only ever the last resort of [search].
            LyricsSource.GEMINI_TRANSCRIPT -> null
        }
        return found
    }

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it. Done here rather
     * than in each parser because most of them write it as a bracket and only
     * [TtmlLyrics] knows it structurally — [withBackgroundVocals] leaves that
     * one's own split alone.
     */
    private fun result(source: LyricsSource, lines: List<LyricLine>) =
        Result(source, lines.withBackgroundVocals())

    /**
     * Longest the lookup will wait to find out which recording this is.
     *
     * Short on purpose. Knowing the recording makes every match better, but not
     * knowing it only puts things back where they were a release ago, and a
     * lyrics panel sitting empty because one host is having a slow morning is a
     * worse failure than a fuzzy match.
     */
    private const val IDENTIFY_TIMEOUT_MS = 2_500L

    /**
     * The one request made before the race: which recording is this?
     *
     * Skipped entirely when the source that answers it is switched off, and
     * given up on rather than waited out — see [IDENTIFY_TIMEOUT_MS]. What it
     * finds is remembered, so a track pays for this once rather than once per
     * lookup, and the hit is handed back so [BiniLyrics] need not search twice.
     */
    private suspend fun identify(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sequence: List<LyricsSource>,
    ): BiniLyrics.Hit? {
        if (LyricsSource.BINI_LYRICS !in sequence) return null
        val hit = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
            runCatching { BiniLyrics.identify(title, artist, durationMs, album) }.getOrNull()
        }
        if (hit == null) return null
        remember(videoId, hit.isrc)
        return hit
    }

    /** How many recordings to keep in hand; see [isrcs]. */
    private const val REMEMBERED = 100

    /**
     * The recording behind a video id, once something has worked it out.
     *
     * Bounded, least-recently-used, and in memory only. This is a shortcut,
     * not a store: losing it costs one fuzzy match, which is what every lookup
     * did before any of this, and keeping it on disk would mean keeping a
     * wrong answer on disk too.
     */
    private val isrcs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > REMEMBERED
        },
    )

    private fun remember(videoId: String, isrc: String?) {
        if (isrc.isNullOrBlank() || videoId.isEmpty()) return
        isrcs.put(videoId, isrc)
    }

    /**
     * Documents fetched during one lookup, by URL, so two sources after the
     * same file make one request between them. That is the ordinary case, not
     * an edge: [BiniLyrics]' search answers with an [LrcRed] URL, and [LrcRed],
     * handed the ISRC that search found, asks for exactly that URL too.
     *
     * Each download runs in the lookup's own scope rather than the asking
     * source's, so the source that started it losing the race doesn't take
     * the other one's copy down with it; [cancel] ends whatever is left.
     */
    private class SharedDocuments(private val scope: CoroutineScope) {
        private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

        suspend fun get(url: String): String? =
            inFlight.computeIfAbsent(url) { scope.async(Dispatchers.IO) { lyricsGet(url) } }.await()

        fun cancel() = inFlight.values.forEach { it.cancel() }
    }
}
