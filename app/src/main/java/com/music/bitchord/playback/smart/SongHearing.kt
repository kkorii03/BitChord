package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.ko.downloads.KoDownloadRecords
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    private val json = Json { ignoreUnknownKeys = true }

    /** What was heard, and of how much sound. Null [words] means no usable answer ([httpCode] says why). */
    class Heard(
        val words: List<LyricsWordAlign.Heard>?,
        val audioMs: Long,
        val clock: LyricsTiming.Clock,
        val httpCode: Int?,
        val retryAfterMs: Long? = null,
        val fromStore: Boolean = false,
    )

    @Serializable
    internal data class Saved(
        /** [recordingKey]: the clock, and whether the sound came off a file. */
        val recording: String,
        val language: String,
        val seconds: Double,
        val whole: Boolean = false,
        val clock: String? = null,
        val words: List<LyricsTiming.SavedWord> = emptyList(),
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

    /** [kept] with [new] taken in: one per recording and language, the longer stays. */
    internal fun merged(kept: List<Saved>, new: Saved): List<Saved> {
        val same = kept.firstOrNull { it.recording == new.recording && it.language == new.language }
        if (same != null && (same.whole || same.seconds >= new.seconds) && !new.whole) return kept
        return kept.filterNot { it === same } + new
    }

    /**
     * The first answer that heard something. A second request goes out if the
     * first has said nothing after [hedgeAfterMs]; the loser is cancelled. A plain
     * failure from one waits for the other, never hides it. With no slot free in
     * [requests] the second is not sent: it would turn away another song's request.
     */
    internal suspend fun <T : Any> hedged(
        hedgeAfterMs: Long,
        ask: suspend () -> T,
        heardSomething: (T) -> Boolean,
    ): T = coroutineScope {
        val first = async { ask() }
        val quick = withTimeoutOrNull(hedgeAfterMs) { first.await() }
        if (quick != null) return@coroutineScope quick
        if (!requests.tryAcquire()) return@coroutineScope first.await()
        val second = async { ask() }
        second.invokeOnCompletion { requests.release() }
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
     * may settle for after a short wait. Null means there was no sound to send.
     */
    suspend fun hear(
        context: Context,
        song: Song,
        untilSeconds: Double,
        language: String,
        minSeconds: Double = untilSeconds,
    ): Heard? {
        val app = context.applicationContext
        while (true) {
            val recording = recordingKey(song)
            if (recording != null) {
                val kept = withContext(Dispatchers.IO) { load(app, song.videoId) }
                covering(kept, recording, language, untilSeconds)?.let { saved ->
                    TrackLog.d(TAG, "song-hear: kept lang=$language until=${untilSeconds.toInt()} secs=${saved.seconds.toInt()} words=${saved.words.size}", song.videoId)
                    return heardFrom(saved)
                }
                // A download taking over from its own stream mid-song. The words heard
                // on the stream hold on the stream's clock; timing() carries them onto
                // the file through its verified map, or drops them when it has none.
                // Decoding the file instead left the song uncorrected for 71 s (11-06
                // w58uXpyHWes, its new sheet 1.0-1.2 s late on the listener's own log).
                if (recording.startsWith(FILE_PREFIX)) {
                    streamWords(kept, language)?.let { saved ->
                        TrackLog.d(TAG, "song-hear: file reads the stream's ${saved.seconds.toInt()}s lang=$language", song.videoId)
                        return heardFrom(saved)
                    }
                }
            }
            // One request per recording and language at a time, whoever asks and for
            // whatever window. Each window flying on its own paid for the same opening
            // twice over (11-06: 4.25 requests a song against the 2 this design needs).
            val flight = "${song.videoId}|$recording|$language"
            val fresh = Flight(
                untilSeconds,
                scope.async(start = CoroutineStart.LAZY) { listen(app, song, untilSeconds, minSeconds, language) },
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

    /**
     * Whether a request flying for [flyingSeconds] answers one for [askedSeconds].
     * A shorter one does not: its words are in the file when it lands, and the
     * asker goes round for whatever is still new.
     */
    internal fun flightAnswers(flyingSeconds: Double, askedSeconds: Double): Boolean =
        flyingSeconds + 1.0 >= askedSeconds

    private fun heardFrom(saved: Saved) = Heard(
        words = saved.words.map { LyricsWordAlign.Heard(it.t, it.s, it.e) },
        audioMs = (saved.seconds * 1000).toLong(),
        clock = LyricsTiming.clockFromKey(saved.clock),
        httpCode = 200,
        fromStore = true,
    )

    private suspend fun listen(
        app: Context,
        song: Song,
        untilSeconds: Double,
        minSeconds: Double,
        language: String,
    ): Heard? {
        val videoId = song.videoId
        val started = System.currentTimeMillis()
        val raw = runCatching { VideoAudio.songStreamOpening(song, untilSeconds, minSeconds) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
        if (raw != null) {
            // The row's head may be all there is yet (a next row, a blocked
            // stream): the same sound again would pay for the same words.
            val recording = recordingKey(raw.fromFile, LyricsTiming.clockForSongAudio(song, raw))
            val already = recording?.let { key ->
                load(app, videoId).filter { it.recording == key && it.language == language }.maxByOrNull { it.seconds }
            }
            if (already != null && !raw.whole && (raw.roughSeconds ?: 0.0) <= already.seconds + NOTHING_NEW_SECONDS) {
                TrackLog.d(TAG, "song-hear: nothing new past ${already.seconds.toInt()}s yet", videoId)
                return heardFrom(already)
            }
            val waitedMs = System.currentTimeMillis() - started
            val answer = send(raw.bytes, language, raw.mime, untilSeconds, videoId, waitedMs, raw.renditionKey)
            // A server that read it (or a limit or a dead network, which the
            // decoded path would only meet again) ends here. Only bytes it could
            // not read go round through the decoder.
            val unread = answer.httpCode != null && answer.httpCode !in 200..299 &&
                answer.httpCode != 429 && answer.httpCode != 401 && answer.httpCode != 403
            if (!unread) {
                keepWavForTest(app, song, untilSeconds)
                return kept(app, song, raw, answer, untilSeconds, language)
            }
            TrackLog.d(TAG, "song-hear: http=${answer.httpCode} for ${raw.mime}, decoding instead", videoId)
        }
        val started2 = System.currentTimeMillis()
        val wav = VideoAudio.songWavCopy(app, song, untilSeconds) ?: return null
        SyncTestMode.saveWav(app, videoId, wav.bytes)
        val answer = send(wav.bytes, language, wav.mime, untilSeconds, videoId, System.currentTimeMillis() - started2, wav.renditionKey)
        return kept(app, song, wav, answer, untilSeconds, language)
    }

    private suspend fun send(
        bytes: ByteArray,
        language: String,
        mime: String,
        untilSeconds: Double,
        videoId: String,
        waitedMs: Long,
        copyKey: String?,
    ): Deepgram.Answer = requests.withPermit {
        val asked = System.currentTimeMillis()
        var hedgedTo = 0
        // No reply at all means the connection failed, usually in under a second
        // (11-06: 7 of 18 requests, none billed). Such a song used to go without
        // a check for the rest of its playback.
        var answer: Deepgram.Answer
        var dropped = 0
        while (true) {
            answer = hedged(
                if (untilSeconds <= OPENING_SECONDS) HEDGE_AFTER_MS else HEDGE_AFTER_LONG_MS,
                ask = {
                    hedgedTo++
                    Deepgram.listen(bytes, language, Deepgram.MODEL_WHISPER, mime)
                },
                heardSomething = { it.words != null },
            )
            if (!Deepgram.usable || !shouldResend(answer.httpCode, dropped)) break
            dropped++
            delay(RESEND_AFTER_MS * dropped)
        }
        TrackLog.d(
            TAG,
            "song-hear: sent lang=$language until=${untilSeconds.toInt()} mime=$mime kb=${bytes.size / 1024} " +
                "wait=${waitedMs}ms send=${System.currentTimeMillis() - asked}ms requests=$hedgedTo " +
                "http=${answer.httpCode} words=${answer.words?.size} audio=${answer.audioMs} copy=$copyKey",
            videoId,
        )
        answer
    }

    /** Whether a request that got no reply goes again: a server's own answer never does. */
    internal fun shouldResend(httpCode: Int?, dropped: Int): Boolean =
        httpCode == null && dropped < MAX_RESENDS

    private fun kept(
        app: Context,
        song: Song,
        copy: VideoAudio.SongAudio,
        answer: Deepgram.Answer,
        untilSeconds: Double,
        language: String,
    ): Heard {
        val clock = LyricsTiming.clockForSongAudio(song, copy)
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
                    whole = copy.whole || (duration > 0.0 && seconds + 3.0 >= duration),
                    clock = LyricsTiming.clockToKey(clock),
                    words = words.map { LyricsTiming.SavedWord(it.text, it.startMs, it.endMs) },
                ),
            )
        }
        return Heard(words, audioMs, clock, answer.httpCode, answer.retryAfterMs)
    }

    /** Test runs score the shown lines against a WAV of the same opening; off, nothing is decoded. */
    private fun keepWavForTest(app: Context, song: Song, untilSeconds: Double) {
        if (!SyncTestMode.enabled(app, song.videoId)) return
        scope.launch {
            VideoAudio.songWavCopy(app, song, untilSeconds)?.let { SyncTestMode.saveWav(app, song.videoId, it.bytes) }
        }
    }

    /** The recording [song] would be heard from now; null when its clock is not known. */
    private fun recordingKey(song: Song): String? {
        val local = song.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val fromFile = local != null && LocalAudioSource.isLocal(local)
        val clock = if (fromFile) {
            val meta = KoDownloadRecords.savedMetadataFor(song.videoId)
            LyricsTiming.clockForLocalCopy(meta?.hqSourceConfigId, meta?.hqTrackId)
        } else {
            LyricsTiming.clockForRendition(song.videoId, VersionAudioAligner.renditionToMeasure(song.videoId)?.key)
        }
        return recordingKey(fromFile, clock)
    }

    private fun recordingKey(fromFile: Boolean, clock: LyricsTiming.Clock): String? =
        LyricsTiming.clockToKey(clock)?.let { (if (fromFile) FILE_PREFIX else STREAM_PREFIX) + it }

    /** The file was replaced, so what was heard off it is no longer what plays. */
    fun forgetFile(context: Context, videoId: String) {
        val app = context.applicationContext
        scope.launch {
            synchronized(lockOf(videoId)) {
                val kept = readFile(app, videoId)
                val left = kept.filterNot { it.recording.startsWith(FILE_PREFIX) }
                if (left.size != kept.size) writeFile(app, videoId, left)
            }
        }
    }

    private fun lockOf(videoId: String): Any = fileLocks.getOrPut(videoId) { Any() }

    private fun load(app: Context, videoId: String): List<Saved> =
        synchronized(lockOf(videoId)) { readFile(app, videoId) }

    private fun save(app: Context, videoId: String, new: Saved) {
        runCatching {
            synchronized(lockOf(videoId)) { writeFile(app, videoId, merged(readFile(app, videoId), new)) }
            prune(app)
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
