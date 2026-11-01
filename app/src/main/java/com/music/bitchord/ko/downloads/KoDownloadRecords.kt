package com.music.bitchord.ko.downloads

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.StoredSongDisplay
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.net.DownloadedMvPolicy
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.DownloadHealthPolicy
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.MvSoundRedownload
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.download.mvRepairAllowsMetered
import com.music.bitchord.playback.smart.HqVerificationStore
import com.music.bitchord.startup.StartupWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * The Korean fork's download records: opening them, the saved-file accessors
 * the rest of the fork reads, and the music-video-sound verdicts kept beside
 * them. Everything here used to live inside [Downloads].
 */
internal object KoDownloadRecords {

    private const val TAG = "BitChord"
    private const val KEY_MV_SOUND_SUSPECTS = "downloaded_mv_sound_suspects"

    /** Where the settings file is opened from, once [Downloads.init] has been told. */
    private var appContext: Context? = null

    /** Set once [open] has finished, so the startup open and an early caller run it once. */
    @Volatile
    private var opened = false

    /**
     * Whether both download records were decoded rather than replaced by an
     * empty map after a parse failure. A corrupt record must not make the
     * offline-package sweep treat every package as unreferenced.
     */
    @Volatile
    private var recordsReadable = false

    private val openLock = Any()
    private val suspectSerializer = MapSerializer(String.serializer(), Long.serializer())

    /**
     * Downloaded song ids whose file is the music video's sound, by how much
     * longer than the song it is when that is known, as judged once and kept
     * across launches.
     *
     * A record may already say video/unknown or name another upload as its
     * source; a legacy record with no provenance can only be told from the song
     * by its length. The verdict is remembered so the same file is neither
     * re-measured nor re-requested on every play, and so the Downloads page can
     * offer to fetch the song version without a network request of its own.
     */
    private val _mvSoundSuspects = MutableStateFlow<Map<String, Long>>(emptyMap())
    val mvSoundSuspects: StateFlow<Map<String, Long>> = _mvSoundSuspects.asStateFlow()

    /** Serialises the suspect map's update-and-write, so a later verdict is not overwritten. */
    private val suspectLock = Any()

    /** The application context [Downloads.init] was given, for metadata built off the main thread. */
    fun applicationContext(): Context? = appContext

    fun init(context: Context) {
        appContext = context.applicationContext
        // Three JSON maps that grow with the listener's Downloads folder. None
        // of them is needed for the first frame, so they are parsed on the
        // startup thread; a caller that gets here first runs [open] itself.
        // The abandoned-destination sweep needs the records to know which
        // offline packages are still referenced, so it runs after [open]. It
        // only touches entries older than this process, which is what keeps a
        // download started by the foreground or liked event safe beside it.
        StartupWork.background("downloads") {
            open()
            DownloadStore.cleanupAbandoned(context, offlinePackageRoots())
        }
    }

    /**
     * Directories the recorded offline packages still point at.
     *
     * Null when the records could not be decoded: an empty answer then would
     * make every old package look unreferenced and delete files a corrupt
     * record still owns.
     */
    private fun offlinePackageRoots(): Set<String>? {
        if (!recordsReadable) return null
        return buildSet {
            for (raw in Downloads.savedValue().values + Downloads.savedMetadataValue().values.map { it.uri }) {
                val path = runCatching { Uri.parse(raw).path }.getOrNull() ?: continue
                val root = File(path).parentFile ?: continue
                if (root.parentFile?.name == "offline-hls") add(root.absolutePath)
            }
        }
    }

