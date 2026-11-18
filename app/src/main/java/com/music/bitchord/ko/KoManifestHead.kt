package com.music.bitchord.ko

import androidx.media3.common.MimeTypes
import com.music.bitchord.download.ManifestFile
import com.music.bitchord.download.Mp4Boxes
import com.music.bitchord.download.OfflineDash
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * [KO] manifest-verify. The opening of a DASH or HLS stream, rejoined into one
 * file the sound check can decode.
 *
 * Phone 11-16..18 (vc210test): kkorii12 answered 57 checks; 53 ended
 * "unmeasured" within 3 ms, because the verifier refused every manifest before
 * fetching a byte. Every swap needs a measured match, so none of those rows
 * could ever be played. The download path already rejoins these CMAF segments
 * ([ManifestFile]); this reuses it for the first [seconds] only.
 *
 * Anything it cannot read (encrypted, multi-period, no init segment, a master
 * playlist) throws, so the row stays unmeasured and is never swapped in blind.
 */
internal object KoManifestHead {

    /** A segment or index request the server refused; mapped by the caller to its own rate-limit rules. */
    class Refused(val code: Int, val headers: Map<String, List<String>>) : IOException("HTTP $code")

    class Plan(val initialization: String, val media: List<String>)

    fun isDash(url: String): Boolean = StreamContainer.manifestMimeOf(url) == MimeTypes.APPLICATION_MPD

    /** The init segment and the media segments that cover the first [seconds]. */
    fun plan(index: String, dash: Boolean, seconds: Double): Plan = try {
        if (dash) {
            val all = OfflineDash.parse(index)
            Plan(all.initialization, opening(all.media, all.seconds, seconds))
        } else {
            val (init, media) = ManifestFile.hlsSegments(index)
            Plan(init, opening(media, hlsDurations(index), seconds))
        }
    } catch (unreadable: IllegalStateException) {
        throw IOException("manifest: ${unreadable.message}")
    }

    /** The leading segments whose lengths reach [seconds]; all of them when a length is unknown. */
    internal fun opening(media: List<String>, durations: List<Double>?, seconds: Double): List<String> {
        if (durations == null || durations.size != media.size) return media
        var covered = 0.0
        var count = 0
        while (count < media.size && covered < seconds) {
            covered += durations[count]
            count++
        }
        return media.subList(0, count.coerceAtLeast(1))
    }

    /** Each media segment's `#EXTINF` length in order, or null when one is missing. */
    internal fun hlsDurations(playlist: String): List<Double>? {
        val out = mutableListOf<Double>()
        var pending: Double? = null
        for (line in playlist.lineSequence().map(String::trim)) {
            when {
                line.startsWith("#EXTINF:") ->
                    pending = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                line.isEmpty() || line.startsWith('#') -> Unit
                else -> {
                    out += pending ?: return null
                    pending = null
                }
            }
        }
        return out
    }

    /** FLAC samples become a native `.flac`; anything else stays a fragmented MP4. */
    internal fun extensionFor(init: ByteArray): String {
        val stsd = Mp4Boxes.find(init, "moov", "trak", "mdia", "minf", "stbl", "stsd") ?: return "m4a"
        val entry = Mp4Boxes.boxes(init, stsd.content + 8, stsd.end).firstOrNull()
        return if (entry?.type == "fLaC") "flac" else "m4a"
    }

    /**
     * Fetches the index, the init segment and the opening segments, and writes
     * them to [file] as one decodable file. Stops adding segments once [limit]
     * bytes are in. Returns the bytes written.
     */
    suspend fun fetchOpening(
        url: String,
        headers: Map<String, String>,
        file: File,
        limit: Long,
        seconds: Double,
        client: OkHttpClient,
    ): Long = withContext(Dispatchers.IO) {
        val scope = coroutineContext
        // The same rule as the single-file head: cancelling the verification
        // closes the request in flight instead of waiting out the socket.
        val current = AtomicReference<Call?>()
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                current.get()?.cancel()
            }
        }
        try {
            fun fetch(target: String): ByteArray {
                scope.ensureActive()
                val call = client.newCall(
                    Request.Builder().url(target).apply { headers.forEach { (name, value) -> header(name, value) } }.build(),
                )
                current.set(call)
                return call.execute().use { response ->
                    if (!response.isSuccessful) throw Refused(response.code, response.headers.toMultimap())
                    response.body?.bytes() ?: throw IOException("manifest: empty answer")
                }
            }
            val base = url.toHttpUrlOrNull() ?: throw IOException("manifest: invalid url")
            fun resolve(ref: String) = base.resolve(ref)?.toString() ?: throw IOException("manifest: invalid segment url")

            val plan = plan(String(fetch(url), Charsets.UTF_8), isDash(url), seconds)
            val init = fetch(resolve(plan.initialization))
            var fetched = init.size.toLong()
            val media = sequence {
                for (ref in plan.media) {
                    if (fetched >= limit) break
                    val segment = fetch(resolve(ref))
                    fetched += segment.size
                    yield(segment)
                }
            }
            try {
                FileOutputStream(file).use { out ->
                    // Beside the candidate, so the FLAC rebuild's temporary frames file is cleaned with it.
                    val directory = file.absoluteFile.parentFile ?: throw IOException("manifest: no cache directory")
                    ManifestFile.assemble(directory, extensionFor(init), init, media, out)
                }
            } catch (cancelled: CancellationException) {
                // A CancellationException is an IllegalStateException too.
                throw cancelled
            } catch (unreadable: IllegalStateException) {
                throw IOException("manifest: ${unreadable.message}")
            }
            file.length()
        } finally {
            watchdog.cancel()
        }
    }
}
