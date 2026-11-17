package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.GroqWhisper
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.AudioCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What a recording says, heard once and kept.
 *
 * The words a recording sings do not depend on which lyric sheet is on screen.
 * They used to be bought per sheet: one song could pay for its forty seconds,
 * its ninety, the refine's ninety and the longer listen, each decoded through
 * the one-at-a-time gate, and a sheet arriving a moment later threw all of it
 * away (11-06: 5 of 7 applied answers never reached the screen, "ai answer for
 * other lyrics"). Here the transcript belongs to the recording and its clock.
 * A later window, another sheet or a replay reads the file; only sound nobody
 * has heard yet costs a request.
 *
 * A stream is sent as the cache holds it ([VideoAudio.songStreamOpening]); a
 * file on the device, or a stream the server cannot read, is decoded as before.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object SongHearing {

    private const val TAG = "SongHearing"
    private const val DIR = "song-hearing"

    /** Files kept before the oldest go; a whole song is about 30 KB. */
    private const val FILES_KEPT = 800

    /**
     * A second copy of the request goes out after this. Deepgram's Whisper usually
     * answers ninety seconds in 5-15 s and now and then takes over a minute for
     * the same audio (11-06: 2 of 22 calls at 64-67 s, the next ones back at 8 s).
     */
    private const val HEDGE_AFTER_MS = 15_000L

    /** The same for a transcript past the opening, which nobody on screen is waiting for yet. */
    private const val HEDGE_AFTER_LONG_MS = 120_000L
    private const val OPENING_SECONDS = 120.0

    private const val FILE_PREFIX = "file|"
    private const val STREAM_PREFIX = "stream|"

    /** How much of the stream's words a file may start from: a verse or two to fit an offset to. */
    private const val FILE_READS_STREAM_SECONDS = 30.0

    /** A request with no reply goes again this many times, this far apart and further. */
    private const val MAX_RESENDS = 2
    private const val RESEND_AFTER_MS = 2_000L

    /** Less new sound than this past a kept transcript buys no request. */
    private const val NOTHING_NEW_SECONDS = 8.0

    /**
     * Requests at once, hedges included: Whisper takes 3 per project, the rest get 429
     * (measured 11-06). The playing song must not queue behind the next row's, nor the one before.
     */
    internal val requests = Semaphore(3)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Flight(val untilSeconds: Double, val answer: Deferred<Heard?>)

    private val inFlight = ConcurrentHashMap<String, Flight>()
    private val fileLocks = ConcurrentHashMap<String, Any>()

    /**
     * [KO] TR7 F2: bumped by [forgetFile] under the video's file lock. A listen notes it
     * when it starts: its flight is not joined by an ask for the new file, and words it
     * heard off the replaced file are not saved for the new one.
     */
    private val fileGenerations = ConcurrentHashMap<String, Long>()

    internal fun fileGenerationOf(videoId: String): Long = fileGenerations[videoId] ?: 0L

    private val json = Json { ignoreUnknownKeys = true }

    /** What was heard, and of how much sound. Null [words] means no usable answer ([httpCode] says why). */
    class Heard(
        val words: List<LyricsWordAlign.Heard>?,
        val audioMs: Long,
        val clock: LyricsTiming.Clock,
        val httpCode: Int?,
        val retryAfterMs: Long? = null,
        val fromStore: Boolean = false,
        /** Words from the quick engine, which Deepgram's replace. */
        val rough: Boolean = false,
    ) {
        /** These words with no reply code: no engine is spent, and a waiting retry or song check stands. */
        fun askedNothing() = Heard(words, audioMs, clock, httpCode = null, fromStore = true, rough = rough)
    }

    @Serializable
    internal data class Saved(
        /** [recordingKey]: the clock, and whether the sound came off a file. */
        val recording: String,
        val language: String,
        val seconds: Double,
        val whole: Boolean = false,
        val clock: String? = null,
        val words: List<LyricsTiming.SavedWord> = emptyList(),
        /** [Heard.rough]: never whole, so Deepgram is still asked for the song. */
        val rough: Boolean = false,
    )

    /**
     * The kept transcript that already covers [untilSeconds], or null. A whole
     * song covers anything; a longer one wins over a shorter one.
     */
    internal fun covering(
        saved: List<Saved>,
        recording: String,
        language: String,
        untilSeconds: Double,
    ): Saved? = saved
        .filter { it.recording == recording && it.language == language }
        .filter { it.whole || it.seconds + 1.0 >= untilSeconds }
        .maxByOrNull { it.seconds }

    /** The longest transcript of the YouTube stream in [language], if it is enough to fit an offset to. */
    internal fun streamWords(kept: List<Saved>, language: String): Saved? = kept
        .filter { it.recording.startsWith(STREAM_PREFIX) && it.language == language }
        .filter { LyricsTiming.clockFromKey(it.clock) == LyricsTiming.Clock.YouTube }
        .filter { it.whole || it.seconds >= FILE_READS_STREAM_SECONDS }
        .maxByOrNull { it.seconds }

    /**
     * [kept] with [new] taken in: one per recording and language, the longer stays. Exact
     * words always replace [Saved.rough] ones and never the reverse, whatever the length:
     * a refit only reads exact words, so losing them would leave the rough fit for good.
     */
    internal fun merged(kept: List<Saved>, new: Saved): List<Saved> {
        val same = kept.firstOrNull { it.recording == new.recording && it.language == new.language }
        if (same != null && same.rough && !new.rough) return kept.filterNot { it === same } + new
        if (same != null && !same.rough && new.rough) return kept
        if (same != null && (same.whole || same.seconds >= new.seconds) && !new.whole) return kept
        return kept.filterNot { it === same } + new
    }

    /**
     * The first answer that heard something. A second request goes out if the
     * first has said nothing after [hedgeAfterMs]; the loser is cancelled. A plain
     * failure from one waits for the other, never hides it. The second waits for
     * a free slot in [requests] rather than giving up: a slot opens when another
     * request ends, and the first may still be the slow one. If the first answers
     * while the second waits, the second is cancelled before it is sent.
     */
    internal suspend fun <T : Any> hedged(
        hedgeAfterMs: Long,
        ask: suspend () -> T,
        heardSomething: (T) -> Boolean,
        /** The row has left ([rowGone]): no second copy is bought for it (LOG6 R1). */
        gone: () -> Boolean = { false },
    ): T = coroutineScope {
        val first = async { ask() }
        val quick = withTimeoutOrNull(hedgeAfterMs) { first.await() }
        if (quick != null) return@coroutineScope quick
        if (gone()) return@coroutineScope first.await()
        // A slot opens when another request ends; the first may still be the slow one.
        val second = async {
            requests.acquire()
            try {
                ask()
            } finally {
                requests.release()
            }
        }
        val (done, other) = select<Pair<T, Deferred<T>>> {
            first.onAwait { it to second }
            second.onAwait { it to first }
        }
        val answer = if (heardSomething(done)) done else other.await()
        first.cancel()
        second.cancel()
        answer
    }

    /**
     * The words of [song] up to [untilSeconds], from the kept transcript when it
     * reaches that far and otherwise from Deepgram. [minSeconds] is what a request
     * may settle for after a short wait. A caller that can make do with less says so
     * in [reuseFrom]: a replaced sheet is fitted to the words kept, at once and for
     * free. Null means there was no sound to send.
     */
    suspend fun hear(
        context: Context,
        song: Song,
        untilSeconds: Double,
        language: String,
        minSeconds: Double = untilSeconds,
        reuseFrom: Double = untilSeconds,
        /** Rough words will not do: they are what this is meant to check or replace. */
        exact: Boolean = false,
    ): Heard? {
        val app = context.applicationContext
        var farmAsked = false
        var waitedOpening = false
        // With no Groq nothing is rough, and two flights would pay Deepgram twice for one window.
        val wantExact = exact && GroqWhisper.apiKey.isNotBlank()
        while (true) {
            val recording = recordingKey(song)
            if (recording != null) {
                val kept = withContext(Dispatchers.IO) { load(app, song.videoId) }.filterNot { wantExact && it.rough }
                covering(kept, recording, language, minOf(untilSeconds, reuseFrom))?.let { saved ->
                    TrackLog.d(TAG, "song-hear: kept lang=$language until=${untilSeconds.toInt()} secs=${saved.seconds.toInt()} words=${saved.words.size}", song.videoId)
                    return heardFrom(saved)
                }
                // A download taking over from its own stream mid-song. The words heard
                // on the stream hold on the stream's clock; timing() carries them onto
                // the file through its verified map, or drops them when it has none.
                // Decoding the file instead left the song uncorrected for 71 s (11-06
                // w58uXpyHWes, its new sheet 1.0-1.2 s late on the listener's own log).
                if (recording.startsWith(FILE_PREFIX) && LyricsTiming.streamWordsReachFile(app, song.videoId)) {
                    streamWords(kept, language)?.let { saved ->
                        TrackLog.d(TAG, "song-hear: file reads the stream's ${saved.seconds.toInt()}s lang=$language", song.videoId)
                        return heardFrom(saved)
                    }
                }
            }
            // The farm's GET can take its whole timeout: only words not kept wait on it.
            if (!farmAsked) {
                farmAsked = true
                if (keepFromFarm(app, song, language)) continue
            }
            // One request per recording, language and window kind (opening or longer) at a time.
            // Each window flying on its own paid for the same opening
            // twice over (11-06: 4.25 requests a song against the 2 this design needs).
            val fileGeneration = fileGenerationOf(song.videoId) // [KO] TR7 F2
            val base = flightBase(song.videoId, recording, language, wantExact, fileGeneration)
            // A whole-song ask that may settle for the opening reads it rather than buying the song beside it.
            if (!waitedOpening && readsOpening(untilSeconds, reuseFrom)) {
                val opening = inFlight[flightSlot(base, reuseFrom)]
                if (opening != null) {
                    waitedOpening = true
                    // join, not await: its failure or cancellation is not this ask's; the loop asks for itself.
                    opening.answer.join()
                    continue
                }
            }
            val flight = flightSlot(base, untilSeconds)
            val fresh = Flight(
                untilSeconds,
                scope.async(start = CoroutineStart.LAZY) { listen(app, song, untilSeconds, minSeconds, language, wantExact, fileGeneration) },
            )
            val running = inFlight.putIfAbsent(flight, fresh) ?: fresh
            if (running === fresh) {
                fresh.answer.invokeOnCompletion { inFlight.remove(flight, fresh) }
                fresh.answer.start()
                return fresh.answer.await()
            }
            fresh.answer.cancel()
            val answer = running.answer.await()
            if (!flightAnswers(running.untilSeconds, untilSeconds)) continue
            return answer
        }
    }

    internal fun streamRecording(clock: String) = STREAM_PREFIX + clock

    /**
     * [hear]'s flight for one recording, language and kind. [KO] TR7 F2: only a
     * file's flight carries [fileGeneration], so a replaced file is heard anew;
     * a stream's audio is the same after the file is replaced, and keying it by
     * the file's generation paid Deepgram twice for it.
     */
    internal fun flightBase(
        videoId: String,
        recording: String?,
        language: String,
        wantExact: Boolean,
        fileGeneration: Long,
    ): String {
        val base = "$videoId|$recording|$language|$wantExact"
        return if (recording?.startsWith(FILE_PREFIX) == true) "$base|$fileGeneration" else base
    }

    /** Words a runner already worked out replace a shorter transcript and spare the request. */
    private suspend fun keepFromFarm(app: Context, song: Song, language: String): Boolean {
        val saved = AlignFarm.fetch(app, song.videoId, language) ?: return false
        withContext(Dispatchers.IO) { save(app, song.videoId, saved) }
        return true
    }

    /** The longest words kept for what [song] plays now; never a request. */
    suspend fun longestKept(context: Context, song: Song, language: String): Heard? {
        val app = context.applicationContext
        val recording = recordingKey(song) ?: return null
        var saved = longestSaved(app, song, recording, language)
        // A whole song leaves the farm nothing to add, so its GET is not waited on.
        if (saved?.whole != true && keepFromFarm(app, song, language)) {
            saved = longestSaved(app, song, recording, language)
        }
        return saved?.let(::heardFrom)
    }

    /**
     * The longest transcript kept of [videoId]'s YouTube stream, in whatever language it
     * was asked in; never a request. The version check in [HqAudioVerify] compares a
     * candidate's words with these, so it only runs once the song check has heard them.
     */
    internal suspend fun keptYouTubeWords(context: Context, videoId: String): Saved? =
        withContext(Dispatchers.IO) { load(context.applicationContext, videoId) }
            .filter { it.recording.startsWith(STREAM_PREFIX) && it.words.isNotEmpty() }
            .filter { LyricsTiming.clockFromKey(it.clock) == LyricsTiming.Clock.YouTube }
            .maxByOrNull { it.seconds }

    private suspend fun longestSaved(app: Context, song: Song, recording: String, language: String): Saved? {
        val kept = withContext(Dispatchers.IO) { load(app, song.videoId) }
        return covering(kept, recording, language, 0.0)
            ?: streamWords(kept, language)?.takeIf { recording.startsWith(FILE_PREFIX) && LyricsTiming.streamWordsReachFile(app, song.videoId) }
    }

    /**
     * Whether a request flying for [flyingSeconds] answers one for [askedSeconds].
     * A shorter one does not: its words are in the file when it lands, and the
     * asker goes round for whatever is still new.
     */
    internal fun flightAnswers(flyingSeconds: Double, askedSeconds: Double): Boolean =
        flyingSeconds + 1.0 >= askedSeconds

    /** The in-flight slot an ask shares: an opening never waits behind a whole song's upload (11-09 230-d: 167 s). */
    internal fun flightSlot(base: String, untilSeconds: Double): String =
        if (untilSeconds <= OPENING_SECONDS) "$base|opening" else base

    /** Whether a longer ask that may settle for the opening waits for one in flight and reads its words. */
    internal fun readsOpening(untilSeconds: Double, reuseFrom: Double): Boolean =
        untilSeconds > OPENING_SECONDS && reuseFrom <= OPENING_SECONDS

    private fun heardFrom(saved: Saved) = Heard(
        words = saved.words.map { LyricsWordAlign.Heard(it.t, it.s, it.e) },
        audioMs = (saved.seconds * 1000).toLong(),
        clock = LyricsTiming.clockFromKey(saved.clock),
        httpCode = 200,
        fromStore = true,
        rough = saved.rough,
    )

    private suspend fun listen(
        app: Context,
        song: Song,
        untilSeconds: Double,
        minSeconds: Double,
        language: String,
        exact: Boolean,
        fileGeneration: Long, // [KO] TR7 F2
    ): Heard? {
        val videoId = song.videoId
        val started = System.currentTimeMillis()
        // Where a listen waits before its request: 11-08 150a PEIvcxSO9zQ logged nothing for 2.5 min.
        TrackLog.d(TAG, "song-hear: listening until=${untilSeconds.toInt()} min=${minSeconds.toInt()}", videoId)
        val raw = runCatching { VideoAudio.songStreamOpening(song, untilSeconds, minSeconds) { !rowGone(videoId) } }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
        TrackLog.d(
            TAG,
            "song-hear: stream opening ${if (raw == null) "none" else "${raw.bytes.size / 1024}KB"} after ${System.currentTimeMillis() - started}ms",
            videoId,
        )
        // The farm's first GET often fails in the rush as a track starts (rounds 116-118: 28
        // failed against 19 answered), and a failed ask is forgotten ([AlignFarm.fetch]). Asked
        // again after the wait for sound, it spares the request: 117b 8gyLR4NfMiI went on to the
        // decoder 24 s in and was answered 162 s later, after the song had played uncorrected,
        // while the farm held its whole song. No GET when the farm already answered or said no.
        farmAgain(app, song, language, untilSeconds, exact, raw)?.let { return it }
        if (raw == null && !decodeAfterNoStream(rowGone(videoId), VersionAudioAligner.renditionToMeasure(videoId) != null)) {
            TrackLog.d(TAG, "song-hear: the row has left with nothing on disk; not decoding", videoId)
            return null
        }
        if (raw != null) {
            // The row's head may be all there is yet (a next row, a blocked
            // stream): the same sound again would pay for the same words.
            // [KO] TR7 F1: the clock of the copy as it was opened, not after a minute-long request.
            val clock = LyricsTiming.clockForSongAudio(song, raw)
            val recording = recordingKey(raw.fromFile, clock)
            val already = recording?.let { key ->
                load(app, videoId).filter { it.recording == key && it.language == language && !(exact && it.rough) }
                    .maxByOrNull { it.seconds }
            }
            if (already != null && !raw.whole && (raw.roughSeconds ?: 0.0) <= already.seconds + NOTHING_NEW_SECONDS) {
                TrackLog.d(TAG, "song-hear: nothing new past ${already.seconds.toInt()}s yet", videoId)
                return heardFrom(already)
            }
            val waitedMs = System.currentTimeMillis() - started
            val answer = send(raw.bytes, language, raw.mime, untilSeconds, videoId, waitedMs, raw.renditionKey, exact)
            noteOutcome(videoId, answer)
            // A server that read it (or a limit or a dead network, which the
            // decoded path would only meet again) ends here. Only bytes it could
            // not read go round through the decoder.
            val unread = answer.httpCode != null && answer.httpCode !in 200..299 &&
                answer.httpCode != 429 && answer.httpCode != 401 && answer.httpCode != 403
            if (!unread) {
                keepWavForTest(app, song, untilSeconds)
                return kept(app, song, raw, answer, untilSeconds, language, clock, fileGeneration)
            }
            TrackLog.d(TAG, "song-hear: http=${answer.httpCode} for ${raw.mime}, decoding instead", videoId)
        }
        val started2 = System.currentTimeMillis()
        val wav = VideoAudio.songWavCopy(app, song, untilSeconds)
        TrackLog.d(
            TAG,
            "song-hear: decoded ${if (wav == null) "nothing" else "${wav.bytes.size / 1024}KB"} after ${System.currentTimeMillis() - started2}ms",
            videoId,
        )
        if (wav == null) return null
        val wavClock = LyricsTiming.clockForSongAudio(song, wav) // [KO] TR7 F1
        SyncTestMode.saveWav(app, videoId, wav.bytes)
        val answer = send(wav.bytes, language, wav.mime, untilSeconds, videoId, System.currentTimeMillis() - started2, wav.renditionKey, exact)
        noteOutcome(videoId, answer)
        return kept(app, song, wav, answer, untilSeconds, language, wavClock, fileGeneration)
    }

    /**
     * How an ask ended, for [HearingReask]: no reply at all arms the playing row's one more
     * ask; any reply (words, nothing heard, a 429) settles its row and says the network is up.
     */
    private fun noteOutcome(videoId: String, answer: Deepgram.Answer) {
        val now = System.currentTimeMillis()
        if (answer.httpCode == null) {
            if (answer.words.isNullOrEmpty() && HearingReask.live.noReply(videoId, now)) {
                TrackLog.d(TAG, "song-hear: no reply; one more ask once the network is back", videoId)
            }
            return
        }
        HearingReask.live.replied(videoId)
        HearingReask.live.networkUp(now)
    }

    /**
     * [keepFromFarm] once more, and the kept words when they cover [untilSeconds] of what is
     * heard from. Looked at even when this call got nothing: the farm's one GET a run may
     * have been answered to another hearing during the wait for sound (rs28 215 pB-5XG-DbAA).
     */
    private suspend fun farmAgain(
        app: Context,
        song: Song,
        language: String,
        untilSeconds: Double,
        exact: Boolean,
        raw: VideoAudio.SongAudio?,
    ): Heard? {
        val answered = keepFromFarm(app, song, language)
        val recording = (if (raw != null) recordingKey(raw.fromFile, LyricsTiming.clockForSongAudio(song, raw)) else recordingKey(song))
            ?: return null
        val kept = withContext(Dispatchers.IO) { load(app, song.videoId) }
        val saved = covering(kept.filterNot { exact && it.rough }, recording, language, untilSeconds) ?: return null
        TrackLog.d(
            TAG,
            if (answered) "song-hear: the farm answered a second ask, ${saved.words.size} words"
            else "song-hear: kept during the wait for sound, ${saved.words.size} words",
            song.videoId,
        )
        return heardFrom(saved)
    }

    private suspend fun send(
        bytes: ByteArray,
        language: String,
        mime: String,
        untilSeconds: Double,
        videoId: String,
        waitedMs: Long,
        copyKey: String?,
        exact: Boolean,
    ): Deepgram.Answer {
        if (!exact && groqFirst(untilSeconds)) {
            val answer = groqWithResend {
                val asked = System.currentTimeMillis()
                GroqWhisper.listen(bytes, language, mime).also { answer ->
                    TrackLog.d(
                        TAG,
                        "song-hear: groq lang=$language until=${untilSeconds.toInt()} mime=$mime kb=${bytes.size / 1024} " +
                            "wait=${waitedMs}ms send=${System.currentTimeMillis() - asked}ms " +
                            "http=${answer.httpCode} words=${answer.words?.size} audio=${answer.audioMs} copy=$copyKey",
                        videoId,
                    )
                }
            }
            if (!answer.words.isNullOrEmpty()) return answer
        }
        return sendToDeepgram(bytes, language, mime, untilSeconds, videoId, waitedMs, copyKey, exact)
    }

    /**
     * Groq once more, [RESEND_AFTER_MS] later, when its first ask got no reply at all.
     *
     * A dropped connection is not an answer: on the 11-09 loop rounds (208-212) 28 of
     * 93 Groq asks died in 0.3-1.8 s with "unexpected end of stream", nothing billed,
     * and the opening fell to Deepgram, whose first ask had died the same way and whose
     * second sat on it 48-80 s in 5 of them (212-b v59g4HRNIkc: answered 12 s after the
     * row had left, the sheet scored +1.85 s uncorrected). Deepgram already sends a
     * no-reply again ([shouldResend]); Groq, the engine someone is waiting on, did not.
     * One resend only, and never over a server's own answer (429, 401, 5xx): those go
     * to Deepgram exactly as before.
     */
    internal suspend fun groqWithResend(ask: suspend () -> Deepgram.Answer): Deepgram.Answer {
        val first = ask()
        if (first.httpCode != null || !first.words.isNullOrEmpty()) return first
        delay(RESEND_AFTER_MS)
        return ask()
    }

    /**
     * When a second copy goes to Deepgram. An exact ask has no listener waiting on it, since
     * Groq spoke first, and a copy sent while one stalls stalled too, 7 of 7 (11-07), so it
     * would only pay twice and hold a slot another song's request needs.
     */
    internal fun hedgeAfterMs(untilSeconds: Double, exact: Boolean): Long =
        if (untilSeconds <= OPENING_SECONDS && !exact) HEDGE_AFTER_MS else HEDGE_AFTER_LONG_MS

    /**
     * The opening goes to Groq first: someone is waiting on it, and Deepgram may sit on it
     * for a minute. An exact ask or a later window is Deepgram's, whose fit then replaces it.
     */
    internal fun groqFirst(untilSeconds: Double): Boolean =
        untilSeconds <= OPENING_SECONDS && GroqWhisper.apiKey.isNotBlank()

    private suspend fun sendToDeepgram(
        bytes: ByteArray,
        language: String,
        mime: String,
        untilSeconds: Double,
        videoId: String,
        waitedMs: Long,
        copyKey: String?,
        exact: Boolean,
        /** When this began waiting for one of the [requests] slots: a queue behind slow uploads shows as `slot`. */
        queuedAt: Long = System.currentTimeMillis(),
    ): Deepgram.Answer = requests.withPermit {
        val asked = System.currentTimeMillis()
        // A `sending` with no `sent` after it is an upload still in flight when the row or the log ended.
        TrackLog.d(TAG, "song-hear: sending until=${untilSeconds.toInt()} kb=${bytes.size / 1024} wait=${waitedMs}ms slot=${asked - queuedAt}ms", videoId)
        val outcome = askWhileWanted(
            hedgeAfterMs(untilSeconds, exact),
            gone = { rowGone(videoId) },
            moved = com.music.bitchord.data.DiagnosticsState.rows,
            resendable = { Deepgram.usable },
            ask = { Deepgram.listen(bytes, language, Deepgram.MODEL_WHISPER, mime) },
        )
        val answer = outcome.answer
        val hedgedTo = outcome.requests
        if (outcome.left) {
            TrackLog.d(TAG, "song-hear: the row left; ask dropped after ${System.currentTimeMillis() - asked}ms requests=$hedgedTo", videoId)
        }
        TrackLog.d(
            TAG,
            "song-hear: sent lang=$language until=${untilSeconds.toInt()} mime=$mime kb=${bytes.size / 1024} " +
                "wait=${waitedMs}ms send=${System.currentTimeMillis() - asked}ms requests=$hedgedTo " +
                "http=${answer.httpCode} words=${answer.words?.size} audio=${answer.audioMs} copy=$copyKey " +
                "slot=${asked - queuedAt}ms",
            videoId,
        )
        answer
    }

    /** Whether a request that got no reply goes again: a server's own answer never does. */
    internal fun shouldResend(httpCode: Int?, dropped: Int): Boolean =
        httpCode == null && dropped < MAX_RESENDS

    /** What [askWhileWanted] got: the [answer], how many [requests] went out, and whether the row [left] first. */
    internal class Asked(val answer: Deepgram.Answer, val requests: Int, val left: Boolean)

    /**
     * One Deepgram ask as [sendToDeepgram] makes it: [hedged], and sent again on no reply
     * ([shouldResend]) while [resendable]. No reply at all means the connection failed,
     * usually in under a second (11-06: 7 of 18 requests, none billed).
     *
     * It ends as soon as [gone] is true, read on each change of [moved] (the rows, so no
     * poll): the call in flight is cancelled ([Deepgram.listen] cancels its OkHttp call)
     * and the caller's slot frees at once, with a no-reply answer. Neither the hedge nor a
     * resend goes out for a row that has left. 11-17 phone (LOG6 R1): asks for rows the
     * user had left held all 3 [requests] for 240 s each, and the playing To4SWGZkEPk
     * never got one.
     */
    internal suspend fun askWhileWanted(
        hedgeAfterMs: Long,
        gone: () -> Boolean,
        moved: Flow<Any?>,
        resendable: () -> Boolean,
        ask: suspend () -> Deepgram.Answer,
    ): Asked = coroutineScope {
        if (gone()) return@coroutineScope Asked(Deepgram.Answer(null), 0, left = true)
        val sent = AtomicInteger()
        val once: suspend () -> Deepgram.Answer = {
            hedged(
                hedgeAfterMs,
                ask = {
                    sent.incrementAndGet()
                    ask()
                },
                heardSomething = { it.words != null },
                gone = gone,
            )
        }
        val work = async {
            var answer = once()
            var dropped = 0
            while (!gone() && resendable() && shouldResend(answer.httpCode, dropped)) {
                dropped++
                delay(RESEND_AFTER_MS * dropped)
                if (gone()) break
                answer = once()
            }
            answer
        }
        val left = async { moved.first { gone() } }
        select<Asked> {
            work.onAwait { answer ->
                left.cancel()
                Asked(answer, sent.get(), left = false)
            }
            left.onAwait {
                work.cancel()
                Asked(Deepgram.Answer(null), sent.get(), left = true)
            }
        }
    }

    /**
     * Saves and returns what [answer] heard of [copy]. [clock] is the copy's clock when it
     * was opened ([LyricsTiming.clockForSongAudio] then): an `#alt` key does not say which
     * source filled it, and an HQ swap during the request used to label the transcript
     * with the clock sounding when it came back (TR2 F1), off by the HQ offset for good.
     */
    internal fun kept(
        app: Context,
        song: Song,
        copy: VideoAudio.SongAudio,
        answer: Deepgram.Answer,
        untilSeconds: Double,
        language: String,
        clock: LyricsTiming.Clock, // [KO] TR7 F1
        /** [KO] TR7 F2: [fileGenerationOf] when the listen started. */
        fileGeneration: Long = fileGenerationOf(song.videoId),
    ): Heard {
        val audioMs = answer.audioMs ?: (untilSeconds * 1000).toLong()
        val words = answer.words
        val recording = recordingKey(copy.fromFile, clock)
        if (recording != null && !words.isNullOrEmpty()) {
            val seconds = audioMs / 1000.0
            val duration = song.durationMillis() / 1000.0
            save(
                app,
                song.videoId,
                Saved(
                    recording = recording,
                    language = language,
                    seconds = seconds,
                    whole = !answer.rough && (copy.whole || (duration > 0.0 && seconds + 3.0 >= duration)),
                    clock = LyricsTiming.clockToKey(clock),
                    words = words.map { LyricsTiming.SavedWord(it.text, it.startMs, it.endMs) },
                    rough = answer.rough,
                ),
                fileGeneration,
            )
        }
        return Heard(words, audioMs, clock, answer.httpCode, answer.retryAfterMs, rough = answer.rough)
    }

    /** Test runs score the shown lines against a WAV of the same opening; off, nothing is decoded. */
    private fun keepWavForTest(app: Context, song: Song, untilSeconds: Double) {
        if (!SyncTestMode.enabled(app, song.videoId)) return
        // A row that has left decodes behind the playing one's: 11-08 150a the row before's
        // whole-song WAV held the one-at-a-time gate 63 s while PEIvcxSO9zQ played.
        scope.launch(AudioDecoder.aheadOfTime.asContextElement(rowGone(song.videoId))) {
            VideoAudio.songWavCopy(app, song, untilSeconds)?.let { SyncTestMode.saveWav(app, song.videoId, it.bytes) }
        }
    }

    /** Whether [videoId] is neither playing nor next; false with nothing playing. */
    internal fun rowGone(videoId: String): Boolean = rowGone(
        videoId,
        com.music.bitchord.data.DiagnosticsState.soundingTrackId,
        com.music.bitchord.data.DiagnosticsState.nextTrackId,
    )

    /** [rowGone] with the playing and next rows given, so the rule is testable. */
    internal fun rowGone(videoId: String, playing: String?, next: String?): Boolean =
        playing != null && videoId != playing && videoId != next

    /**
     * Whether a listen that found no stream to send goes on to the decoder. A row
     * that has left with nothing of it on disk does not: the decoder has no audio
     * for it either, and its head wait only asks YouTube for a stream nobody
     * plays. 11-08 rounds 156-157 and 187-188: 11 of 20 fall-backs were for a
     * row already gone and all 11 decoded nothing; 187a v59g4HRNIkc's fall-backs
     * re-resolved the gone row and pulled 6.8 MB of it while the next row never started.
     */
    internal fun decodeAfterNoStream(rowGone: Boolean, anythingOnDisk: Boolean): Boolean =
        !rowGone || anythingOnDisk

    /** The recording [song] would be heard from now; null when its clock is not known. */
    private fun recordingKey(song: Song): String? {
        val local = song.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val fromFile = local != null && LocalAudioSource.isLocal(local)
        val clock = if (fromFile) {
            val meta = KoDownloadRecords.savedMetadataFor(song.videoId)
            LyricsTiming.clockForLocalCopy(meta?.hqSourceConfigId, meta?.hqTrackId)
        } else {
            val copy = VersionAudioAligner.renditionToMeasure(song.videoId)
            LyricsTiming.clockForRendition(
                song.videoId,
                copy?.key,
                copy?.origin == AudioCache.Origin.YOUTUBE,
            )
        }
        return recordingKey(fromFile, clock)
    }

    private fun recordingKey(fromFile: Boolean, clock: LyricsTiming.Clock): String? =
        LyricsTiming.clockToKey(clock)?.let { (if (fromFile) FILE_PREFIX else STREAM_PREFIX) + it }

    /**
     * The file was replaced, so what was heard off it is no longer what plays.
     *
     * [KO] TR7 F2: done before it returns (still off the caller's thread), so the reset that
     * calls it cannot let an ask for the new file read the old file's words in the gap, and
     * the generation moves under the same lock a save takes: a listen of the old file that
     * lands after this keeps nothing ([save]).
     */
    suspend fun forgetFile(context: Context, videoId: String) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            synchronized(lockOf(videoId)) {
                fileGenerations.merge(videoId, 1L, Long::plus)
                runCatching {
                    val kept = readFile(app, videoId)
                    val left = kept.filterNot { it.recording.startsWith(FILE_PREFIX) }
                    if (left.size != kept.size) writeFile(app, videoId, left)
                }.onFailure { TrackLog.w(TAG, "song-hear: replaced file's words not dropped: ${it.message}", videoId) }
            }
        }
    }

    private fun lockOf(videoId: String): Any = fileLocks.getOrPut(videoId) { Any() }

    private fun load(app: Context, videoId: String): List<Saved> =
        synchronized(lockOf(videoId)) { readFile(app, videoId) }

    private fun save(app: Context, videoId: String, new: Saved, fileGeneration: Long? = null) {
        runCatching {
            val changed = synchronized(lockOf(videoId)) {
                // [KO] TR7 F2: words heard off a file [forgetFile] has since dropped are not the new file's.
                if (fileGeneration != null && new.recording.startsWith(FILE_PREFIX) && fileGenerationOf(videoId) != fileGeneration) {
                    TrackLog.i(TAG, "song-hear: words of a replaced file not kept", videoId)
                    return@synchronized false
                }
                val kept = readFile(app, videoId)
                val next = merged(kept, new)
                writeFile(app, videoId, next)
                next != kept
            }
            prune(app)
            // The save is the event: a refit on these words need not wait for the next playback.
            if (changed) LyricsTiming.wordsKept(videoId, new.rough)
        }.onFailure { TrackLog.w(TAG, "song-hear: not kept: ${it.message}", videoId) }
    }

    private fun dir(app: Context) = File(app.filesDir, DIR)

    private fun fileOf(app: Context, videoId: String) =
        File(dir(app), videoId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun readFile(app: Context, videoId: String): List<Saved> = runCatching {
        val file = fileOf(app, videoId)
        if (!file.isFile) emptyList()
        else json.decodeFromString(ListSerializer(Saved.serializer()), file.readText())
    }.getOrElse { emptyList() }

    private fun writeFile(app: Context, videoId: String, saved: List<Saved>) {
        val file = fileOf(app, videoId)
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(json.encodeToString(ListSerializer(Saved.serializer()), saved))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    private fun prune(app: Context) {
        val files = dir(app).listFiles() ?: return
        if (files.size <= FILES_KEPT) return
        files.sortedBy { it.lastModified() }.take(files.size - FILES_KEPT).forEach { it.delete() }
    }
}