    /**
     * Opens the record now if the startup thread has not got to it yet.
     *
     * Only needed by callers that read [Downloads.saved] or [Downloads.collections]
     * as a value rather than collecting them: a collector picks the loaded map
     * up when it arrives, a one-off read would keep the empty one it saw first.
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
    fun open() {
        synchronized(openLock) {
            if (opened) return
            val context = appContext ?: return
            Downloads.openPrefs(context)
            val saved = runCatching {
                Downloads.decodeSaved(Downloads.prefs)
            }
            val metadata = runCatching {
                Downloads.decodeMetadata(Downloads.prefs)
            }
            Downloads.setSaved(saved.getOrDefault(emptyMap()))
            Downloads.setSavedMetadata(metadata.getOrDefault(emptyMap()))
            recordsReadable = saved.isSuccess && metadata.isSuccess
            _mvSoundSuspects.value = runCatching {
                Downloads.json.decodeFromString(
                    suspectSerializer,
                    Downloads.prefs.getString(KEY_MV_SOUND_SUSPECTS, null) ?: "{}",
                )
            }.getOrDefault(emptyMap())
            Downloads.setCollections(
                runCatching {
                    Downloads.decodeCollections(Downloads.prefs)
                }.getOrDefault(emptyMap()),
            )
            opened = true
        }
    }

    /**
     * A file already on disk for [track] at [extension], including one a failed
     * replacement rename left under its staging name.
     *
     * Asking only for [DownloadStore.fileNameFor] missed that file, and the
     * next pass downloaded the same bytes again.
     */
    fun existingDownload(context: Context, track: Song, extension: String): Uri? {
        open()
        val baseName = DownloadStore.fileNameFor(track, extension)
        val recordedName = Downloads.savedMetadataValue()[track.videoId]?.displayName
        return DownloadStore.lookupNames(baseName, recordedName).firstNotNullOfOrNull { name ->
            DownloadStore.existing(context, name)
        }
    }

    /**
     * The other id that names the same downloaded file as [videoId], when one
     * was recorded — see [Downloads.remember], which files a music video and the
     * catalogue release it resolved to under both ids.
     *
     * Null for an ordinary download, which is recorded under its own id only.
     * Cheaper than looking the file up again, and enough for a caller to build
     * the release row without a network request.
     */
    fun savedAliasFor(videoId: String): String? {
        open()
        val saved = Downloads.savedValue()
        val uri = saved[videoId] ?: return null
        return saved.entries
            .firstOrNull { (id, value) -> id != videoId && value == uri }
            ?.key
    }

    /** The metadata recorded for a downloaded id, without touching its file. */
    fun savedMetadataFor(videoId: String): SavedSongMetadata? {
        open()
        return Downloads.savedMetadataValue()[videoId]
    }

    /** A snapshot of every recorded download, without a disk check per row. */
    fun savedMetadataSnapshot(): Map<String, SavedSongMetadata> {
        open()
        return Downloads.savedMetadataValue()
    }

    /** A downloaded row as a playable [Song], or null when it was never saved. */
    fun savedSongFor(videoId: String): Song? {
        open()
        val meta = Downloads.savedMetadataValue()[videoId] ?: return null
        return StoredSongDisplay.clean(
            Song(
                videoId = meta.videoId,
                title = meta.title,
                artist = meta.artist,
                thumbnailUrl = meta.thumbnailUrl,
                durationText = meta.durationText,
                albumName = meta.albumName,
                localUri = Downloads.savedValue()[videoId] ?: meta.uri,
                downloadFormat = meta.downloadFormat,
                isVideo = meta.wasVideoRow(),
                typeUnknown = meta.typeUnknown,
            ),
        )
    }

    /** Whether [videoId]'s recorded file carries the provenance of [hq]. */
    fun hqApplied(videoId: String, hq: HqDownloadSource): Boolean {
        open()
        val meta = Downloads.savedMetadataValue()[videoId] ?: return false
        return meta.hqSourceConfigId == hq.sourceConfigId &&
            meta.hqTrackId == hq.trackId &&
            meta.hqTier == hq.tier.name
    }

    /**
     * Whether [videoId]'s file was proved to be another recording.
     *
     * The flag survives until a replacement lands, so a wrong file is neither
     * served to the player nor adopted as the answer to a repair download.
     */
    fun isWrongRecording(videoId: String): Boolean {
        open()
        return Downloads.savedMetadataValue()[videoId]?.wrongRecording == true
    }

