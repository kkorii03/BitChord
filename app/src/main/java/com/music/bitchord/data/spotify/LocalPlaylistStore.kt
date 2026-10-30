package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.withVideoThumbnailFallback
import com.music.bitchord.startup.StartupWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

/** How a restored backup treats the local playlists already on this device. */
enum class PlaylistImportMode {
    /** Throw this device's local playlists away and use the backup's list. */
    REPLACE,

    /** Keep this device's rows and add only the backup rows that are new. */
    MERGE,
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    /**
     * The raw text this file held before a load had to skip a damaged row.
     *
     * Kept beside the live key rather than on disk because it is the same
     * small string, and because the next save rewrites the live key from what
     * was parsed — losing the row that could not be.
     */
    private const val KEY_PLAYLISTS_BACKUP = "playlists_json.bak"

    private const val TAG = "BitChord"

    private lateinit var prefs: SharedPreferences

    /**
     * Writes run one at a time off the calling thread: a large playlist is a
     * whole JSON serialisation, and on the UI thread that showed up as a
     * frame hitch every save.
     */
    private val writer = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1),
    )

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    /**
     * Set once [load] has finished reading the stored JSON.
     *
     * The read moved off the main thread, so a write can arrive before it has
     * run. [LoadOnce] makes the read and that first write a single step: the
     * writer waits for the rows on disk instead of persisting the empty list it
     * started with and losing them.
     */
    private val loadGate = LoadOnce(::load)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        loadGate.reset()
        // A large playlist file is a full JSON parse; it used to run in
        // Application.onCreate ahead of the first frame. The startup thread
        // opens it, and any read or write that arrives first falls back to the
        // sync path in [ensureLoaded].
        StartupWork.background("local-playlists") { loadGate.ensureLoaded() }
    }

    private fun ensureLoaded() = loadGate.ensureLoaded()

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        val parsed = parsePlaylists(raw)
        if (parsed == null) {
            // Not even an array: try the copy kept from an earlier damaged
            // load first, so an overwritten live value does not strand the
            // listener with an empty library.
            recoverFromBackup()?.let { recovered ->
                _playlists.value = recovered
                persistAsync(recovered)
                Log.w(TAG, "local playlists are unreadable; restored the last readable backup")
                return
            }
            // Keep the text aside and leave the live value alone rather than
            // start empty and overwrite it on the next save.
            backup(raw)
            Log.w(TAG, "local playlists are unreadable; kept the original as a backup")
            return
        }
        if (parsed.damaged) {
            backup(raw)
            Log.w(TAG, "skipped damaged local playlist rows; kept the original as a backup")
        }
        _playlists.value = parsed.playlists
    }

    /** A fully readable backup, or null when none was kept or it is damaged too. */
    private fun recoverFromBackup(): List<LocalPlaylist>? {
        val raw = prefs.getString(KEY_PLAYLISTS_BACKUP, null) ?: return null
        val parsed = parsePlaylists(raw) ?: return null
        return parsed.playlists.takeUnless { parsed.damaged }
    }

    private fun backup(raw: String) {
        prefs.edit().putString(KEY_PLAYLISTS_BACKUP, raw).apply()
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        ensureLoaded()
        // The timestamp keeps ids readable, but two saves inside the same
        // millisecond used to share one: the second replaced the first in
        // [updated] and was the only one left on disk. Step forward until the
        // id is one this device has never used.
        val id = newLocalPlaylistId(
            nowMs = System.currentTimeMillis(),
            existing = _playlists.value.mapTo(HashSet()) { it.id },
        )
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persistAsync(updated)
        return playlist
    }

    fun deletePlaylist(id: String) {
        ensureLoaded()
        val updated = withoutPlaylist(_playlists.value, id)
        _playlists.value = updated
        persistAsync(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        ensureLoaded()
        val updated = renamedPlaylists(_playlists.value, id, newTitle)
        _playlists.value = updated
        persistAsync(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        ensureLoaded()
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    /** The rows a backup should carry. */
    fun exportAll(): List<LocalPlaylist> {
        ensureLoaded()
        return _playlists.value
    }

    /**
     * Applies restored [incoming] rows with [mode], returning how many rows the
     * store holds afterwards, or -1 when the write did not reach the preference
     * file.
     *
     * Unlike the editing methods above, an import writes synchronously: the
     * backup screen is about to tell the listener the restore succeeded, and
     * that line must not run ahead of the write it describes. The in-memory list
     * only changes after the commit, so a failed import leaves the previous
     * library in place instead of a value the next launch will replace.
     *
     * Matching is by [LocalPlaylist.id], which survives an export/import round
     * trip. That is what makes importing the same file twice a no-op in MERGE
     * mode: every row already present is recognised instead of appended again.
     */
    suspend fun importAll(incoming: List<LocalPlaylist>, mode: PlaylistImportMode): Int {
        ensureLoaded()
        val next = when (mode) {
            PlaylistImportMode.REPLACE -> replacePlaylists(incoming)
            PlaylistImportMode.MERGE -> mergePlaylists(_playlists.value, incoming)
        }
        // Go through the same single-thread writer as every edit: a save that
        // was queued before this import must land first, not after it. Awaiting
        // the write is what makes the success line match the preference file.
        val saved = runCatching { writer.async { persist(next) }.await() }.getOrDefault(false)
        if (!saved) return -1
        _playlists.value = next
        return next.size
    }

    private fun persistAsync(list: List<LocalPlaylist>) {
        writer.launch { persist(list) }
    }

    private fun persist(list: List<LocalPlaylist>): Boolean {
        if (!this::prefs.isInitialized) return false
        return runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    sObj.put("isVideoOrigin", song.isVideoOrigin)
                    sObj.put("setVideoId", song.setVideoId)
                    sObj.put("localUri", song.localUri)
                    sObj.put("localPath", song.localPath)
                    sObj.put("downloadFormat", song.downloadFormat)
                    sObj.put("sourceQuality", song.sourceQuality)
                    sObj.put("isExplicit", song.isExplicit)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            val saved = prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).commit()
            if (!saved) Log.w(TAG, "saving local playlists failed")
            saved
        }.onFailure {
            Log.w(TAG, "saving local playlists threw: ${it.message}")
        }.getOrDefault(false)
    }
}

