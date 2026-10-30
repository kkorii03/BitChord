package com.music.bitchord.data.stats

import android.content.Context
import android.net.Uri
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.withVideoThumbnailFallback
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.settings.SettingsTransfer
import com.music.bitchord.data.taste.TasteSignals
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.spotify.PlaylistImportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The whole of what this app knows about you, as one JSON file you own.
 *
 * Two errands, and they are the same errand: moving to a new phone, and being
 * able to see what is being kept. Everything Replay counts lives only on this
 * device, which is the point of it — and the flip side of that is that a factory
 * reset takes it with no way back, so there has to be a way out.
 *
 * ## What is in it
 *
 * Every preference except credentials (see [AppSettings.exportPrefs]), every
 * month of listening, the local playlists this device made, the liked and
 * disliked rows it knows, and its search history. Not the audio cache, the
 * downloads or the artwork: those are megabytes that re-fetch themselves, and a
 * backup that is mostly cache is one nobody keeps.
 *
 * ## Typed values rather than a JSON object
 *
 * Preferences come off Android as `Map<String, Any?>` and go back the same way,
 * where the *type* decides which `put` is called. Written as plain JSON, `0.5`
 * comes back a Double and lands in a Float preference as a class-cast crash the
 * first time it is read — weeks later, in a settings screen, with nothing
 * pointing at the import. So each value carries its type and is parsed back into
 * exactly the type it left as, and anything unrecognised is skipped rather than
 * guessed at.
 */