    /**
     * Marks [videoId]'s file as the wrong recording without touching its bytes.
     *
     * Called by the playback health check once the sound comparison or the
     * arrangement evidence has condemned it. The file stays where it is until
     * the repair download commits, so a failed repair leaves something to
     * inspect rather than silently losing the listener's data.
     */
    fun markWrongRecording(
        videoId: String,
        inspectedUri: String,
        inspectedVersion: String? = null,
    ): Boolean {
        open()
        val context = appContext
        Downloads.record(
            saved = { it },
            meta = { map ->
                val current = map[videoId] ?: return@record map
                val version = context?.let { fileVersion(it, current.uri) }
                if (!DownloadHealthPolicy.shouldMarkWrong(
                        currentUri = current.uri,
                        inspectedUri = inspectedUri,
                        alreadyWrong = current.wrongRecording,
                        currentVersion = version,
                        inspectedVersion = inspectedVersion,
                    )
                ) {
                    return@record map
                }
                map + (videoId to current.copy(wrongRecording = true))
            },
        )
        val current = Downloads.savedMetadataValue()[videoId] ?: return false
        val version = context?.let { fileVersion(it, current.uri) }
        val marked = DownloadHealthPolicy.shouldMarkWrong(
            currentUri = current.uri,
            inspectedUri = inspectedUri,
            alreadyWrong = false,
            currentVersion = version,
            inspectedVersion = inspectedVersion,
        ) && current.wrongRecording
        // The source pair that supplied the wrong recording must not be picked
        // again: the replacement plan reads accepted pairs from the store, and
        // a flag on the file alone left this very pair first in line.
        if (marked && context != null && current.hqSourceConfigId != null) {
            HqVerificationStore.reject(context, videoId, current.hqSourceConfigId, current.hqTrackId)
        }
        return marked
    }

    /** The verified rung a downloaded file was saved at, when the download recorded one. */
    fun storedTier(videoId: String): HqTier? =
        Downloads.savedMetadataValue()[videoId]?.hqTier?.let { name -> runCatching { HqTier.valueOf(name) }.getOrNull() }

    /**
     * The file's own length and modified time, as one value.
     *
     * The health check compares this before and after its multi-second
     * verification so a repair that commits in the window is not mistaken for
     * the file that was inspected. A `content://` uri has no modified time,
     * but a replacement there gets a new uri anyway.
     */
    fun fileVersion(context: Context, uriString: String): String {
        val parsed = Uri.parse(uriString)
        val file = runCatching { File(parsed.path.orEmpty()) }
            .getOrNull()
            ?.takeIf { it.exists() }
        if (file != null) return "${file.length()}|${file.lastModified()}"
        val length = runCatching {
            context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { it.length }
        }.getOrNull() ?: 0L
        return "$length|0"
    }

    /** Whether [videoId] is one of this app's downloads, rather than a local file. */
    fun isDownloaded(videoId: String): Boolean {
        open()
        return videoId in Downloads.savedValue() || videoId in Downloads.savedMetadataValue()
    }

    /**
     * The id whose stream produced the file saved for [videoId], when it was
     * recorded. Null for a download made before provenance was kept.
     *
     * A music video is filed under both its own id and the catalogue release it
     * resolved to, so this is the only way to tell the two apart afterwards:
     * the ids name two uploads, the file can only be the sound of one of them.
     */
    fun savedSoundSourceFor(videoId: String): String? {
        open()
        return Downloads.savedMetadataValue()[videoId]?.soundSourceVideoId
    }

    /**
     * The recorded upload type, or null for a record written before the field
     * existed. Null is not the same as false: an old own-id record may still be
     * a music video's sound.
     */
    fun savedIsVideoFor(videoId: String): Boolean? {
        open()
        return Downloads.savedMetadataValue()[videoId]?.isVideo
    }

    /** Whether the saved row's upload type was still unknown, false if unrecorded. */
    fun savedTypeUnknownFor(videoId: String): Boolean {
        open()
        return Downloads.savedMetadataValue()[videoId]?.typeUnknown == true
    }

