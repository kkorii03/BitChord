package com.music.bitchord.data.innertube

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers whether YouTube Music is usable from this network, per signed-in
 * listener, and remembers it across launches.
 *
 * Korea without YouTube Music Premium answers `music.youtube.com` with a
 * Premium upsell and no shelves, while `www.youtube.com` still works and still
 * knows the account's own history, likes and mixes. That is a property of the
 * network the request leaves from, so it is detected from responses rather than
 * guessed from the phone's country, and the verdict is stored per identity
 * because one account being gated says nothing about the next one.
 *
 * Detection is deliberately passive: [observeBrowse] and [observePlayer] read
 * responses the app was already making. The only extra request is the daily
 * re-check [shouldTryMusic] permits, which is one browse on the first Home load
 * of a new day.
 */
object MusicGateFallback {

    private const val TAG = "BitChord"
    private const val STORE_KEY = "music_gate_fallback_v1"
    private const val FORCE_KEY = "music_gate_force_v1"

    /** How long a GATED verdict is trusted before one request re-checks it. */
    const val RECHECK_MS = 24L * 60 * 60 * 1000

    /** A tiny string store; the phone's SharedPreferences, the desktop's preferences file. */
    interface Store {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
    }

    enum class State { UNKNOWN, OPEN, GATED }

    enum class Signal { NONE, OPEN, GATED }

