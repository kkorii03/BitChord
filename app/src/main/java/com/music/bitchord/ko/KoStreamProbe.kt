package com.music.bitchord.ko

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.StreamRefusalRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [KO] direct-stream-probe
 *
 * Whether a module stream can serve its first bytes, asked *inside* the lookup
 * rather than after the player has already committed to it.
 *
 * [SourceResolver.directStream][com.music.bitchord.data.sources.SourceResolver.directStream]
 * used to hand the addon's URL to the player without ever opening it, so a
 * dead one only showed up once playback was under way and the recovery had to
 * fail, resolve YouTube and start again. Measured on this device:
 *
 * ```
 *   08:49:43  direct: 'Feel Good Inc.' from Deezer at FLAC   ← URL never opened
 *   08:49:45  deezer.nom.deno.net refused the stream: 403
 *   08:49:52  falling back to YouTube audio
 *   08:49:52  TIMING first audio: 11995ms since track selected
 * ```
 *
 * The same ranged read the player would make, done here first, moves that
 * refusal inside the wait: the candidate is dropped, the next one is tried, and
 * when none of them serves, the lookup answers null and
 * [DirectSourceGate][com.music.bitchord.playback.DirectSourceGate] falls back to
 * YouTube through its own path — the same one resolve, one stream, with the
 * failure paid before the first note instead of after it.
 *
 * A probe is not a proof of quality and does not try to be: only a refusal
 * (403/404/410) rejects a candidate. A timeout, a 5xx or a dropped connection
 * answers "yes, try it", because a probe that could not run says nothing about
 * the stream and a slow host must keep the chance it always had.
 *
 * shortcut: the bytes read here are read again by the player, so a healthy
 * start costs one 64KB request; write them into [AudioCache] from the lookup
 * when the cache can be fed a URL without re-resolving it.
 */
internal object KoStreamProbe {

    private const val TAG = "BitChord"

    /** Past the header and the first frames, far short of a track. */
    private const val PROBE_BYTES = 64L * 1024

    /**
     * A healthy host answers the first 64KB well inside this, and a dead one
     * answers 403 at once. Short on purpose: this sits inside the 15s direct
     * lookup budget and must never be what spends it.
     */
    private const val PROBE_TIMEOUT_MS = 2_500L

    /** [Http.client]'s connection pool and DNS, bounded per call rather than per read. */
    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * False only when the server *refused* this stream; true when it served the
     * first bytes, and true when the probe could not reach it at all.
     *
     * A refusal is recorded through [StreamRefusalRecovery] as well, so the
     * player never opens that URL again and the host is rested only once it has
     * refused a second, different stream — see
     * [StreamRefusalRecovery.noteRefusedStream].
     */
    suspend fun servesBytes(stream: SourceStream): Boolean = withContext(Dispatchers.IO) {
        val url = stream.url
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
            .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!StreamRefusalRecovery.isRefusal(response.code)) return@withContext true
                refused(url, response.code, response.headers.toMultimap())
                false
            }
        } catch (e: Exception) {
            // Not a refusal: a probe that could not run proves nothing, and the
            // stream keeps the chance it had before this existed.
            TrackLog.d(
                TAG,
                "direct probe could not reach ${HqHostTracker.hostOf(url) ?: "?"}: ${e.message}",
            )
            true
        }
    }

    /** Records the refusal so the URL is never opened again and a dead host is rested. */
    private fun refused(url: String, code: Int, headers: Map<String, List<String>>) {
        val host = HqHostTracker.hostOf(url)
        val error = HttpDataSource.InvalidResponseCodeException(
            code,
            "direct probe",
            IOException("HTTP $code"),
            headers,
            DataSpec.Builder().setUri(android.net.Uri.parse(url)).build(),
            ByteArray(0),
        )
        if (StreamRefusalRecovery.noteRefusedStream(url, host, error)) {
            val restMs = HqHostTracker.noteRateLimited(host, header = null, code = code)
            TrackLog.w(TAG, "hq-stream: $host refused $code again; resting it ${restMs / 1000}s")
        } else {
            TrackLog.w(TAG, "hq-stream: $host refused $code; not starting this track on it")
        }
    }
}