    /**
     * Fills in the upload type of a pre-field record once it has been verified
     * against the catalogue, so the same file is not looked up on every play.
     * Does nothing when the record already states a type, or has no record.
     */
    fun backfillUploadType(videoId: String, isVideo: Boolean, typeUnknown: Boolean) {
        open()
        Downloads.record(
            saved = { it },
            meta = { meta ->
                val now = meta[videoId]
                if (now == null || now.isVideo != null) {
                    meta
                } else {
                    meta + (
                        videoId to now.copy(
                            isVideo = isVideo,
                            typeUnknown = typeUnknown,
                            // The catalogue just agreed this is the recording;
                            // naming its own id also records the provenance the
                            // old entry lacked, so it is not verified again.
                            soundSourceVideoId = now.soundSourceVideoId ?: videoId,
                        )
                    )
                }
            },
        )
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
            if (Downloads.prefsReady()) {
                Downloads.prefs.edit()
                    .putString(KEY_MV_SOUND_SUSPECTS, Downloads.json.encodeToString(suspectSerializer, next))
                    .apply()
            }
        }
    }

    fun clearMvSoundSuspects(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        synchronized(suspectLock) {
            val next = _mvSoundSuspects.updateAndGet { it - videoIds.toSet() }
            if (Downloads.prefsReady()) {
                Downloads.prefs.edit()
                    .putString(KEY_MV_SOUND_SUSPECTS, Downloads.json.encodeToString(suspectSerializer, next))
                    .apply()
            }
        }
    }

    fun suspectsSnapshot(): Map<String, Long> = _mvSoundSuspects.value

    /** The song id for the file [videoId] names: the shortest of the ids filed against it. */
    fun songIdForFile(videoId: String): String {
        open()
        val saved = Downloads.savedValue()
        val metadata = Downloads.savedMetadataValue()
        val uri = saved[videoId] ?: metadata[videoId]?.uri ?: return videoId
        return saved
            .filterValues { it == uri }
            .keys
            .minByOrNull { candidate ->
                metadata[candidate]?.durationText?.durationMillis()
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
        val saved = Downloads.savedValue()
        val metadata = Downloads.savedMetadataValue()
        val uri = saved[videoId] ?: metadata[videoId]?.uri
        val recorded = if (uri != null) {
            metadata.values
                .filter { it.uri == uri }
                .mapNotNull { it.durationText?.durationMillis()?.takeIf { ms -> ms > 0L } }
        } else {
            emptyList()
        }
        return (recorded + listOfNotNull(rowDurationMs?.takeIf { it > 0L })).minOrNull()
    }

    /**
     * Scans the downloads for files that are the music video's sound, using
     * the recognition already on each record before measuring anything.
     *
     * A record that names its own stream as the song version is left alone. A
     * record that says video/unknown, or whose bytes came from another upload,
     * is kept even when provenance is known. Only a legacy record with no
     * answer is measured by length. No network; the file's own metadata is read
     * only for records that have a catalogue length to compare it with.
     */
    suspend fun scanForMvSoundSuspects(context: Context) = withContext(Dispatchers.IO) {
        open()
        val found = LinkedHashMap<String, Long>()
        Downloads.savedMetadataValue().values.groupBy { it.uri }.forEach { (_, entries) ->
            if (entries.isEmpty()) return@forEach
            val durations = entries.mapNotNull { meta ->
                meta.durationText?.durationMillis()
                    ?.takeIf { ms -> ms > 0L }
                    ?.let { meta.videoId to it }
            }
            if (durations.isEmpty()) return@forEach
            val fileUri = entries.first().uri
            if (!DownloadStore.exists(context, fileUri.toUri())) return@forEach
            // A file already recorded as a song version from its own stream is
            // the answer, even when an alias row still carries the video's row
            // type. Checking every entry, not just the shortest, keeps a
            // song/video pair with equal lengths from being re-opened on
            // whichever alias happens to come first.
            if (entries.any {
                    DownloadedMvPolicy.isRecordedSongVersion(
                        storedIsVideo = it.isVideo,
                        storedTypeUnknown = it.typeUnknown,
                        soundSourceVideoId = it.soundSourceVideoId,
                        rowVideoId = it.videoId,
                    )
                }
            ) {
                return@forEach
            }
            val (catalogId, catalogMs) = durations.minBy { it.second }
            // The shortest duration is the song side of a video/song pair. Its
            // own record is the one whose verdict describes the bytes the app
            // plays; the video alias may still carry its row's video type even
            // when the file itself is the song version.
            val catalog = entries.firstOrNull { it.videoId == catalogId } ?: entries.first()
            // The recorded lengths say this file might be the video's sound;
            // the file's own metadata is the one that says whether it is.
            // Read only for the candidates, never for every download.
            val fileMs = localDurationMs(context, fileUri)
                ?: durations.maxOf { it.second }
            // Provenance names another upload, but only a source that is itself
            // recorded as a video is the official MV. A song version fetched
            // from another id (a quality replacement) must not be repaired.
            val source = catalog.soundSourceVideoId
                ?.takeIf { it != catalog.videoId }
                ?.let { sourceId ->
                    Downloads.savedMetadataValue()[sourceId]
                        ?: entries.firstOrNull { it.videoId == sourceId }
                }
            val sourceRecordedAsMv = source != null &&
                (source.isVideo == true ||
                    (source.isVideo == null && DownloadedMvPolicy.hasMvTitleMarker(source.title)))
            val delta = DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = catalog.videoId,
                title = catalog.title,
                fileDurationMs = fileMs,
                storedIsVideo = catalog.isVideo,
                storedTypeUnknown = catalog.typeUnknown,
                soundSourceVideoId = catalog.soundSourceVideoId,
                catalogDurationMs = catalogMs,
                artist = catalog.artist,
                sourceRecordedAsMv = sourceRecordedAsMv,
            ) ?: return@forEach
            found[songIdForFile(catalogId)] = delta
        }
        var changed = false
        synchronized(suspectLock) {
            val before = _mvSoundSuspects.value
            val next = _mvSoundSuspects.updateAndGet { current ->
                val kept = current.filter { (id, _) ->
                    val meta = Downloads.savedMetadataValue()[id] ?: return@filter false
                    val uri = Downloads.savedValue()[id] ?: meta.uri
                    !meta.isCoverRecording() &&
                        !DownloadedMvPolicy.isRecordedSongVersion(
                            storedIsVideo = meta.isVideo,
                            storedTypeUnknown = meta.typeUnknown,
                            soundSourceVideoId = meta.soundSourceVideoId,
                            rowVideoId = meta.videoId,
                        ) && DownloadStore.exists(context, uri.toUri())
                }
                kept + found
            }
            changed = next != before
            if (changed && Downloads.prefsReady()) {
                Downloads.prefs.edit()
                    .putString(KEY_MV_SOUND_SUSPECTS, Downloads.json.encodeToString(suspectSerializer, next))
                    .apply()
            }
        }
        if (_mvSoundSuspects.value.isNotEmpty()) MvSoundRedownload.scheduleIfEnabled(context)
    }

    /** The file's own length from its container metadata, or null if it cannot be read. */
    fun localDurationMs(context: Context, uriString: String): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uriString.toUri())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()?.takeIf { it > 0L }

    /** [localDurationMs] on the IO dispatcher, so the queue look-ahead never blocks the main thread. */
    suspend fun localDurationMsOffMain(context: Context, uriString: String): Long? =
        withContext(Dispatchers.IO) { localDurationMs(context, uriString) }

    /** The downloaded rows still on disk that are remembered as the video's sound. */
    suspend fun suspectedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        open()
        _mvSoundSuspects.value.keys.mapNotNull { id ->
            val meta = Downloads.savedMetadataValue()[id] ?: return@mapNotNull null
            val uri = Downloads.savedValue()[id] ?: meta.uri
            if (!DownloadStore.exists(context, uri.toUri())) return@mapNotNull null
            // The verdict names a file, and the same file may be filed under
            // the video and the song. Fetch the shorter name: that is the song
            // id, whose own stream is the recording to keep.
            val target = songIdForFile(id)
            val targetMeta = Downloads.savedMetadataValue()[target] ?: meta
            Song(
                videoId = target,
                title = targetMeta.title,
                artist = targetMeta.artist,
                thumbnailUrl = targetMeta.thumbnailUrl,
                durationText = targetMeta.durationText,
                albumName = targetMeta.albumName,
                localUri = uri,
                // Restore the recorded upload type: a cover must still read as
                // the cover it is, so the repair can skip it rather than
                // replacing the listener's chosen performance.
                isVideo = targetMeta.isVideo == true,
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
     *
     * A suspect that failed recently is skipped until its cooldown passes;
     * [manual] is the banner tap, which is allowed to try again immediately.
     */
    suspend fun redownloadSuspected(context: Context, manual: Boolean = false): Int {
        if (!mvRepairAllowsMetered(manual, AppSettings.downloadsAllowedNow)) {
            Log.i(TAG, "mv-sound: waiting for an unmetered connection")
            return 0
        }
        val all = suspectedSongs(context)
        // The scan already refuses covers, but a verdict persisted by an older
        // build can still name one. Check again right before downloading: a
        // cover is the listener's chosen performance, never replaced by the
        // original recording.
        val (covers, songs) = all.partition { song ->
            DownloadedMvPolicy.isCover(song.videoId, song.title, song.artist, song.isVideo)
        }
        if (covers.isNotEmpty()) {
            open()
            clearMvSoundSuspects(
                _mvSoundSuspects.value.keys.filter { id ->
                    Downloads.savedMetadataValue()[id]?.isCoverRecording() == true
                },
            )
        }
        val now = System.currentTimeMillis()
        val retryable = songs.filter { song ->
            MvSoundRedownload.mayAttempt(context, song.videoId, now, manual)
        }
        retryable.forEach {
            Downloads.enqueue(context, it, allowMetered = manual, mvSoundRepair = true)
        }
        return retryable.size
    }

    /** Whether any suspect may be repaired now, rather than waiting out a failure. */
    fun hasRepairableMvSuspects(context: Context, nowMs: Long): Boolean {
        open()
        return _mvSoundSuspects.value.keys.any { id ->
            MvSoundRedownload.mayAttempt(context, id, nowMs, manual = false)
        }
    }

    /**
     * Whether the file [uri] is recorded as [videoId]'s own downloaded
     * recording, rather than a same-named file that merely happens to be there.
     *
     * Download adoption asks this before treating an existing file as the
     * answer: the file manager's folder is not ours, and the old bug was a
     * music video's sound being adopted under the catalogue song's id.
     */
    fun savedFileIsRecording(videoId: String, uri: String): Boolean {
        open()
        val meta = Downloads.savedMetadataValue()[videoId] ?: return false
        return !meta.wrongRecording &&
            meta.uri == uri &&
            SongVersionPolicy.mayAdoptDownloadedFile(videoId, meta.soundSourceVideoId)
    }

    /**
     * A local artwork URL that is still there.
     *
     * `content:` is assumed live — checking it needs a [Context] this record
     * does not hold — while a `file:` sidecar is a cheap `stat`. A record
     * naming a deleted sidecar is what made a re-download keep the dead path
     * instead of the remote cover it was given.
     */
    fun isUsableLocalArtwork(url: String): Boolean = when {
        url.startsWith("file:") -> runCatching {
            url.toUri().path?.let(::File)?.let { it.isFile && it.length() > 0L } == true
        }.getOrDefault(false)
        url.startsWith("content:") -> true
        else -> false
    }

    /**
     * What to write down as a track's picture after a download.
     *
     * A fresh sidecar wins. When there is none, a remote thumbnail is kept as
     * it is; a local path that no longer exists is replaced by YouTube's still
     * so the record does not keep promising a file the next reader cannot
     * open.
     */
    fun recordedThumbnail(saved: String?, song: Song, isLocalArtwork: (String) -> Boolean): String? {
        if (!saved.isNullOrBlank()) return saved
        val recorded = song.thumbnailUrl?.takeIf { it.isNotBlank() }
        if (recorded != null && (!isLocalArtwork(recorded) || isUsableLocalArtwork(recorded))) {
            return recorded
        }
        // A dead local path with no YouTube identity has no honest fallback;
        // null draws the placeholder instead of promising a missing file.
        return com.music.bitchord.data.cover.videoThumbnailUrl(song.videoId)
    }
}
