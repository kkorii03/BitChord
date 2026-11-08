package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * A song's words heard ahead of time on a GitHub runner (bench/farm/farm.py). They land in
 * [SongHearing]'s kept transcripts, so a song the farm knows needs no request and no wait, from
 * the first line. One GET per video per run; a song it does not know goes on as before.
 *
 * The files sit in a private R2 bucket behind a Worker that can only read, and only for
 * [readToken] (bench/farm/r2_tool.py). Names and contents are still under the Deepgram key baked
 * into the signed APK, which the repository does not hold. No key, no lookup.
 */
internal object AlignFarm {
    private const val TAG = "AlignFarm"
    private const val BASE = "https://bitchord-align-read.bitchord-align.workers.dev/"
    private const val READ_TOKEN_LABEL = "bitchord-farm-read"

    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val json = Json { ignoreUnknownKeys = true }
    private const val CALL_TIMEOUT_MS = 4_000L

    /**
     * A GET asked while the row is still next: nothing waits on it, so it may take a slow
     * route's time. Rounds 116-118 on the emulators' tunnel: 24 of 28 failures were the
     * 4 s call timeout ([CALL_TIMEOUT_MS]), which a hearing must not wait out.
     */
    private const val AHEAD_TIMEOUT_MS = 15_000L
    private val client by lazy {
        Http.client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }
    private val aheadClient by lazy {
        Http.client.newBuilder().callTimeout(AHEAD_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    /** Outlives the next-row bundle that asks: a replaced row's small GET just ends unread. */
    private val aheadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * GETs asked ahead, by video: the file's bytes, empty when the farm has none, null when
     * no reply came. Taken by the row's first ask ([fetch]). Only the last few rows are kept:
     * rows skipped past ask again when they play.
     */
    private val ahead = object : LinkedHashMap<String, CompletableDeferred<ByteArray?>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompletableDeferred<ByteArray?>>) =
            size > MAX_AHEAD
    }
    private const val MAX_AHEAD = 4

    @Serializable
    internal data class Result(
        val lang: String,
        val audio_s: Double,
        val words: List<Word>,
    )

    @Serializable
    internal data class Word(val t: String, val s: Long, val e: Long)

