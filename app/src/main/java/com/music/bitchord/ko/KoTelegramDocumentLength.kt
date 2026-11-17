package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.StreamContainerSniff
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.telegram.TelegramAudioReader
import com.music.bitchord.telegram.TelegramClientManager
import com.music.bitchord.telegram.TelegramTrackMapper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [KO] A runtime for Telegram FLAC files posted as documents (review 11-17, C3a-1).
 *
 * TDLib's document carries no runtime, so a `Artist - Song.flac` file row had no
 * `durationText`, and every strict-length walk (the mid-song upgrade, hq-ahead,
 * the blocked start) refused it as `duration`: a lossless copy the listener's own
 * chat held could never be swapped in. The strict length check stays as it is;
 * this measures the row first. The FLAC's own STREAMINFO states the sample count
 * and rate in its first bytes, so [StreamContainerSniff.HEAD_BYTES] read under a
 * stream lease, the same way the download probe reads a Telegram head, gives the
 * real length.
 *
 * Only inside a strict walk, never at plain search: a row is measured only when it
 * already matches the wanted title and artist, says it is lossless and has no
 * runtime. At most [MAX_PROBES] per answer and [PROBE_BUDGET_MS] in all, so the
 * source's own deadline keeps room for the stream open. A measured length (or
 * "not a FLAC") is kept in memory by track id, so a walk never reads the same file
 * twice; a failed read keeps nothing and the row stays refused, exactly as before.
 */
internal object KoTelegramDocumentLength {

    private const val NOT_FLAC = 0
    private const val MAX_PROBES = 2
    private const val PROBE_BUDGET_MS = 5_000L
    private const val MEMO_MAX = 512
    private const val TAG = "BitChord"

    private val measured = ConcurrentHashMap<String, Int>()

    /** The runtime a FLAC [head] states, as the row's [Song.durationText]; [song] when it states none. */
    fun withHead(song: Song, head: ByteArray): Song {
        val seconds = StreamContainerSniff.flacSeconds(head) ?: return song
        return withSeconds(song, seconds)
    }

    /** Whether [song] is worth a head read for [target]: a lossless row with no runtime that matches by name. */
    fun needsLength(song: Song, target: TrackMatcher.Target): Boolean =
        song.durationText == null &&
            song.sourceQuality == "LOSSLESS" &&
            // Title and credited artist only; the runtime is what is missing.
            TrackMatcher.score(song, target.copy(durationSec = null)) != null

    /** Seconds already measured for Telegram [trackId], or null. For [TelegramSource.stream]. */
    fun seconds(trackId: String): Int? = measured[trackId]?.takeIf { it > 0 }

    /** [rows] with every runtime already measured filled in. No I/O. */
    fun known(source: MusicSource, rows: List<Song>): List<Song> =
        knownFor(telegramConfig(source), rows)

    /**
     * [rows] with their Telegram document runtimes, measuring the ones a strict
     * walk ([wanted]) would otherwise refuse. Any other source, or a walk that is
     * not strict, gets [rows] back untouched.
     */
    suspend fun measure(
        source: MusicSource,
        rows: List<Song>,
        target: TrackMatcher.Target,
        wanted: Boolean,
    ): List<Song> = measureRows(telegramConfig(source), rows, target, wanted) { readHead(it) }

    internal suspend fun measureRows(
        configId: String?,
        rows: List<Song>,
        target: TrackMatcher.Target,
        wanted: Boolean,
        readHead: suspend (String) -> ByteArray?,
    ): List<Song> {
        if (configId == null || rows.isEmpty()) return rows
        val filled = knownFor(configId, rows)
        if (!wanted) return filled
        val todo = filled
            .filter { needsLength(it, target) }
            .mapNotNull { trackIdOf(configId, it) }
            .filterNot { measured.containsKey(it) }
            .distinct()
            .take(MAX_PROBES)
        if (todo.isEmpty()) return filled
        withTimeoutOrNull(PROBE_BUDGET_MS) {
            for (id in todo) {
                val head = try {
                    readHead(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    TrackLog.d(TAG, "telegram: document length unread (${failure::class.simpleName})")
                    null
                } ?: continue
                remember(id, StreamContainerSniff.flacSeconds(head) ?: NOT_FLAC)
            }
        }
        return knownFor(configId, filled)
    }

    private fun telegramConfig(source: MusicSource): String? =
        source.configId.takeIf { source.kind == SourceKind.TELEGRAM }

    private fun knownFor(configId: String?, rows: List<Song>): List<Song> {
        if (configId == null || measured.isEmpty()) return rows
        return rows.map { song ->
            if (song.durationText != null) return@map song
            val id = trackIdOf(configId, song) ?: return@map song
            seconds(id)?.let { withSeconds(song, it) } ?: song
        }
    }

    private fun trackIdOf(configId: String, song: Song): String? {
        val (config, id) = SourceTrackKeys.parse(song.videoId) ?: return null
        return id.takeIf { config == configId && it.isNotBlank() }
    }

    private fun withSeconds(song: Song, seconds: Int): Song =
        TelegramTrackMapper.durationText(seconds)?.let { song.copy(durationText = it) } ?: song

    private fun remember(trackId: String, seconds: Int) {
        if (measured.size >= MEMO_MAX) measured.clear()
        measured[trackId] = seconds
    }

    /**
     * The file's first bytes through TDLib, under one balanced stream lease (a
     * search has already released its client), interrupted when the walk gives up.
     */
    private suspend fun readHead(trackId: String): ByteArray = runInterruptible(Dispatchers.IO) {
        TelegramClientManager.beginStream(trackId)
        try {
            TelegramAudioReader.open(
                trackId,
                0L,
                StreamContainerSniff.HEAD_BYTES.toLong(),
                TelegramAudioReader.REASON_VERIFY,
            ).use { reader ->
                val head = ByteArray(StreamContainerSniff.HEAD_BYTES)
                var filled = 0
                while (filled < head.size) {
                    val read = reader.read(head, filled, head.size - filled)
                    if (read == -1) break
                    filled += read
                }
                head.copyOf(filled)
            }
        } finally {
            TelegramClientManager.endStream(trackId)
        }
    }

    internal fun clearForTest() = measured.clear()
}
