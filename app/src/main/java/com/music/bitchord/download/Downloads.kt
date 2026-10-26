package com.music.bitchord.download

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.playback.TranslationAhead
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.startup.StartupWork
import com.music.bitchord.ui.FriendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.io.File
import java.util.Locale

/** Where a track is between "not on this device" and "on it". */
sealed interface DownloadState {

    /** Accepted, waiting for the one in front of it. */
    data object Queued : DownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/**
 * The verified pair a high-quality replacement must fetch.
 *
 * Both ids are the ones the sound comparison stored: searching again could
 * pick a different recording with the same title. [tier], [offsetMs] and
 * [rate] are written into the new file's metadata so the provenance and the
 * lyric clock survive the replacement.
 */
data class HqDownloadSource(
    val sourceConfigId: String,
    val trackId: String,
    val tier: HqTier,
    val offsetMs: Long = 0L,
    val rate: Double = 1.0,
)

/**
 * The download queue, and the record of what came out of it.
 *
 * Split deliberately into two pieces of state that look similar and behave
 * nothing alike:
 *
 *  - [active] is what is happening now — queued, running, just failed. It lives
 *    in memory, is driven by [DownloadService], and is empty on a cold start
 *    because a download interrupted by the process dying did not happen.
 *  - [saved] is what exists on disk, keyed by videoId and remembered across
 *    launches. It is the only way the app can answer "do I already have this?"
 *    without a media-store query per row, and the only way it knows *which*
 *    file a track corresponds to when asked to delete it.
 *
 * [saved] is a claim about a folder this app does not own. The user is expected
 * to manage Downloads with a file manager, so an entry here can outlive the
 * file it names — which is why every read of it goes through [savedUri], and
 * why that verifies before it answers.
 */
object Downloads {

    private const val TAG = "BitChord"
    private const val KEY_SAVED_METADATA = "downloaded_tracks_metadata"
    private const val KEY_SAVED_COLLECTIONS = "downloaded_collections"
    private const val KEY_MV_SOUND_SUSPECTS = "downloaded_mv_sound_suspects"
    private lateinit var prefs: SharedPreferences

    /** Where the settings file is opened from, once [init] has been told. */
    private var appContext: Context? = null

    /** Set once [open] has finished, so the startup open and an early caller run it once. */
    @Volatile
    private var opened = false

    private val openLock = Any()

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val metadataSerializer = MapSerializer(String.serializer(), SavedSongMetadata.serializer())
    private val collectionSerializer = MapSerializer(String.serializer(), SavedCollection.serializer())
    private val suspectSerializer = MapSerializer(String.serializer(), Long.serializer())

    private val _active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadState>> = _active.asStateFlow()

    /**
     * Ids asked for as part of a release's own download tap, by the browseId
     * that was tapped.
     *
     * [active] is one flat map for the whole app — a track queued from one
     * release is still the same row if it happens to sit in another release
     * too, and correctly so. But that means a release page can't tell "one of
     * my tracks is queued" apart from "one of my tracks is queued *because I
     * was asked for*" just by scanning [active] for its own ids: two releases
     * that happen to share a track would both read as downloading the moment
     * either one is. This is what lets a release's header ask the narrower
     * question instead — never pruned explicitly, since a stale id here is
     * harmless once it drops out of [active].
     */
    private val _requested = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val requested: StateFlow<Map<String, Set<String>>> = _requested.asStateFlow()

    /** Record that [videoIds] were asked for as [browseId]'s own release. */
    fun markRequested(browseId: String, videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        _requested.update { it + (browseId to (it[browseId].orEmpty() + videoIds)) }
    }

    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())

    /** videoId to the uri of the file saved for it. */
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _savedMetadata = MutableStateFlow<Map<String, SavedSongMetadata>>(emptyMap())

    /**
     * Downloaded song ids whose file is longer than the catalogue track, by how
     * much, as judged once and kept across launches.
     *
     * A file saved before provenance was recorded cannot be told from the song
     * by id alone; its length can. The verdict is remembered so the same file
     * is neither re-measured nor re-requested on every play, and so the
     * Downloads page can offer to fetch the song version without a network
     * request of its own.
     */
    private val _mvSoundSuspects = MutableStateFlow<Map<String, Long>>(emptyMap())
    val mvSoundSuspects: StateFlow<Map<String, Long>> = _mvSoundSuspects.asStateFlow()

    /** Serialises the suspect map's update-and-write, so a later verdict is not overwritten. */
    private val suspectLock = Any()

    private val _collections = MutableStateFlow<Map<String, SavedCollection>>(emptyMap())

    /**
     * The releases that were downloaded *as* releases, by the id they were asked
     * for under.
     *
     * Read by the Downloads page so a batch download reads back as the thing
     * that was tapped. Exposed rather than kept private because the page is a
     * snapshot and has to be retaken when this changes — see
     * [collectionsAmong], which is what turns it into something drawable.
     */
    val collections: StateFlow<Map<String, SavedCollection>> = _collections.asStateFlow()

    /** Waiting, in the order asked for. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, Song>()

    /**
     * A rung asked for by a deliberate replacement — see [enqueue] — rather
     * than the standing [AppSettings.downloadQuality]. Guarded by [lock] while
     * written, and only read by [prepare] for the track it names.
     */
    private val qualityOverrides = HashMap<String, DownloadQuality>()

    /**
     * The verified source/track pair a replacement must use, when it is the
     * automatic high-quality pass asking rather than a listener tap. Guarded
     * by [lock], like [qualityOverrides].
     */
    private val hqOverrides = HashMap<String, HqDownloadSource>()

    private val lock = Any()

    /**
     * The tracks taken off the queue and not yet finished, to the job fetching
     * each — null in the gap between a worker claiming a track and its job
     * existing.
     *
     * A map rather than the single slot this used to be, because several
     * downloads run at once now. That plurality is the only reason it is here:
     * [cancel] has to find *this* track's job among several, and a worker
     * claiming the next track must not be able to step on another worker's.
     * Guarded by [lock].
     */
    private val running = LinkedHashMap<String, Job?>()

    fun init(context: Context) {
        appContext = context.applicationContext
        // Three JSON maps that grow with the listener's Downloads folder. None
        // of them is needed for the first frame, so they are parsed on the
        // startup thread; a caller that gets here first runs [open] itself.
        StartupWork.background("downloads") { open() }
    }

    /**
     * Opens the record now if the startup thread has not got to it yet.
     *
     * Only needed by callers that read [saved] or [collections] as a value
     * rather than collecting them: a collector picks the loaded map up when it
     * arrives, a one-off read would keep the empty one it saw first.
     */
    fun ensureOpen() = open()

    /**
     * Opens the record and publishes what it held, once per process.
     *
     * The flows are the normal way to read it, and go on serving an empty map
     * only until this has run. Callers that read the maps directly go through
     * here first so a menu opened in the first moments of a cold start does not
     * show a downloaded track as "Download" and, worse, so a write cannot land
     * while the record is still being read in and then be overwritten by it.
     */
    private fun open() {
        synchronized(openLock) {
            if (opened) return
            val context = appContext ?: return
            prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
            _saved.value = runCatching {
                json.decodeFromString(serializer, prefs.getString(KEY_SAVED, null) ?: "{}")
            }.getOrDefault(emptyMap())
            _savedMetadata.value = runCatching {
                json.decodeFromString(metadataSerializer, prefs.getString(KEY_SAVED_METADATA, null) ?: "{}")
            }.getOrDefault(emptyMap())
            _mvSoundSuspects.value = runCatching {
                json.decodeFromString(suspectSerializer, prefs.getString(KEY_MV_SOUND_SUSPECTS, null) ?: "{}")
            }.getOrDefault(emptyMap())
            _collections.value = runCatching {
                json.decodeFromString(collectionSerializer, prefs.getString(KEY_SAVED_COLLECTIONS, null) ?: "{}")
            }.getOrDefault(emptyMap())
            opened = true
        }
    }

    // ---- Asking -------------------------------------------------------------

