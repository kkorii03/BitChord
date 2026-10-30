package com.music.bitchord.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import androidx.annotation.RequiresApi
import com.music.bitchord.data.model.Song
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Where a downloaded track goes, and how it gets there.
 *
 * The destination is the device's own Music folder, in a `BitChord`
 * subfolder — somewhere the file manager lists, other players can open, and a
 * user can back up or delete without going through this app. That choice is
 * what makes this class necessary at all: an app-private directory would be
 * four lines of [File], but a shared one crosses the scoped-storage line and
 * the two sides of that line have nothing in common.
 *
 * It goes through the audio collection rather than Downloads because that is
 * where audio belongs and where every other player on the device looks. What
 * first ruled Downloads out was narrower and is worth keeping on the record:
 * the files were `.webm` then — a container extension Android's own mime table
 * ties to video regardless of what MIME type this class declares for it — and a
 * Gallery app crawling Downloads for video-looking files does not care what a
 * column says otherwise. Nothing writes `.webm` any more (see [storable] and
 * `StreamResolver.resolveForDownload`), so that particular trap is behind us;
 * the conclusion it led to is still the right one.
 *
 *  - **API 29+** goes through [MediaStore]. There is no filesystem path to
 *    write to; the store mints a row, hands back a content uri, and the file
 *    exists at a location it chooses. `IS_PENDING` keeps the row invisible to
 *    everything else until the bytes are all there, so a cancelled download is
 *    never a half-file somebody can find and play.
 *  - **API 26–28** is a real path and a runtime permission. The file is written
 *    beside its final name with a `.part` suffix and renamed on completion,
 *    which is the same guarantee `IS_PENDING` gives for free above, and the
 *    media scanner is told afterwards or the file stays invisible to everything
 *    that reads the index rather than the disk.
 *
 * Neither side writes tags — this class only ever copies the bytes the server
 * on the other end sent. [MediaTagger] rewrites the finished file afterwards to
 * add them; the filename below is what every downloaded track carries
 * regardless of whether that rewrite finds a layout it recognises.
 */
object DownloadStore {

    private const val TAG = "BitChord"

    /**
     * The wall clock when this process first touched the download store.
     *
     * Nothing this process writes can be older than this, so a sweep may treat
     * an older `.part`, pending row or offline package as abandoned even while
     * a download is starting beside it. Without the cutoff, the startup sweep
     * and a download started by the foreground or liked events can race, and
     * the sweep would delete the active destination.
     */
    private val processStartedAtMs = System.currentTimeMillis()

    /** The subfolder of Music that everything lands in. */
    const val FOLDER = "BitChord"

    private val relativePath = "${Environment.DIRECTORY_MUSIC}/$FOLDER"

    /**
     * Whether saving needs `WRITE_EXTERNAL_STORAGE` asked for at runtime.
     *
     * Only below API 29. From there on the app writes through the media store,
     * which grants access to rows it created and needs no permission for them —
     * and the permission it would ask for isn't grantable anyway.
     *
     * Every version check in this file is written out inline rather than
     * routed through this, deliberately: lint reads an inline `SDK_INT`
     * comparison as a guard around the API-29 calls beside it and does not
     * read a boolean property the same way, so hiding the check behind a name
     * costs a `NewApi` error on the release build.
     */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    // ---- Naming -------------------------------------------------------------

