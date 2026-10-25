package com.music.bitchord.data

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.time.LocalDate
import java.util.Locale

data class SongInfo(
    val album: String? = null,
    val releaseYear: String? = null,
    val releaseDate: String? = null,
    val views: String? = null,
    val likes: String? = null,
    val comments: String? = null,
    val failed: Boolean = false,
    val genre: String? = null,
    val composer: String? = null,
    val lyricist: String? = null,
)

/**
 * YouTube-style short form of a raw count: 950 → "950", 1234 → "1.2K",
 * 100000 → "100K", 2345678 → "2.3M". Truncated rather than rounded, as YouTube
 * does, so 999999 never shows as "1000K". Text that isn't a plain number is
 * returned unchanged.
 */
fun compactCount(raw: String): String {
    val n = raw.toLongOrNull()?.takeIf { it >= 0 } ?: return raw
    val (unit, suffix) = when {
        n >= 1_000_000_000 -> 1_000_000_000L to "B"
        n >= 1_000_000 -> 1_000_000L to "M"
        n >= 1_000 -> 1_000L to "K"
        else -> return n.toString()
    }
    val whole = n / unit
    if (whole >= 10) return "$whole$suffix"
    val tenth = n % unit * 10 / unit
    val separator = java.text.DecimalFormatSymbols.getInstance().decimalSeparator
    return if (tenth == 0L) "$whole$suffix" else "$whole$separator$tenth$suffix"
}

/**
 * Which YouTube video the Song Info request belongs to.
 *
 * The queue's id is not always a video id: an addon/JioSaavn row carries
 * `src:<config>::<track>` and a local file can carry a content URI. Sending
 * either to YouTube can only fail, and it used to make the whole panel look
 * broken. A real 11-character id is used as-is (a song version already has its
 * own id); a source-backed row is matched to the YouTube song behind it.
 */
internal object SongInfoTarget {
    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    fun youtubeId(song: Song): String? = song.videoId.takeIf { YOUTUBE_ID.matches(it) }

    fun needsCatalogueLookup(song: Song): Boolean =
        youtubeId(song) == null && SourceTrackKeys.parse(song.videoId) != null
}

/** One line of the Song Info panel, in Apple Music's order. */
enum class SongInfoField {
    ALBUM,
    RELEASE_DATE,
    GENRE,
    COMPOSER,
    LYRICIST,
    QUALITY,
    VIEWS,
    LIKES,
    COMMENTS,
}

/**
 * A line the panel should draw. [value] is null only while the request is
 * still in flight; [unknown] marks a known-missing Apple-style field, which is
 * shown grey rather than as an error.
 */
data class SongInfoLine(
    val field: SongInfoField,
    val value: String?,
    val unknown: Boolean = false,
)

/**
 * Chooses which lines the panel shows.
 *
 * Apple Music's song page leads with the release facts and drops the
 * YouTube-style tally when it has nothing to say. So album and release date
 * are always present (as "No information" when genuinely missing), while
 * genre, credits, quality and the three YouTube counts only appear when they
 * have a value. Kept pure so the rule is testable without a network or a
 * screen.
 */
object SongInfoDisplay {
    fun loadingLines(song: Song): List<SongInfoLine> = listOf(
        SongInfoLine(SongInfoField.ALBUM, song.albumName?.takeIf { it.isNotBlank() }),
        SongInfoLine(SongInfoField.RELEASE_DATE, null),
    )

    fun lines(
        info: SongInfo,
        song: Song,
        quality: String?,
        unknownLabel: String,
    ): List<SongInfoLine> {
        val out = mutableListOf<SongInfoLine>()

        fun required(field: SongInfoField, value: String?) {
            val text = value?.trim()?.takeIf { it.isNotBlank() }
            out += SongInfoLine(field, text ?: unknownLabel, unknown = text == null)
        }

        fun known(field: SongInfoField, value: String?) {
            value?.trim()?.takeIf { it.isNotBlank() }?.let {
                out += SongInfoLine(field, it)
            }
        }

        required(
            SongInfoField.ALBUM,
            info.album?.takeIf { it.isNotBlank() } ?: song.albumName,
        )
        required(
            SongInfoField.RELEASE_DATE,
            info.releaseDate?.takeIf { it.isNotBlank() }
                ?: info.releaseYear?.takeIf { it.isNotBlank() },
        )
        known(SongInfoField.GENRE, info.genre)
        known(SongInfoField.COMPOSER, info.composer)
        known(SongInfoField.LYRICIST, info.lyricist)
        known(SongInfoField.QUALITY, quality)
        known(SongInfoField.VIEWS, info.views?.let(::compactCount))
        known(SongInfoField.LIKES, info.likes)
        known(SongInfoField.COMMENTS, info.comments)
        return out
    }
}

