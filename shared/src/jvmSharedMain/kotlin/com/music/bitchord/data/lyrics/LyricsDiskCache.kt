package com.music.bitchord.data.lyrics

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Whole lyric lookups kept on the device, so a song heard before opens with
 * its lyrics at once — after a restart too — instead of asking every source
 * again. Only answers are kept: "nothing found" may just have been the phone
 * offline. See [LyricsRepository.lyrics] for how a kept answer that could
 * still be bettered (lines only, no word timing) is checked again in the
 * background — at most once a [RECHECK_MS].
 *
 * One small file per question under the app's own files, never the cache
 * Android may clear; the oldest go once there are more than [MAX_FILES].
 */
internal object LyricsDiskCache {

    /** A kept answer, found at [at], last checked for something better at [checkedAt]. */
    class Kept(val result: LyricsRepository.Result?, val at: Long, val checkedAt: Long)

    private const val DIRECTORY = "lyrics_v1"
    private const val MAX_FILES = 4_000
    private const val PRUNE_EVERY = 40

    /** How long a kept answer that could be bettered goes before it is looked for again. */
    private const val RECHECK_MS = 24L * 60L * 60L * 1000L

    /** How good lyrics are: syllables 3, words 2, lines 1, untimed 0. */
    fun rank(result: LyricsRepository.Result): Int = when {
        result.lines.any { line -> line.words.any { it.syllables.isNotEmpty() } } -> 3
        result.lines.any { it.isWordSynced } -> 2
        result.lines.any { it.timeMs > 0 } -> 1
        else -> 0
    }

    /** Worth a quiet search for better: not word-synced yet, and not checked lately. */
    fun worthCheckingAgain(kept: Kept): Boolean {
        val result = kept.result ?: return false
        return rank(result) < 2 && System.currentTimeMillis() - kept.checkedAt > RECHECK_MS
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var writes = 0

    private fun dir(): File? = LyricsTranslation.keepDir?.let { File(it, DIRECTORY) }

    private fun fileFor(key: String): File? {
        val dir = dir() ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun read(key: String): Kept? = runCatching {
        val file = fileFor(key)?.takeIf { it.exists() } ?: return null
        val entry = json.decodeFromString(Entry.serializer(), file.readText())
        if (entry.version != VERSION) return null
        val result = entry.source?.let { name ->
            val source = runCatching { LyricsSource.valueOf(name) }.getOrNull() ?: return null
            LyricsRepository.Result(
                source = source,
                lines = entry.lines.map { it.toLine() },
                searchedAs = entry.asTitle?.let { it to entry.asArtist.orEmpty() },
            )
        }
        Kept(result, entry.at, maxOf(entry.at, entry.checkedAt))
    }.getOrNull()

    fun write(key: String, result: LyricsRepository.Result?) {
        runCatching {
            val file = fileFor(key) ?: return
            file.parentFile?.mkdirs()
            val now = System.currentTimeMillis()
            val entry = Entry(
                at = now,
                checkedAt = now,
                source = result?.source?.name,
                lines = result?.lines.orEmpty().map(::fromLine),
                asTitle = result?.searchedAs?.first,
                asArtist = result?.searchedAs?.second,
            )
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.encodeToString(Entry.serializer(), entry))
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            if (++writes % PRUNE_EVERY == 0) prune()
        }
    }

    /** Marks [key] as just checked, so the background search waits [RECHECK_MS] again. */
    fun touchChecked(key: String) {
        runCatching {
            val file = fileFor(key)?.takeIf { it.exists() } ?: return
            val entry = json.decodeFromString(Entry.serializer(), file.readText())
            val updated = Entry(entry.version, entry.at, System.currentTimeMillis(), entry.source, entry.lines, entry.asTitle, entry.asArtist)
            file.writeText(json.encodeToString(Entry.serializer(), updated))
        }
    }

    private fun prune() {
        val files = dir()?.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    // ---- On disk ---------------------------------------------------------------

    private const val VERSION = 1

    @Serializable
    private class Entry(
        val version: Int = VERSION,
        val at: Long,
        val checkedAt: Long = 0L,
        /** [LyricsSource] name; null for a miss. */
        val source: String? = null,
        val lines: List<Line> = emptyList(),
        val asTitle: String? = null,
        val asArtist: String? = null,
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
            Word(word.startMs, word.endMs, word.text, word.syllables.map { Syllable(it.startMs, it.endMs, it.startChar, it.endChar) })
        },
        u = line.sungUntilMs,
        b = line.background?.let(::fromLine),
        end = line.alignment == LyricAlignment.End,
    )
}
