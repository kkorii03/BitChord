package com.music.bitchord.data.lyrics

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.sources.TrackMatcher
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A song's name in its own language, for when its Hangul name finds no lyrics.
 *
 * A Korean cover of a Japanese song is uploaded as "나나호시 관현악단 - 발묘",
 * and every lyrics catalogue files that song as "ナナホシ管弦楽団 - 抜錨". So
 * when the Hangul name comes back empty everywhere, [LyricsRepository] asks
 * here for the original and searches again under it:
 *
 *  1. [fromYouTubeMusic] — an anonymous YouTube Music song search for the
 *     Hangul name, which usually lists the original release first. Free.
 *  2. [fromGemini] — only when that turns up nothing usable, and only with a
 *     Gemini key set. Costs a request, so it is made once per song.
 *
 * Both answers — including "there is no foreign original" — are kept on disk,
 * so a song pays for each of these once, not once per play.
 */
object OriginalNames {

    data class Name(val title: String, val artist: String)

    /** Whether a name is worth looking up in another language at all. */
    fun worthTrying(title: String, artist: String): Boolean = hasHangul(title) || hasHangul(artist)

    /**
     * The original release's name as YouTube Music lists it, or null when the
     * search finds the song under its Korean name (it *is* Korean) or nothing
     * that fits. A search that failed outright is not remembered.
     */
    suspend fun fromYouTubeMusic(title: String, artist: String, durationMs: Long): Name? {
        val key = key(title, artist)
        entry(key)?.takeIf { it.youtubeChecked }?.let { return it.youtube() }
        val found = try {
            searchYouTubeMusic(title, artist, durationMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        update(key) { it.copy(youtubeChecked = true, youtubeTitle = found?.title, youtubeArtist = found?.artist) }
        return found
    }

    /**
     * The song a cover sings, found by its [title] alone — for an upload whose
     * title names the song and the singer but never the original's artist
     * ("Drowning - 타비 Cover(06.12)"). YouTube Music is searched for the title
     * and a row is taken only if its title is that title (or, for a Hangul
     * title, the first row in another script) and its length is close to what
     * is playing. Remembered per title, like [fromYouTubeMusic].
     */
    suspend fun fromTitle(title: String, durationMs: Long): Name? {
        val key = key(title, "")
        entry(key)?.takeIf { it.youtubeChecked }?.let { return it.youtube() }
        val found = try {
            searchTitleOnly(title, durationMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        update(key) { it.copy(youtubeChecked = true, youtubeTitle = found?.title, youtubeArtist = found?.artist) }
        return found
    }

    /** Gemini's answer for the original's name; null when it has none or isn't set up. */
    suspend fun fromGemini(title: String, artist: String): Name? {
        val key = key(title, artist)
        entry(key)?.takeIf { it.geminiChecked }?.let { return it.gemini() }
        return when (val answer = LyricsTranslation.nativeName(title, artist)) {
            is LyricsTranslation.NativeNameResult.Found -> {
                val name = Name(answer.title, answer.artist)
                update(key) { it.copy(geminiChecked = true, geminiTitle = name.title, geminiArtist = name.artist) }
                name
            }
            LyricsTranslation.NativeNameResult.NotForeign -> {
                update(key) { it.copy(geminiChecked = true, geminiTitle = null, geminiArtist = null) }
                null
            }
            LyricsTranslation.NativeNameResult.Unavailable -> null
        }
    }

    // ---- YouTube Music ---------------------------------------------------------

    /** How far the original's length may stray from what is playing: covers run a little long or short. */
    private const val MAX_DRIFT_SECONDS = 15

    /**
     * Only the top of the list is trusted: a Hangul spelling YouTube Music has
     * no alias for still gets an answer, and further down that answer is
     * "other songs with similar words".
     */
    private const val TOP_ROWS = 2

    private suspend fun searchYouTubeMusic(title: String, artist: String, durationMs: Long): Name? {
        val response = Innertube.searchAnonymous("$title $artist", SearchFilter.SONGS.params)
        val songs = InnertubeParser.parseSearchPage(response, includeVideos = false).rows
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }
            .take(TOP_ROWS)
        val seconds = (durationMs / 1000).toInt()
        for (song in songs) {
            if (hasHangul(song.title)) {
                // Listed under the Korean name itself: a Korean song (or its
                // Korean release), which the first search already covered.
                if (compact(song.title) == compact(title)) return null
                continue
            }
            // The length is the only check that works across scripts, so a row
            // without one is not taken on trust.
            val length = TrackMatcher.secondsOf(song.durationText) ?: continue
            if (seconds > 0 && abs(length - seconds) > MAX_DRIFT_SECONDS) continue
            val foundArtist = TrackMatcher.primaryArtist(song.artist).ifBlank { song.artist }.trim()
            val foundTitle = song.title.trim()
            if (foundTitle.isEmpty()) continue
            return Name(foundTitle, foundArtist.ifEmpty { artist })
        }
        return null
    }

    /** A live take runs longer or shorter than the record by more than a studio cover does. */
    private const val MAX_TITLE_ONLY_DRIFT_SECONDS = 20

    /** See [searchTitleOnly]: the same title, a cut that runs on. */
    private const val MAX_SAME_TITLE_DRIFT_SECONDS = 75

    private val TITLE_BRACKETS = Regex("""[(\[（【「『][^)\]）】」』]*[)\]）】」』]""")

    /** A title alone is a weaker question, so a few more rows are looked at — each held to the title. */
    private const val TITLE_ONLY_ROWS = 3

    private suspend fun searchTitleOnly(title: String, durationMs: Long): Name? {
        // A cover's title often carries its reading in brackets
        // ("ギラギラ(기라기라)"): searched without it, matched on any of them.
        val main = TITLE_BRACKETS.replace(title, " ").trim().ifBlank { title }
        val response = Innertube.searchAnonymous(main, SearchFilter.SONGS.params)
        val songs = InnertubeParser.parseSearchPage(response, includeVideos = false).rows
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }
            .take(TITLE_ONLY_ROWS)
        val seconds = (durationMs / 1000).toInt()
        val wanted = RecordingPick.readings(title)
        songs.forEachIndexed { index, song ->
            val length = TrackMatcher.secondsOf(song.durationText) ?: return@forEachIndexed
            val sameTitle = RecordingPick.readings(song.title).any { it in wanted }
            // A stream archive's cut runs on past the song (a greeting, the
            // chat read out), so a title that matches outright is given more room.
            val drift = if (sameTitle) MAX_SAME_TITLE_DRIFT_SECONDS else MAX_TITLE_ONLY_DRIFT_SECONDS
            if (seconds > 0 && abs(length - seconds) > drift) return@forEachIndexed
            // "야화" for a Japanese original: the only row that can be trusted
            // without a matching title is the first, and only across scripts.
            val transliterated = index == 0 && hasHangul(main) && !hasHangul(song.title)
            if (!sameTitle && !transliterated) return@forEachIndexed
            val foundArtist = TrackMatcher.primaryArtist(song.artist).ifBlank { song.artist }.trim()
            if (foundArtist.isEmpty() || song.title.isBlank()) return@forEachIndexed
            return Name(song.title.trim(), foundArtist)
        }
        return null
    }

    // ---- Remembered answers ------------------------------------------------------

    @Serializable
    private data class Entry(
        val youtubeChecked: Boolean = false,
        val youtubeTitle: String? = null,
        val youtubeArtist: String? = null,
        val geminiChecked: Boolean = false,
        val geminiTitle: String? = null,
        val geminiArtist: String? = null,
    ) {
        fun youtube(): Name? = youtubeTitle?.let { Name(it, youtubeArtist.orEmpty()) }
        fun gemini(): Name? = geminiTitle?.let { Name(it, geminiArtist.orEmpty()) }
    }

    @Serializable
    private data class Store(val entries: Map<String, Entry> = emptyMap())

    private const val FILE_NAME = "original_names_v1.json"
    private const val MAX_ENTRIES = 500

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Insertion-ordered, oldest first, so the trim drops the longest-unasked songs. */
    private var entries: LinkedHashMap<String, Entry>? = null

    private fun file(): File? = (LyricsTranslation.keepDir ?: LyricsTranslation.cacheDir)?.let { File(it, FILE_NAME) }

    private suspend fun loaded(): LinkedHashMap<String, Entry> {
        entries?.let { return it }
        val read = withContext(Dispatchers.IO) {
            runCatching {
                file()?.takeIf { it.isFile }?.readText()?.let { json.decodeFromString<Store>(it).entries }
            }.getOrNull()
        }
        return LinkedHashMap(read.orEmpty()).also { entries = it }
    }

    private suspend fun entry(key: String): Entry? = mutex.withLock { loaded()[key] }

    private suspend fun update(key: String, change: (Entry) -> Entry): Unit = mutex.withLock {
        val map = loaded()
        val next = change(map.remove(key) ?: Entry())
        map[key] = next
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
        val snapshot = Store(LinkedHashMap(map))
        // Written under the lock, so two answers landing together can't have
        // the older snapshot overwrite the newer one.
        withContext(Dispatchers.IO) {
            runCatching {
                val target = file() ?: return@runCatching
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "$FILE_NAME.tmp")
                temporary.writeText(json.encodeToString(Store.serializer(), snapshot))
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            }
        }
        Unit
    }

    // ---- Pieces ---------------------------------------------------------------------

    private fun key(title: String, artist: String) = compact(title) + "\u0000" + compact(artist)

    /** Lower case, letters and digits only: "발묘 (Cover)" and "발묘" are one song. */
    private fun compact(text: String): String =
        text.replace(Regex("""[(\[（【].*?[)\]）】]"""), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .ifEmpty { text.lowercase().trim() }

    internal fun hasHangul(text: String): Boolean = text.any {
        Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL
    }
}