/**
 * The playback format the listener is actually hearing, or the format a
 * download was saved as. A catalogue's advertised tier is deliberately not
 * used: "LOSSLESS" on a row says what was asked for, not what arrived.
 */
object SongInfoQuality {
    fun label(song: Song, snapshot: NerdStats.Snapshot?): String? {
        val current = snapshot?.takeIf {
            it.mediaId == song.videoId || (song.queueEntryId != null && it.mediaId == song.queueEntryId)
        }
        if (current != null) {
            val lossless = current.isLossless
            val parts = listOfNotNull(
                NerdStats.codecLabel(current.mimeType) ?: current.container,
                current.bitDepth?.takeIf { lossless }?.let { "$it-bit" },
                current.sampleRateHz?.let(::rateLabel),
                current.bitrateKbps?.takeUnless { lossless }?.let { "$it kbps" },
            )
            if (parts.isNotEmpty()) return parts.joinToString(" · ")
        }
        return song.downloadFormat?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun rateLabel(hz: Int): String =
        NerdStats.megahertzLabel(hz)
            ?: "${String.format(Locale.ROOT, "%.1f", hz / 1000f).removeSuffix(".0")} kHz"
}

/** Loaded only when requested. Counts belong to this exact upload, never a search match. */
object SongInfoRepository {
    private const val TAG = "BitChord"

    /** A source row's YouTube match is a nicety; the panel must not wait on it. */
    private const val CATALOGUE_LOOKUP_MS = 8_000L

    /** The album link is also a nicety; the watch description already has the facts. */
    private const val LINK_LOOKUP_MS = 5_000L

    suspend fun load(song: Song): SongInfo = withContext(Dispatchers.IO) {
        val direct = SongInfoTarget.youtubeId(song)
        val resolved = if (direct == null && SongInfoTarget.needsCatalogueLookup(song)) {
            withTimeoutOrNull(CATALOGUE_LOOKUP_MS) { resolveCatalogueTarget(song) }
        } else {
            null
        }
        val target = direct ?: resolved?.videoId
        val via = when {
            direct != null -> "id"
            resolved != null -> "search"
            else -> "none"
        }
        if (target == null) {
            // No YouTube identity behind this row. The metadata already on the
            // row is still worth showing; the missing parts are simply absent.
            TrackLog.i(TAG, "song-info: no youtube id via=$via")
            return@withContext SongInfo(album = song.albumName?.takeIf { it.isNotBlank() })
        }

        val player = async { attempt { Innertube.playerResponse(target) } }
        val watch = async { attempt { Innertube.watchNext(target) } }
        val links = async {
            attempt {
                if (song.albumId == null) {
                    YtMusicRepository.trackLinks(target).getOrThrow()
                } else {
                    song
                }
            }
        }

        val playerResult = player.await()
        val watchResult = watch.await()
        val parsed = attempt {
            SongInfoParser.parseWatch(
                player = playerResult.getOrNull(),
                data = watchResult.getOrNull(),
                videoId = target,
            )
        }

        val linked = withTimeoutOrNull(LINK_LOOKUP_MS) { links.await() }
        if (linked == null) links.cancel()
        var album = song.albumName?.takeIf { it.isNotBlank() }
            ?: linked?.getOrNull()?.albumName?.takeIf { it.isNotBlank() }
        val albumId = linked?.getOrNull()?.albumId ?: song.albumId
        val header = albumId?.let { id ->
            attempt { InnertubeParser.parseBrowseHeader(Innertube.browse(id)) }.getOrNull()
        }
        album = album ?: header?.title?.takeIf { it.isNotBlank() }
        val year = header?.subtitle
            ?.split('•', '·')
            ?.map { it.trim() }
            ?.firstOrNull { it.matches(Regex("(?:19|20)[0-9]{2}")) }

        val stats = parsed.getOrNull()
        val failed = stats == null || stats.failed
        if (failed) {
            val cause = reason(
                parsed.exceptionOrNull()
                    ?: playerResult.exceptionOrNull()
                    ?: watchResult.exceptionOrNull(),
            )
            TrackLog.w(
                TAG,
                "song-info: id=$target via=$via failed ($cause) " +
                    "links=${if (linked?.isSuccess == true) "ok" else reason(linked?.exceptionOrNull())}",
            )
        }

        SongInfo(
            album = album,
            releaseYear = year,
            releaseDate = stats?.releaseDate,
            genre = stats?.genre,
            composer = stats?.composer,
            lyricist = stats?.lyricist,
            views = stats?.views,
            likes = stats?.likes,
            comments = stats?.comments,
            failed = failed,
        )
    }

