package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.MusicGateFallback
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
) {

    class Entry(val value: String, val at: Long)

    private val entries = object : LinkedHashMap<String, Entry>(limit, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > limit
    }

    @Volatile
    private var loadedStore: MusicGateFallback.Store? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the saved map once per store; null keeps the cache memory-only. */
    @Synchronized
    fun ensureLoaded(store: MusicGateFallback.Store?) {
        if (store == null || store === loadedStore) return
        loadedStore = store
        entries.clear()
        val raw = runCatching { store.getString(storeKey) }.getOrNull() ?: return
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            root["entries"]?.jsonObject?.forEach { (key, value) ->
                val objectValue = value as? JsonObject ?: return@forEach
                val text = (objectValue["value"] as? JsonPrimitive)?.contentOrNull
                    ?: return@forEach
                val at = (objectValue["at"] as? JsonPrimitive)?.contentOrNull
                    ?.toLongOrNull() ?: 0L
                entries[key] = Entry(text, at)
            }
        }.onFailure { Log.w(TAG, "could not read $storeKey: ${it.message}") }
        // A file written by an older, larger build is trimmed to this one's cap.
        val iterator = entries.keys.iterator()
        while (entries.size > limit && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    @Synchronized
    fun get(key: String): Entry? = entries[key]

    @Synchronized
    fun put(key: String, value: String, at: Long = System.currentTimeMillis()) {
        entries[key] = Entry(value, at)
        persist()
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    /** Tests start from a blank cache and a fresh store. */
    @Synchronized
    fun resetForTest() {
        entries.clear()
        loadedStore = null
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
    }
}