    /**
     * What the file is called: `Artist - Title.ext`.
     *
     * Artist first because a Music folder is sorted by name and nothing
     * else — no tags to group by — so leading with the artist is the only thing
     * that puts an album back together in the listing.
     */
    fun fileNameFor(song: Song, extension: String): String {
        val artist = sanitise(song.artist)
        val title = sanitise(song.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { song.videoId }
        return "${stem.take(MAX_STEM_CHARS).trimEnd()}.$extension"
    }

    /**
     * The name a download may actually use.
     *
     * `Artist - Title.ext` is only unique when two different uploads never
     * share a name. When they do, the legacy and app-private paths rename the
     * new `.part` over the old file, and the old record keeps pointing at the
     * same path — so the old song starts playing the new song's bytes. A
     * collision that is not this track's own recorded file gets the video id
     * appended instead; a deliberate replacement (or the file the record
     * already names) keeps the plain name so the old file is still replaced.
     */
    fun downloadFileName(
        song: Song,
        extension: String,
        nameTaken: Boolean,
        existingIsRecording: Boolean,
        replacing: Boolean,
    ): String = when {
        !nameTaken || existingIsRecording || replacing -> fileNameFor(song, extension)
        else -> disambiguatedFileName(song, extension)
    }

    /** `Artist - Title (videoId).ext`, for a file that would otherwise be overwritten. */
    fun disambiguatedFileName(song: Song, extension: String): String {
        val base = fileNameFor(song, extension)
        val stem = base.removeSuffix(".$extension")
        val suffix = sanitise(song.videoId).replace(WHITESPACE, "").ifEmpty { "copy" }
        return "$stem ($suffix).$extension"
    }

    /**
     * Everything a FAT32 volume, the media store or a shell would each object
     * to for its own reasons, plus the whitespace that survives them.
     */
    private fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')

    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Long enough for anything real, short of the 255-byte filename ceiling. */
    private const val MAX_STEM_CHARS = 120

    /** What a file of some codec is called and what the store is told it is. */
    class Storable(val extension: String, val mimeType: String)

    /**
     * How to file a track of [codec], or null if this device won't have it.
     *
     * A source that can serve lossless does not thereby serve something Android
     * will keep: the media store's audio collection accepts a closed list of
     * MIME types, and one it doesn't recognise is refused outright at [begin] —
     * which is a download that cannot start rather than one that sounds worse
     * than hoped. Anything not answered for here falls the caller back to
     * YouTube's AAC, so an unfamiliar codec costs quality and not the download.
     *
     * Kept as a table rather than derived from the codec string because two of
     * these are not the identity mapping they look like. WAV's registered type
     * is `audio/x-wav` on Android, and ALAC ships inside an MP4 container, so an
     * ALAC file is an `.m4a` as far as both the store and [Mp4Tagger] are
     * concerned — the tagger works on the box tree and never asks what the
     * samples inside are.
     */
    fun storable(codec: String?): Storable? = when (codec?.lowercase(Locale.ROOT)?.trim()) {
        "flac", "x-flac" -> Storable("flac", "audio/flac")
        "wav", "x-wav", "wave" -> Storable("wav", "audio/x-wav")
        "alac", "m4a", "mp4", "eac3-joc", "ec3-joc", "dolby-atmos" -> Storable("m4a", "audio/mp4")
        else -> null
    }

    // ---- Lookup -------------------------------------------------------------

    /**
     * The uri of a file already saved under this name, or null.
     *
     * Worth asking before every download because the media store does not
     * refuse a duplicate — it silently renames it to `… (1)`, and a user who
     * taps download twice gets two copies rather than being told they already
     * have one.
     */
    fun existing(context: Context, name: String): Uri? =
        if (!AppSettings.exportDownloads.value) {
            privateFile(context, name).takeIf { it.exists() }?.let(Uri::fromFile)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreEntry(context, name)
        } else {
            legacyFile(name).takeIf { it.exists() }?.let(Uri::fromFile)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreEntry(context: Context, name: String): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "%$FOLDER%"),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(cursor.getLong(0).toString())
                .build()
        }
    }.onFailure { Log.w(TAG, "media store lookup failed for $name: ${it.message}") }.getOrNull()

    /**
     * Whether [uri] still names a file that is there.
     *
     * The record of what has been downloaded is kept by this app, but the files
     * are not this app's to keep: they sit in a folder built for the user to
     * manage, and one deleted from a file manager leaves the record behind
     * claiming a download that no longer exists. Cheap to ask, and the answer
     * is what stops the menu offering to delete nothing.
     */
    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    /**
     * Removes half-written destinations left by a process death.
     *
     * [Pending] keeps an unfinished file out of sight — an `IS_PENDING` row on
     * API 29+, a `.part` beside the final name below it — but nothing owns it
     * once the process that was writing it is gone. The bytes stay on disk and,
     * on the media-store path, the row stays invisible forever; a few
     * interrupted replacements can quietly consume the space the next download
     * needs. Called once from [Downloads.init].
     *
     * Only entries older than [processStartedAtMs] are touched: the startup
     * sweep runs off the main thread and a download started by the foreground
     * or liked event may already be writing. An offline package is kept when
     * [keepOfflinePackages] names its directory, so a finished package is not
     * mistaken for an abandoned one before its record is written. A null
     * [keepOfflinePackages] means the records could not be read, so no offline
     * package is swept.
     */
    fun cleanupAbandoned(
        context: Context,
        keepOfflinePackages: Set<String>? = null,
    ) {
        deleteAbandonedParts(File(context.filesDir, "downloads"), processStartedAtMs)
        deleteAbandonedParts(legacyFolder(), processStartedAtMs)
        if (keepOfflinePackages != null) {
            OfflinePackage.sweepUnreferenced(
                parent = File(context.filesDir, "offline-hls"),
                referencedRoots = keepOfflinePackages,
                olderThanMs = processStartedAtMs,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) clearPendingRows(context)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun clearPendingRows(context: Context) {
        runCatching {
            context.contentResolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
                    "${MediaStore.MediaColumns.DATE_ADDED} < ?",
                arrayOf("%$FOLDER%", (processStartedAtMs / 1_000).toString()),
            )
        }.onFailure {
            Log.w(TAG, "could not clear abandoned pending downloads: ${it.message}")
        }
    }

    /** Deletes the `.part` files this app leaves in [folder]; returns how many went. */
    internal fun deleteAbandonedParts(
        folder: File,
        olderThanMs: Long = Long.MAX_VALUE,
    ): Int {
        val files = folder.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (file.isFile && file.name.endsWith(".part") &&
                file.lastModified() < olderThanMs && file.delete()
            ) {
                removed++
            }
        }
        return removed
    }

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                if (file.name == "playlist.m3u8" && file.parentFile?.parentFile?.name == "offline-hls") {
                    file.parentFile?.deleteRecursively() == true
                } else file.delete()
            } == true
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    }.onFailure { Log.w(TAG, "could not delete $uri: ${it.message}") }.getOrDefault(false)

    // ---- Writing ------------------------------------------------------------

    /**
     * A destination that exists but is not yet a file anyone else can see.
     *
     * Every path out of here is either [commit] or [abort]; there is no third
     * option, because the thing being protected against is a partial file
     * surviving a failure and looking like a whole one.
     */
    class Pending internal constructor(
        private val context: Context,
        val uri: Uri,
        val name: String,
        /** Set on the legacy path only: the `.part` file being written. */
        private val part: File?,
        /** Set on the legacy path only: what [part] is renamed to. */
        private val target: File?,
    ) {
        /** The bytes being built, whether this is a MediaStore row or a legacy `.part` file. */
        val tagUri: Uri get() = part?.let(Uri::fromFile) ?: uri

        fun openStream(): OutputStream =
            part?.outputStream()
                ?: context.contentResolver.openOutputStream(uri)
                ?: error("Could not open $name for writing")

        /** @return the uri the finished file can be reached at. */
        fun commit(): Uri {
            if (part != null && target != null) {
                if (!part.renameTo(target)) error("Could not finish writing $name")
                // Nothing indexes a file that simply appeared; without this it
                // is on disk and invisible to every app that lists media.
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(target.absolutePath),
                    null,
                    null,
                )
                return Uri.fromFile(target)
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        }

        fun abort() {
            part?.delete()
            if (part == null) runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    /**
     * Reserve [name] and return somewhere to write it.
     *
     * @throws IllegalStateException if the folder or the store row can't be
     *   made — a failure worth surfacing, since every one of them means the
     *   download cannot start rather than that it might not finish.
     */
    fun begin(context: Context, name: String, mimeType: String): Pending {
        if (!AppSettings.exportDownloads.value) {
            val target = privateFile(context, name)
            val folder = target.parentFile ?: error("다운로드 폴더 없음")
            if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
            val part = File(folder, ".$name.part")
            part.delete()
            return Pending(context, Uri.fromFile(target), name, part = part, target = target)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            // A MIME type the audio collection doesn't recognise is not a null
            // return but an IllegalArgumentException thrown from inside the
            // resolver, several frames away from anything that names the
            // download it belongs to. Every type written here came from
            // [storable] or from the stream resolver, so landing in this branch
            // means one of those two is wrong about this device — worth saying
            // in those words the first time it happens again.
            val uri = runCatching {
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrElse { cause ->
                Log.w(TAG, "the media store refused $mimeType for $name: ${cause.message}")
                error("Android won't store ${name.substringAfterLast('.', mimeType)} files in Music")
            } ?: error("Could not create $name in Music")
            return Pending(context, uri, name, part = null, target = null)
        }

        val target = legacyFile(name)
        val folder = target.parentFile ?: error("이 기기에 음악 폴더가 없습니다")
        if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
        val part = File(folder, "$name.part")
        part.delete()
        return Pending(context, Uri.fromFile(target), name, part = part, target = target)
    }

    @Suppress("DEPRECATION")
    private fun legacyFolder() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        FOLDER,
    )

    @Suppress("DEPRECATION")
    private fun legacyFile(name: String) = File(legacyFolder(), name)

    private fun privateFile(context: Context, name: String) =
        File(File(context.filesDir, "downloads"), name)
}
