package com.music.bitchord.data.lyrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Turns the wire formats used by the smaller providers into BitChord lyrics. */
object ProviderLyrics {

    fun parse(raw: String): List<LyricLine>? {
        val content = unescapeTtml(unwrap(raw) ?: return null)
        val lines = when {
            content.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) ||
                content.contains("http://www.w3.org/ns/ttml", ignoreCase = true) -> TtmlLyrics.parse(content)
            KaraokeLrc.looksLike(content) -> KaraokeLrc.parse(content)
            content.trimStart().startsWith("<") -> emptyList()
            else -> EnhancedLrc.parse(content).ifEmpty { LrcLib.parseLrc(content) }
                .ifEmpty { plain(content) }
        }
        return lines.takeIf { found -> found.any { it.text.isNotBlank() } }
    }

    private fun plain(content: String): List<LyricLine> {
        if (content.contains(Regex("""(?i)\b(?:lyrics? (?:not found|unavailable)|error)\b"""))) return emptyList()
        return content.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .filterNot { it.matches(Regex("""\[[A-Za-z]+:.*]""")) }
            .map { LyricLine(0L, it) }.toList()
    }

    /** Providers sometimes wrap the same lyric string in one or two JSON envelopes. */
    internal fun unwrap(raw: String): String? {
        var value = raw.replace("\uFEFF", "").trim()
        if (value.startsWith("```")) {
            value = value.lineSequence().drop(1).toList()
                .let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
                .joinToString("\n").trim()
        }
        if (value.isBlank()) return null
        val json = runCatching { lyricsJson.parseToJsonElement(value) }.getOrNull() ?: return value
        return extract(json)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extract(element: JsonElement): String? = when (element) {
        JsonNull -> null
        is JsonPrimitive -> if (element.isString) {
            val text = element.content.trim()
            val nested = runCatching { lyricsJson.parseToJsonElement(text) }.getOrNull()
            if (nested != null && nested !is JsonPrimitive) extract(nested) else text
        } else null
        is JsonArray -> element.mapNotNull(::extract).joinToString("\n").takeIf { it.isNotBlank() }
        is JsonObject -> {
            if (element["isError"]?.toString() == "true" || element["ok"]?.toString() == "false" ||
                element["error"]?.let { it !is JsonNull && it.toString() !in setOf("false", "\"\"") } == true
            ) return null
            CONTENT_KEYS.asSequence().mapNotNull { element[it]?.let(::extract) }.firstOrNull()
                ?: (element["metadata"] as? JsonObject)?.let(::extract)
                ?: element["words"]?.let(::extract)
        }
    }

    private fun unescapeTtml(value: String): String = if (value.contains("&lt;tt", ignoreCase = true)) {
        value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
    } else value

    private val CONTENT_KEYS = listOf(
        "ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
        "plainLyrics", "syncedLyrics", "line", "lines", "lyric",
        "data", "result", "response",
    )
}

/** QQ/QRC and NetEase YRC: millisecond line and word ranges. */
object KaraokeLrc {
    private val LINE = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
    private val PREFIX_WORD = Regex("""\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)""")
    private val SUFFIX_WORD = Regex("""([^()]*)\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)""")
    private val WORD_TIME = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
    /** A KuGou-KRC word stamp: offset and length relative to the line's own stamp. */
    private val KRC_WORD = Regex("""<(\d{1,8}),(\d{1,8})(?:,-?\d{1,3})?>([^<>]*)""")
    private val KRC_STAMP = Regex("""<\d{1,8},\d{1,8}(?:,-?\d{1,3})?>""")
    private val CONTENT = Regex("""LyricContent\s*=\s*\"([^\"]*)\"""", RegexOption.IGNORE_CASE)

    fun looksLike(raw: String): Boolean = lyricContent(raw).lineSequence().any { line ->
        LINE.matchEntire(line.trim())?.groupValues?.get(3)?.let {
            PREFIX_WORD.containsMatchIn(it) || SUFFIX_WORD.containsMatchIn(it)
        } == true
    }

    fun parse(raw: String): List<LyricLine> {
        val rows = lyricContent(raw).lineSequence().mapNotNull { source ->
            val match = LINE.matchEntire(source.trim()) ?: return@mapNotNull null
            val lineStart = match.groupValues[1].toLong()
            val lineDuration = match.groupValues[2].toLong()
            val body = match.groupValues[3]
            // QRC and YRC stamp each syllable, and say which ones make a word
            // by leaving the space out between them — see [wordsFromRuns].
            val prefixed = wordsFromRuns(
                PREFIX_WORD.findAll(body).map { word ->
                    timedRun(word.groupValues[3], word.groupValues[1], word.groupValues[2])
                }.toList(),
            )
            val suffixed = wordsFromRuns(
                SUFFIX_WORD.findAll(body).map { word ->
                    timedRun(word.groupValues[1], word.groupValues[2], word.groupValues[3])
                }.toList(),
            )
            // KuGou KRC and Soda Music put the stamp before the word and give
            // its offset relative to the line's stamp — see [krcWords].
            val words = krcWords(lineStart, body).ifEmpty {
                if (prefixed.sumOf { it.text.length } >= suffixed.sumOf { it.text.length }) prefixed else suffixed
            }
            if (words.isEmpty()) return@mapNotNull null
            LyricLine(
                timeMs = minOf(lineStart, words.first().startMs),
                text = EnhancedLrc.decodeEntities(KRC_STAMP.replace(body.replace(WORD_TIME, ""), "")).trim(),
                words = words,
                sungUntilMs = (lineStart + lineDuration).takeIf { lineDuration > 0 },
            )
        }.filter { it.text.isNotEmpty() }.sortedBy { it.timeMs }.toList()
        return rows.withInstrumentalGaps()
    }

    private fun timedRun(text: String, start: String, duration: String): TimedRun {
        val startMs = start.toLong()
        return TimedRun(startMs, startMs + duration.toLong(), EnhancedLrc.decodeEntities(text))
    }

    private fun krcWords(lineStart: Long, body: String): List<LyricWord> {
        // A KRC row opens with its first word stamp; anything else is not KRC.
        if (!body.trimStart().startsWith("<")) return emptyList()
        return wordsFromRuns(
            KRC_WORD.findAll(body).map { word ->
                val start = lineStart + word.groupValues[1].toLong()
                TimedRun(start, start + word.groupValues[2].toLong(), EnhancedLrc.decodeEntities(word.groupValues[3]))
            }.toList(),
        )
    }

    private fun lyricContent(raw: String): String = CONTENT.find(raw)?.groupValues?.get(1)
        ?.replace("&quot;", "\"")?.replace("&apos;", "'")?.replace("&lt;", "<")
        ?.replace("&gt;", ">")?.replace("&amp;", "&") ?: raw
}
