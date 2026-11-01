package com.music.bitchord.ko

import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoParser
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.time.LocalDate
import java.util.Locale

/**
 * The player/watch metadata reader, kept out of the upstream parser so
 * upgrades only need the `// [KO] song-info` hook lines in SongInfoParser.
 */
internal object KoSongInfoParser {
    fun parse(html: String, videoId: String): SongInfo? {
        val scripts = Jsoup.parse(html).select("script").map { it.data() }
        val player = scripts.firstNotNullOfOrNull { SongInfoParser.extract(it, "ytInitialPlayerResponse") }
            ?: error("Missing player metadata")
        val data = scripts.firstNotNullOfOrNull { SongInfoParser.extract(it, "ytInitialData") }
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
            countValue(it["countText"]?.text())
        }
            ?: data?.find("commentsEntryPointHeaderRenderer")?.firstOrNull()
                ?.get("commentCount")?.text()?.let(::countValue)
            ?: data?.find("engagementPanelSectionListRenderer")?.firstOrNull {
                it.str("targetId") == "engagement-panel-comments-section" ||
                    it.str("panelIdentifier") == "engagement-panel-comments-section"
            }?.find("engagementPanelTitleHeaderRenderer")?.firstOrNull()
                ?.get("contextualInfo")?.text()?.let(::countValue)
        val meta = descriptionMeta(
            (if (hasPlayer) {
                details.str("shortDescription")
                    ?: player?.obj("microformat")?.obj("playerMicroformatRenderer")
                        ?.str("description")
            } else {
                null
            }) ?: descriptionOf(data),
        )
        val viewRenderer = primary?.find("videoViewCountRenderer")?.firstOrNull()
        return SongInfo(
            releaseDate = meta.releaseDate,
            genre = meta.genre,
            composer = meta.composer,
            lyricist = meta.lyricist,
            views = countValue(
                (if (hasPlayer) details.str("viewCount") else null)
                    // The exact figure when YouTube states one; Korean watch
                    // data often carries "18억" next to the full count.
                    ?: viewRenderer?.str("originalViewCount")
                        ?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
                    ?: viewRenderer?.get("viewCount")?.text(),
            ),
            likes = countValue(newLike ?: oldLike),
            comments = comments,
            failed = !hasPlayer && data == null,
        )
    }

    private fun descriptionOf(data: JsonObject?): String? {
        val secondary = data?.find("videoSecondaryInfoRenderer")?.firstOrNull()
        return secondary?.obj("attributedDescription")?.str("content")
            ?: secondary?.obj("description")?.text()
            ?: data?.find("attributedDescription")?.firstOrNull()?.str("content")
    }

    /**
     * A count as YouTube states it, in any locale.
     *
     * Korean watch data mixes full figures ("조회수 59,809,305회") with
     * abbreviated ones ("조회수 1.2만회", "좋아요 1949만", "댓글 246만").
     * Stripping every non-digit turned "1.2만" into "12"; keeping the unit is
     * both correct and what the Korean UI shows. A plain number is normalised
     * to its digits so [com.music.bitchord.data.compactCount] can shorten it.
     */
    private fun countValue(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.any(Char::isDigit) } ?: return null
        if (text.startsWith("-")) return null
        val abbreviated = ABBREVIATED_COUNT.find(text)
        if (abbreviated != null) {
            val number = abbreviated.groupValues[1]
            val unit = abbreviated.groupValues[2]
            return if (unit.lowercase(Locale.ROOT) in setOf("k", "m", "b")) {
                number + unit.uppercase(Locale.ROOT)
            } else {
                number + unit
            }
        }
        val number = Regex("\\d[\\d,.]*").find(text)?.value ?: return text
        val digits = number.filter(Char::isDigit)
        return digits.toLongOrNull()?.takeIf { it >= 0 }?.toString() ?: text
    }

    /** A number and its unit, wherever it sits inside a localised label. */
    private val ABBREVIATED_COUNT =
        Regex("""(\d+(?:[.,]\d+)?)\s*([KMBkmb만억천万亿千億])""")

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
            // Auto-generated credits write "Composer  Lyricist"; collapsing
            // the run of spaces lets one label match without loosening the
            // colon rule every other field relies on.
            ?.map { it.trim().replace(WHITESPACE, " ") }
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
            lyricist = field("Lyricist", "Lyrics by", "Composer Lyricist", "작사"),
        )
    }

    private val WHITESPACE = Regex("\\s+")

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
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
