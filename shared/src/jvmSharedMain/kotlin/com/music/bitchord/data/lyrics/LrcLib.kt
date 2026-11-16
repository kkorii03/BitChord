package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Lyrics from LRCLIB — a free, key-less, community lyrics database.
 *
 * Two calls: an exact `get` keyed on artist + title + duration, and a fuzzy
 * `search` when that misses (YouTube Music titles carry "(From ...)" and
 * "| Official Video" noise that the exact endpoint won't match). Results are
 * line-synced `[mm:ss.xx]` LRC — LRCLIB has no word-level timing, so
 * highlighting is per line.
 */
object LrcLib {

    private const val BASE = "https://lrclib.net/api"
    private const val AGENT = "BitChord (https://github.com/bitchord)"

    private val json = Json { ignoreUnknownKeys = true }

    /** Synced lyrics for a track, or null when nothing usable is published. */
    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val cleanTitle = title.clean()
            val cleanArtist = artist.clean()
            val seconds = (durationMs / 1000).toInt()

            // The exact endpoint needs a length to identify the recording; a
            // download row without one used to send "0" and match whatever the
            // server did with it.
            val exact = if (seconds > 0) {
                runCatching { exactMatch(cleanTitle, cleanArtist, seconds) }.getOrNull()
            } else {
                null
            }
            val synced = exact ?: runCatching { bestSearchHit(cleanTitle, cleanArtist, seconds) }
                .getOrNull()
            synced?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        }

    private fun exactMatch(title: String, artist: String, seconds: Int): String? {
        val url = "$BASE/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("duration", seconds.toString())
            .build()
        val body = get(url.toString()) ?: return null
        return (json.parseToJsonElement(body) as? JsonObject)
            ?.get("syncedLyrics")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Fuzzy fallback. Prefers whichever hit is closest in length to what we're
     * actually playing — same song, different edit, would drift otherwise.
     */
    private fun bestSearchHit(title: String, artist: String, seconds: Int): String? {
        val url = "$BASE/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        val body = get(url.toString()) ?: return null
        val hits = json.parseToJsonElement(body) as? JsonArray ?: return null
        return pickHit(hits.mapNotNull { it as? JsonObject }, title, seconds)
    }

    /**
     * [KO] The search hit that is this recording, or null (review L10).
     *
     * The search is fuzzy: its rows are other songs of the artist, live cuts,
     * remixes. Taking the nearest length with no bound and never reading the
     * hit's title showed another song's sheet. Each hit now passes
     * [RecordingMatch] (same title outside brackets, same version words, length
     * window) like the other name-matched sources. The artist test is left out:
     * the search was already made with the artist name, and LRCLIB's spelling of
     * it (IU / 아이유) cannot be compared. With no length known the API's own
     * order decides, as before.
     */
    internal fun pickHit(hits: List<JsonObject>, title: String, seconds: Int): String? {
        val synced = hits.filter { it["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
        val candidates = synced.map {
            RecordingMatch.Candidate(
                value = it,
                title = it["trackName"]?.jsonPrimitive?.contentOrNull,
                artist = null,
                seconds = it["duration"]?.jsonPrimitive?.doubleOrNull,
            )
        }
        return RecordingMatch.best(candidates, title, "", seconds.coerceAtLeast(0) * 1000L)
            ?.get("syncedLyrics")?.jsonPrimitive?.contentOrNull
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /**
     * `[mm:ss.xx] words`. Metadata tags carry no timestamp and fall out on
     * their own. A stamp with no words marks an instrumental break; those are
     * kept, but only where the silence is long enough to be worth showing —
     * otherwise the line would blink out between two sung phrases. A stamp
     * with nothing after it closes the final line, so it always survives.
     */
    fun parseLrc(lrc: String): List<LyricLine> {
        val offset = EnhancedLrc.sheetOffsetMs(lrc)
        val all = lrc.lineSequence().flatMap { line ->
            val match = STAMP.find(line) ?: return@flatMap emptySequence()
            // [KO] a compressed row repeats one line at several times
            // ("[00:12.00][01:15.00]chorus"): every leading stamp is a line.
            val stamps = mutableListOf(match)
            while (true) {
                val next = STAMP.find(line, stamps.last().range.last + 1)
                    ?.takeIf { it.range.first == stamps.last().range.last + 1 } ?: break
                stamps += next
            }
            val body = line.substring(stamps.last().range.last + 1)
            // Detect the RTL marker that was written by [toEnhancedLrc].
            val (text, alignment) = stripAlignmentMarker(body)
            // Word stamps are absolute, so they belong to the first time only.
            val words = parseWordRuns(text)
            stamps.take(if (words.isEmpty()) stamps.size else 1).asSequence().map { stamp ->
                LyricLine(
                    timeMs = stampMs(stamp),
                    // Stripped rather than rebuilt from the runs below: the spacing
                    // and punctuation between two words belong to the line, and
                    // re-joining the words with single spaces would quietly rewrite
                    // a line that never had them.
                    text = text.replace(WORD_STAMP, "").trim(),
                    words = words,
                    alignment = alignment,
                )
            }
        }.sortedBy { it.timeMs }.toList().let { rows -> if (offset == 0L) rows else rows.timedBy(null, offset) }

        val kept = all.filterIndexed { index, line ->
            if (!line.isGap) return@filterIndexed true
            // A trailing stamp closes off the last line — that's the outro.
            val next = all.getOrNull(index + 1) ?: return@filterIndexed true
            next.timeMs - line.timeMs >= MIN_GAP_MS
        }

        // Nothing stands for the intro — LRC files start at the first sung
        // word — so give the run-up its own break when it's long enough.
        val first = kept.firstOrNull() ?: return kept
        return if (!first.isGap && first.timeMs >= MIN_GAP_MS) {
            listOf(LyricLine(0L, "")) + kept
        } else {
            kept
        }
    }

    /**
     * Strip the alignment marker written by [toEnhancedLrc] and return the
     * clean body along with the recovered alignment.
     *
     * The marker is `<R>` — a word-stamp-looking token that never appears in
     * genuine A2 output (word stamps use `<mm:ss.xx>`, not bare `<R>`). It sits
     * at the very start of the body, before the first real stamp.
     */
    private fun stripAlignmentMarker(body: String): Pair<String, LyricAlignment> {
        if (body.startsWith(ALIGNMENT_MARKER)) {
            return body.substringAfter(ALIGNMENT_MARKER) to LyricAlignment.End
        }
        return body to LyricAlignment.Start
    }

    private const val ALIGNMENT_MARKER = "<R>"

    /**
     * YouTube Music titles are noisy — "(From "Raees")", "| Official Video",
     * "(Lyrical)" — and LRCLIB matches on the plain song name.
     */
    private fun String.clean(): String = this
        .replace(NOISE, " ")
        .substringBefore(" | ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { this }

    /**
     * The `<mm:ss.xx>` runs of an "enhanced" A2 line, as words.
     *
     * Each run ends where the next one starts, which is why a line written by
     * [toEnhancedLrc] closes with a bare stamp: that last one names no word, it
     * just states where the previous one stopped. A run with no text is
     * therefore a terminator rather than a word, here and in the files other
     * A2 writers produce.
     *
     * Empty for a plain line, which is what keeps [LyricLine.isWordSynced]
     * honest — a line-synced source stays line-synced through this.
     */
    private fun parseWordRuns(body: String): List<LyricWord> {
        val marks = WORD_STAMP.findAll(body).toList()
        if (marks.isEmpty()) return emptyList()
        val runs = marks.mapIndexed { index, mark ->
            val until = marks.getOrNull(index + 1)?.range?.first ?: body.length
            msOf(mark) to body.substring(mark.range.last + 1, until)
        }
        // The next run's stamp is this one's end — including when that run is
        // the closing terminator, which is the only thing that gives the last
        // word of a line an end at all. A run with no space after it is one
        // syllable of a word that carries on, which is how [toEnhancedLrc]
        // writes a word whose syllables were timed apart.
        return wordsFromRuns(
            runs.mapIndexed { index, (startMs, text) ->
                TimedRun(startMs, runs.getOrNull(index + 1)?.first ?: startMs, text)
            },
        )
    }

    private fun msOf(mark: MatchResult): Long {
        val (minutes, seconds, fraction) = mark.destructured
        val fractionMs = when (fraction.length) {
            2 -> fraction.toLong() * 10
            3 -> fraction.toLong()
            else -> 0L
        }
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }

    /** A line stamp; one fraction digit is tenths, two centiseconds, three milliseconds. */
    private fun stampMs(stamp: MatchResult): Long {
        val (minutes, seconds, fraction) = stamp.destructured
        val fractionMs = when (fraction.length) {
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            3 -> fraction.toLong()
            else -> 0L
        }
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }

    // [KO] fraction optional and 1-3 digits: "[01:23]" and "[01:23.4]" rows are lines too.
    private val STAMP = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{2})[.:](\d{2,3})>""")
    private val NOISE = Regex(
        """\((?:from|feat\.?|official|lyrical|video|audio|remix)[^)]*\)|\[[^]]*]|""" +
            """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
}
