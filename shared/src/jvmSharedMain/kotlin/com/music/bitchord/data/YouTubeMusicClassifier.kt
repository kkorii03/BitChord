package com.music.bitchord.data

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Decides which rows of a youtube.com history feed are music.
 *
 * The signed-in youtube.com history is one list of every video the account
 * watched, music or not. The Music-gated fallback used the history shelf for
 * "Recently played", so ordinary uploads leaked in. The cheap signals that
 * settle most rows without a request are the ones YouTube Music itself puts on
 * a music upload: a `- Topic` channel, a VEVO channel, or metadata the parser
 * already recognised as music. Everything else gets exactly one anonymous WEB
 * player lookup for the video category.
 *
 * A lookup that fails is not evidence of anything, so its row is kept; only a
 * category that YouTube states as something other than "Music" hides a row.
 * The answer is persisted per video id (bounded by [MAX_ENTRIES]) so the cost
 * is paid once per video rather than once per Home load.
 */
internal object YouTubeMusicClassifier {

    enum class Verdict { MUSIC, NOT_MUSIC, UNKNOWN }

    const val MAX_ENTRIES = 2_000

    /** Requests in flight at once while judging a page; battery over latency. */
    const val MAX_CONCURRENT = 4

    /**
     * How long a failed/unknown lookup is trusted before asking again. An
     * unknown verdict never hides a row, so this only bounds pointless retries.
     */
    private const val UNKNOWN_RECHECK_MS = 6L * 60 * 60 * 1000

    private const val STORE_KEY = "youtube_music_verdicts_v1"

    private val cache = StoredVerdictCache(STORE_KEY, MAX_ENTRIES)

    /** Replaceable so the rules and the cache can be tested without a network. */
    @Volatile
    internal var categoryLookup: suspend (String) -> String? = { Innertube.playerCategory(it) }

    /**
     * Anything this long is a programme/full musical, not a song: the listener
     * liked a video, not a track. Checked before the channel rules so a
     * `- Topic` upload of a whole concert is hidden too.
     */
    const val MAX_SONG_SECONDS = 15 * 60

    /** Whether a row's own metadata settles it without a request, or null when it does not. */
    fun ruleVerdict(
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Int? = null,
    ): Verdict? {
        if (durationSec != null && durationSec > MAX_SONG_SECONDS) return Verdict.NOT_MUSIC
        val channel = artist.trim()
        if (!album.isNullOrBlank()) return Verdict.MUSIC
        if (channel.endsWith("- Topic", ignoreCase = true)) return Verdict.MUSIC
        if (channel.contains("VEVO", ignoreCase = true)) return Verdict.MUSIC
        return null
    }

    fun categoryVerdict(category: String?): Verdict = when {
        category.isNullOrBlank() -> Verdict.UNKNOWN
        category.trim().equals("Music", ignoreCase = true) -> Verdict.MUSIC
        else -> Verdict.NOT_MUSIC
    }

    /** The stored verdict, or null when there is none or it is too old to trust. */
    internal fun cached(videoId: String, now: Long = System.currentTimeMillis()): Verdict? {
        cache.ensureLoaded(MusicGateFallback.sharedStore())
        val entry = cache.get(videoId) ?: return null
        val verdict = runCatching { Verdict.valueOf(entry.value) }.getOrNull() ?: return null
        if (verdict == Verdict.UNKNOWN && now - entry.at >= UNKNOWN_RECHECK_MS) return null
        return verdict
    }

    internal fun remember(
        videoId: String,
        verdict: Verdict,
        now: Long = System.currentTimeMillis(),
    ) {
        if (videoId.isBlank()) return
        cache.ensureLoaded(MusicGateFallback.sharedStore())
        cache.put(videoId, verdict.name, now)
    }

    /**
     * Whether [videoId] is a music row. Network failure keeps the row (true),
     * because an unknown row is a missing answer, not a negative one.
     */
    suspend fun isMusic(
        videoId: String,
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Int? = null,
    ): Boolean {
        ruleVerdict(title, artist, album, durationSec)?.let { verdict ->
            if (cached(videoId) == null) remember(videoId, verdict)
            return verdict != Verdict.NOT_MUSIC
        }
        when (cached(videoId)) {
            Verdict.MUSIC -> return true
            Verdict.NOT_MUSIC -> return false
            Verdict.UNKNOWN -> return true
            null -> Unit
        }
        val category = runCatching { categoryLookup(videoId) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
        val verdict = categoryVerdict(category)
        remember(videoId, verdict)
        return verdict != Verdict.NOT_MUSIC
    }

    /**
     * The rows of the first [limit] that are music, followed by the rest of the
     * page untouched.
     *
     * Only the part of a page that is actually shown is judged: a full history
     * response can be long, and a lookup per row would be a page load's worth
     * of requests for rows nobody has scrolled to yet. Rows beyond the shown
     * page are left in place rather than hidden, because they have not been
     * asked about and an unasked row is not evidence of anything.
     */
    suspend fun filterMusic(songs: List<Song>, limit: Int = 30): List<Song> {
        if (limit <= 0) return songs
        val shown = songs.take(limit)
        if (shown.isEmpty()) return songs
        val gate = Semaphore(MAX_CONCURRENT)
        val judged = coroutineScope {
            val verdicts = shown.map { song ->
                async {
                    gate.withPermit {
                        isMusic(
                            song.videoId,
                            song.title,
                            song.artist,
                            song.albumName,
                            com.music.bitchord.data.sources.TrackMatcher.secondsOf(song.durationText),
                        )
                    }
                }
            }.awaitAll()
            shown.filterIndexed { index, song -> verdicts[index] || CoverTitles.isCover(song) }
        }
        return judged + songs.drop(shown.size)
    }

    /**
     * Separates the rows of the first [limit] that are music from the videos
     * that are not, keeping the page's order inside each half. Used where the
     * list must stay complete but the video rows should be hidden — Liked
     * Music — rather than dropped irrecoverably.
     */
    suspend fun splitMusic(songs: List<Song>, limit: Int = 30): Pair<List<Song>, List<Song>> {
        if (limit <= 0) return songs to emptyList()
        val shown = songs.take(limit)
        if (shown.isEmpty()) return songs to emptyList()
        val gate = Semaphore(MAX_CONCURRENT)
        val verdicts = coroutineScope {
            shown.map { song ->
                async {
                    gate.withPermit {
                        isMusic(
                            song.videoId,
                            song.title,
                            song.artist,
                            song.albumName,
                            com.music.bitchord.data.sources.TrackMatcher.secondsOf(song.durationText),
                        )
                    }
                }
            }.awaitAll()
        }
        val music = ArrayList<Song>(shown.size)
        val hidden = ArrayList<Song>()
        shown.forEachIndexed { index, song ->
            // A cover is a music upload even when YouTube files it under a
            // broad category; it stays visible and the display cleaner names
            // it as the cover it is.
            if (verdicts[index] || CoverTitles.isCover(song)) music += song else hidden += song
        }
        music += songs.drop(shown.size)
        return music to hidden
    }

    internal fun cacheSize(): Int = cache.size()

    @Synchronized
    internal fun resetForTest(store: MusicGateFallback.Store? = null) {
        cache.resetForTest()
        store?.let { cache.ensureLoaded(it) }
        categoryLookup = { Innertube.playerCategory(it) }
    }
}
