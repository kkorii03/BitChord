package com.music.bitchord.ko

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.music.bitchord.data.MonotonicClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [KO] Runs yt-dlp's EJS once over a full player script and returns its
 * preprocessed player — the step InnerTubeX itself caps at 30 s
 * (see [KoEjsPlayerPrep] for why that cap left every song re-solving).
 *
 * Same bootstrap as InnerTubeX's EjsChallengeSolver (YouTube globals, then
 * the lib and core bundles, then `jsc`), in a runtime of its own that is
 * closed when done. The player script reaches JS through a function binding
 * instead of being escaped into a string literal inside the source: that
 * literal copy is the 15.6 MB allocation that hit OutOfMemoryError at the
 * 192 MB heap cap in rs22 182b.
 */
object KoEjsQuickJs {
    // InnerTubeX's figure: a ~2.6 MiB player needs more than 128 MiB while EJS builds its AST.
    private const val NATIVE_MEMORY_LIMIT_BYTES = 192L * 1024L * 1024L
    private const val MAX_STACK_SIZE_BYTES = 256L * 1024L

    suspend fun preprocess(playerJs: String, lib: String, core: String): String? =
        withContext(Dispatchers.Default) {
            // One budget for the whole run, so it always ends before KoEjsPlayerPrep.WAIT_MS does.
            val deadline = MonotonicClock.nowMs() + KoEjsPlayerPrep.EVAL_BUDGET_MS
            val qjs = QuickJs.create(Dispatchers.Default)
            try {
                qjs.memoryLimit = NATIVE_MEMORY_LIMIT_BYTES
                qjs.function("__bcPlayerJs") { _ -> playerJs }
                run(qjs, deadline, YOUTUBE_GLOBALS) ?: return@withContext null
                run(qjs, deadline, lib) ?: return@withContext null
                run(qjs, deadline, "Object.assign(globalThis, lib);") ?: return@withContext null
                run(qjs, deadline, core) ?: return@withContext null
                if (!budget(qjs, deadline)) return@withContext null
                qjs.evaluate<String?>(SOLVE)?.takeIf { it.isNotEmpty() }
            } finally {
                qjs.close()
            }
        }

    /** Gives the next evaluate only what is left of the run's budget; false when nothing is left. */
    private fun budget(qjs: QuickJs, deadline: Long): Boolean {
        val left = deadline - MonotonicClock.nowMs()
        if (left <= 0) return false
        qjs.evaluationTimeoutMillis = left
        // QuickJS measures its stack limit from the current native thread; refresh before each call.
        qjs.maxStackSize = MAX_STACK_SIZE_BYTES
        return true
    }

    private suspend fun run(qjs: QuickJs, deadline: Long, code: String): Unit? {
        if (!budget(qjs, deadline)) return null
        qjs.evaluate<Any?>("$code\n;undefined;")
        return Unit
    }

    /** One throwaway challenge, as InnerTubeX's own player preload asks; only the preprocessed player is kept. */
    private val SOLVE = """
        (function() {
          var r = jsc({"type":"player","player":__bcPlayerJs(),"output_preprocessed":true,
                       "requests":[{"type":"sig","challenges":["prewarm"]}]});
          if (!r || r.type !== "result" || typeof r.preprocessed_player !== "string") return "";
          // The throwaway challenge must have been answered too, or the output is not trusted.
          if (!r.responses || !r.responses[0] || r.responses[0].type !== "result") return "";
          return r.preprocessed_player;
        })()
    """.trimIndent()

    /** InnerTubeX's QuickJsEngine.setupYoutubeGlobals, verbatim: the player expects a browser-ish global. */
    private val YOUTUBE_GLOBALS = """
        if (typeof globalThis.XMLHttpRequest === "undefined") {
            globalThis.XMLHttpRequest = { prototype: {} };
        }
        if (typeof URL === "undefined") {
            globalThis.location = {
                hash: "",
                host: "www.youtube.com",
                hostname: "www.youtube.com",
                href: "https://www.youtube.com/watch?v=yt-dlp-wins",
                origin: "https://www.youtube.com",
                password: "",
                pathname: "/watch",
                port: "",
                protocol: "https:",
                search: "?v=yt-dlp-wins",
                username: "",
            };
        } else {
            globalThis.location = new URL("https://www.youtube.com/watch?v=yt-dlp-wins");
        }
        if (typeof globalThis.document === "undefined") {
            globalThis.document = Object.create(null);
        }
        if (typeof globalThis.navigator === "undefined") {
            globalThis.navigator = Object.create(null);
        }
        if (typeof globalThis.self === "undefined") {
            globalThis.self = globalThis;
        }
        if (typeof globalThis.window === "undefined") {
            globalThis.window = globalThis;
        }
        if (typeof globalThis.Intl === "undefined") {
            const NumberFormat = function(locale, options) {
                this.options = options || {};
            };
            NumberFormat.supportedLocalesOf = function(locales) {
                return Array.isArray(locales) ? locales : [locales];
            };
            NumberFormat.prototype.format = function(value) {
                let formatted = String(value);
                const minimumDigits = this.options.minimumIntegerDigits || 0;
                while (formatted.length < minimumDigits) formatted = "0" + formatted;
                return formatted;
            };
            const DateTimeFormat = function() {};
            DateTimeFormat.prototype.resolvedOptions = function() {
                return { timeZone: "UTC" };
            };
            DateTimeFormat.prototype.format = function(value) {
                return String(value);
            };
            globalThis.Intl = { NumberFormat, DateTimeFormat };
        }
    """.trimIndent()
}