    private class Entry(var state: State, var checkedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    private val _active = MutableStateFlow(false)

    /** Whether the identity in use right now should be served by the YouTube fallback. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    private var store: Store? = null

    @Volatile
    private var loaded = false

    /**
     * The debug-only override, persisted beside the verdicts.
     *
     * It makes every identity read as gated, which is how the emulator tour
     * reproduces the Korea-without-Premium Home from a network that would
     * otherwise answer YouTube Music normally. Only a debug build is allowed
     * to turn it on (see [setDebugOverridesAllowed]); a release build ignores
     * a flag left behind by one.
     */
    @Volatile
    private var forced = false

    @Volatile
    private var debugOverridesAllowed = true

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether [forceForTesting] may take effect. The application sets this to
     * `BuildConfig.DEBUG` before [init], so a release build can never be forced
     * by a flag a debug build wrote into the same preferences.
     */
    fun setDebugOverridesAllowed(allowed: Boolean) {
        debugOverridesAllowed = allowed
    }

    /** Call once at startup, before the first request. */
    @Synchronized
    fun init(store: Store) {
        this.store = store
        if (loaded) return
        loaded = true
        forced = debugOverridesAllowed &&
            runCatching { store.getString(FORCE_KEY) == "true" }.getOrDefault(false)
        if (!debugOverridesAllowed) {
            runCatching { store.putString(FORCE_KEY, "false") }
        }
        val raw = runCatching { store.getString(STORE_KEY) }.getOrNull()
        if (raw != null) {
            runCatching {
                val root = json.parseToJsonElement(raw).jsonObject
                root["entries"]?.jsonObject?.forEach { (key, value) ->
                    val objectValue = value as? JsonObject ?: return@forEach
                    val state = when (objectValue.string("state")) {
                        "GATED" -> State.GATED
                        "OPEN" -> State.OPEN
                        else -> State.UNKNOWN
                    }
                    val at = objectValue.string("at")?.toLongOrNull() ?: 0L
                    entries[key] = Entry(state, at)
                }
            }.onFailure { Log.w(TAG, "could not read the music-gate state: ${it.message}") }
        }
        refreshActive()
    }

    /**
     * The same small key-value store, for the other persistent verdicts the
     * youtube.com fallback keeps (the music classifier and square-cover cache).
     *
     * Sharing it rather than adding a second preferences file keeps all of the
     * fallback's derived state in one place, and a store that has not been
     * installed yet reads as "memory only", which is the safe answer for a
     * cache.
     */
    internal fun sharedStore(): Store? = store

    /** The identity gate state belongs to; anonymous has one shared entry. */
    fun currentKey(): String = Innertube.sessionKey()

    fun state(key: String = currentKey()): State =
        if (forced) State.GATED else (entries[key]?.state ?: State.UNKNOWN)

    fun isActive(key: String = currentKey()): Boolean = state(key) == State.GATED

    /**
     * Makes every identity read as gated, or gives the real verdicts back.
     *
     * Debug builds only — the application allows it through
     * [setDebugOverridesAllowed], and the flag is persisted so the emulator
     * tour can force the gate on one launch and read the same Home after the
     * restart it performs.
     */
    @Synchronized
    fun forceForTesting(on: Boolean) {
        if (on && !debugOverridesAllowed) return
        forced = on
        runCatching { store?.putString(FORCE_KEY, on.toString()) }
            .onFailure { Log.w(TAG, "could not save the music-gate override: ${it.message}") }
        refreshActive()
    }

    /**
     * Whether asking music.youtube.com is still worthwhile.
     *
     * False only for a GATED verdict made less than [RECHECK_MS] ago. The first
     * Home load after that window asks once more, so a Premium subscription or
     * a network change is noticed within a day without anything polling.
     */
    fun shouldTryMusic(key: String = currentKey(), now: Long = System.currentTimeMillis()): Boolean {
        if (forced) return false
        val entry = entries[key] ?: return true
        return entry.state != State.GATED || now - entry.checkedAt >= RECHECK_MS
    }

    /** True when a stored GATED verdict has aged past its once-a-day re-check. */
    fun needsRecheck(key: String = currentKey(), now: Long = System.currentTimeMillis()): Boolean {
        if (forced) return false
        val entry = entries[key] ?: return false
        return entry.state == State.GATED && now - entry.checkedAt >= RECHECK_MS
    }

    /**
     * Forgets every identity's verdict after the network they were made on
     * changed — currently when the proxy settings move.
     *
     * A GATED verdict describes the old route, so it must not keep Home on the
     * youtube.com fallback for a day after a working proxy was configured. The
     * entries are kept (so they are persisted as UNKNOWN) rather than removed,
     * which also makes the next re-check happen immediately through
     * [shouldTryMusic].
     */
    @Synchronized
    fun resetAll() {
        if (entries.isEmpty()) return
        entries.keys.forEach { entries[it] = Entry(State.UNKNOWN, 0L) }
        persist()
        refreshActive()
    }

    fun markOpen(key: String = currentKey(), now: Long = System.currentTimeMillis()) {
        val changed = entries[key]?.state != State.OPEN
        if (!changed) return
        update(key, State.OPEN, now)
        Log.d(TAG, "music gate: YouTube Music usable for ${mask(key)}")
    }

    fun markGated(
        reason: String,
        key: String = currentKey(),
        now: Long = System.currentTimeMillis(),
    ) {
        val existing = entries[key]
        val changed = existing?.state != State.GATED
        // A player refusal while the verdict is already fresh is not new
        // information and does not need a preference write.
        if (!changed && now - (existing?.checkedAt ?: 0) < RECHECK_MS) return
        update(key, State.GATED, now)
        if (changed) {
            Log.w(TAG, "music gate: YouTube Music restricted for ${mask(key)} ($reason)")
        }
    }

    /** Records that a re-check happened even when it could not answer either way. */
    @Synchronized
    fun touch(key: String = currentKey(), now: Long = System.currentTimeMillis()) {
        entries.computeIfAbsent(key) { Entry(State.UNKNOWN, now) }.checkedAt = now
        persist()
    }

    /** Reads a music.youtube.com browse response the app just received. */
    fun observeBrowse(browseId: String, root: JsonObject): Signal {
        val key = currentKey()
        return when (browseId) {
            "FEmusic_home" -> when {
                isGatedHome(root) -> {
                    markGated("home is empty and carries a Premium upsell", key)
                    Signal.GATED
                }
                else -> {
                    markOpen(key)
                    Signal.OPEN
                }
            }
            "FEmusic_moods_and_genres" -> when {
                isGatedMoods(root) -> {
                    markGated("moods and genres carry no items", key)
                    Signal.GATED
                }
                else -> {
                    markOpen(key)
                    Signal.OPEN
                }
            }
            else -> Signal.NONE
        }
    }

    /** Reads a music.youtube.com player response, when one is seen. */
    fun observePlayer(root: JsonObject): Signal {
        if (!isGatedPlayer(root)) return Signal.NONE
        markGated("player is UNPLAYABLE for a Premium reason")
        return Signal.GATED
    }

    /** Records a stream resolver refusal that names the Premium gate. */
    fun observePremiumFailure(message: String?) {
        val reason = message.orEmpty()
        if (!PREMIUM_FAILURE.containsMatchIn(reason)) return
        markGated("stream resolver: ${reason.take(120)}")
    }

    /** Recomputes [active] after a sign-in, a profile switch, or a restart. */
    fun refreshActive() {
        _active.value = isActive()
    }

    fun onSessionChanged() {
        refreshActive()
    }

    @Synchronized
    private fun update(key: String, state: State, at: Long) {
        entries[key] = Entry(state, at)
        persist()
        refreshActive()
    }

    @Synchronized
    private fun persist() {
        val target = store ?: return
        val payload = buildJsonObject {
            put("version", 1)
            putJsonObject("entries") {
                entries.forEach { (key, entry) ->
                    putJsonObject(key) {
                        put("state", entry.state.name)
                        put("at", entry.checkedAt)
                    }
                }
            }
        }.toString()
        runCatching { target.putString(STORE_KEY, payload) }
            .onFailure { Log.w(TAG, "could not save the music-gate state: ${it.message}") }
    }

    // ---- Pure detection -----------------------------------------------------

    fun isGatedHome(root: JsonObject): Boolean =
        musicHomeShelfCount(root) == 0 && hasPremiumUpsell(root)

    fun isGatedMoods(root: JsonObject): Boolean = musicMoodItemCount(root) == 0

    fun isGatedPlayer(root: JsonObject): Boolean {
        val status = findFirst(root, "playabilityStatus") ?: return false
        if (status.string("status") != "UNPLAYABLE") return false
        val text = buildString {
            append(status.string("reason").orEmpty())
            append(' ')
            append(status.string("subreason").orEmpty())
            append(' ')
            collectStrings(status, this)
        }
        return PREMIUM_FAILURE.containsMatchIn(text)
    }

    fun musicHomeShelfCount(root: JsonElement): Int =
        countFilledShelves(root, MUSIC_SHELF_KEYS)

    fun musicMoodItemCount(root: JsonElement): Int =
        countKeys(root, NAVIGATION_BUTTONS) + countFilledShelves(root, MUSIC_SHELF_KEYS)

    fun hasPremiumUpsell(root: JsonElement): Boolean {
        val text = StringBuilder()
        collectStrings(root, text)
        return PREMIUM_UPSELL.containsMatchIn(text)
    }

    // ---- Internals ----------------------------------------------------------

    private val MUSIC_SHELF_KEYS = setOf(
        "musicCarouselShelfRenderer",
        "musicShelfRenderer",
        "musicCardShelfRenderer",
    )

    private val NAVIGATION_BUTTONS = setOf("musicNavigationButtonRenderer")

    private val PREMIUM_UPSELL = Regex(
        """(?i)(youtube\s*music\s*premium|music\s*premium|premium\s*(?:회원|구독|membership)|프리미엄|youtubemusicpremiumcontent)""",
    )

    private val PREMIUM_FAILURE = Regex(
        """(?i)(youtubemusicpremiumcontent|youtube\s*music\s*premium|music\s*premium|프리미엄)""",
    )

    private fun countKeys(element: JsonElement, keys: Set<String>): Int = when (element) {
        is JsonObject -> element.entries.sumOf { (key, value) ->
            (if (key in keys) 1 else 0) + countKeys(value, keys)
        }
        is JsonArray -> element.sumOf { countKeys(it, keys) }
        else -> 0
    }

    /**
     * Shelves that actually hold rows, not merely an empty renderer.
     *
     * A Premium upsell can arrive as a carousel of its own; counting the
     * renderer name alone would read that as content and miss the gate.
     */
    private fun countFilledShelves(element: JsonElement, keys: Set<String>): Int = when (element) {
        is JsonObject -> element.entries.sumOf { (key, value) ->
            val own = if (
                key in keys &&
                value is JsonObject &&
                (value["contents"] as? JsonArray)?.isNotEmpty() == true
            ) {
                1
            } else {
                0
            }
            own + countFilledShelves(value, keys)
        }
        is JsonArray -> element.sumOf { countFilledShelves(it, keys) }
        else -> 0
    }

    private fun findFirst(element: JsonElement, key: String): JsonObject? = when (element) {
        is JsonObject -> {
            (element[key] as? JsonObject)
                ?: element.values.firstNotNullOfOrNull { findFirst(it, key) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { findFirst(it, key) }
        else -> null
    }

    private fun collectStrings(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonObject -> element.values.forEach { collectStrings(it, out) }
            is JsonArray -> element.forEach { collectStrings(it, out) }
            is JsonPrimitive -> {
                element.contentOrNull?.let {
                    out.append(it)
                    out.append(' ')
                }
            }
        }
    }

    private fun JsonElement.string(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    /** Enough of an identity to read in a log line, not enough to be a credential. */
    private fun mask(key: String): String =
        if (key == "anonymous") "anonymous" else "…${key.takeLast(6)}"

    /** The stored verdicts, keys masked the same way its own log lines are. */
    fun describe(now: Long = System.currentTimeMillis()): List<String> =
        entries.entries
            .sortedBy { it.key }
            .map { (key, entry) ->
                val age = if (entry.checkedAt <= 0) "never" else "${(now - entry.checkedAt) / 60_000L}m ago"
                "${mask(key)} ${entry.state} checked=$age"
            }

    /** Tests reset the singleton between cases. */
    internal fun resetForTest() {
        entries.clear()
        store = null
        loaded = false
        forced = false
        debugOverridesAllowed = true
        _active.value = false
    }
}