    /**
     * The YouTube song behind a source-backed row (`src:…`). Matched with the
     * same rules the substitute path uses, so a cover or a live take cannot
     * lend the panel another recording's album and credits. A local file with
     * no YouTube id is deliberately left alone: a personal recording is not
     * something a title search should guess at.
     */
    private suspend fun resolveCatalogueTarget(song: Song): Song? {
        if (song.title.isBlank() || song.artist.isBlank()) return null
        val target = TrackMatcher.targetOf(song)
        for (query in TrackMatcher.queries(target)) {
            val result = YtMusicRepository.search(query, SearchFilter.SONGS, SearchKind.CATALOG)
            if (result.isFailure) continue
            val candidates = result.getOrNull().orEmpty()
                .filterIsInstance<SearchResult.Track>()
                .map { it.song }
                .filterNot { it.isVideo }
            TrackMatcher.bestSameArtist(candidates, target)?.let { return it }
        }
        return null
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** A log-safe cause: no URL, query or key ever reaches the line. */
    private fun reason(error: Throwable?): String = when (error) {
        null -> "none"
        is io.ktor.client.plugins.ResponseException -> "http ${error.response.status.value}"
        is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
        else -> error::class.simpleName ?: "error"
    }
}

internal object SongInfoParser {
    fun parse(html: String, videoId: String): SongInfo {
        val scripts = Jsoup.parse(html).select("script").map { it.data() }
        val player = scripts.firstNotNullOfOrNull { extract(it, "ytInitialPlayerResponse") }
            ?: error("Missing player metadata")
        val data = scripts.firstNotNullOfOrNull { extract(it, "ytInitialData") }
        return parseWatch(player, data, videoId)
    }

    /**
     * Reads a youtubei `player` response and/or a `next` response. Either one
     * is enough: the player carries views and the description's release facts,
     * the watch data carries likes and comments.
     */
    fun parseWatch(player: JsonObject?, data: JsonObject?, videoId: String): SongInfo {
        val details = player?.obj("videoDetails") ?: JsonObject(emptyMap())
        val playerId = details.str("videoId")
        if (playerId != null) {
            check(playerId == videoId) { "Metadata belongs to another track" }
        }
        val hasPlayer = playerId == videoId
        val primary = data?.find("videoPrimaryInfoRenderer")?.firstOrNull()
        val oldLike = primary?.find("toggleButtonRenderer")?.firstOrNull {
            it.obj("defaultIcon").str("iconType") == "LIKE"
        }?.get("defaultText")?.text()
        val newLike = primary?.find("likeButtonViewModel")?.firstOrNull()
            ?.find("buttonViewModel")?.firstOrNull()?.str("title")
        val comments = data?.find("commentsHeaderRenderer")?.firstNotNullOfOrNull {
            it["countText"]?.text()?.withCount()
        }
            ?: data?.find("commentsEntryPointHeaderRenderer")?.firstOrNull()
                ?.get("commentCount")?.text()?.withCount()
            ?: data?.find("engagementPanelSectionListRenderer")?.firstOrNull {
                it.str("targetId") == "engagement-panel-comments-section" ||
                    it.str("panelIdentifier") == "engagement-panel-comments-section"
            }?.find("engagementPanelTitleHeaderRenderer")?.firstOrNull()
                ?.get("contextualInfo")?.text()?.withCount()
        val meta = descriptionMeta(
            (if (hasPlayer) details.str("shortDescription") else null) ?: descriptionOf(data),
        )
        return SongInfo(
            releaseDate = meta.releaseDate,
            genre = meta.genre,
            composer = meta.composer,
            lyricist = meta.lyricist,
            views = viewCount(
                (if (hasPlayer) details.str("viewCount") else null)
                    ?: primary?.find("videoViewCountRenderer")?.firstOrNull()
                        ?.get("viewCount")?.text(),
            ),
            likes = (newLike ?: oldLike)?.withCount(),
            comments = comments,
            failed = !hasPlayer && data == null,
        )
    }

