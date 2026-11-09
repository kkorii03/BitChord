package com.music.bitchord.ko

import com.music.bitchord.data.Http
import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * [KO] Passes every player config through and reports its player URL — the exact
 * string InnerTubeX later hands its cipher service, so [KoEjsPlayerPrep] can
 * match the library's cache key instead of guessing the URL's locale or variant.
 */
class KoPlayerUrlTap(
    private val inner: YtConfigParser,
    private val seen: (String) -> Unit,
) : YtConfigParser {
    override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig =
        inner.fetchConfig(videoId, useLoginCookies).also { seen(it.playerUrl) }

    override suspend fun fetchEmbeddedConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig =
        inner.fetchEmbeddedConfig(videoId, useLoginCookies).also { seen(it.playerUrl) }
}

/**
 * [KO] Makes InnerTubeX's EJS "preprocessed player" once per YouTube player
 * version, without the library's 30 s QuickJS cap, and hands it back under the
 * library's own cache key.
 *
 * Why: InnerTubeX's cipher path (TVHTML5 and friends) solves each song's
 * signature with EJS. With a preprocessed player that is 2-15 s on the emulator;
 * without one it is a full solve of the 2.6 MB player JS, which the library caps
 * at 30 s. When the full solve ran over (rs22-rs24: 51 `EJS solve failed
 * QuickJsInterruptedException`, 187b 10 of 10), nothing was ever saved, so every
 * later song repeated the same doomed solve, then ran a parser fallback that took
 * 88-351 s the first time and never produced a URL — all while holding the
 * library's one cipher lock, so later rows waited minutes.
 *
 * How: the library asks its preprocessed-player store ([onMissing]) whenever it
 * has none in memory. The key is sha1("$playerUrl:$bundleHash"); the player URLs
 * come from the player configs the extractor fetched ([onPlayerUrl]), so the key
 * is matched exactly, never guessed. One run per key per process: concurrent asks
 * share it, a cancelled ask (a skipped track) does not cancel it, and a key that
 * was already tried is not tried again (no repeated CPU burn on a player EJS
 * cannot handle). Nothing here runs on a timer; it only runs on a miss.
 */