/** One id no row already uses; consecutive saves in the same millisecond step forward. */
internal fun newLocalPlaylistId(nowMs: Long, existing: Set<String>): String {
    var candidate = nowMs
    while ("sp_local_$candidate" in existing) candidate += 1
    return "sp_local_$candidate"
}

/** [id] as the store writes it, accepting a browse id or a `VL`-prefixed page id. */
internal fun localPlaylistId(id: String): String =
    id.removePrefix("local:playlist:").removePrefix("VL")

/** The list with the row [id] names taken out — by id or by browse id. */
internal fun withoutPlaylist(current: List<LocalPlaylist>, id: String): List<LocalPlaylist> {
    val cleanId = localPlaylistId(id)
    return current.filterNot { it.id == id || it.browseId == id || it.id == cleanId }
}

/** The list with the row [id] names carrying [newTitle], in the same position. */
internal fun renamedPlaylists(
    current: List<LocalPlaylist>,
    id: String,
    newTitle: String,
): List<LocalPlaylist> {
    val cleanId = localPlaylistId(id)
    return current.map {
        if (it.id == cleanId || it.id == id || it.browseId == id) it.copy(title = newTitle) else it
    }
}

/**
 * Runs [load] once, on whichever caller arrives first.
 *
 * Used by [LocalPlaylistStore] to keep its background open and the first write
 * from interleaving: a write that arrives before the background open has
 * finished performs the load itself instead of persisting the empty list.
 */
internal class LoadOnce(private val load: () -> Unit) {
    @Volatile private var loaded = false
    private val lock = Any()

    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            load()
            loaded = true
        }
    }

    fun reset() {
        synchronized(lock) { loaded = false }
    }
}

/**
 * The pure merge behind [LocalPlaylistStore.importAll].
 *
 * Current rows keep their place; incoming rows follow in file order, and an
 * id that is already present is skipped. Importing the same backup a second
 * time therefore changes nothing.
 */
