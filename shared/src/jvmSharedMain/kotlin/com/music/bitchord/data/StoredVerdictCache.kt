package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.MusicGateFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A small, persistent string-to-string cache with a hard size ceiling.
 *
 * The youtube.com fallback derives two kinds of facts that are worth keeping
 * across launches (is this video music, and which square cover belongs to this
 * song) and both are allowed to grow without bound if nothing bounds them. The
 * oldest entry is dropped once [limit] is reached, so a long-lived install
 * cannot turn a cache into a leak. Entries carry the time they were settled,
 * which lets a caller expire the ones that were only a guess.
 */
internal class StoredVerdictCache(
    private val storeKey: String,
    private val limit: Int,
    /**
     * A superseded key whose settled "found" answers are still valid. A
     * version bump must not throw those away; only the old "no answer" values
     * are dropped so they are looked up once under the new rules.
     */
    private val legacyKey: String? = null,
    private val legacyDropValue: String? = null,
) {

    class Entry(val value: String, val at: Long)

    private val entries = object : LinkedHashMap<String, Entry>(limit, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > limit
    }

    @Volatile
    private var loadedStore: MusicGateFallback.Store? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Where the debounced write runs.
     *
     * Every put used to rewrite the whole map — up to a few thousand entries of
     * JSON — on whatever thread asked. A shelf resolving its rows does that
     * several times in a second, so one write is enough and the rest were the
     * same bytes again. [schedulePersist] keeps a single pending job instead:
     * the state it writes is the map as it stands when the job runs, so the
     * newest put is always the one on disk.
     */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The one coalesced write waiting to run, if any. */
    private var pendingWrite: Job? = null

    /** Reads the saved map once per store; null keeps the cache memory-only. */
    @Synchronized
    fun ensureLoaded(store: MusicGateFallback.Store?) {
        if (store == null || store === loadedStore) return
        // Nothing may be lost by moving to another store: whatever is still
        // waiting reaches the old one first.
        if (loadedStore != null) {
            pendingWrite?.cancel()
            pendingWrite = null
            persist()
        }
        loadedStore = store
        entries.clear()
        val raw = runCatching { store.getString(storeKey) }.getOrNull()
        val legacyStoreKey = legacyKey
        if (raw != null) {
            readEntries(raw)
        } else if (legacyStoreKey != null) {
            // The new key has nothing yet, so carry the old settled hits over
            // and let the old misses be looked up once under the new rules.
            val legacy = runCatching { store.getString(legacyStoreKey) }.getOrNull()
            if (legacy != null && readEntries(legacy, dropValue = legacyDropValue)) {
                persist()
            }
        }
        // A file written by an older, larger build is trimmed to this one's cap.
        val iterator = entries.keys.iterator()
        while (entries.size > limit && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    /**
     * Reads one stored payload into [entries]. A missing or unreadable payload
     * leaves them empty; the caller then looks the rows up again.
     *
     * @return true when at least one entry was kept, so a migration is worth
     *   writing back under the new key.
     */
    private fun readEntries(raw: String, dropValue: String? = null): Boolean {
        var kept = false
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            root["entries"]?.jsonObject?.forEach { (key, value) ->
                val objectValue = value as? JsonObject ?: return@forEach
                val text = (objectValue["value"] as? JsonPrimitive)?.contentOrNull
                    ?: return@forEach
                if (text == dropValue) return@forEach
                val at = (objectValue["at"] as? JsonPrimitive)?.contentOrNull
                    ?.toLongOrNull() ?: 0L
                entries[key] = Entry(text, at)
                kept = true
            }
        }.onFailure { Log.w(TAG, "could not read $storeKey: ${it.message}") }
        return kept
    }

    @Synchronized
    fun get(key: String): Entry? = entries[key]

    @Synchronized
    fun put(key: String, value: String, at: Long = System.currentTimeMillis()) {
        entries[key] = Entry(value, at)
        schedulePersist()
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
        pendingWrite?.cancel()
        pendingWrite = null
        persist()
    }

    /** Tests start from a blank cache and a fresh store. */
    @Synchronized
    fun resetForTest() {
        pendingWrite?.cancel()
        pendingWrite = null
        // The last put has to reach the store before the test forgets it.
        persist()
        entries.clear()
        loadedStore = null
    }

    /**
     * One pending write, covering every put until it runs.
     *
     * A put landing while the job is waiting changes nothing: the job reads the
     * map when it finally runs, so it writes the newest state either way.
     */
    private fun schedulePersist() {
        if (pendingWrite?.isActive == true) return
        pendingWrite = writer.launch {
            delay(PERSIST_DELAY_MS)
            synchronized(this@StoredVerdictCache) {
                persist()
                pendingWrite = null
            }
        }
    }

    @Synchronized
    private fun persist() {
        val target = loadedStore ?: return
        val payload = buildJsonObject {
            put("version", 1)
            putJsonObject("entries") {
                entries.forEach { (key, entry) ->
                    putJsonObject(key) {
                        put("value", entry.value)
                        put("at", entry.at)
                    }
                }
            }
        }.toString()
        runCatching { target.putString(storeKey, payload) }
            .onFailure { Log.w(TAG, "could not save $storeKey: ${it.message}") }
    }

    private companion object {
        const val TAG = "BitChord"

        /**
         * How long the coalesced write waits. Long enough that one shelf's
         * worth of puts is a single write, short enough that the answer is on
         * disk before the next launch.
         */
        const val PERSIST_DELAY_MS = 1_500L
    }
}
