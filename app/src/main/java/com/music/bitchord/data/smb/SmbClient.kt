package com.music.bitchord.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.share.DiskShare
import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.EnumSet

/**
 * Listing (and only listing) over [SmbConnection]: the recursive walk that
 * turns a share into audio rows plus the pictures filed beside them.
 *
 * Server paths are backslash-joined — the wire's own separator. Anything with
 * a forward slash in it is a client-side display path and never goes out.
 */
object SmbClient {

    private const val TAG = "BitChord"

    data class Entry(
        /** Display path with forward slashes, relative to the share. */
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    data class Listing(val audio: List<Entry>, val images: List<Entry>)

    suspend fun testConnection(
        host: String,
        share: String,
        basePath: String,
        username: String,
        password: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(SmbConfig.isConfigured(host, share)) { "서버와 공유 이름을 입력하십시오" }
            SmbConnection.withTemporaryShare(host, share, username, password) { diskShare ->
                diskShare.list(smbPath(basePath))
            }
        }.onSuccess {
            SmbAuth.update(host, share, basePath, username, password)
        }.map { }
    }

    suspend fun listLibrary(maxFiles: Int = 10_000): Result<Listing> = withContext(Dispatchers.IO) {
        runCatching {
            require(SmbAuth.isConfigured()) { "SMB가 설정되지 않았습니다" }
            SmbConnection.withShare { diskShare ->
                walkLibrary(
                    root = SmbAuth.basePath,
                    maxFiles = maxFiles,
                    listDirectory = { dir -> listDir(diskShare, dir) },
                    logSkip = { dir, failure ->
                        Log.w(TAG, "Skipping SMB folder $dir: ${failure.message}")
                    },
                )
            }
        }
    }

    /**
     * The recursive walk over one share. Kept separate from [listLibrary] so
     * the failure policy can be exercised without a server.
     *
     * A server status on one folder (access denied, gone) skips that folder
     * and keeps the rest of the library. A transport failure is not a folder
     * to skip: it means the session is dead, so it is rethrown for
     * [SmbConnection.withShare] to invalidate and retry. Swallowing it here
     * returned a partial library and left every later folder on a dead
     * session until its own 30s timeout.
     *
     * smbj wraps the transport [IOException] in [SMBRuntimeException] on the
     * way out of `Share.receive`, so the old `catch (IOException)` never
     * fired for a dropped wire. [isTransportFailure] unwraps that, while
     * [SMBApiException] (a status answer) still counts as a folder to skip.
     *
     * The lists live inside this call, so the retry after a transport failure
     * starts a fresh walk instead of appending a second copy of the entries
     * it already found.
     */
    internal fun walkLibrary(
        root: String,
        maxFiles: Int,
        listDirectory: (String) -> List<Entry>,
        logSkip: (String, Exception) -> Unit = { _, _ -> },
    ): Listing {
        val audio = ArrayList<Entry>()
        val images = ArrayList<Entry>()
        val stack = ArrayDeque<String>()
        stack.add(root)
        val visited = HashSet<String>()
        while (stack.isNotEmpty() && audio.size < maxFiles) {
            val dir = stack.removeFirst()
            if (!visited.add(dir.lowercase())) continue
            val children = try {
                listDirectory(dir)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isTransportFailure(failure)) throw failure
                // One unreadable folder must not hide the rest of the
                // share. The root is different: a refused root is a
                // refused library, not a folder to skip.
                if (dir == root) throw failure
                logSkip(dir, failure)
                continue
            }
            for (child in children) {
                val childPath = if (dir.isBlank()) child.path else "$dir/${child.path}"
                if (child.isDirectory) {
                    if (!visited.contains(childPath.lowercase())) stack.add(childPath)
                } else {
                    val name = child.path.substringAfterLast('\\').substringAfterLast('/')
                    when {
                        SmbConfig.isAudioFile(name) ->
                            if (audio.size < maxFiles) audio.add(Entry(childPath, false, child.size))
                        SmbConfig.isImageFile(name) ->
                            if (images.size < maxFiles) images.add(Entry(childPath, false, child.size))
                    }
                }
            }
        }
        return Listing(audio, images)
    }

    /**
     * True when [failure] is a dropped wire rather than the server's answer
     * about one folder. smbj raises [SMBRuntimeException] around
     * [TransportException] from its receive path; [SMBApiException] is a
     * status answer and stays a folder-skip even though it shares that base
     * class. Walks the cause chain because the wrapper can be nested.
     */
    internal fun isTransportFailure(failure: Throwable): Boolean {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < 8) {
            if (current is SMBApiException) return false
            if (current is TransportException || current is IOException) return true
            val cause = current.cause
            if (cause === current) return false
            current = cause
            depth++
        }
        return false
    }

    /**
     * Opens [path] (display form) for reading. The handle is the caller's to
     * close — one per player open, so seeks never share a cursor.
     */
    fun openFile(path: String): com.hierynomus.smbj.share.File =
        SmbConnection.withShare { diskShare ->
            diskShare.openFile(
                smbPath(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        }

    /**
     * Reads a whole small file — a cover, never audio. Capped so a
     * mislabeled video file can't balloon the heap on a list scroll.
     */
    fun readFully(streamUrl: String, maxBytes: Long = 16 * 1024 * 1024): ByteArray {
        val path = SmbConfig.relativePath(streamUrl, SmbAuth.share)
            ?: throw SmbConnection.SmbException("Not an SMB file: $streamUrl")
        val file = openFile(path)
        try {
            val length = runCatching { file.length }.getOrNull() ?: -1
            val out = java.io.ByteArrayOutputStream(
                length.takeIf { it > 0 }?.coerceAtMost(maxBytes)?.toInt() ?: 8192,
            )
            val chunk = ByteArray(64 * 1024)
            var offset = 0L
            // Bounded by the known length: some servers answer a read past
            // EOF with bytes no parser accepts instead of an empty one, so
            // the end of the file is taken from the entry, never probed for.
            while (length < 0 || offset < length) {
                val read = file.read(chunk, offset, 0, chunk.size)
                if (read <= 0) break
                offset += read
                if (offset > maxBytes) throw SmbConnection.SmbException("아트워크로 사용하기에는 파일이 너무 큽니다")
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        } finally {
            runCatching { file.close() }
        }
    }

    /** Display path back to the wire form. */
    fun smbPath(displayPath: String): String =
        displayPath.replace('/', '\\').trim('\\').ifBlank { "" }

    private fun listDir(diskShare: DiskShare, dir: String): List<Entry> {
        return diskShare.list(smbPath(dir)).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            Entry(name, isDir, info.endOfFile)
        }
    }
}