class KoEjsPlayerPrep(
    private val bundleHash: () -> String?,
    private val download: suspend (playerUrl: String) -> String?,
    private val preprocess: suspend (playerJs: String) -> String?,
    private val store: (key: String, value: String) -> Unit,
    private val scope: CoroutineScope,
    private val waitMs: Long = WAIT_MS,
) {
    private val lock = Any()
    private val seenUrls = ArrayDeque<String>()
    private val running = HashMap<String, Deferred<String?>>()
    private val tried = HashSet<String>()

    /** A player URL the extractor will hand the cipher service. */
    fun onPlayerUrl(url: String) {
        if (url.isBlank()) return
        synchronized(lock) {
            seenUrls.remove(url)
            seenUrls.addLast(url)
            while (seenUrls.size > MAX_URLS) seenUrls.removeFirst()
        }
    }

    /**
     * InnerTubeX has no preprocessed player stored under [key]. Returns one made
     * now (or by a run already in flight), or null so the library carries on
     * exactly as before.
     */
    suspend fun onMissing(key: String): String? {
        val run = synchronized(lock) {
            running[key] ?: run {
                if (key in tried) return null
                val hash = bundleHash() ?: return null
                val url = seenUrls.firstOrNull { sha1("$it:$hash") == key } ?: return null
                tried += key
                scope.async { make(key, url) }.also { deferred ->
                    running[key] = deferred
                    // Drop the reference once done: the result is megabytes and
                    // already on disk; the library keeps its own copy in memory.
                    deferred.invokeOnCompletion { synchronized(lock) { running.remove(key) } }
                }
            }
        }
        return withTimeoutOrNull(waitMs) { run.await() }
    }

    private suspend fun make(key: String, url: String): String? {
        val start = MonotonicClock.nowMs()
        return try {
            val js = fetchPlayer(url)?.takeIf { it.isNotBlank() && it.length <= MAX_PLAYER_JS_LENGTH }
            if (js == null) {
                TrackLog.w(TAG, "EJS player prep: no player JS for ${playerId(url)}")
                return null
            }
            val out = preprocess(js)?.takeIf { it.isNotBlank() && it.length <= MAX_PLAYER_JS_LENGTH }
            if (out == null) {
                TrackLog.w(TAG, "EJS player prep failed player=${playerId(url)} elapsed=${MonotonicClock.nowMs() - start}ms")
                return null
            }
            store(key, out)
            TrackLog.d(TAG, "EJS player prep done player=${playerId(url)} size=${out.length} elapsed=${MonotonicClock.nowMs() - start}ms")
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // OutOfMemoryError included: a failed prep must leave playback on the
            // library's own path, never take the process down.
            TrackLog.w(TAG, "EJS player prep failed player=${playerId(url)} type=${e::class.simpleName}")
            null
        }
    }

    /**
     * One retry after a transient download failure ([download] throws
     * IOException for a network error or a retryable status). A failed solve
     * is never retried: that is the expensive part.
     */
    private suspend fun fetchPlayer(url: String): String? {
        try {
            return download(url)
        } catch (e: IOException) {
            TrackLog.w(TAG, "EJS player prep: player JS download failed (${e::class.simpleName}), retrying once")
        }
        delay(DOWNLOAD_RETRY_DELAY_MS)
        return download(url)
    }

    /** The EJS solver bundle InnerTubeX ships, and the hash it puts in its cache keys. */
    class Bundle(val lib: String, val core: String) {
        val hash: String = sha1(lib + core)
    }

    companion object {
        private const val TAG = "BitChord"

        /** Same bound InnerTubeX applies to a player or a preprocessed player. */
        const val MAX_PLAYER_JS_LENGTH = 8 * 1024 * 1024

        /** Each player JS download attempt; two attempts at most. */
        const val DOWNLOAD_ATTEMPT_MS = 12_000L
        const val DOWNLOAD_RETRY_DELAY_MS = 1_000L

        /**
         * One budget for every QuickJS evaluate of a run (bootstrap + solve):
         * each evaluate gets only what is left of it.
         */
        const val EVAL_BUDGET_MS = 90_000L

        /**
         * Longer than the longest possible run (two downloads, the retry delay,
         * the whole eval budget, plus slack for runtime creation), so the library
         * never starts its own full solve while ours is still evaluating: two
         * full EJS solves at once is ~2 x 150 MB native on the slow devices that
         * already hit OOM.
         */
        const val WAIT_MS = 2 * DOWNLOAD_ATTEMPT_MS + DOWNLOAD_RETRY_DELAY_MS + EVAL_BUDGET_MS + 10_000L
        private const val MAX_URLS = 4

        /** Read the way InnerTubeX's loader reads it, so the hash matches its keys. */
        fun classpathBundle(): Bundle? = runCatching {
            Bundle(resource("yt.solver.lib.min.js"), resource("yt.solver.core.min.js"))
        }.getOrNull()

        private fun resource(name: String): String {
            val path = "yt_ejs/$name"
            val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
                ?: KoEjsPlayerPrep::class.java.classLoader?.getResourceAsStream(path)
                ?: error("missing $path")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        /**
         * The production wiring: the EJS bundle from the classpath, the player
         * JS fetched once, and [runEjs] doing the QuickJS work (the app's, since
         * QuickJS is an Android dependency).
         */
        fun create(
            runEjs: suspend (playerJs: String, lib: String, core: String) -> String?,
            store: (key: String, value: String) -> Unit,
            scope: CoroutineScope,
        ): KoEjsPlayerPrep {
            val bundleHash by lazy { classpathBundle()?.hash }
            return KoEjsPlayerPrep(
                bundleHash = { bundleHash },
                download = ::downloadPlayer,
                preprocess = { js -> classpathBundle()?.let { runEjs(js, it.lib, it.core) } },
                store = store,
                scope = scope,
            )
        }

        private val playerClient by lazy {
            Http.client.newBuilder().callTimeout(DOWNLOAD_ATTEMPT_MS, TimeUnit.MILLISECONDS).build()
        }

        /** Null for a definitive answer; IOException (network, timeout, 408/425/429/5xx) for a retryable one. */
        private suspend fun downloadPlayer(url: String): String? = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Referer", "https://www.youtube.com/").build()
            playerClient.newCall(request).execute().use { response ->
                if (response.code == 408 || response.code == 425 || response.code == 429 || response.code >= 500) {
                    throw IOException("HTTP ${response.code}")
                }
                if (!response.isSuccessful) return@use null
                val body = response.body
                if (body.contentLength() > MAX_PLAYER_JS_LENGTH) return@use null
                body.string()
            }
        }

        private fun playerId(url: String): String =
            Regex("/s/player/([^/]+)/").find(url)?.groupValues?.get(1) ?: "unknown"

        /** InnerTubeX's sha1: SHA-1 of the UTF-8 bytes, lowercase hex. */
        internal fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
