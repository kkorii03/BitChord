package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.sources.TrackMatcher
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * The video-timing lab: real covers from a playlist, their real originals,
 * the app's own lookups, chroma alignment on the decoded audio — one line per
 * video saying what happened and why. Runs only with LIVE_TIMING=1 (the
 * timing-lab workflow, which has ffmpeg); writes its report to TIMING_OUT.
 */
class LyricsTimingLiveTest {
    private val live = System.getenv("LIVE_TIMING") == "1"

    private val playlist = System.getenv("TIMING_PLAYLIST") ?: "PL-ODED49YdZByxxHBLaIIOOePlhj8nWcc"
    private val limit = System.getenv("TIMING_LIMIT")?.toIntOrNull() ?: 60

    @Test
    fun playlistCovers() {
        if (!live) return
        val out = File(System.getenv("TIMING_OUT") ?: "timing-lab.txt")
        val report = java.util.Collections.synchronizedList(mutableListOf<String>())
        val work = File(System.getProperty("java.io.tmpdir"), "timing-lab").apply { mkdirs() }
        runBlocking {
            val songs = playlistSongs("VL$playlist")
            report += "PLAYLIST ${songs.size} songs"
            // Foreign songs first — the ones the listener cares most about.
            val ordered = songs.sortedBy { if (isForeign(it)) 0 else 1 }.take(limit)
            val gate = Semaphore(3)
            ordered.mapIndexed { index, song ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val line = withTimeoutOrNull(240_000L) { runCatching { one(index, song, work) }.getOrElse { "ERROR ${it::class.simpleName}: ${it.message}" } }
                            ?: "TIMEOUT"
                        val row = "#$index ${song.videoId} '${song.title}' / '${song.artist}' :: $line"
                        println("TIMING $row")
                        report += row
                        out.writeText(report.joinToString("\n"))
                    }
                }
            }.awaitAll()
        }
        out.writeText(report.joinToString("\n"))
    }

    private fun isForeign(song: Song): Boolean {
        val q = CoverTitles.lyricsQuery(song)
        val text = q.title + q.artist
        return text.none { it in '가'..'힣' } || text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }
    }

    private suspend fun playlistSongs(browseId: String): List<Song> {
        val out = LinkedHashMap<String, Song>()
        var response = Innertube.browse(browseId)
        repeat(10) {
            val shelf = InnertubeParser.parsePlaylistShelf(response) ?: return out.values.toList()
            shelf.songs.forEach { out.putIfAbsent(it.videoId, it) }
            val token = shelf.continuation ?: return out.values.toList()
            response = Innertube.browseContinuation(token)
        }
        return out.values.toList()
    }

    private suspend fun one(index: Int, upload: Song, work: File): String {
        val song = upload.copy(isVideo = true)
        val q = CoverTitles.lyricsQuery(song)
        val head = "q='${q.title}'/'${q.artist}' known=${q.artistKnown} alt=${q.alternatives.size}"
        val durationMs = song.durationMillis()
        val sources = LyricsSource.offered.toSet() - LyricsSource.PAXSENIX_SPOTIFY - LyricsSource.PAXSENIX_MUSIXMATCH
        val found = LyricsRepository.lyrics(
            videoId = song.videoId,
            title = q.title,
            artist = q.artist,
            durationMs = durationMs,
            sources = sources,
            prioritizeSyllableSync = true,
            isVideo = q.isVideo,
            alternatives = q.alternatives,
            artistKnown = q.artistKnown,
        ) ?: return "$head | NO-LYRICS"
        val timed = found.lines.any { it.timeMs > 0 }
        val lyr = "lyrics=${found.source} lines=${found.lines.size} timed=$timed as=${found.searchedAs}"
        if (!timed) return "$head | $lyr | UNTIMED"
        if (LyricsSourceVideo.timedToVideo(found.source)) return "$head | $lyr | VIDEO-OWN"
        val names = buildList {
            if (q.artistKnown) add(q.title to q.artist)
            found.searchedAs?.let(::add)
            add(q.title to q.artist)
            addAll(q.alternatives)
        }.distinct()
        val candidates = RecordingPick.find(names, durationMs)
        if (candidates.isEmpty()) return "$head | $lyr | NO-ORIGINAL for $names"
        val picks = "picks=" + candidates.joinToString(" ; ") { "'${it.title}'/'${it.artist}' ${it.durationText}" } + " (video ${song.durationText})"
        val videoFile = File(work, "${song.videoId}.audio")
        download(song.videoId, videoFile)?.let { return "$head | $lyr | $picks | NO-VIDEO-AUDIO ${it.take(60)}" }
        val video = features(videoFile) ?: return "$head | $lyr | DECODE-FAIL video"
        val tried = mutableListOf<String>()
        for (original in candidates) {
            val orig = "orig=${original.videoId} '${original.title}'/'${original.artist}' ${original.durationText}"
            val recordFile = File(work, "${original.videoId}.audio")
            download(original.videoId, recordFile)?.let { tried += "$orig NO-RECORD-AUDIO $it"; continue }
            val record = features(recordFile) ?: run { tried += "$orig DECODE-FAIL"; continue }
            val t1 = System.nanoTime()
            val result = ChromaAlignment.align(record, video)
            val verdict = ChromaAlignment.lastVerdict
            val alignMs = (System.nanoTime() - t1) / 1_000_000
            val sizes = "rec=${record.durationSeconds.roundToInt()}s vid=${video.durationSeconds.roundToInt()}s align=${alignMs}ms"
            if (result == null) {
                tried += "$orig $sizes $verdict"
                continue
            }
            val first = found.lines.first { it.text.isNotBlank() }
            val last = found.lines.last { it.text.isNotBlank() }
            val moved = "first ${first.timeMs}->${result.map.map(first.timeMs)} last ${last.timeMs}->${result.map.map(last.timeMs)}"
            return "$head | $lyr | ${tried.joinToString(" || ")}${if (tried.isEmpty()) "" else " || "}$orig $sizes | $verdict | $moved"
        }
        return "$head | $lyr | FAILED ${tried.joinToString(" || ")}"
    }

    private val downloaded = ConcurrentHashMap<String, String>()

    /** Null when [file] now holds the audio; otherwise why not. App's resolver first, yt-dlp second. */
    private suspend fun download(videoId: String, file: File): String? {
        downloaded[videoId]?.let { return it.takeIf { r -> r.isNotEmpty() } }
        val viaApp = runCatching { fetchWithResolver(videoId, file) }.exceptionOrNull()
        val why = if (viaApp == null && file.length() > 100_000) {
            null
        } else {
            val app = "app:${viaApp?.let { "${it::class.simpleName} ${it.message?.take(120)}" } ?: "short ${file.length()}"}"
            file.delete()
            val ytdlp = runCatching { fetchWithYtDlp(videoId, file) }.exceptionOrNull()
            if (ytdlp == null && file.length() > 100_000) null else "$app; yt-dlp:${ytdlp?.message?.take(160)}"
        }
        downloaded[videoId] = why.orEmpty()
        return why
    }

    private suspend fun fetchWithResolver(videoId: String, file: File) {
        val url = StreamResolver.resolve(videoId)
        val headers = StreamResolver.mediaHeadersFor(url)
        file.outputStream().use { sink ->
            var position = 0L
            var total = Long.MAX_VALUE
            while (position < total) {
                val end = position + CHUNK - 1
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    header("Range", "bytes=$position-$end")
                }.build()
                Http.client.newCall(request).execute().use { response ->
                    check(response.code == 206 || response.code == 200) { "HTTP ${response.code}" }
                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION", "USELESS_ELVIS")
                    val body = response.body!!
                    val range = response.header("Content-Range")
                    total = range?.substringAfterLast('/')?.toLongOrNull()
                        ?: body.contentLength().takeIf { response.code == 200 } ?: total
                    val bytes = body.bytes()
                    check(bytes.isNotEmpty()) { "empty body at $position" }
                    sink.write(bytes)
                    position += bytes.size
                    if (response.code == 200) total = position
                }
            }
        }
    }

    private fun fetchWithYtDlp(videoId: String, file: File) {
        val process = ProcessBuilder(
            "yt-dlp", "-q", "--no-warnings", "-f", "bestaudio", "-o", file.absolutePath, "--force-overwrites",
            "https://www.youtube.com/watch?v=$videoId",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${process.exitValue()}" }
    }

    /** Decoded by ffmpeg to 48 kHz mono float, the way the phone hands MediaCodec's output over. */
    private fun features(file: File): ChromaAlignment.Features? {
        val process = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", file.absolutePath, "-ac", "1", "-ar", "48000", "-f", "f32le", "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val extractor = ChromaAlignment.Extractor(48_000)
        val bytes = ByteArray(1 shl 16)
        val floats = FloatArray(bytes.size / 4)
        var carry = 0
        process.inputStream.use { input ->
            while (true) {
                val n = input.read(bytes, carry, bytes.size - carry)
                if (n < 0) break
                val have = carry + n
                val whole = have / 4
                ByteBuffer.wrap(bytes, 0, whole * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats, 0, whole)
                extractor.push(floats, whole)
                carry = have - whole * 4
                if (carry > 0) System.arraycopy(bytes, whole * 4, bytes, 0, carry)
            }
        }
        if (process.waitFor() != 0) return null
        return extractor.finish().takeIf { it.frames > 0 }
    }

    private companion object {
        const val CHUNK = 1_000_000L
    }
}

/** The sources whose timing is already the video's — mirrors the app's LyricsTiming.timedToVideo. */
private object LyricsSourceVideo {
    fun timedToVideo(source: LyricsSource?): Boolean = source == LyricsSource.YOUTUBE_TRANSCRIPT ||
        source == LyricsSource.YOUTUBE_MUSIC || source == LyricsSource.SIMP_MUSIC
}