object Backup {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** A suggested filename, dated so successive exports don't collide. */
    fun suggestedName(): String =
        "bitchord-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
            Instant.now().atZone(ZoneId.systemDefault()),
        )}.json"

    /**
     * Writes a backup to [target], a document the user picked.
     *
     * Through the content resolver rather than a [java.io.File] because the
     * destination is wherever they chose — Drive, a USB stick, a folder this app
     * has no path to and no permission for. The picker grants access to that one
     * document and nothing else, which is the correct amount.
     */
    suspend fun exportTo(context: Context, target: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val buckets = ListeningStats.exportAll()
            val overrides = LikeState.overrides.value
            // TasteSignals holds ratings already written to disk on an earlier
            // run; session overrides cover one made since launch and win over
            // an older stored value. Union them so an export after a restart
            // still carries the listener's likes instead of only this process.
            val ratings = TasteSignals.ratings() + overrides
            val file = BackupFile(
                app = APP_TAG,
                version = SCHEMA_VERSION,
                versionName = BuildConfig.VERSION_NAME,
                exportedAt = Instant.now().toString(),
                settings = AppSettings.exportPrefs()
                    // The list has its own section below; keeping the raw pref
                    // in here too would let two copies disagree after an edit.
                    .filterKeys { it != SearchHistory.KEY_HISTORY }
                    .mapNotNull { (key, value) ->
                        PrefValue.of(value)?.let { key to it }
                    }.toMap(),
                listening = buckets,
                localPlaylists = LocalPlaylistStore.exportAll().map { BackupPlaylist.from(it) },
                likedVideoIds = ratings.filterValues { it == LikeStatus.LIKE }.keys.sorted(),
                dislikedVideoIds = ratings.filterValues { it == LikeStatus.DISLIKE }.keys.sorted(),
                searchHistory = SearchHistory.export(),
            )
            val bytes = encodeBackupBytes(file)
            context.contentResolver.openOutputStream(target, "wt")
                ?.use { it.write(bytes) }
                ?: error("Couldn't open that file for writing")
            buckets.size
        }
    }

    /**
     * Reads [source] and replaces this device's settings, listening, search
     * history and liked rows with it; [playlistMode] decides whether its local
     * playlists replace this device's or are merged into them.
     *
     * Validated before anything is written: a file that isn't one of ours, is
     * from a schema this build can't read, or carries a malformed playlist row
     * is refused whole. A half-applied import is worse than a refused one — it
     * leaves settings from two devices mixed together with nothing to say which
     * came from where.
     *
     * Schema 1 files predate the library sections. They still restore settings,
     * listening and search history (which schema 1 carried in `settings`), and
     * leave this device's local playlists, likes and dislikes untouched.
     */
    suspend fun importFrom(
        context: Context,
        source: Uri,
        playlistMode: PlaylistImportMode = PlaylistImportMode.REPLACE,
    ): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)
                ?.use { readBackupText(it) }
                ?: error("파일을 열 수 없습니다")
            val file = decode(text)
            validate(file)
            val playlists = if (file.version >= LIBRARY_SCHEMA_VERSION) {
                file.localPlaylists.map { it.toLocalPlaylist() }
            } else {
                null
            }

            ListeningStats.importAll(file.listening)
            AppSettings.importPrefs(file.settings.mapValues { it.value.decoded() })
            // Shares AppSettings' preference file, so it has already been
            // overwritten by the line above — it just doesn't know yet.
            SearchHistory.reload()
            if (file.version >= LIBRARY_SCHEMA_VERSION) {
                playlists?.let { LocalPlaylistStore.importAll(it, playlistMode) }
                // Explicit rather than relying on the settings map: schema 2
                // states the list itself, and an empty list is a real answer.
                SearchHistory.replace(file.searchHistory)
                val restoredRatings = buildMap {
                    file.likedVideoIds.forEach { put(it, LikeStatus.LIKE) }
                    file.dislikedVideoIds.forEach { put(it, LikeStatus.DISLIKE) }
                }
                restoredRatings.forEach { (videoId, status) -> LikeState.set(videoId, status) }
                // LikeState makes the restore visible now; TasteSignals is what
                // survives the next launch, which the session map alone did not.
                TasteSignals.restoreRatings(restoredRatings)
                TasteSignals.flush()
            }
            Summary(
                months = file.listening.size,
                settings = file.settings.size,
                from = file.versionName,
                at = file.exportedAt,
                playlists = playlists?.size ?: 0,
                liked = if (file.version >= LIBRARY_SCHEMA_VERSION) file.likedVideoIds.size else 0,
                searchHistory = if (file.version >= LIBRARY_SCHEMA_VERSION) {
                    file.searchHistory.size
                } else {
                    0
                },
            )
        }
    }

    internal fun encode(file: BackupFile): String =
        json.encodeToString(BackupFile.serializer(), file)

    /**
     * Encodes an export and refuses it up front if it would exceed the same
     * [MAX_BACKUP_BYTES] ceiling [readBackupText] applies on import. Without
     * this check the app could write a backup it would later refuse to restore,
     * which is the one thing an export must never do.
     */
    internal fun encodeBackupBytes(
        file: BackupFile,
        maxBytes: Int = MAX_BACKUP_BYTES,
    ): ByteArray {
        val bytes = encode(file).toByteArray()
        require(bytes.size <= maxBytes) { "백업 파일이 너무 큽니다" }
        return bytes
    }

    internal fun decode(text: String): BackupFile =
        runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { error("BitChord 백업 파일이 아닙니다") }

    /**
     * Reads an imported document up to [maxBytes], refusing it rather than
     * growing the buffer without bound. A backup is much larger than a settings
     * share — months of listening and every local playlist ride in it — so the
     * ceiling is generous, but it still keeps a wrongly shared multi-gigabyte
     * file from being read into memory just to discover it is not ours.
     */
    internal fun readBackupText(input: InputStream, maxBytes: Int = MAX_BACKUP_BYTES): String =
        with(SettingsTransfer) {
            input.readTextBounded(maxBytes, tooLargeMessage = "백업 파일이 너무 큽니다")
        }

    /** Everything that can be checked before the first write. */
    internal fun validate(file: BackupFile) {
        require(file.app == APP_TAG) { "다른 앱의 백업입니다" }
        require(file.version <= SCHEMA_VERSION) {
            "더 최신 버전의 BitChord에서 만든 백업입니다"
        }
        if (file.version >= LIBRARY_SCHEMA_VERSION) {
            file.localPlaylists.forEach { playlist ->
                require(playlist.id.isNotBlank()) { "백업의 로컬 플레이리스트에 id가 없습니다" }
                playlist.songs.forEach { song ->
                    require(song.videoId.isNotBlank()) { "백업의 곡에 videoId가 없습니다" }
                }
            }
        }
    }

    /** What an import turned out to contain, for the line shown afterwards. */
    data class Summary(
        val months: Int,
        val settings: Int,
        val from: String,
        val at: String,
        val playlists: Int = 0,
        val liked: Int = 0,
        val searchHistory: Int = 0,
    )

    private const val APP_TAG = "bitchord"

    /** A backup carries months of listening, so it needs more room than a settings share. */
    private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

    /**
     * A backup is text this app wrote, but the file it reads is one the user
     * picked from anywhere. Reading it whole would let a wrong pick or a
     * hostile document exhaust the heap before the schema check can refuse it;
     * the largest real backup — years of months plus playlists — stays far
     * below this.
     */
    internal const val MAX_IMPORT_BYTES = 32 * 1024 * 1024

    /** Reads at most [maxBytes] of UTF-8 text, refusing rather than growing without bound. */
    @JvmName("readBackupTextFromStream")
    internal fun InputStream.readBackupText(maxBytes: Int = MAX_IMPORT_BYTES): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "백업 파일이 너무 큽니다" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().decodeToString()
    }

    /**
     * Bump when the shape below stops being readable by an older build. A file
     * from a *newer* schema is refused rather than partially read; one from an
     * older schema is read as-is, since every field has a default.
     *
     * 2 added local playlists, liked/disliked rows and an explicit search
     * history list. A 1 file has none of those keys, so they arrive as empty
     * lists and [importFrom] deliberately leaves the device's own library
     * alone for it.
     */
    private const val SCHEMA_VERSION = 2

    /** The first schema that carries the library sections. */
    private const val LIBRARY_SCHEMA_VERSION = 2

    @Serializable
    data class BackupFile(
        /**
         * No default tag: a document that never names this app is not one of
         * ours, and [validate] has to be able to tell the difference.
         */
        val app: String = "",
        /**
         * 1 is the oldest shape that ever shipped, so a file missing the key
         * is treated as the cautious case rather than as a current one. Export
         * writes [SCHEMA_VERSION] explicitly.
         */
        val version: Int = 1,
        /** Which build wrote it — for the reader, not for any logic here. */
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, PrefValue> = emptyMap(),
        val listening: List<StoredBucket> = emptyList(),
        /** Local playlists, with their rows, in the order Library shows them. */
        val localPlaylists: List<BackupPlaylist> = emptyList(),
        /** Video ids this device is holding as liked, independent of the account. */
        val likedVideoIds: List<String> = emptyList(),
        /** Video ids this device is holding as disliked. */
        val dislikedVideoIds: List<String> = emptyList(),
        /** Newest first, the same order the search screen shows. */
        val searchHistory: List<SearchHistoryEntity> = emptyList(),
    )

    /** One local playlist, serializable independently of the on-disk format. */
    @Serializable
    data class BackupPlaylist(
        val id: String,
        val title: String,
        val songs: List<BackupSong> = emptyList(),
    ) {
        fun toLocalPlaylist(): LocalPlaylist = LocalPlaylist(
            id = id,
            title = title,
            songs = songs.map { it.toSong() },
        )

        companion object {
            fun from(playlist: LocalPlaylist): BackupPlaylist = BackupPlaylist(
                id = playlist.id,
                title = playlist.title,
                songs = playlist.songs.map { BackupSong.from(it) },
            )
        }
    }

    /**
     * A playlist row's song fields.
     *
     * Wider than the on-disk playlist JSON because a backup may be the only
     * copy of a row: an album name, a local file marker or a source quality
     * cannot be re-fetched if it is dropped here. The fields that describe a
     * queue position rather than a library row are intentionally absent.
     */
    @Serializable
    data class BackupSong(
        val videoId: String,
        val title: String = "",
        val artist: String = "",
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val artistId: String? = null,
        val channelId: String? = null,
        val albumId: String? = null,
        val albumName: String? = null,
        val isVideo: Boolean = false,
        val isVideoOrigin: Boolean = false,
        val setVideoId: String? = null,
        val localUri: String? = null,
        val localPath: String? = null,
        val downloadFormat: String? = null,
        val sourceQuality: String? = null,
        val isExplicit: Boolean? = null,
    ) {
        fun toSong(): Song = Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationText = durationText,
            artistId = artistId,
            channelId = channelId,
            albumId = albumId,
            albumName = albumName,
            isVideo = isVideo,
            isVideoOrigin = isVideoOrigin || isVideo,
            setVideoId = setVideoId,
            localUri = localUri,
            localPath = localPath,
            downloadFormat = downloadFormat,
            sourceQuality = sourceQuality,
            isExplicit = isExplicit,
        ).withVideoThumbnailFallback()

        companion object {
            fun from(song: Song): BackupSong = BackupSong(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationText = song.durationText,
                artistId = song.artistId,
                channelId = song.channelId,
                albumId = song.albumId,
                albumName = song.albumName,
                isVideo = song.isVideo,
                isVideoOrigin = song.isVideoOrigin,
                setVideoId = song.setVideoId,
                localUri = song.localUri,
                localPath = song.localPath,
                downloadFormat = song.downloadFormat,
                sourceQuality = song.sourceQuality,
                isExplicit = song.isExplicit,
            )
        }
    }

    /** One preference, with the type it has to be restored as. */
    @Serializable
    data class PrefValue(
        val type: String,
        val value: String? = null,
        val values: List<String> = emptyList(),
    ) {
        fun decoded(): Any? = when (type) {
            BOOLEAN -> value?.toBooleanStrictOrNull()
            INT -> value?.toIntOrNull()
            LONG -> value?.toLongOrNull()
            FLOAT -> value?.toFloatOrNull()
            STRING -> value
            STRING_SET -> values.toSet()
            else -> null
        }

        companion object {
            /**
             * Numbers go out as strings on purpose. JSON has one number type and
             * every parser picks its own Kotlin type back off it, which is
             * exactly the ambiguity this whole class exists to remove.
             */
            fun of(value: Any?): PrefValue? = when (value) {
                is Boolean -> PrefValue(BOOLEAN, value.toString())
                is Int -> PrefValue(INT, value.toString())
                is Long -> PrefValue(LONG, value.toString())
                is Float -> PrefValue(FLOAT, value.toString())
                is String -> PrefValue(STRING, value)
                is Set<*> -> PrefValue(STRING_SET, values = value.filterIsInstance<String>())
                else -> null
            }

            private const val BOOLEAN = "bool"
            private const val INT = "int"
            private const val LONG = "long"
            private const val FLOAT = "float"
            private const val STRING = "string"
            private const val STRING_SET = "stringSet"
        }
    }
}