    /**
     * Queue [song], and make sure something is draining the queue.
     *
     * A track already saved, queued or running is left alone rather than
     * doubled — the menu row shows which of those it is, but a second tap
     * before the sheet updates should still be a no-op.
     *
     * The Wi-Fi-only check is here rather than only at the tap because this is
     * the one door into the queue, and a setting that can be bypassed by a
     * caller that forgot about it is not a setting. Callers that can say
     * something better than a failed row — a single toast for a whole album, say
     * — check [AppSettings.downloadsAllowedNow] themselves first; this is what
     * catches the rest.
     *
     * @param from what release this track was asked for as part of, when it was
     *   one of many. Carried no further than [DownloadSession], which is the
     *   only thing that has to say *why* forty tracks are in the queue.
     * @param allowMetered a replacement the listener asked for, or one the
     *   automatic repair is doing: data is not the constraint, so the
     *   Wi-Fi-only rule does not apply.
     * @param quality the rung this particular replacement should fetch, when it
     *   is a deliberate upgrade rather than the standing download setting. It
     *   stays with the track across a failed retry and is dropped once the
     *   download succeeds or is cancelled.
     * @param hq the exact verified pair the automatic high-quality pass wants,
     *   or null for an ordinary download. When present the search is skipped:
     *   only that source's that track is opened.
     */
    fun enqueue(
        context: Context,
        song: Song,
        from: String? = null,
        allowMetered: Boolean = false,
        quality: DownloadQuality? = null,
        hq: HqDownloadSource? = null,
    ) {
        val id = song.videoId
        // A copy straight out of the song cache spends no data, so the Wi-Fi
        // rule has nothing to say about it.
        if (!allowMetered && !AppSettings.downloadsAllowedNow && !servableFromCache(id)) {
            // Distinct from the duplicate-tap no-op below: nothing is in flight
            // here to leave alone, and a refusal nobody is told about reads as a
            // dead button. A download already queued or running started on a
            // connection that allowed it and is none of this check's business.
            val inFlight = _active.value[id]
            if (inFlight !is DownloadState.Queued && inFlight !is DownloadState.Running) {
                DownloadSession.queued(song, from)
                fail(id, WIFI_ONLY_REFUSAL)
            }
            return
        }
        synchronized(lock) {
            if (id in pending || id in running) return
            pending[id] = song
            if (quality != null) qualityOverrides[id] = quality
            if (hq != null) hqOverrides[id] = hq
        }
        _active.update { it + (id to DownloadState.Queued) }
        DownloadSession.queued(song, from)

        val app = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        }.onFailure {
            // Refused only when the app has no window and no exemption, which
            // means the queue has nothing to drain it and would sit there
            // looking accepted forever.
            Log.w(TAG, "could not start the download service: ${it.message}")
            synchronized(lock) {
                pending.remove(id)
                qualityOverrides.remove(id)
                hqOverrides.remove(id)
            }
            fail(id, "지금은 다운로드를 시작할 수 없습니다")
        }
    }

    /**
     * Tracks asked for from the Cached songs folder, which are saved from the
     * cache's own complete copy when it holds one — see [AudioCache.completeCopyOf].
     *
     * Kept apart from every other download on purpose. Anywhere else the
     * listener is asking for the Download quality setting, which can be a
     * lossless file a source holds; from the folder they are asking for the
     * song they already have, and taking that copy is instant and works
     * offline. Any download still falls back to the cache when the network
     * route fails — see [prepare].
     */
    private val fromCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Marks [videoIds] as wanted from the song cache; see [fromCache]. Call before [enqueue]. */
    fun preferCachedCopy(videoIds: Collection<String>) {
        fromCache += videoIds
    }

    /**
     * Whether [videoId] was asked for from the cache and the cache can serve
     * it whole — a download that needs no network at all. Reads the cache's
     * index and the first bytes of one file.
     */
    fun servableFromCache(videoId: String): Boolean =
        videoId in fromCache && AudioCache.completeCopyOf(videoId)?.let(::cachedRoute) != null

    /**
     * Drop [videoId] from the queue, or stop it if it is one of the ones
     * running.
     *
     * Dropping it from [running] is what makes this safe in the gap between a
     * track being dequeued and its job existing: a cancel landing in that
     * window finds no job to stop, but [onRunning] then finds the id it was
     * told to run is no longer wanted, and stops it on arrival.
     */
    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            if (videoId !in running) return@synchronized null
            running.remove(videoId)
        }
        job?.cancel()
        clear(videoId)
        // A download the user called off is not something they need reminding to
        // check on, so it leaves the manager rather than sitting in it as a
        // permanent "cancelled" row.
        DownloadSession.forget(videoId)
    }

    // ---- The record ---------------------------------------------------------

    /**
     * The file saved for [videoId], or null — pruning the record if the file
     * has been deleted from under it.
     *
     * Touches the filesystem, so call it off the main thread.
     */
    suspend fun savedUri(context: Context, videoId: String): Uri? = withContext(Dispatchers.IO) {
        open()
        val recorded = _saved.value[videoId] ?: return@withContext null
        val uri = recorded.toUri()
        if (DownloadStore.exists(context, uri)) return@withContext uri
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        null
    }

    /**
     * True when [uriString] names a `file://` path that is not there.
     *
     * Deliberately answers only for `file://`, and deliberately cheaply: this is
     * called from [Song.toMediaItem], which runs on the main thread once per
     * item for a whole queue. A `stat` is a few microseconds and safe at that
     * rate; the `openFileDescriptor` a `content://` uri would need is a binder
     * round trip, and three hundred of those while building a queue is a frame
     * budget gone. Stale `content://` records are left to
     * [PlaybackService.recoverFrom], which catches every scheme at the moment a
     * read actually fails and costs nothing until then.
     *
     * False for anything unparseable, which keeps "I could not tell" out of the
     * "the file is missing" answer — the caller drops a uri on a true here.
     */
    fun isMissingLocalFile(uriString: String): Boolean {
        if (!uriString.startsWith("file://")) return false
        val path = runCatching { uriString.toUri().path }.getOrNull() ?: return false
        return !File(path).exists()
    }

    /**
     * As [savedUri], but synchronous and without a [Context] parameter — for
     * [Song.toMediaItem], which builds a [MediaItem] on whatever thread that
     * happens to run on and has neither a suspend context nor a [Context] in
     * hand to reach [DownloadStore.exists] with.
     *
     * Without this, a record surviving the file it names — deleted by a file
     * manager, or a folder wiped out from under the app — sent the player a
     * `file://` uri to a path that is simply not there. Nothing downstream
     * checks that either: [AudioCache.playbackFactory] hands `file://` and
     * `content://` uris straight to [androidx.media3.datasource.FileDataSource],
     * which fails with `ERROR_CODE_IO_FILE_NOT_FOUND` — retried a handful of
     * times and then given up on, so the track just refuses to play, with
     * nothing to say why.
     *
     * Prunes the record on the way past, the same as [savedUri]: a claim that
     * has just been shown to be false is not worth keeping to be shown false
     * again on the next play.
     */
    fun verifiedSavedUri(videoId: String): String? {
        open()
        val recorded = _saved.value[videoId] ?: return null
        if (!isMissingLocalFile(recorded)) return recorded
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        return null
    }

    /**
     * The other id that names the same downloaded file as [videoId], when one
     * was recorded — see [remember], which files a music video and the
     * catalogue release it resolved to under both ids.
     *
     * Null for an ordinary download, which is recorded under its own id only.
     * Cheaper than looking the file up again, and enough for a caller to build
     * the release row without a network request.
     */
    fun savedAliasFor(videoId: String): String? {
        open()
        val uri = _saved.value[videoId] ?: return null
        return _saved.value.entries
            .firstOrNull { (id, saved) -> id != videoId && saved == uri }
            ?.key
    }

    /** The metadata recorded for a downloaded id, without touching its file. */
    internal fun savedMetadataFor(videoId: String): SavedSongMetadata? {
        open()
        return _savedMetadata.value[videoId]
    }

    /** A snapshot of every recorded download, without a disk check per row. */
    internal fun savedMetadataSnapshot(): Map<String, SavedSongMetadata> {
        open()
        return _savedMetadata.value
    }

    /** A downloaded row as a playable [Song], or null when it was never saved. */
    internal fun savedSongFor(videoId: String): Song? {
        open()
        val meta = _savedMetadata.value[videoId] ?: return null
        return Song(
            videoId = meta.videoId,
            title = meta.title,
            artist = meta.artist,
            thumbnailUrl = meta.thumbnailUrl,
            durationText = meta.durationText,
            albumName = meta.albumName,
            localUri = _saved.value[videoId] ?: meta.uri,
            downloadFormat = meta.downloadFormat,
            isVideo = meta.wasVideoRow(),
            typeUnknown = meta.typeUnknown,
        )
    }

    /** Whether [videoId]'s recorded file carries the provenance of [hq]. */
    internal fun hqApplied(videoId: String, hq: HqDownloadSource): Boolean {
        open()
        val meta = _savedMetadata.value[videoId] ?: return false
        return meta.hqSourceConfigId == hq.sourceConfigId &&
            meta.hqTrackId == hq.trackId &&
            meta.hqTier == hq.tier.name
    }

    /** Whether [videoId] is one of this app's downloads, rather than a local file. */
    fun isDownloaded(videoId: String): Boolean {
        open()
        return videoId in _saved.value || videoId in _savedMetadata.value
    }

    /**
     * The id whose stream produced the file saved for [videoId], when it was
     * recorded. Null for a download made before provenance was kept.
     *
     * A music video is filed under both its own id and the catalogue release it
     * resolved to, so this is the only way to tell the two apart afterwards:
     * the ids name two uploads, the file can only be the sound of one of them.
     */
    internal fun savedSoundSourceFor(videoId: String): String? {
        open()
        return _savedMetadata.value[videoId]?.soundSourceVideoId
    }

    /** How much longer than the song this file was judged to be, or null. */
    fun mvSoundSuspectMs(videoId: String): Long? {
        open()
        return _mvSoundSuspects.value[videoId]
    }

    /** Keeps the length verdict for [videoId] across launches. */
    fun markMvSoundSuspect(videoId: String, deltaMs: Long) {
        open()
        synchronized(suspectLock) {
            val next = _mvSoundSuspects.updateAndGet { it + (videoId to deltaMs) }
            if (::prefs.isInitialized) {
                prefs.edit()
                    .putString(KEY_MV_SOUND_SUSPECTS, json.encodeToString(suspectSerializer, next))
                    .apply()
            }
        }
    }

    private fun clearMvSoundSuspects(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        synchronized(suspectLock) {
            val next = _mvSoundSuspects.updateAndGet { it - videoIds.toSet() }
            if (::prefs.isInitialized) {
                prefs.edit()
                    .putString(KEY_MV_SOUND_SUSPECTS, json.encodeToString(suspectSerializer, next))
                    .apply()
            }
        }
    }

    /**
     * The song id for the file [videoId] names: the shortest of the ids filed
     * against it.
     *
     * A music video and the catalogue release it resolved to share one file,
     * and only the shorter row is the recording the app plays as the song.
     * [videoId] itself when there is no second id.
     */
    fun songIdForFile(videoId: String): String {
        open()
        val uri = _saved.value[videoId] ?: _savedMetadata.value[videoId]?.uri ?: return videoId
        return _saved.value
            .filterValues { it == uri }
            .keys
            .minByOrNull { candidate ->
                _savedMetadata.value[candidate]?.durationText?.durationMillis()
                    ?: Long.MAX_VALUE
            } ?: videoId
    }

    /**
     * The shortest duration known for [videoId]'s song: the row's own catalogue
     * length, or a shorter length recorded against the same file (the song
     * version the video was resolved to).
     */
    fun catalogDurationMs(videoId: String, rowDurationMs: Long?): Long? {
        open()
        val uri = _saved.value[videoId] ?: _savedMetadata.value[videoId]?.uri
        val recorded = if (uri != null) {
            _savedMetadata.value.values
                .filter { it.uri == uri }
                .mapNotNull { it.durationText?.durationMillis()?.takeIf { ms -> ms > 0L } }
        } else {
            emptyList()
        }
        return (recorded + listOfNotNull(rowDurationMs?.takeIf { it > 0L })).minOrNull()
    }

    /**
     * Scans the downloads whose provenance is unknown for files longer than
     * their song, using only lengths already written down. No network; the
     * file's own metadata is read only for records that have a catalogue
     * length to compare it with.
     */
    suspend fun scanForMvSoundSuspects(context: Context) = withContext(Dispatchers.IO) {
        open()
        val found = LinkedHashMap<String, Long>()
        _savedMetadata.value.values.groupBy { it.uri }.forEach { (_, entries) ->
            if (entries.isEmpty() || entries.any { it.soundSourceVideoId != null }) return@forEach
            val durations = entries.mapNotNull { meta ->
                meta.durationText?.durationMillis()
                    ?.takeIf { ms -> ms > 0L }
                    ?.let { meta.videoId to it }
            }
            if (durations.isEmpty()) return@forEach
            val fileUri = entries.first().uri
            if (!DownloadStore.exists(context, fileUri.toUri())) return@forEach
            val (catalogId, catalogMs) = durations.minBy { it.second }
            // The recorded lengths say this file might be the video's sound;
            // the file's own metadata is the one that says whether it is.
            // Read only for the candidates, never for every download.
            val fileMs = localDurationMs(context, fileUri)
                ?: durations.maxOf { it.second }
            val delta = SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = fileMs,
                catalogDurationMs = catalogMs,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ) ?: return@forEach
            found[songIdForFile(catalogId)] = delta
        }
        var changed = false
        synchronized(suspectLock) {
            val before = _mvSoundSuspects.value
            val next = _mvSoundSuspects.updateAndGet { current ->
                val kept = current.filter { (id, _) ->
                    val meta = _savedMetadata.value[id] ?: return@filter false
                    val uri = _saved.value[id] ?: meta.uri
                    meta.soundSourceVideoId == null &&
                        DownloadStore.exists(context, uri.toUri())
                }
                kept + found
            }
            changed = next != before
            if (changed && ::prefs.isInitialized) {
                prefs.edit()
                    .putString(KEY_MV_SOUND_SUSPECTS, json.encodeToString(suspectSerializer, next))
                    .apply()
            }
        }
        if (_mvSoundSuspects.value.isNotEmpty()) MvSoundRedownload.scheduleIfEnabled(context)
    }

    /** The file's own length from its container metadata, or null if it cannot be read. */
    private fun localDurationMs(context: Context, uriString: String): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uriString.toUri())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()?.takeIf { it > 0L }

    /** The downloaded rows still on disk that are remembered as the video's sound. */
    suspend fun suspectedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        open()
        _mvSoundSuspects.value.keys.mapNotNull { id ->
            val meta = _savedMetadata.value[id] ?: return@mapNotNull null
            val uri = _saved.value[id] ?: meta.uri
            if (!DownloadStore.exists(context, uri.toUri())) return@mapNotNull null
            // The verdict names a file, and the same file may be filed under
            // the video and the song. Fetch the shorter name: that is the song
            // id, whose own stream is the recording to keep.
            val target = songIdForFile(id)
            val targetMeta = _savedMetadata.value[target] ?: meta
            Song(
                videoId = target,
                title = targetMeta.title,
                artist = targetMeta.artist,
                thumbnailUrl = targetMeta.thumbnailUrl,
                durationText = targetMeta.durationText,
                albumName = targetMeta.albumName,
                localUri = uri,
                // The file was judged to be the video's sound; let the repair
                // resolve the song version instead of downloading this id's
                // own (video) stream again.
                typeUnknown = true,
            )
        }.distinctBy { it.localUri }
    }

    /**
     * Queues every remembered suspect as its song version. The old file is
     * replaced once the new recording is on disk; until then the banner stays,
     * so a failure is visible rather than silently forgotten.
     */
    suspend fun redownloadSuspected(context: Context): Int {
        val songs = suspectedSongs(context)
        songs.forEach { enqueue(context, it, allowMetered = true) }
        return songs.size
    }

    /**
     * Whether the file [uri] is recorded as [videoId]'s own downloaded
     * recording, rather than a same-named file that merely happens to be there.
     *
     * Download adoption asks this before treating an existing file as the
     * answer: the file manager's folder is not ours, and the old bug was a
     * music video's sound being adopted under the catalogue song's id.
     */
    internal fun savedFileIsRecording(videoId: String, uri: String): Boolean {
        open()
        val meta = _savedMetadata.value[videoId] ?: return false
        return meta.uri == uri &&
            SongVersionPolicy.mayAdoptDownloadedFile(videoId, meta.soundSourceVideoId)
    }

    /**
     * Delete the file saved for [videoId] and forget it.
     *
     * The record is dropped only once the file is really gone. Forgetting it
     * regardless — which is what this used to do — left a file on the device
     * that nothing could reach through the app any more, while the Downloads
     * page said it had been removed.
     *
     * A record whose file has already disappeared counts as deleted: there is
     * nothing left to remove, and keeping the claim would make every later
     * delete fail.
     */
    suspend fun delete(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        open()
        val wasSuspect = mvSoundSuspectMs(videoId) != null
        val uri = _saved.value[videoId]?.toUri() ?: return@withContext false
        if (!DownloadStore.exists(context, uri)) {
            Log.d(TAG, "$videoId was already gone; forgetting the record")
            forget(videoId)
            if (wasSuspect) {
                runCatching {
                    com.music.bitchord.playback.smart.LyricsTiming.resetSongCheck(context, videoId)
                }
            }
            return@withContext true
        }
        val deleted = DownloadStore.delete(context, uri)
        if (deleted) {
            forget(videoId)
            if (wasSuspect) {
                runCatching {
                    com.music.bitchord.playback.smart.LyricsTiming.resetSongCheck(context, videoId)
                }
            }
        } else {
            Log.w(TAG, "$videoId could not be deleted; keeping the record")
        }
        deleted
    }

    private fun forget(videoId: String) {
        record(saved = { it - videoId }, meta = { it - videoId })
        clearMvSoundSuspects(listOf(videoId))
    }

    /**
     * Drop the record for [videoId] because a read of the file it names has
     * just failed.
     *
     * The public counterpart to [forget], for [PlaybackService.recoverFrom] —
     * the one caller that does not need to check anything first, because the
     * player has already done better than a check: it opened the file and got
     * `ENOENT`. That covers the `content://` records [isMissingLocalFile]
     * deliberately declines to answer for, which is the whole reason this is
     * reachable from outside.
     *
     * Named for what it asserts rather than what it does, so a caller that has
     * *not* established the file is missing has no business calling it.
     */
    fun forgetMissing(videoId: String) {
        open()
        if (videoId !in _saved.value) return
        Log.d(TAG, "$videoId could not be opened; forgetting the download")
        forget(videoId)
    }

    // ---- Releases -----------------------------------------------------------

    /**
     * Remember that [songs] were asked for as one release rather than one at a
     * time.
     *
     * The reason this exists at all: a batch download used to be indistinguishable
     * from forty separate ones the moment it finished. What reached the Downloads
     * page was forty rows, and the only thing that could group them back up was
     * whatever album tag each row happened to carry — which an album page's rows
     * don't carry at all (the release is billed once, in the header) and a
     * playlist's rows *never* can, because a playlist is not an album and its
     * tracks are off forty different ones. So the thing the user tapped was the
     * one thing not written down anywhere.
     *
     * Recorded at the tap rather than on completion, and keyed by the id that
     * was tapped, so re-downloading the same release updates one entry instead
     * of accumulating near-duplicates. The order is the running order the page
     * had, which is what makes the entry read back as the release rather than as
     * a bag of tracks.
     *
     * Nothing here asserts the files exist. That is deliberate and matches
     * [saved]: this is a record of what was *asked* for, and which of those
     * tracks is actually on disk is answered where it is read — see
     * [collectionsAmong].
     */
    fun rememberCollection(target: DownloadTarget, songs: List<Song>) {
        if (songs.isEmpty()) return
        val ids = songs.map { it.videoId }.distinct()
        recordCollections { current ->
            val existing = current[target.id]
            val record = SavedCollection(
                id = target.id,
                title = target.title,
                subtitle = target.subtitle,
                // Once the remote cover has been cached, do not replace it with
                // the YouTube URL when the same collection is downloaded again.
                thumbnailUrl = existing?.thumbnailUrl?.takeIf(::isLocalArtwork)
                    ?: target.thumbnailUrl
                    ?: existing?.thumbnailUrl,
                playlist = target.playlist,
                // A release fetched in pages can be downloaded twice from two
                // different depths of the same page, so the two asks are merged
                // rather than the second replacing the first — but the new order
                // leads, since it is the one just seen on screen.
                videoIds = (ids + (existing?.videoIds ?: emptyList())).distinct(),
            )
            current + (target.id to record)
        }
    }

    /** Replace a collection's remote artwork URL with its durable local copy. */
    fun rememberCollectionArtwork(id: String, thumbnailUrl: String) {
        recordCollections { current ->
            val existing = current[id] ?: return@recordCollections current
            if (existing.thumbnailUrl == thumbnailUrl) current
            else current + (id to existing.copy(thumbnailUrl = thumbnailUrl))
        }
    }

    private fun isLocalArtwork(url: String): Boolean =
        url.startsWith("file:") || url.startsWith("content:")

    /** Drop a release from the record without touching the files under it. */
    fun forgetCollection(id: String) {
        open()
        if (id !in _collections.value) return
        recordCollections { it - id }
    }

    /**
     * Delete every file downloaded for release [id] and drop the record of it.
     *
     * The counterpart to [forgetCollection]: that one is for a record whose
     * files are already gone, this one is what actually takes them off the
     * device — the "delete download" a whole album or playlist card offers,
     * where a single track only ever offers [delete].
     *
     * Returns true only when every recorded file was deleted. The collection
     * record is kept when any of them could not be, because it is still the
     * only thing that knows those files belong to this release; the tracks
     * that did go are forgotten individually. A false here is the caller's
     * cue to say so rather than let the card disappear.
     */
    suspend fun deleteCollection(context: Context, id: String): Boolean {
        open()
        val record = _collections.value[id] ?: return false
        var failedAny = false
        record.videoIds.forEach { videoId ->
            if (videoId !in _saved.value) return@forEach
            if (!delete(context, videoId)) failedAny = true
        }
        if (failedAny) {
            Log.w(TAG, "kept the record for $id: some of its downloads could not be deleted")
        } else {
            forgetCollection(id)
        }
        return !failedAny
    }

    /**
     * How one downloaded playlist is addressed as a page of its own.
     *
     * Under `local:` deliberately: that prefix is how the rest of the app asks
     * "is this already on the device?", and it is what keeps the download button
     * off such a page's header and out of its menu. What it must not be taken
     * for is one of the two device *folders* — `local:downloads` and `local:all`
     * open the tabbed Songs / Artists / Albums view, and this opens a plain
     * track listing — so it gets a segment of its own rather than an id in the
     * same namespace.
     *
     * Built for playlists, which is why the word is in the prefix: a playlist's
     * tracks are off forty different releases and no tag on any of them names
     * it, so it is the one that needs a page. A downloaded album opens through
     * it as well, from its card on the Library's On Device shelf — the record
     * is the same either way — though the Albums tab still groups one up on
     * its own.
     */
    const val PLAYLIST_PREFIX = "local:playlist:"

    /** The page id for the release recorded under [id]. */
    fun pageIdFor(id: String): String = PLAYLIST_PREFIX + id

    /** The release [pageIdFor] built [browseId] from, or null if it didn't. */
    fun recordIdOf(browseId: String): String? =
        browseId.removePrefix(PLAYLIST_PREFIX).takeIf { it != browseId && it.isNotEmpty() && !it.startsWith("sp_local_") }

    /**
     * The playlists and albums downloaded whole, in name order, without their
     * tracks.
     *
     * What the Library page's On Device shelf draws a card from. Unlike
     * [collectionsAmong] there is no track list here to prune against — that
     * page never reads the folder — so this prunes against [onDisk] instead,
     * which drops a playlist once the last file recorded for it has been deleted
     * *through this app* and keeps the rest. That is the same claim [saved] makes
     * everywhere else, and opening the card is what settles it either way.
     *
     * It is also what keeps a playlist off the shelf between the tap that queues
     * it and the first track landing: [rememberCollection] writes the record at
     * the tap, and a playlist with nothing downloaded yet is not on the device.
     *
     * [onDisk] is a parameter for the same reason [collectionsAmong] takes its
     * songs: the rule is worth stating on a known folder rather than only on
     * whatever this process happens to have recorded.
     */
    fun savedReleases(onDisk: Map<String, String>? = null): List<SavedCollection> {
        open()
        val current = onDisk ?: _saved.value
        if (_collections.value.isEmpty()) return emptyList()
        return _collections.value.values
            .filter { record -> record.videoIds.any { it in current } }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    /**
     * The releases at least one of [songs] belongs to, each with its own tracks
     * picked out of that list.
     *
     * Given the page's own songs rather than reading the disk itself, because
     * the page has already done that work — every row in it is a file that was
     * there when it was taken — and a release is only worth drawing for the
     * tracks that survived. A release whose files have all been deleted from a
     * file manager therefore disappears from the page without anything having to
     * notice it went.
     *
     * The lookup goes through [saved] as well as by id because one file answers
     * to two of them: a music video is swapped for its catalogue track on the
     * way down (see [remember]) and the page keeps whichever of the pair it
     * listed first, which is not necessarily the id the release named.
     */
    fun collectionsAmong(songs: List<Song>): List<DownloadedCollection> {
        if (songs.isEmpty()) return emptyList()
        open()
        if (_collections.value.isEmpty()) return emptyList()
        val byId = songs.associateBy { it.videoId }
        val byUri = songs.mapNotNull { song -> song.localUri?.let { it to song } }.toMap()
        val uris = _saved.value
        return _collections.value.values
            .mapNotNull { record ->
                val tracks = record.videoIds
                    .mapNotNull { id -> byId[id] ?: uris[id]?.let(byUri::get) }
                    .distinctBy { it.localUri ?: it.videoId }
                if (tracks.isEmpty()) {
                    null
                } else {
                    DownloadedCollection(
                        id = record.id,
                        title = record.title,
                        subtitle = record.subtitle,
                        thumbnailUrl = record.thumbnailUrl ?: tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        playlist = record.playlist,
                        songs = tracks,
                    )
                }
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun recordCollections(update: (Map<String, SavedCollection>) -> Map<String, SavedCollection>) {
        open()
        synchronized(collectionLock) {
            val map = update(_collections.value)
            _collections.value = map
            if (::prefs.isInitialized) {
                prefs.edit()
                    .putString(KEY_SAVED_COLLECTIONS, json.encodeToString(collectionSerializer, map))
                    .apply()
            }
        }
    }

    private val collectionLock = Any()

    /**
     * Record one file under every id it could be asked about.
     *
     * [asked] is the row the user tapped and [fetched] is what was actually
     * downloaded, and for a music video those are two different tracks. Filing
     * it under both is what lets the same song, found later through search,
     * still know it is already on the device. A stale id costs nothing: the
     * verification in [savedUri] prunes whichever one stops resolving.
     */
    private suspend fun remember(
        asked: Song,
        fetched: Song,
        uri: Uri,
        downloadFormat: String? = null,
        artworkUri: String? = null,
        soundSourceVideoId: String = fetched.videoId,
        hq: HqDownloadSource? = null,
    ) {
        open()
        val ids = setOf(asked.videoId, fetched.videoId)
        val previousUris = ids.mapNotNull { id ->
            _savedMetadata.value[id]?.uri ?: _saved.value[id]
        }.toSet()
        val previousSuspects = _mvSoundSuspects.value.keys.filter { id ->
            val old = _savedMetadata.value[id]?.uri ?: _saved.value[id]
            old != null && (old == uri.toString() || old in previousUris)
        }
        // MediaStore supplies this for exported downloads, but app-private and
        // legacy file:// downloads never pass through that index. Keep the
        // creation time in our own record so "Date added" remains stable if a
        // file is subsequently retagged or otherwise modified. An adopted file
        // predating this field falls back to its last-modified time.
        val fileModifiedMillis = uri.takeIf { it.scheme == "file" }
            ?.path
            ?.let(::File)
            ?.lastModified()
            ?.takeIf { it > 0 }
        val existingAdded = ids.firstNotNullOfOrNull { _savedMetadata.value[it]?.dateAddedSeconds }
        val dateAddedSeconds = resolvedDownloadDates(existingAdded, fileModifiedMillis).first
            ?: System.currentTimeMillis() / 1_000
        // HLS packages cannot carry MP4 tags. Point the app's own metadata at
        // the cover saved beside their playlist so Downloads remains fully
        // offline even though another player cannot open that package.
        val savedArtwork = uri.takeIf { it.scheme == "file" && it.lastPathSegment == "playlist.m3u8" }
            ?.path?.let(::File)?.parentFile
            ?.listFiles()?.firstOrNull { it.nameWithoutExtension == "cover" }
            ?.let(Uri::fromFile)?.toString()
        // Either row may be the one that knew the release: a music video is
        // swapped for the catalogue track before this, and it is the catalogue
        // row that usually carries the album — but a search hit tapped directly
        // is both, and an album page's rows are neither.
        val album = fetched.albumName?.takeIf { it.isNotBlank() }
            ?: asked.albumName?.takeIf { it.isNotBlank() }
        val metaAsked = SavedSongMetadata(
            videoId = asked.videoId,
            title = asked.title,
            artist = asked.artist,
            thumbnailUrl = savedArtwork ?: artworkUri ?: asked.thumbnailUrl,
            durationText = asked.durationText,
            albumName = album,
            uri = uri.toString(),
            downloadFormat = downloadFormat,
            dateAddedSeconds = dateAddedSeconds,
            soundSourceVideoId = soundSourceVideoId,
            isVideo = asked.isVideo,
            typeUnknown = asked.typeUnknown,
            hqSourceConfigId = hq?.sourceConfigId,
            hqTrackId = hq?.trackId,
            hqOffsetMs = hq?.offsetMs,
            hqRate = hq?.rate,
            hqTier = hq?.tier?.name,
        )
        val metaFetched = SavedSongMetadata(
            videoId = fetched.videoId,
            title = fetched.title,
            artist = fetched.artist,
            thumbnailUrl = savedArtwork ?: artworkUri ?: fetched.thumbnailUrl,
            durationText = fetched.durationText,
            albumName = album,
            uri = uri.toString(),
            downloadFormat = downloadFormat,
            dateAddedSeconds = dateAddedSeconds,
            soundSourceVideoId = soundSourceVideoId,
            isVideo = fetched.isVideo,
            typeUnknown = fetched.typeUnknown,
            hqSourceConfigId = hq?.sourceConfigId,
            hqTrackId = hq?.trackId,
            hqOffsetMs = hq?.offsetMs,
            hqRate = hq?.rate,
            hqTier = hq?.tier?.name,
        )
        record(
            saved = { it + ids.associateWith { id -> uri.toString() } },
            meta = {
                it + mapOf(asked.videoId to metaAsked, fetched.videoId to metaFetched)
            },
        )
        // A file whose recorded source is one of these ids is the recording
        // these rows asked for, so a length verdict from the old file no longer
        // applies. When the new file landed elsewhere, remove the old one once
        // nothing else names it — that is the "replace the old file" half of a
        // re-download.
        if (soundSourceVideoId in ids) {
            clearMvSoundSuspects(previousSuspects + ids)
            val context = appContext
            val fileReplaced = previousUris.any { it != uri.toString() }
            if (context != null && (fileReplaced || previousSuspects.isNotEmpty())) {
                // A song's file was replaced. Any AI timing answer was measured
                // against the old bytes, so drop it (and the spent engine
                // record) and let the song check run against the new
                // recording. Video rows keep their own timing.
                buildSet {
                    if (!asked.isVideo) add(asked.videoId)
                    if (!fetched.isVideo) add(fetched.videoId)
                    addAll(previousSuspects)
                }.forEach { id ->
                    runCatching {
                        com.music.bitchord.playback.smart.LyricsTiming.resetSongCheck(context, id)
                    }
                }
            }
            // Other ids that named the old file are aliases of the same
            // download (a music video and its song). Point them at the new
            // file rather than dropping them, so a playlist that was filed
            // under the old id keeps its track.
            val staleIds = _saved.value
                .filterValues { value -> value != uri.toString() && value in previousUris }
                .keys.toList()
            if (staleIds.isNotEmpty()) {
                record(
                    saved = { it + staleIds.associateWith { uri.toString() } },
                    meta = { meta ->
                        meta.mapValues { (id, value) ->
                            if (id in staleIds) {
                                value.copy(
                                    uri = uri.toString(),
                                    soundSourceVideoId = soundSourceVideoId,
                                )
                            } else {
                                value
                            }
                        }
                    },
                )
            }
            if (context != null) {
                previousUris.forEach { old ->
                    if (old != uri.toString() && _saved.value.values.none { it == old }) {
                        runCatching { DownloadStore.delete(context, old.toUri()) }
                    }
                }
            }
        }
    }

    /**
     * Apply [saved] and [meta] to the two records and write the result down.
     *
     * Takes transforms rather than finished maps because several downloads
     * finish at once now, and "read the map, add my track, store it back" run
     * from two threads loses one of the two tracks — silently, and permanently,
     * since this is the only record that a file was written. Both flows are
     * updated compare-and-set, and the persist is serialised so the copy that
     * reaches disk is never older than one already written.
     */
    private fun record(
        saved: (Map<String, String>) -> Map<String, String>,
        meta: (Map<String, SavedSongMetadata>) -> Map<String, SavedSongMetadata>,
    ) {
        open()
        val savedMap = _saved.updateAndGet(saved)
        val metaMap = _savedMetadata.updateAndGet(meta)
        if (!::prefs.isInitialized) return
        synchronized(recordLock) {
            prefs.edit()
                .putString(KEY_SAVED, json.encodeToString(serializer, savedMap))
                .putString(KEY_SAVED_METADATA, json.encodeToString(metadataSerializer, metaMap))
                .apply()
        }
    }

    private val recordLock = Any()

    /** Returns all downloaded songs whose files still exist on disk. */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        open()
        val metaMap = _savedMetadata.value
        val result = mutableListOf<Song>()
        val seenUris = mutableSetOf<String>()
        val migratedAddedByUri = mutableMapOf<String, Long>()
        val migratedFormatByUri = mutableMapOf<String, String>()

        for ((_, meta) in metaMap) {
            // A downloaded music video and the catalogue track it resolved to
            // are deliberately recorded as two ids for one file. Verify and
            // materialise that file once, not once per alias.
            if (!seenUris.add(meta.uri)) continue
            val uri = meta.uri.toUri()
            if (DownloadStore.exists(context, uri)) {
                val fileModifiedMillis = uri.takeIf { it.scheme == "file" }
                    ?.path
                    ?.let(::File)
                    ?.lastModified()
                    ?.takeIf { it > 0 }
                val (dateAddedSeconds, dateModifiedSeconds) = resolvedDownloadDates(
                    persistedAddedSeconds = meta.dateAddedSeconds,
                    fileModifiedMillis = fileModifiedMillis,
                )
                if (meta.dateAddedSeconds == null && dateAddedSeconds != null) {
                    migratedAddedByUri[meta.uri] = dateAddedSeconds
                }
                val downloadFormat = meta.downloadFormat
                    ?: legacyYoutubeDownloadBadge(context, uri)?.also {
                        migratedFormatByUri[meta.uri] = it
                    }
                result.add(
                    Song(
                        videoId = meta.videoId,
                        title = meta.title,
                        artist = meta.artist,
                        thumbnailUrl = meta.thumbnailUrl,
                        durationText = meta.durationText,
                        albumName = meta.albumName,
                        localUri = meta.uri,
                        downloadFormat = downloadFormat,
                        localDateAddedSeconds = dateAddedSeconds,
                        localDateModifiedSeconds = dateModifiedSeconds,
                        isVideo = meta.wasVideoRow(),
                        typeUnknown = meta.typeUnknown,
                    )
                )
            } else {
                // Clear every id for the shared file. Leaving its second alias
                // behind would make a later menu claim the missing download
                // still exists.
                metaMap.filterValues { it.uri == meta.uri }.keys.forEach(::forget)
            }
        }
        if (migratedAddedByUri.isNotEmpty() || migratedFormatByUri.isNotEmpty()) {
            record(
                saved = { it },
                meta = { current ->
                    current.mapValues { (_, saved) ->
                        saved.copy(
                            dateAddedSeconds = saved.dateAddedSeconds
                                ?: migratedAddedByUri[saved.uri],
                            downloadFormat = saved.downloadFormat
                                ?: migratedFormatByUri[saved.uri],
                        )
                    }
                },
            )
        }
        result
    }

    /**
     * Returns the available files for one downloaded collection, in its saved
     * running order.
     *
     * Opening one downloaded playlist used to call [getDownloadedSongs] and
     * then build every saved collection merely to discard all but this one.
     * That makes the cost of opening a ten-track playlist proportional to the
     * listener's entire Downloads folder, including a ContentResolver open for
     * every recorded id.  The collection record already says exactly which
     * files can belong here, so only those files need to be verified.
     */
    suspend fun getCollectionSongs(context: Context, collectionId: String): List<Song> =
        withContext(Dispatchers.IO) {
            open()
            val record = _collections.value[collectionId] ?: return@withContext emptyList()
            val metadata = _savedMetadata.value
            val saved = _saved.value
            val metadataByUri = metadata.values.associateBy { it.uri }
            val seenUris = HashSet<String>()

            record.videoIds.mapNotNull { videoId ->
                val uriString = saved[videoId] ?: metadata[videoId]?.uri ?: return@mapNotNull null
                if (!seenUris.add(uriString)) return@mapNotNull null
                val uri = uriString.toUri()
                if (!DownloadStore.exists(context, uri)) {
                    // Both a video id and its catalogue replacement can name
                    // this file. Prune every alias now that the one disk check
                    // has established that the shared file is gone.
                    saved.filterValues { it == uriString }.keys.forEach(::forget)
                    return@mapNotNull null
                }

                val meta = metadata[videoId] ?: metadataByUri[uriString] ?: return@mapNotNull null
                val fileModifiedMillis = uri.takeIf { it.scheme == "file" }
                    ?.path
                    ?.let(::File)
                    ?.lastModified()
                    ?.takeIf { it > 0 }
                val (dateAddedSeconds, dateModifiedSeconds) = resolvedDownloadDates(
                    persistedAddedSeconds = meta.dateAddedSeconds,
                    fileModifiedMillis = fileModifiedMillis,
                )
                Song(
                    videoId = videoId,
                    title = meta.title,
                    artist = meta.artist,
                    thumbnailUrl = meta.thumbnailUrl,
                    durationText = meta.durationText,
                    albumName = meta.albumName,
                    localUri = uriString,
                    downloadFormat = meta.downloadFormat ?: legacyYoutubeDownloadBadge(context, uri),
                    localDateAddedSeconds = dateAddedSeconds,
                    localDateModifiedSeconds = dateModifiedSeconds,
                    isVideo = meta.wasVideoRow(),
                    typeUnknown = meta.typeUnknown,
                )
            }
        }

    private fun String.toUri(): Uri = Uri.parse(this)

    // ---- Driven by DownloadService -----------------------------------------

    /**
     * The next track to fetch, or null when the queue is empty.
     *
     * Claims it as running under the same lock that removed it, so there is no
     * instant where a track is in neither the queue nor the running slot and a
     * [cancel] for it would quietly do nothing.
     */
    internal fun takeNext(): Song? = synchronized(lock) {
        val entry = pending.entries.firstOrNull() ?: return null
        pending.remove(entry.key)
        running[entry.key] = null
        entry.value
    }

    /** Attach the job fetching [videoId], unless it has been cancelled meanwhile. */
    internal fun onRunning(videoId: String, job: Job) {
        val cancelled = synchronized(lock) {
            if (videoId !in running) return@synchronized true
            running[videoId] = job
            false
        }
        if (cancelled) job.cancel()
    }

    /** [videoId] is finished, one way or another, and no longer holds a worker. */
    internal fun onIdle(videoId: String) {
        synchronized(lock) { running.remove(videoId) }
    }

    /** Whether anything is still queued or in flight — see [DownloadService]'s workers. */
    internal fun busy(): Boolean = synchronized(lock) { pending.isNotEmpty() || running.isNotEmpty() }

    /**
     * The service is gone, so nothing is running any more.
     *
     * Distinct from [onIdle], which is one worker reporting one finished track.
     * This is the whole drain going away at once — every claim in [running] is
     * void, and leaving one behind would have [enqueue] refuse that track
     * forever as already in flight.
     */
    internal fun onStopped() {
        synchronized(lock) { running.clear() }
    }

    /**
     * Fetch one track, start to finish.
     *
     * Two halves, and the split is what lets a queue go at any speed: [prepare]
     * decides where the bytes come from and [transfer] moves them. Everything
     * that can go wrong past the point of reserving a destination has to
     * unreserve it — a cancelled or failed download must not leave a partial
     * file behind pretending to be a whole one, which is what
     * [DownloadStore.Pending] exists to make hard to get wrong.
     *
     * Pinned to [Dispatchers.IO] here rather than trusted to arrive on it.
     * Resolving a stream blocks on HTTP and runs YouTube's player JavaScript
     * through Rhino, and [DownloadService] drives this from a main-thread scope
     * so its notification work stays where it belongs — inheriting that would
     * put every network call in the resolve on the main thread, where they
     * don't fail loudly so much as fail *uniformly*: `NetworkOnMainThreadException`
     * is caught by the same per-client `runCatching` that exists to tolerate a
     * client being turned away, so every client appears to be refused and the
     * whole thing reads as a network outage.
     *
     * Several of these run at once — see [DownloadService]. Nothing in here is
     * shared between them but the two state flows, and both are written through
     * atomic updates for exactly that reason.
     */
    internal suspend fun run(context: Context, song: Song) = withContext(Dispatchers.IO) {
        val id = song.videoId
        // Set before the lookup, not after it. Resolving where a lossless track
        // comes from is the long part of a download, and leaving the row on
        // "Queued" for all of it reads as a queue that has stopped rather than
        // one that is working.
        _active.update { it + (id to DownloadState.Running(0f)) }
        DownloadSession.running(id, 0f)

        var completed = false
        try {
            val plan = prepare(context, song)
            // The manager is showing the row that was tapped, which for a music
            // video is the wrong title and the wrong cover for the file actually
            // being written. Corrected here rather than left to disagree with
            // the notification and with the Downloads page afterwards.
            DownloadSession.retitle(id, plan.track)
            transfer(context, song, plan)
            completed = true
        } catch (e: CancellationException) {
            clear(id)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed for $id: ${e.message}", e)
            fail(id, e.friendly())
        } finally {
            // One HQ target at a time: the next one is only queued once this
            // one has landed or failed, so the pass cannot fan out into the
            // four-worker download queue. Cheap for ordinary downloads — the
            // id is not the pass's active target and the call returns.
            HqLibraryUpgrade.onDownloadSettled(context, id, completed)
        }
    }

    /**
     * Everything that has to be known before a byte can be asked for, and
     * nothing that touches the destination.
     *
     * Split out of [run] so it *can* be done ahead of time. Nothing does yet —
     * [DownloadService] still calls it inline, and the overlap that used to be
     * the point of the split is bought by running four workers instead. Kept
     * apart anyway, because the split is what makes the expensive half
     * separately measurable and separately cancellable.
     *
     * On a lossless queue this is the expensive half by a wide margin: a module
     * search fans out across a whole index and then a stream endpoint is
     * opened, tens of seconds against the few the transfer itself takes on a
     * fast connection. Run between transfers, as it used to be, that time was
     * simply the connection standing idle once per track, which is what a long
     * queue spent most of its life doing.
     *
     * Nothing here writes to [_active] or to [DownloadSession]. It may be
     * running for a track that is still queued — or for one that gets cancelled
     * before its turn — and a preparation is not a download.
     */
    internal suspend fun prepare(context: Context, song: Song): Prepared = withContext(Dispatchers.IO) {
        // A music video keeps the video's sound if downloaded as its own row.
        // The catalogue release is the recording the app plays, so a video row
        // is resolved first and the bytes, the name and the record all follow
        // that id. A row already known to have no version keeps its own, and a
        // catalogue row is never looked up at all.
        val track = if (SongVersionPolicy.downloadIsSongVersion(
                song.isVideo,
                song.typeUnknown,
                YtMusicRepository.isSongVersionMissing(song.videoId),
            )
        ) {
            runCatching { YtMusicRepository.songVersion(song) }.getOrNull() ?: song
        } else {
            song
        }
        // Read once, here, for the whole of this track. Both routes below
        // and the re-resolve inside [Downloader.fetch] have to agree on
        // which rung they are fetching, and re-reading the setting per call
        // would let a change made mid-download splice two renditions into
        // one file. A replacement asked for at a specific rung keeps that
        // rung instead of the standing setting.
        val quality = synchronized(lock) { qualityOverrides[song.videoId] }
            ?: AppSettings.downloadQuality.value
        // The automatic pass pins the exact verified pair. It must not adopt a
        // file already under the same name either: a FLAC replacement has the
        // same filename as the FLAC it is replacing, and adopting that would
        // answer a hi-res verdict with yesterday's lossless bytes.
        val hq = synchronized(lock) { hqOverrides[song.videoId] }

        // Asked before the lookup rather than after it, unlike the check on the
        // route's own filename below. A file already sitting in Music under a
        // lossless extension is the answer to the whole question, and spending
        // a twenty-second module search to arrive at a name we could have
        // guessed is the difference between re-running a 300-track queue in
        // seconds and re-running it in hours. Only the extensions that can only
        // be lossless are worth guessing at: an `.m4a` may be this app's ALAC
        // or its AAC, and adopting the wrong one would quietly answer a request
        // for lossless with a transcode.
        if (hq == null && quality.keepsLossless) {
            LOSSLESS_EXTENSIONS.firstNotNullOfOrNull { extension ->
                DownloadStore.existing(context, DownloadStore.fileNameFor(track, extension))
            }?.takeIf { Downloads.savedFileIsRecording(track.videoId, it.toString()) }?.let { uri ->
                return@withContext Prepared(song.videoId, track, route = null, alreadyAt = uri)
            }
        }

        // Asked for from the Cached songs folder: the copy already on disk is
        // the answer, if it is whole. See [fromCache]. A pinned high-quality
        // replacement is not asking for whatever the cache happens to hold.
        if (hq == null && (fromCache.remove(song.videoId) || fromCache.remove(track.videoId))) {
            AudioCache.completeCopyOf(track.videoId)?.let(::cachedRoute)?.let { route ->
                Log.d(TAG, "downloading ${track.videoId} from the song cache as .${route.extension}")
                return@withContext Prepared(song.videoId, track, route = route, alreadyAt = null)
            }
        }
        val route = try {
            if (hq != null) {
                routeForVerified(context, track, quality, hq)
            } else {
                routeFor(context, track, quality)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No connection, or nothing would resolve. A complete copy in the
            // song cache is still the song, and better saved than refused.
            // A pinned replacement has no such fallback: the cache is not the
            // verified higher rung, so the old file stays instead.
            if (hq != null) throw e
            AudioCache.completeCopyOf(track.videoId)?.let(::cachedRoute)
                ?.also { Log.w(TAG, "${track.videoId} could not be fetched (${e.message}); saving the cached copy") }
                ?: throw e
        }
        Log.d(TAG, "downloading ${track.videoId} as .${route.extension} (${route.describe}, ${quality.label})")
        Prepared(song.videoId, track, route = route, alreadyAt = null, hq = hq)
    }

    /**
     * What [prepare] worked out, ready for a transfer to be run against it.
     *
     * [videoId] rides along so a look-ahead can be checked against the track
     * actually taken off the queue: the two diverge whenever something is
     * cancelled while its route is being resolved, and a plan applied to the
     * wrong track would write one song's bytes under another's name.
     */
    internal class Prepared(
        val videoId: String,
        /** The catalogue track behind the row, which may not be the row. */
        val track: Song,
        /** Null when [alreadyAt] answered the question instead. */
        val route: Route?,
        /** A file already in Music that is this download, if there is one. */
        val alreadyAt: Uri?,
        /** The verified pair this download must carry, when the automatic pass asked. */
        val hq: HqDownloadSource? = null,
    )

    /**
     * Fetch the bytes [plan] points at and publish them.
     *
     * Owns the destination from end to end: every exit out of here either
     * commits or aborts, so a caller is free to call it a second time with a
     * freshly resolved plan without the first attempt leaving anything behind.
     */
    private suspend fun transfer(context: Context, song: Song, plan: Prepared) {
        val id = song.videoId
        val track = plan.track

        // Already there from a previous run the record lost track of — adopt it
        // rather than writing a second copy beside it.
        plan.alreadyAt?.let { uri ->
            remember(song, track, uri, hq = plan.hq)
            TranslationAhead.downloaded(context, track, uri.toString())
            DownloadSession.done(id)
            clear(id)
            return
        }
        val route = plan.route ?: error("다운로드할 항목이 없습니다")

        var pending: DownloadStore.Pending? = null
        var lyrics: Deferred<LyricsTag.Embeddable?>? = null
        var artwork: Deferred<MediaTagger.Artwork?>? = null
        try {
            coroutineScope {
                // Started before the transfer rather than after it, so four lyric
                // services are being raced while the bytes are already moving. Done
                // after the commit instead, every download would pay the slowest of
                // them in dead time — and it is a *suspending* wait, so it would sit
                // in the one stretch of this function that has no way back: past the
                // commit, [pending] is null and a cancellation there would abandon a
                // finished file that nothing has recorded yet. Awaited below while
                // there is still a pending destination to abort.
                //
                // [LyricsTag.forTrack] is contracted not to throw for anything but
                // cancellation, and that contract is load-bearing here: this is a
                // plain child of the scope, so a failure inside it would cancel the
                // download it was only meant to decorate.
                // HLS has no tag container, but its private offline package has
                // sidecars for exactly the same lyrics and full-resolution cover.
                if (route.taggable && (route.offlineHls != null || MediaTagger.carriesTags(route.extension))) {
                    lyrics = async { LyricsTag.forTrack(track) }
                    artwork = async { MediaTagger.artworkFor(context, track) }
                }

                val name = DownloadStore.fileNameFor(track, route.extension)
                val alreadyThere = DownloadStore.existing(context, name)
                if (plan.hq == null &&
                    alreadyThere != null &&
                    savedFileIsRecording(track.videoId, alreadyThere.toString())
                ) {
                    Log.d(TAG, "$name is already in Music; adopting it")
                    remember(song, track, alreadyThere)
                    TranslationAhead.downloaded(context, track, alreadyThere.toString())
                    DownloadSession.done(id)
                    clear(id)
                    return@coroutineScope
                }
                if (alreadyThere != null && plan.hq == null) {
                    // Same name, but not a file this record knows to be the
                    // requested recording. A video's sound sitting under the
                    // song's name is exactly what used to be adopted here.
                    Log.d(TAG, "$name exists but is not ${track.videoId}'s verified recording; downloading it")
                }

                val manifest = route.offlineHls
                if (manifest != null) {
                    val onSegment: (Long, Long) -> Unit = { written, total ->
                        val fraction = written.toFloat() / total
                        _active.update { it + (id to DownloadState.Running(fraction)) }
                        DownloadSession.running(id, fraction)
                    }
                    val savedUri = if (manifest.dash) {
                        OfflineDash.save(
                            context = context,
                            id = id,
                            url = manifest.url,
                            headers = manifest.headers,
                            onProgress = onSegment,
                            lyrics = lyrics?.await(),
                            artwork = artwork?.await(),
                        )
                    } else {
                        OfflineHls.save(
                            context = context,
                            id = id,
                            url = manifest.url,
                            headers = manifest.headers,
                            onProgress = onSegment,
                            lyrics = lyrics?.await(),
                            artwork = artwork?.await(),
                        )
                    }
                    remember(song, track, savedUri, route.downloadFormat, hq = plan.hq)
                    TranslationAhead.downloaded(context, track, savedUri.toString())
                    DownloadSession.done(id)
                    clear(id)
                    Log.d(TAG, "saved offline ${if (manifest.dash) "DASH" else "HLS"} package for $name")
                    return@coroutineScope
                }

                val destination = DownloadStore.begin(context, name, route.mimeType)
                pending = destination
                destination.openStream().use { sink ->
                    route.write(sink) { written, total ->
                        val fraction = written.toFloat() / total
                        _active.update { it + (id to DownloadState.Running(fraction)) }
                        DownloadSession.running(id, fraction)
                    }
                }
                val words = lyrics?.await()
                val cover = artwork?.await()
                // Publish only after metadata is part of the file. This keeps
                // concurrent album workers from exposing untagged tracks.
                MediaTagger.embed(context, destination.tagUri, track, route.extension, words, cover)
                val artworkUri = MediaTagger.persistArtwork(context, track.thumbnailUrl, cover)
                val savedUri = destination.commit()
                pending = null
                remember(song, track, savedUri, route.downloadFormat, artworkUri, hq = plan.hq)
                TranslationAhead.downloaded(context, track, savedUri.toString())
                DownloadSession.done(id)
                clear(id)
                Log.d(TAG, "saved $name")
            }
        } catch (e: Throwable) {
            pending?.abort()
            throw e
        } finally {
            // Every exit needs this, not just the failing ones: the adopt-it
            // path above returns with the lookup still in flight, and
            // [coroutineScope] does not return while a child of it is running —
            // so an unwaited job would hold the whole queue up for the length of
            // a lyrics search per already-downloaded track.
            lyrics?.cancel()
            artwork?.cancel()
        }
    }

    /**
     * One resolved download: what to call the file, what to tell the store it
     * is, and how to fill it.
     *
     * Exists so [run] has one linear body rather than two nearly-identical
     * ones. Everything after the bytes are chosen — the duplicate check, the
     * pending row, the commit, the tagging, the abort on failure — is the same
     * work whichever server the audio came from, and the two routes differ only
     * in these four answers.
     */
    internal class Route(
        val extension: String,
        val mimeType: String,
        /** For the log line, so a download's provenance is on the record. */
        val describe: String,
        /** Short premium-rendition badge shown only in BitChord's Downloads list. */
        val downloadFormat: String? = null,
        val taggable: Boolean = true,
        val offlineHls: Manifest? = null,
        val write: suspend (OutputStream, (written: Long, total: Long) -> Unit) -> Unit,
    )

    /**
     * A stream that arrived as an index rather than as audio, and which of the
     * two index formats it is. Both are saved into the same offline package —
     * see [OfflineDash] — so [dash] only decides who does the parsing.
     */
    internal class Manifest(
        val url: String,
        val headers: Map<String, String>,
        val dash: Boolean = false,
    )

    /**
     * Where this download's bytes are coming from.
     *
     * A configured source gets asked first, and YouTube is what happens when
     * none of them can serve it — see [SourceResolver.forDownload] for what
     * "can" means, which is narrower here than it is for playback.
     *
     * @param quality read once by the caller and passed down, so that a setting
     *   changed while this track is in the queue applies to the next one rather
     *   than to the middle of this one. [Downloader.fetch] resolves again after
     *   a mid-download refusal and has to ask for the same rung it started on.
     */
    private suspend fun routeFor(context: Context, track: Song, quality: DownloadQuality): Route {
        fromSources(track, quality)?.let { (stream, storable) ->
            routeFromSource(context, track, stream, storable)?.let { return it }
        }
        // MediaStore.Audio refuses audio/webm on affected Android versions.
        // Keep Opus for app-private downloads, but pin exported downloads to
        // AAC-in-MP4 for both the initial resolve and any mid-transfer retry.
        val requireM4a = AppSettings.exportDownloads.value
        val stream = StreamResolver.resolveForDownload(track.videoId, quality.maxKbps, requireM4a)
        return Route(
            extension = stream.downloadExtension,
            mimeType = stream.downloadMimeType,
            describe = "${stream.kbps}kbps ${stream.mimeType}",
            downloadFormat = youtubeDownloadBadge(stream.downloadExtension, stream.kbps),
            write = { sink, onProgress ->
                Downloader.fetch(track.videoId, stream, quality.maxKbps, requireM4a, sink, onProgress)
            },
        )
    }

    /**
     * The route for the exact pair a sound comparison accepted.
     *
     * Deliberately not [routeFor]: that searches the configured sources again
     * and can land on a different row under the same title. The verdict names
     * one source and one track id, and the replacement has to be that stream or
     * nothing. A source that now answers with a lower rung than was proved is
     * refused too, so a failed catalogue cannot quietly downgrade the file it
     * was meant to upgrade.
     */
    private suspend fun routeForVerified(
        context: Context,
        track: Song,
        quality: DownloadQuality,
        hq: HqDownloadSource,
    ): Route {
        val stream = SourceResolver.forDownloadVerified(
            sourceConfigId = hq.sourceConfigId,
            trackId = hq.trackId,
            request = SourceResolver.requestForDownload(quality),
        ) ?: error("검증된 고음질 음원을 받을 수 없습니다")
        val served = HqQuality.tierOf(stream.format)
        if (served.rank > hq.tier.rank) {
            error("검증된 고음질 음원이 더 낮은 등급으로 응답했습니다 (${served.label})")
        }
        val storable = DownloadStore.storable(stream.format.codec)
            ?: error("이 기기에 저장할 수 없는 형식입니다 (${stream.format.codec})")
        return routeFromSource(context, track, stream, storable)
            ?: error("고음질 음원을 내보낼 수 없습니다")
    }

    /**
     * Turn an open source stream into a file route, or null when it cannot be
     * filed where this download is going (an exported manifest, for instance).
     */
    private fun routeFromSource(
        context: Context,
        track: Song,
        stream: SourceStream,
        storable: DownloadStore.Storable,
    ): Route? {
        // A manifest is an index, not audio. Whichever kind it is, fetching
        // it as a file writes the index into something named `.flac` —
        // which is exactly what a `.mpd` did until [OfflineDash] existed:
        // a 2.7 KB download that reported success and could never play.
        // Read off the URL rather than off `stream.format`, which describes
        // the audio inside and says nothing about the envelope.
        val dash = OfflineDash.handles(stream.url)
        val hls = stream.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val packaged = hls || dash
        // A manifest's segments are joined back into one ordinary file —
        // see [ManifestFile] — exported or not, so a lossless download is
        // a `.flac` wherever it lands. Exports used to decline manifests
        // instead, which meant YouTube: Tidal serves every lossless tier
        // as a manifest, so an exporting user heard FLAC and saved 131kbps
        // AAC. Only a codec the assembler can't file falls through to the
        // package (in-app) or YouTube (export) below.
        if (packaged && storable.extension in ManifestFile.EXTENSIONS) {
            return Route(
                extension = storable.extension,
                mimeType = storable.mimeType,
                describe = "${stream.format.summary} (assembled from ${if (dash) "DASH" else "HLS"})",
                downloadFormat = stream.format.downloadBadge(),
                write = { sink, onProgress ->
                    ManifestFile.write(
                        context.cacheDir, stream.url, stream.headers, dash,
                        storable.extension, sink, onProgress,
                    )
                },
            )
        }
        if (packaged && AppSettings.exportDownloads.value) {
            Log.d(TAG, "can't export a .${storable.extension} manifest; taking YouTube for ${track.videoId}")
            return null
        }
        return Route(
            // DASH is saved *as* HLS — see [OfflineDash] for why — so both
            // kinds land as the same package and are named for what was
            // written rather than for what was fetched.
            extension = if (packaged) "m3u8" else storable.extension,
            mimeType = if (packaged) "application/vnd.apple.mpegurl" else storable.mimeType,
            describe = stream.format.summary,
            downloadFormat = stream.format.downloadBadge(),
            taggable = true,
            offlineHls = Manifest(stream.url, stream.headers, dash = dash).takeIf { packaged },
            write = { sink, onProgress ->
                Downloader.fetchDirect(stream.url, stream.headers, sink, onProgress)
            },
        )
    }

    /**
     * A route that copies [copy] out of the song cache instead of fetching
     * anything, or null when it cannot be filed where downloads go: exported
     * downloads are pinned away from WebM for the same MediaStore reason
     * [routeFor] gives.
     */
    private fun cachedRoute(copy: AudioCache.CompleteCopy): Route? {
        if (copy.extension == "webm" && AppSettings.exportDownloads.value) return null
        return Route(
            extension = copy.extension,
            mimeType = copy.mimeType,
            describe = "song cache, ${copy.length} bytes",
            downloadFormat = if (copy.extension == "flac") "FLAC" else null,
            write = { sink, onProgress -> AudioCache.copyOut(copy, sink, onProgress) },
        )
    }

    /**
     * The stream to keep for [track] from a configured source, with how to file
     * it — or null, which is not a failure, just YouTube's turn.
     *
     * Usually a bit-exact one; not always. [SourceResolver.forDownload] falls
     * back to the best lossy copy any enabled source holds when nothing has the
     * recording losslessly, and only gives up on the sources entirely when what
     * they offer would not beat YouTube's own AAC. Which of those happened is
     * the resolver's business — from here it is a URL and a codec either way.
     *
     * Bounded, because a module search waits on every backend it has (see
     * `ModuleSource.SEARCH_PATIENT_MS`) and does that once per query the matcher
     * offers. For a track no module holds, that is the whole queue stopped for
     * the better part of a minute on the way to a download that was always
     * going to be YouTube's. `PlaybackService.SUBSTITUTE_TIMEOUT_MS` bounds the
     * same search for the same reason.
     *
     * The [DownloadStore.storable] check belongs here rather than inside the
     * resolver: the resolver's job is finding the best audio, and whether this
     * device will keep a file of that codec is a question about Android.
     *
     * @param quality pinned by [run] for the whole of this track. Passed on to
     *   the resolver rather than left to it, so that the twenty seconds this may
     *   spend searching cannot be a window in which the setting changes and the
     *   two halves of one decision disagree.
     */
    private suspend fun fromSources(
        track: Song,
        quality: DownloadQuality,
    ): Pair<SourceStream, DownloadStore.Storable>? {
        // Wrapped so the two ways this can come back empty stay apart in the
        // log. `withTimeoutOrNull` collapses them into one null, and the
        // difference is the whole diagnosis: "no enabled source holds this
        // recording" is a fact about the track, and "the clock ran out" is a
        // fact about [SOURCE_LOOKUP_MS] — which is what silently sent a whole
        // Lossless queue to YouTube's AAC while the log said nothing at all.
        var timedOut = true
        val stream = withTimeoutOrNull(SOURCE_LOOKUP_MS) {
            try {
                SourceResolver.forDownload(
                    TrackMatcher.targetOf(track),
                    SourceResolver.requestForDownload(quality),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "source lookup failed for ${track.videoId}: ${e.message}")
                null
            }.also { timedOut = false }
        }
        if (stream == null) {
            if (timedOut) {
                Log.w(
                    TAG,
                    "source lookup for ${track.videoId} ran past ${SOURCE_LOOKUP_MS}ms; " +
                        "downloading it from YouTube instead of ${quality.label}",
                )
            }
            return null
        }

        val storable = DownloadStore.storable(stream.format.codec)
        if (storable == null) {
            Log.d(TAG, "nothing to file a '${stream.format.codec}' as; taking YouTube for ${track.videoId}")
            return null
        }
        return stream to storable
    }

    /** Back to "not downloaded" — used for success, where [saved] takes over, and for cancellation. */
    private fun clear(videoId: String) {
        _active.update { it - videoId }
        synchronized(lock) {
            qualityOverrides.remove(videoId)
            hqOverrides.remove(videoId)
        }
    }

    private fun fail(videoId: String, reason: String) {
        _active.update { it + (videoId to DownloadState.Failed(reason)) }
        DownloadSession.failed(videoId, reason)
    }

    /**
     * A failure a user can read. The technical text an exception carries is
     * logged here and replaced with one Korean sentence for the screen —
     * `Unsupported MIME type audio/webm` belongs in the log, not in a row next
     * to the song it stopped.
     */
    private fun Exception.friendly(): String {
        Log.w(TAG, "download failed: ${message.orEmpty()}", this)
        return appContext?.let { FriendlyError.message(it, this) }
            ?: "다운로드 실패 — 연결 상태를 확인하십시오"
    }

    /**
     * How long the source lookup may hold a download up before it goes to
     * YouTube regardless.
     *
     * **This has to clear `ModuleSource.SEARCH_PATIENT_MS`, and for a long time
     * it didn't.** It was 20s against a patient window of 25s, which made the
     * arithmetic decide the outcome: [SourceResolver.forDownload] asks every
     * source with `waitForAll = true`, that flag buys each module the full 25s
     * patient window, and this timeout fired five seconds before the window it
     * was waiting on could even close. Every track whose modules were not
     * unusually quick came back null — not "no source has it", but "nobody was
     * asked for long enough" — and null here means YouTube. So a Lossless
     * setting reliably produced YouTube's AAC, and it looked like a bulk-only
     * fault because it *was* one: a single download runs with the engines to
     * itself and lands inside 20s, while four workers sharing three interpreter
     * engines per module (`QuickJsExecutor.ENGINES_PER_MODULE`) do not.
     *
     * Sized off what it actually bounds, therefore, rather than off the playback
     * timeout it used to be matched to. One patient search is 25s, the matcher
     * offers up to three queries (`TrackMatcher.queries` — the third only for a
     * kana title, in the other script), and [streamBest] may then open up to
     * `STREAM_ATTEMPTS` stream endpoints on the winner. Eighty-five seconds
     * covers a slow-but-working index; it is still finite, because the
     * alternative is the queue stalled per track on modules that simply do not
     * have the recording.
     *
     * The comparison with playback is no longer the right one and is worth
     * stating plainly: `PlaybackService.SUBSTITUTE_TIMEOUT_MS` is short because
     * a listener is staring at a paused player. Nothing is waiting here, the
     * answer becomes a permanent file, and re-fetching it later costs the whole
     * download again.
     *
     * It bounds the lossy half of that lookup too, which is why
     * [SourceResolver.forDownload] runs both halves at once rather than in
     * turn: a fast source queued behind a slow one would spend this budget
     * waiting for a module and never be asked.
     */
    private const val SOURCE_LOOKUP_MS = 85_000L

    /**
     * The extensions a file in Music can carry that say, on their own, that a
     * lossless request has already been answered.
     *
     * `m4a` is deliberately absent even though [DownloadStore.storable] files
     * ALAC as one: an `.m4a` in this folder is just as likely to be the AAC a
     * download at the High rung wrote, and there is nothing in the name to
     * separate them. Guessing wrong there would answer a request for lossless
     * with a transcode and never fetch the real thing.
     */
    private val LOSSLESS_EXTENSIONS = listOf("flac", "wav")

    /**
     * Why a download didn't start, when the reason is a setting rather than a
     * fault.
     *
     * Names the switch, because a refusal that only says no leaves the user
     * looking for a network problem that isn't there. Shared with the callers
     * that show it as a toast so the two cannot drift apart.
     */
    internal const val WIFI_ONLY_REFUSAL = "다운로드가 Wi-Fi 전용으로 설정되어 있습니다"

    /** Dropped when the sheet is reopened; a failure is worth showing once. */
    fun dismissFailure(videoId: String) {
        if (_active.value[videoId] is DownloadState.Failed) clear(videoId)
    }

    private const val KEY_SAVED = "downloaded_tracks"
}

@kotlinx.serialization.Serializable
internal data class SavedSongMetadata(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    /**
     * What release this track is off, when the row it was downloaded from knew.
     *
     * Added after the fact and defaulted, so a record written before it existed
     * still decodes — those entries come back with a null album and are filled
     * in from the file's own tags instead, see LocalMediaRepository.
     */
    val albumName: String? = null,
    val uri: String,
    val downloadFormat: String? = null,
    /** Stable creation time for downloads that MediaStore does not index. */
    val dateAddedSeconds: Long? = null,
    /**
     * The id whose stream produced the file's bytes.
     *
     * A music video is filed under its own id and the catalogue release it
     * resolved to, so the two ids alone cannot say which recording is on disk.
     * Defaulted for records written before this field existed: those are
     * verified online before their file is trusted, and kept offline — see
     * [SongVersionPolicy.verifiesUnknownDownload].
     */
    val soundSourceVideoId: String? = null,
    /**
     * Whether the row this record was written from was a music video.
     *
     * Defaulted for records written before this field existed. Such a row is
     * recovered from [soundSourceVideoId] when that names a different upload,
     * and otherwise verified online like any other unknown-provenance file —
     * see [SongVersionPolicy.verifiesUnknownDownload].
     */
    val isVideo: Boolean = false,
    /** Whether the row's upload type was still unknown when it was saved. */
    val typeUnknown: Boolean = false,
    /**
     * The verified high-quality pair this file was fetched from, when it was
     * replaced by the automatic pass. Defaulted so older records still decode.
     *
     * The ids are the ones the sound comparison stored, so a later pass can
     * tell that this exact candidate has already landed rather than fetching
     * it again. [hqOffsetMs] and [hqRate] are the alignment the lyrics clock
     * needs; [hqTier] is the rung that was proved at the time.
     */
    val hqSourceConfigId: String? = null,
    val hqTrackId: String? = null,
    val hqOffsetMs: Long? = null,
    val hqRate: Double? = null,
    val hqTier: String? = null,
)

/**
 * Whether this record names a music-video row.
 *
 * New records say so directly. An older record can still be recovered from its
 * provenance: a file recorded from a *different* id's stream was filed under
 * the video's id as well, so this id is the video side of that pair.
 */
private fun SavedSongMetadata.wasVideoRow(): Boolean =
    isVideo || (soundSourceVideoId != null && soundSourceVideoId != videoId)

/**
 * Dates for a downloaded file-backed row.
 *
 * Old metadata has no persisted creation time, so the file timestamp is the
 * only honest migration value. From then on the persisted value stays fixed,
 * while modified time continues to follow the file on disk.
 */
internal fun resolvedDownloadDates(
    persistedAddedSeconds: Long?,
    fileModifiedMillis: Long?,
): Pair<Long?, Long?> {
    val modifiedSeconds = fileModifiedMillis?.takeIf { it > 0 }?.div(1_000)
    return (persistedAddedSeconds ?: modifiedSeconds) to modifiedSeconds
}

/** Premium source labels used in BitChord's Downloads list. */
private fun StreamFormat.downloadBadge(): String? = when {
    isDolbyAtmos -> "DOLBY"
    codec.equals("flac", ignoreCase = true) || codec.equals("x-flac", ignoreCase = true) -> "FLAC"
    else -> null
}

/**
 * YouTube does not expose a named quality tier once the resolved file has been
 * saved, so retain the concrete codec/container choice and bitrate alongside
 * the download. This is display metadata only: the bytes are still the exact
 * WebM/Opus or MP4/AAC rendition returned by YouTube.
 */
internal fun youtubeDownloadBadge(extension: String, kbps: Int): String? {
    if (kbps <= 0) return null
    val codec = if (extension.equals("m4a", ignoreCase = true)) "AAC" else "OPUS"
    return "$codec · $kbps kbps"
}

/** Recover display metadata for private YouTube downloads made before it was persisted. */
private fun legacyYoutubeDownloadBadge(context: Context, uri: Uri): String? {
    if (!uri.lastPathSegment.orEmpty().substringBefore('?').endsWith(".webm", ignoreCase = true)) {
        return null
    }
    val bitsPerSecond = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull() ?: return null
    val kbps = ((bitsPerSecond + 500L) / 1_000L).toInt()
    return youtubeDownloadBadge("webm", kbps)
}

/**
 * What a batch download was asked for as a whole.
 *
 * Built by whichever surface the tap came from — a release page's own download
 * button, a shelf card's menu — because that surface is the only thing that
 * knows the answer, and by the time the tracks reach the queue they are forty
 * unrelated rows. Null everywhere a single track is downloaded on its own,
 * which is the honest answer there: one song off an album is not the album.
 */
data class DownloadTarget(
    /**
     * What this release is filed under: its browse id where it has one, so the
     * same album downloaded twice is one entry rather than two.
     */
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    /** Playlists and albums are grouped alike but not billed alike. */
    val playlist: Boolean = false,
)

/**
 * A release the record says was downloaded whole, as it is written down.
 *
 * Only the tracks' ids, not the tracks: a [Song] is a wide row full of things
 * that go stale — like state, autoplay provenance, a resolved local path — and
 * a second copy of one per release is a second copy to keep in step. The songs
 * are looked up out of what the Downloads page already read off disk instead;
 * see [Downloads.collectionsAmong].
 */
@kotlinx.serialization.Serializable
data class SavedCollection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
    /** In the order the page listed them, which is the order to play them in. */
    val videoIds: List<String> = emptyList(),
)

/** A [SavedCollection] with its surviving tracks attached, ready to draw. */
data class DownloadedCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val playlist: Boolean,
    val songs: List<Song>,
)
