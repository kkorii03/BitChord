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
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.spotify.PlaylistImportMode
import com.music.bitchord.data.taste.TasteSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.YearMonth
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

    /**
     * The largest backup this build will read into memory.
     *
     * A backup is text the user hands us through a document picker, so its size
     * is not ours to trust. Listening buckets and local playlists make a real
     * one larger than a settings transfer, but a year of listening plus a few
     * thousand playlist rows is still a few megabytes; 32 MiB leaves room for
     * that while refusing a file that would exhaust the process before the
     * schema check can run.
     */
    internal const val MAX_IMPORT_BYTES = 32 * 1024 * 1024

    /**
     * Reads at most [MAX_IMPORT_BYTES] of UTF-8 text, refusing the document
     * rather than growing the buffer without bound.
     */
    internal fun InputStream.readTextBounded(maxBytes: Int = MAX_IMPORT_BYTES): String {
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
            val ratings = mergeRatedIds(
                session = LikeState.overrides.value,
                persistedLiked = TasteSignals.ratedVideoIds(LikeStatus.LIKE),
                persistedDisliked = TasteSignals.ratedVideoIds(LikeStatus.DISLIKE),
            )
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
                likedVideoIds = ratings.first,
                dislikedVideoIds = ratings.second,
                searchHistory = SearchHistory.export(),
            )
            val text = encode(file)
            context.contentResolver.openOutputStream(target, "wt")
                ?.use { it.write(text.toByteArray()) }
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
                ?.use { it.readTextBounded() }
                ?: error("파일을 열 수 없습니다")
            importText(text, playlistMode)
        }
    }

    /**
     * The body of [importFrom], after the document has been read.
     *
     * Split out so the rollback path can be exercised with the same stores an
     * import uses, without a real document picker. It is still the only place
     * that applies a backup.
     */
    internal suspend fun importText(
        text: String,
        playlistMode: PlaylistImportMode = PlaylistImportMode.REPLACE,
    ): Summary {
        val file = decode(text)
        validate(file)
        val playlists = if (file.version >= LIBRARY_SCHEMA_VERSION) {
            file.localPlaylists.map { it.toLocalPlaylist() }
        } else {
            null
        }
        val settings = file.settings.mapValues { it.value.decoded() }
        val library = file.version >= LIBRARY_SCHEMA_VERSION

        // Everything below is applied in a rollback-able order. The listening
        // folder goes last because it is the only store whose commit can still
        // refuse after validation; if it does, the cheaper sections are put
        // back from these snapshots instead of leaving the device holding half
        // a restore.
        val previousSettings = AppSettings.exportPrefs()
        val previousPlaylists = LocalPlaylistStore.exportAll()
        val previousSearch = SearchHistory.export()
        val previousLiked = TasteSignals.ratedVideoIds(LikeStatus.LIKE)
        val previousDisliked = TasteSignals.ratedVideoIds(LikeStatus.DISLIKE)
        val previousOverrides = LikeState.overrides.value

        suspend fun rollback() {
            runCatching { AppSettings.importPrefs(previousSettings) }
            runCatching { SearchHistory.reload() }
            if (library) {
                runCatching {
                    LocalPlaylistStore.importAll(previousPlaylists, PlaylistImportMode.REPLACE)
                }
                runCatching { SearchHistory.replace(previousSearch) }
                runCatching { TasteSignals.restoreRatings(previousLiked, previousDisliked) }
                runCatching { TasteSignals.flushAndAwait() }
            }
            LikeState.clear()
            previousOverrides.forEach { (id, status) -> LikeState.set(id, status) }
        }

        try {
            check(AppSettings.importPrefs(settings)) {
                "설정을 저장하지 못했습니다"
            }
            // Shares AppSettings' preference file, so it has already been
            // overwritten by the line above — it just doesn't know yet.
            SearchHistory.reload()
            if (library) {
                val restored = playlists?.let {
                    LocalPlaylistStore.importAll(it, playlistMode)
                } ?: 0
                check(restored >= 0) { "로컬 재생목록을 저장하지 못했습니다" }
                // Explicit rather than relying on the settings map: schema 2
                // states the list itself, and an empty list is a real answer.
                check(SearchHistory.replace(file.searchHistory)) {
                    "검색 기록을 저장하지 못했습니다"
                }
                // A restore is a replacement, not a merge: the session copy
                // from before the import would otherwise keep shadowing the
                // restored ratings in every screen that reads LikeState.
                LikeState.clear()
                // The persisted rating store is what survives a restart; the
                // optimistic session copy is set as well so the player and
                // library repaint without waiting for a reload.
                TasteSignals.restoreRatings(file.likedVideoIds, file.dislikedVideoIds)
                check(TasteSignals.flushAndAwait()) {
                    "평점을 저장하지 못했습니다"
                }
                file.likedVideoIds.forEach { LikeState.set(it, LikeStatus.LIKE) }
                file.dislikedVideoIds.forEach { LikeState.set(it, LikeStatus.DISLIKE) }
            }

            // The listening folder is the last write by design. Its own import
            // is journaled and restores the previous months if a rename fails;
            // this check only has to roll back the sections above when it
            // returns false.
            check(ListeningStats.importAll(file.listening)) {
                "청취 기록을 저장하지 못했습니다"
            }
        } catch (failure: Throwable) {
            rollback()
            throw failure
        }
        return Summary(
            months = file.listening.size,
            settings = file.settings.size,
            from = file.versionName,
            at = file.exportedAt,
            playlists = playlists?.size ?: 0,
            liked = if (library) file.likedVideoIds.size else 0,
            searchHistory = if (library) file.searchHistory.size else 0,
        )
    }

    internal fun encode(file: BackupFile): String =
        json.encodeToString(BackupFile.serializer(), file)

    internal fun decode(text: String): BackupFile =
        runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { error("BitChord 백업 파일이 아닙니다") }

    /**
     * Combines the ratings kept on disk with the optimistic ones made since
     * this process started.
     *
     * Both lists are sorted and an id appears on exactly one side. A session
     * tap is the newer answer, so it moves an id between lists; the persisted
     * lists alone are already disjoint because a track stores one rating.
     */
    internal fun mergeRatedIds(
        session: Map<String, LikeStatus>,
        persistedLiked: Collection<String>,
        persistedDisliked: Collection<String>,
    ): Pair<List<String>, List<String>> {
        val liked = persistedLiked.toMutableSet()
        val disliked = persistedDisliked.toMutableSet()
        session.forEach { (id, status) ->
            if (id.isBlank()) return@forEach
            when (status) {
                LikeStatus.LIKE -> {
                    disliked.remove(id)
                    liked.add(id)
                }
                LikeStatus.DISLIKE -> {
                    liked.remove(id)
                    disliked.add(id)
                }
                else -> Unit
            }
        }
        return liked.sorted() to disliked.sorted()
    }

    /** Everything that can be checked before the first write. */
    internal fun validate(file: BackupFile) {
        require(file.app == APP_TAG) { "다른 앱의 백업입니다" }
        // A file with no version is the oldest shipped shape (see
        // [BackupFile.version]); an explicit 0 or negative one never shipped
        // and is not something this build can claim to understand.
        require(file.version >= MIN_SCHEMA_VERSION) {
            "이 백업 파일의 버전을 읽을 수 없습니다"
        }
        require(file.version <= SCHEMA_VERSION) {
            "더 최신 버전의 BitChord에서 만든 백업입니다"
        }
        // The month is the file's identity on disk. [ListeningStats.importAll]
        // deletes the old buckets before writing the new ones, so one bad month
        // has to be refused here rather than silently skipped after that
        // deletion. Two buckets for one month would likewise overwrite each
        // other and lose half the restored history.
        val months = HashSet<String>(file.listening.size)
        file.listening.forEach { bucket ->
            val month = runCatching { YearMonth.parse(bucket.month).toString() }.getOrNull()
            require(month != null) { "백업의 청취 기록에 잘못된 달이 있습니다" }
            require(months.add(month)) { "백업의 청취 기록에 같은 달이 두 번 있습니다" }
        }
        if (file.version >= LIBRARY_SCHEMA_VERSION) {
            file.localPlaylists.forEach { playlist ->
                require(playlist.id.isNotBlank()) { "백업의 로컬 재생목록에 id가 없습니다" }
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

    /** The oldest shape that ever shipped; 0 and below are not ours. */
    private const val MIN_SCHEMA_VERSION = 1

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
