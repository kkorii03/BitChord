package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.startup.StartupWork
import java.util.concurrent.Executors

/**
 * What's been searched for lately, kept on this device only.
 *
 * YouTube Music has an account-level search history, but reading it back needs
 * a separate signed-in call on every keystroke's worth of empty query — and it
 * would be wrong for a signed-out user. A local list is instant, works either
 * way, and is what Spotify and Apple Music show in the same spot.
 *
 * Stored as JSON rather than a delimited string: a query can contain any
 * character, separators included.
 */
object SearchHistory {

    /** Deep enough to be useful, shallow enough that the list stays scannable. */
    private const val MAX_ENTRIES = 20

    private lateinit var prefs: SharedPreferences

    /** Where the settings file is opened from, once [init] has been told. */
    private var appContext: Context? = null

    /** Set once [open] has finished, so the startup open and an early caller run it once. */
    @Volatile
    private var opened = false

    private val openLock = Any()

    /**
     * One thread for the encode and the preference write, in call order.
     *
     * Recording is a tap-time action on the main thread; the list is small but
     * JSON-encoding it and handing it to SharedPreferences need not happen
     * there. A single thread rather than a pool because two writes may land
     * back to back (tap a suggestion, then another) and the last one has to win.
     */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "search-history").apply { isDaemon = true }
    }

    private val json = Json
    private val serializer = ListSerializer(SearchHistoryEntity.serializer())
    private val addonSerializer = ListSerializer(String.serializer())

    private val _recent = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())

    /**
     * Most recent first.
     *
     * Served from memory so a collector can subscribe before [open] has run:
     * the loaded list is published into this same flow when it arrives.
     */
    val recent: StateFlow<List<SearchHistoryEntity>> = _recent.asStateFlow()

    private val _addonRecent = MutableStateFlow<List<String>>(emptyList())

    /**
     * What was typed into the Addons search, most recent first. Text only: an
     * addon search has no entity worth remembering, and re-running the words
     * is what a tap on it should do.
     */
    val addonRecent: StateFlow<List<String>> = _addonRecent.asStateFlow()

    fun recordAddonQuery(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        updateAddons { existing ->
            listOf(text) + existing.filterNot { it.equals(text, ignoreCase = true) }
        }
    }

    fun removeAddonQuery(query: String) = updateAddons { existing ->
        existing.filterNot { it == query }
    }

    fun clearAddonQueries() = updateAddons { emptyList() }

    /**
     * Applies [transform] to the addon list after [open] has loaded it, on the
     * writer thread.
     *
     * The list used to be read at the call site before [save] opened the store.
     * The first Addons search after launch therefore started from the still
     * empty in-memory value and wrote that over the stored one, so every
     * restart lost the addon history — and removing the first entry could wipe
     * all of it. Opening first on the single writer thread also keeps the
     * decode off the main thread and leaves add/remove calls in order.
     */
    private fun updateAddons(transform: (List<String>) -> List<String>) {
        writer.execute {
            open()
            val next = transform(_addonRecent.value).take(MAX_ENTRIES)
            _addonRecent.value = next
            if (!this::prefs.isInitialized) return@execute
            prefs.edit()
                .putString(KEY_ADDON_HISTORY, json.encodeToString(addonSerializer, next))
                .apply()
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // The search screen is not on the first screen. This shares the file
        // AppSettings already opened, so only the decode moves off the launch
        // path; a caller that gets there first runs [open] itself.
        StartupWork.background("search-history") { open() }
    }

    private fun open() {
        synchronized(openLock) {
            if (opened) return
            val context = appContext ?: return
            prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
            load()
            opened = true
        }
    }

    /**
     * Re-reads the list off disk.
     *
     * This shares its preference file with [AppSettings], so an import replaces
     * what is stored here without ever calling [record] — and the flow above
     * would otherwise go on serving the list the old device had.
     */
    fun reload() {
        open()
        if (!this::prefs.isInitialized) return
        load()
    }

    /** The rows a backup should carry, newest first. */
    fun export(): List<SearchHistoryEntity> {
        // A backup can be the first caller after launch; opening here keeps an
        // export from writing an empty list while the stored one is unread.
        open()
        return _recent.value
    }

    /**
     * Replaces the list with [entries] and writes it immediately.
     *
     * Used by backup import rather than [record]: an import is one explicit
     * action, and the caller's success message should not run ahead of a
     * queued write. Duplicate ids are collapsed and the list is capped the
     * same way [record] caps it.
     */
    fun replace(entries: List<SearchHistoryEntity>) {
        open()
        val seen = HashSet<String>(entries.size)
        val trimmed = entries
            .filter { seen.add(it.id.lowercase()) }
            .take(MAX_ENTRIES)
        _recent.value = trimmed
        if (!this::prefs.isInitialized) return
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(serializer, trimmed)).apply()
    }

    private fun load() {
        _recent.value = runCatching {
            json.decodeFromString(serializer, prefs.getString(KEY_HISTORY, null) ?: "[]")
        }.getOrDefault(emptyList())
        _addonRecent.value = runCatching {
            json.decodeFromString(addonSerializer, prefs.getString(KEY_ADDON_HISTORY, null) ?: "[]")
        }.getOrDefault(emptyList())
    }

    /**
     * Records [entity], or moves it back to the top if it's already there.
     * Matching is by id so re-tapping an existing entity updates its timestamp.
     */
    fun record(entity: SearchHistoryEntity) {
        // The in-app list is local, so private mode leaves it alone: only what
        // leaves the device is suppressed. The guard stays as the one place a
        // future policy change would take effect.
        if (!PrivateListening.allows(ListeningSink.SEARCH_HISTORY, AppSettings.privateListening.value)) return
        open()
        val deduped = _recent.value.filterNot { it.id.equals(entity.id, ignoreCase = true) }
        save((listOf(entity) + deduped).take(MAX_ENTRIES))
    }

    fun remove(id: String) {
        open()
        save(_recent.value.filterNot { it.id.equals(id, ignoreCase = true) })
    }

    fun clear() = save(emptyList())

    private fun save(value: List<SearchHistoryEntity>) {
        open()
        _recent.value = value
        if (!this::prefs.isInitialized) return
        // The flow is updated here so the recents list reacts immediately, but
        // the JSON pass and the prefs write go to the writer thread. The single
        // writer keeps two rapid records from being written out of order.
        writer.execute {
            prefs.edit().putString(KEY_HISTORY, json.encodeToString(serializer, value)).apply()
        }
    }

    internal const val KEY_HISTORY = "search_history"
    private const val KEY_ADDON_HISTORY = "addon_search_history"
}