internal fun mergePlaylists(
    current: List<LocalPlaylist>,
    incoming: List<LocalPlaylist>,
): List<LocalPlaylist> {
    val seen = HashSet<String>(current.size + incoming.size)
    return (current + incoming).filter { seen.add(it.id) }
}

/** The pure replace behind [LocalPlaylistStore.importAll]; duplicates in the file collapse. */
internal fun replacePlaylists(incoming: List<LocalPlaylist>): List<LocalPlaylist> {
    val seen = HashSet<String>(incoming.size)
    return incoming.filter { seen.add(it.id) }
}

/** What [parsePlaylists] could read out of a stored playlist file. */
internal class ParsedPlaylists(
    val playlists: List<LocalPlaylist>,
    /**
     * True when at least one entry, or one of its songs, could not be read and
     * was left out — the caller keeps a copy of the original text so that row
     * is never lost to the next save.
     */
    val damaged: Boolean,
)

/**
 * Reads the stored playlist array one entry at a time.
 *
 * This used to be one `runCatching` around the whole loop: a single malformed
 * object — a missing title, a `songs` value that is not an array — threw out of
 * the loop, left `_playlists` empty, and the next save wrote that emptiness
 * back. One bad row cost every playlist. A bad row now costs only itself.
 *
 * Returns null when [raw] is not an array at all, which is a different answer:
 * the whole file is unreadable rather than one row in it being damaged.
 *
 * Parsed with `kotlinx.serialization` rather than `org.json` so the same code
 * runs — and is tested — on the JVM, where the Android `org.json` stubs return
 * nothing.
 */
internal fun parsePlaylists(raw: String): ParsedPlaylists? {
    val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
    val playlists = mutableListOf<LocalPlaylist>()
    var damaged = false
    array.forEach { element ->
        val entry = runCatching { parsePlaylist(element.jsonObject) }.getOrNull()
        if (entry == null) {
            damaged = true
        } else {
            playlists.add(entry.playlist)
            if (entry.damaged) damaged = true
        }
    }
    return ParsedPlaylists(playlists, damaged)
}

private class ParsedPlaylist(val playlist: LocalPlaylist, val damaged: Boolean)

private fun parsePlaylist(obj: JsonObject): ParsedPlaylist {
    val id = obj.requiredString("id")
    val title = obj.requiredString("title")
    val songsArray = obj["songs"] as? JsonArray ?: error("songs is not an array")
    var damaged = false
    val songs = mutableListOf<Song>()
    songsArray.forEach { element ->
        val song = runCatching { parseSong(element.jsonObject) }.getOrNull()
        if (song == null) damaged = true else songs.add(song)
    }
    return ParsedPlaylist(LocalPlaylist(id, title, songs), damaged)
}

private fun parseSong(obj: JsonObject): Song {
    val isVideo = (obj["isVideo"] as? JsonPrimitive)?.booleanOrNull == true
    return Song(
        videoId = obj.requiredString("videoId"),
        title = obj.stringOrNull("title").orEmpty(),
        artist = obj.stringOrNull("artist").orEmpty(),
        thumbnailUrl = obj.stringOrNull("thumbnailUrl"),
        durationText = obj.stringOrNull("durationText"),
        artistId = obj.stringOrNull("artistId"),
        albumId = obj.stringOrNull("albumId"),
        albumName = obj.stringOrNull("albumName"),
        isVideo = isVideo,
        isVideoOrigin = (obj["isVideoOrigin"] as? JsonPrimitive)?.booleanOrNull ?: isVideo,
        setVideoId = obj.stringOrNull("setVideoId"),
        localUri = obj.stringOrNull("localUri"),
        localPath = obj.stringOrNull("localPath"),
        downloadFormat = obj.stringOrNull("downloadFormat"),
        sourceQuality = obj.stringOrNull("sourceQuality"),
        isExplicit = (obj["isExplicit"] as? JsonPrimitive)?.booleanOrNull,
    ).withVideoThumbnailFallback()
}

private fun JsonObject.requiredString(key: String): String =
    stringOrNull(key) ?: error("missing $key")

/**
 * A stored string, with the shapes `org.json` left behind treated as absent:
 * a key it was handed null for was dropped, and older rows can carry the text
 * `"null"`.
 */
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() && it != "null" }
