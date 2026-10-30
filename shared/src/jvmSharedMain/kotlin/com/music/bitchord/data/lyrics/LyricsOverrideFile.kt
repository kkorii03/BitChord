package com.music.bitchord.data.lyrics

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The per-song lyrics overrides kept beside the app's other files.
 *
 * A downloaded track carries the lyrics it was saved with, but a later
 * re-check can find a better sheet. Rewriting the audio container to hold it
 * means reading the whole file into memory (a lossless track can be over
 * 100MB) and replacing the file while the player is reading it, which is both
 * an out-of-memory risk and a way to corrupt the one thing playing. So the
 * replacement lives here instead: a small JSON file keyed by video id, read
 * before the file's own tags and before any provider lookup.
 *
 * The lines are stored structurally rather than as LRC text so the exact line
 * list — and therefore the text key the re-check saved its sync under — comes
 * back unchanged. [Entry.source] names the provider; [Entry.kind] says whether
 * a re-check wrote the override or the listener did. The second distinction is
 * here for the future hand-edit/provider-pin feature: a `user` override must
 * not be replaced by a later automatic re-check.
 *
 * Pure on purpose — [read] and [write] take a [File] rather than a `Context`,
 * and [choose] has no I/O at all, so the format and the priority rule are
 * tested on the JVM.
 */
object LyricsOverrideFile {

    /** One small JSON object under the app's own `files` directory. */
    const val FILE_NAME = "lyrics-overrides.json"

    private const val VERSION = 1

    /** Why an override exists. */
    enum class Kind(val value: String) {
        RECHECK("recheck"),
        USER("user"),
        ;

        companion object {
            fun of(value: String?): Kind =
                entries.firstOrNull { it.value == value } ?: RECHECK
        }
    }

    /** An override as the player reads it back. */
    class Stored(
        val lines: List<LyricLine>,
        val source: LyricsSource?,
        val savedAt: Long,
        val kind: Kind,
    )

    /**
     * The first layer that has lyrics: a per-song override, then the file's
     * own tags/sidecar, then what an online provider returned.
     */
    fun <T> choose(override: T?, file: T?, online: T?): T? = override ?: file ?: online

    fun read(file: File, videoId: String): Stored? = synchronized(lock) {
        val entry = readFile(file).songs[videoId] ?: return@synchronized null
        val lines = entry.lines.map { it.toLine() }
            .takeIf { parsed -> parsed.any { it.text.isNotBlank() } }
            ?: return@synchronized null
        val source = entry.source?.let { name ->
            LyricsSource.entries.firstOrNull { it.name == name }
        }
        Stored(lines, source, entry.savedAt, Kind.of(entry.kind))
    }

    fun write(
        file: File,
        videoId: String,
        lines: List<LyricLine>,
        source: LyricsSource?,
        savedAt: Long = System.currentTimeMillis(),
        kind: Kind = Kind.RECHECK,
    ): Boolean = synchronized(lock) {
        if (videoId.isBlank() || lines.none { it.text.isNotBlank() }) return@synchronized false
        runCatching {
            val current = readFile(file)
            val songs = current.songs.toMutableMap()
            songs[videoId] = Entry(
                source = source?.name,
                savedAt = savedAt,
                kind = kind.value,
                lines = lines.map(::fromLine),
            )
            val data = Data(VERSION, songs)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, ".${file.name}.tmp")
            temporary.writeText(json.encodeToString(Data.serializer(), data))
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun remove(file: File, videoId: String, kind: Kind? = null): Boolean = synchronized(lock) {
        runCatching {
            val current = readFile(file)
            val entry = current.songs[videoId] ?: return@synchronized false
            // A caller cleaning up after a replaced file removes only the
            // automatic answer; the listener's own override is theirs to keep.
            if (kind != null && Kind.of(entry.kind) != kind) return@synchronized false
            val songs = current.songs.toMutableMap()
            songs.remove(videoId)
            val data = Data(VERSION, songs)
            val temporary = File(file.parentFile, ".${file.name}.tmp")
            temporary.writeText(json.encodeToString(Data.serializer(), data))
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            true
        }.getOrDefault(false)
    }

    /** Whether [text] is a readable override file, without touching any file. */
    fun readText(text: String): Boolean = runCatching {
        json.decodeFromString(Data.serializer(), text)
        true
    }.getOrDefault(false)

    /**
     * Replaces the whole file with a device-migration backup's copy inside the
     * same lock as [write] and [remove].
     *
     * Parsing before the swap keeps a malformed section from costing the device
     * the overrides it already had, and sharing the lock means a concurrent
     * re-check write cannot land its `.tmp` rename over the restored file.
     * A null [text] removes the file.
     */
    fun restore(file: File, text: String?): Boolean = synchronized(lock) {
        runCatching {
            if (text == null) {
                file.delete()
            } else {
                if (!readText(text)) return@synchronized false
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, ".${file.name}.tmp")
                temporary.writeText(text)
                if (!temporary.renameTo(file)) {
                    temporary.copyTo(file, overwrite = true)
                    temporary.delete()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun readFile(file: File): Data = runCatching {
        if (!file.isFile) return Data()
        val data = json.decodeFromString(Data.serializer(), file.readText())
        if (data.version != VERSION) Data() else data
    }.getOrDefault(Data())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    @Serializable
    private class Data(
        val version: Int = VERSION,
        /** Keyed by video id; the value is the override for that recording. */
        val songs: Map<String, Entry> = emptyMap(),
    )

    @Serializable
    private class Entry(
        /** [LyricsSource] name, or null when the writer did not know it. */
        val source: String? = null,
        val savedAt: Long = 0L,
        /** [Kind.value]: `recheck` or `user`. */
        val kind: String = Kind.RECHECK.value,
        val lines: List<Line> = emptyList(),
    )

    @Serializable
    private class Line(
        val t: Long,
        val x: String,
        val w: List<Word> = emptyList(),
        val u: Long? = null,
        val b: Line? = null,
        val end: Boolean = false,
    ) {
        fun toLine(): LyricLine = LyricLine(
            timeMs = t,
            text = x,
            words = w.map { it.toWord() },
            sungUntilMs = u,
            background = b?.toLine(),
            alignment = if (end) LyricAlignment.End else LyricAlignment.Start,
        )
    }

    @Serializable
    private class Word(val s: Long, val e: Long, val x: String, val y: List<Syllable> = emptyList()) {
        fun toWord() = LyricWord(s, e, x, y.map { LyricSyllable(it.s, it.e, it.a, it.b) })
    }

    @Serializable
    private class Syllable(val s: Long, val e: Long, val a: Int, val b: Int)

    private fun fromLine(line: LyricLine): Line = Line(
        t = line.timeMs,
        x = line.text,
        w = line.words.map { word ->
            Word(
                word.startMs,
                word.endMs,
                word.text,
                word.syllables.map { Syllable(it.startMs, it.endMs, it.startChar, it.endChar) },
            )
        },
        u = line.sungUntilMs,
        b = line.background?.let(::fromLine),
        end = line.alignment == LyricAlignment.End,
    )
}