    /** A file name that does not say which video it is. Same as farm.py's data_name. */
    internal fun nameOf(videoId: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    /** What the Worker asks for: read-only, and no R2 key in the APK. Same as r2_tool.py's read_token. */
    internal fun readToken(secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(READ_TOKEN_LABEL.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 counter keystream, the same both ways. Same as farm.py's stream. */
    internal fun decipher(data: ByteArray, password: String): ByteArray {
        val key = password.toByteArray()
        val out = ByteArray(data.size)
        var at = 0
        while (at < data.size) {
            val counter = ByteArray(8) { i -> (at.toLong() ushr (8 * (7 - i))).toByte() }
            val pad = MessageDigest.getInstance("SHA-256").digest(key + counter)
            for (i in 0 until minOf(32, data.size - at)) out[at + i] = (data[at + i].toInt() xor pad[i].toInt()).toByte()
            at += 32
        }
        return out
    }

    /** The farm's words as a kept transcript of the YouTube stream, if they suit [language]. */
    internal fun savedFrom(bytes: ByteArray, language: String, password: String): SongHearing.Saved? {
        val result = runCatching { json.decodeFromString(Result.serializer(), decipher(bytes, password).decodeToString()) }.getOrNull()
            ?: return null
        if (result.lang != language || result.words.isEmpty()) return null
        val clock = LyricsTiming.clockToKey(LyricsTiming.Clock.YouTube) ?: return null
        return SongHearing.Saved(
            recording = SongHearing.streamRecording(clock),
            language = language,
            seconds = result.audio_s,
            whole = true,
            clock = clock,
            words = result.words.map { LyricsTiming.SavedWord(it.t, it.s, it.e) },
        )
    }

    /** GETs that got no reply, per video: each costs a hearing up to the call timeout. */
    private val failures = ConcurrentHashMap<String, Int>()

    /** Whether [videoId] has failed fewer than [MAX_FAILED_ASKS] times; an answered video is still asked once only. */
    internal fun mayAsk(videoId: String): Boolean = (failures[videoId] ?: 0) < MAX_FAILED_ASKS

    /** A GET for [videoId] got no reply: it may be asked again, up to [MAX_FAILED_ASKS] in all. */
    internal fun noteFailure(videoId: String) {
        failures.merge(videoId, 1, Int::plus)
        asked.remove(videoId)
    }

    /**
     * Failed GETs a video may have before the farm is left alone this run: the ask at the
     * track's start, the one after the wait for sound ([SongHearing]) and one on a retry.
     * An ask ahead ([askAhead]) counts as one, and never takes the row's last.
     */
    internal const val MAX_FAILED_ASKS = 3

    /**
     * Whether a next row may be asked ahead: one GET at a time, none once the row itself has
     * asked, and never with the last failure the row's own asks still need.
     */
    internal fun mayAskAhead(failed: Int, askedByRow: Boolean, held: Boolean): Boolean =
        !askedByRow && !held && failed + 1 < MAX_FAILED_ASKS

    /**
     * The farm's file for [videoId] while it is still the next row, so its first lines need no
     * GET of their own: at the row's start the GET races its stream (rounds 116-118: 28 failed
     * against 19 answered; 161 6rS7OUGXUik failed three times and played uncorrected).
     */
    fun askAhead(app: Context, videoId: String) {
        val secret = secretOf(app) ?: return
        askAhead(videoId) { download(videoId, secret, aheadClient, ahead = true) }
    }

    internal fun askAhead(videoId: String, get: suspend () -> ByteArray?): Boolean {
        val pending = CompletableDeferred<ByteArray?>()
        synchronized(ahead) {
            if (!mayAskAhead(failures[videoId] ?: 0, videoId in asked, ahead.containsKey(videoId))) return false
            ahead[videoId] = pending
        }
        aheadScope.launch {
            val bytes = runCatching { get() }.getOrNull()
            // No reply: counted, and the row asks for itself.
            if (bytes == null) {
                synchronized(ahead) { if (ahead[videoId] === pending) ahead.remove(videoId) }
                noteFailure(videoId)
            } else {
                TrackLog.d(TAG, "align-farm ahead: ${bytes.size / 1024}KB", videoId)
            }
            pending.complete(bytes)
        }
        return true
    }

    /** What the farm holds for [videoId], asked once a run; null on anything but a usable answer. */
    suspend fun fetch(app: Context, videoId: String, language: String): SongHearing.Saved? {
        val secret = secretOf(app) ?: return null
        return answer(videoId, language, secret + videoId) { download(videoId, secret, client, ahead = false) }
    }

    /**
     * [fetch] past the key. A GET asked ahead is this ask's, waited on no longer than a GET of
     * its own would take; [get] runs only when none holds or is getting the answer.
     */
    internal suspend fun answer(
        videoId: String,
        language: String,
        password: String,
        get: suspend () -> ByteArray?,
    ): SongHearing.Saved? {
        val pending = synchronized(ahead) {
            val held = ahead[videoId]
            if (held == null && (!mayAsk(videoId) || !asked.add(videoId))) return null
            held
        }
        val bytes = if (pending != null) {
            withTimeoutOrNull(CALL_TIMEOUT_MS) { pending.await() }
            // Still flying: a later ask (the one after the wait for sound) waits on it again.
            if (!pending.isCompleted) return null
            // It got no reply, which is counted: ask as before.
            val got = pending.await() ?: return answer(videoId, language, password, get)
            if (!asked.add(videoId)) return null
            synchronized(ahead) { if (ahead[videoId] === pending) ahead.remove(videoId) }
            got
        } else {
            get() ?: run {
                noteFailure(videoId)
                return null
            }
        }
        val how = if (pending != null) " asked ahead" else ""
        return withContext(Dispatchers.IO) { savedFrom(bytes, language, password) }
            ?.also { TrackLog.i(TAG, "align-farm: ${it.words.size} words lang=$language$how", videoId) }
    }

    private fun secretOf(app: Context): String? =
        app.getString(com.music.bitchord.R.string.default_deepgram_api_key).trim().takeIf { it.isNotEmpty() }

    /** One GET of [videoId]'s file: its bytes, empty when the farm has none, null when no reply came. */
    private suspend fun download(videoId: String, secret: String, client: okhttp3.OkHttpClient, ahead: Boolean): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE${nameOf(videoId, secret)}.enc")
                    .header("x-farm-token", readToken(secret))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use ByteArray(0)
                    response.body?.bytes() ?: ByteArray(0)
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                // A dropped connection says nothing about the farm: the next hearing may ask again.
                // The message tells a timeout ("timeout") from a route that dropped the call.
                TrackLog.d(TAG, "align-farm: ${it.javaClass.simpleName}${if (ahead) " ahead" else ""} ${it.message?.take(80)}", videoId)
            }.getOrNull()
        }
}