    private fun descriptionOf(data: JsonObject?): String? {
        val secondary = data?.find("videoSecondaryInfoRenderer")?.firstOrNull()
        return secondary?.o("attributedDescription").str("content")
            ?: secondary?.o("description").text()
            ?: data?.find("attributedDescription")?.firstOrNull()?.str("content")
    }

    /** YouTube's own count strings are full numbers, sometimes with a label. */
    private fun viewCount(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.any(Char::isDigit) } ?: return null
        if (text.startsWith("-")) return null
        val first = text.substringBefore(' ')
        if (Regex("^[\\d.,]+[KMB]$", RegexOption.IGNORE_CASE).matches(first)) return first
        val number = Regex("\\d[\\d,.]*").find(text)?.value ?: return text
        val digits = number.filter(Char::isDigit)
        return digits.toLongOrNull()?.takeIf { it >= 0 }?.toString() ?: text
    }

    private data class DescriptionMeta(
        val releaseDate: String?,
        val genre: String?,
        val composer: String?,
        val lyricist: String?,
    )

    /**
     * Catalogue uploads state these in their description. Upload/publish dates
     * describe the video and must never substitute for a recording's release
     * date, which is why only an explicit "Released on" line is accepted.
     */
    private fun descriptionMeta(description: String?): DescriptionMeta {
        val lines = description?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()

        fun field(vararg labels: String): String? = lines.firstNotNullOfOrNull { line ->
            labels.firstNotNullOfOrNull { label ->
                Regex("^${Regex.escape(label)}\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }

        val release = lines.firstNotNullOfOrNull { line ->
            Regex("(?:Released on|발매일)\\s*:\\s*(\\d{4}-\\d{2}-\\d{2})", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)
        }?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }

        return DescriptionMeta(
            releaseDate = release,
            genre = field("Genre", "장르"),
            composer = field("Composer", "Written by", "Writer", "작곡"),
            lyricist = field("Lyricist", "Lyrics by", "작사"),
        )
    }

    /** Balanced JSON extraction tolerates braces inside titles and trailing script statements. */
    private fun extract(script: String, name: String): JsonObject? {
        val assignment = Regex("(?:var\\s+)?${name}\\s*=\\s*").find(script) ?: return null
        val start = assignment.range.last + 1
        if (script.getOrNull(start) != '{') return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until script.length) {
            val c = script[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching {
                    Json.parseToJsonElement(script.substring(start, i + 1)) as? JsonObject
                }.getOrNull()
            }
        }
        return null
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun String.withCount(): String? = takeIf { it.any(Char::isDigit) }
    private fun JsonElement.text(): String? = (this as? JsonObject)?.let {
        it.str("simpleText") ?: (it["runs"] as? JsonArray)?.joinToString("") { run ->
            (run as? JsonObject)?.str("text").orEmpty()
        }
    }?.takeIf { it.isNotBlank() }
    private fun JsonElement.find(key: String): List<JsonObject> = when (this) {
        is JsonObject -> listOfNotNull(this[key] as? JsonObject) + values.flatMap { it.find(key) }
        is JsonArray -> flatMap { it.find(key) }
        else -> emptyList()
    }
}
