package com.music.bitchord.ko

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.BoundedMemo
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.TranslationAhead
import com.music.bitchord.playback.fromAutoplay
import com.music.bitchord.playback.queueEntryId
import com.music.bitchord.playback.toSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [KO] autoplay-cover: takes an unmarked cover of a better known song out of
 * AutoPlay's queue before it plays. The decision is [KoAutoplayCover]'s; this is
 * its one search, its one sheet, and what is remembered about a row.
 *
 * Driven by next-track preparation once the next row's own sheet is in hand: no
 * timer, at most one catalogue search and one LRCLIB lookup per song and process.
 * Never for a row or a station the listener chose, a party, a video or a cover
 * station.
 */
internal object KoAutoplayCovers {

    /** Per videoId: what the comparison counted for a cover, [KEPT] for a row that stays. */
    private val verdicts = BoundedMemo<String, String>(256)

    private val pages = BoundedMemo<KoAutoplayCover.Ask, List<Song>>(32)

    fun rejected(videoId: String): Boolean = verdicts.get(videoId).let { it != null && it != KEPT }

    internal fun remember(videoId: String, verdict: KoAutoplayCover.Verdict) =
        verdicts.put(videoId, if (verdict.cover) verdict.detail else KEPT)

    internal fun counted(videoId: String): String = verdicts.get(videoId).orEmpty()

    /** A top-up's rows without the ones already found to be covers; a station the listener chose is left as it is. */
    fun withoutRejected(rows: List<Song>, seed: Song): List<Song> =
        if (exemptStation(seed)) rows else rows.filterNot { rejected(it.videoId) }

    private fun exemptStation(song: Song): Boolean =
        song.radioName != null || song.videoRadio || CoverTitles.isCover(song)

    /** The row the player goes to next when it is AutoPlay's own and may be dropped, or null. */
    fun droppableNext(player: ExoPlayer): Int? {
        val index = player.nextMediaItemIndex
        if (index == C.INDEX_UNSET || index <= player.currentMediaItemIndex || index >= player.mediaItemCount) return null
        val item = player.getMediaItemAt(index)
        if (!item.fromAutoplay || ListenTogether.state.value.inParty) return null
        val row = item.toSong()
        val current = player.currentMediaItem?.toSong() ?: return null
        val kept = row.isVideo || row.localUri != null || exemptStation(row) || exemptStation(current) ||
            LikeState.overrides.value[row.videoId] == LikeStatus.LIKE
        return index.takeUnless { kept }
    }

    /**
     * Whether the next row [song], with its own sheet [sheet], is such a cover.
     * Answers false whenever it cannot tell; a failure is asked again next time.
     */
    suspend fun isCover(context: Context, player: ExoPlayer?, song: Song, sheet: TranslationAhead.AheadLyrics?): Boolean {
        val index = player?.let(::droppableNext) ?: return false
        if (player.getMediaItemAt(index).mediaId != song.videoId) return false
        verdicts.get(song.videoId)?.let { return it != KEPT }
        val lines = sheet?.lines?.map { it.text } ?: return false
        if (AppSettings.lowPower.value || KoNetworkPolicy.isOffline(context)) return false
        return withTimeoutOrNull(CHECK_MS) { check(song, lines, sheet.searchedAs?.second) } ?: false
    }

    private suspend fun check(song: Song, lines: List<String>, searchedArtist: String?): Boolean =
        withContext(Dispatchers.IO) {
            fun keep(reason: String): Boolean {
                verdicts.put(song.videoId, KEPT)
                TrackLog.i("BitChord", "autoplay: cover check ${song.videoId} keep reason=$reason", about = song.videoId)
                return false
            }

            val own = KoAutoplayCover.words(lines)
            if (!KoAutoplayCover.enough(own)) return@withContext keep("few-words:${own.size}")
            if (KoAutoplayCover.borrowedSheet(song, searchedArtist)) return@withContext keep("borrowed-sheet")
            val ask = KoAutoplayCover.ask(song) ?: return@withContext keep("no-title")
            val candidate = KoAutoplayCover.candidate(song, page(ask)) ?: run {
                TrackLog.i("BitChord", "autoplay: cover check ${song.videoId} keep reason=no-answer", about = song.videoId)
                return@withContext false
            }
            val original = candidate.original ?: return@withContext keep(candidate.reason)
            // Only a source the listener has on; never the repository, whose answers are the screen's.
            if (LyricsSource.LRCLIB !in AppSettings.lyricsSources.value) return@withContext keep("lrclib-off")
            val sheet = LrcLib.lyrics(original.title, original.artist, original.durationMillis())
                ?: return@withContext keep("no-sheet")
            val verdict = KoAutoplayCover.compare(own, KoAutoplayCover.words(sheet.map { it.text }))
            if (!verdict.cover) return@withContext keep("words ${verdict.detail}")
            remember(song.videoId, verdict)
            true
        }

    /** The title's Songs page, catalogue rows only; null when the search failed. */
    private suspend fun page(ask: KoAutoplayCover.Ask): List<Song>? = pages.get(ask) ?: try {
        InnertubeParser.parseSearchPage(
            Innertube.searchAnonymous(
                query = ask.query,
                params = SearchFilter.SONGS.params,
                hl = ask.hl,
                gl = ask.gl,
                kind = SearchKind.COVER,
            ),
            includeVideos = false,
        ).rows.filterIsInstance<SearchResult.Track>().map { it.song }
            .also { if (it.isNotEmpty()) pages.put(ask, it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }

    /** The row a refill was already asked for while it was the only one ahead. */
    private var refillAskedFor: String? = null

    fun refillDue(rowId: String): Boolean = (rowId != refillAskedFor).also { refillAskedFor = rowId }

    private const val KEPT = ""

    /** The search and the sheet together; the check never holds the bundle longer. */
    private const val CHECK_MS = 10_000L
}

/**
 * Drops the next row when it is a cover [KoAutoplayCovers] rejected; true when it
 * is on its way out. Called by next-track preparation and on every queue edit, so
 * a drop put off (a blend, a version swap, nothing else queued) happens then.
 *
 * Posted, because the removal's own timeline event cancels the preparation that
 * asked for it. The only row ahead is never removed: a refill is asked for once
 * and its append lands here again, so a refill that fails leaves music playing.
 */
internal fun PlaybackService.koDropCoverNext(): Boolean {
    val rowId = koRejectedNextRow() ?: return false
    val exoPlayer = player ?: return false
    if (exoPlayer.mediaItemCount - exoPlayer.currentMediaItemIndex <= 2) {
        if (autoplayLoadJob?.isActive != true && KoAutoplayCovers.refillDue(rowId)) {
            autoplaySeed = null
            loadAutoplayForCurrentTrack()
        }
        return false
    }
    scope.launch {
        val live = player ?: return@launch
        // Everything again: the queue may have moved between the post and now.
        if (koRejectedNextRow() != rowId || live.mediaItemCount - live.currentMediaItemIndex <= 2) return@launch
        val index = live.nextMediaItemIndex
        val videoId = live.getMediaItemAt(index).mediaId
        TrackLog.i(
            "BitChord",
            "autoplay: dropped next $videoId cover of a better known song ${KoAutoplayCovers.counted(videoId)}",
            about = videoId,
        )
        live.removeMediaItem(index)
    }
    return true
}

/** The next row's id when it is a rejected cover and nothing is holding the queue, or null. */
private fun PlaybackService.koRejectedNextRow(): String? {
    val exoPlayer = player ?: return null
    if (isQueueUndoRestoring(exoPlayer) || versionSwapJob?.isActive == true || hqSwapOwnsStandby ||
        crossfade?.isTransitioning() == true
    ) {
        return null
    }
    val index = KoAutoplayCovers.droppableNext(exoPlayer) ?: return null
    val item = exoPlayer.getMediaItemAt(index)
    return (item.queueEntryId ?: item.mediaId).takeIf { KoAutoplayCovers.rejected(item.mediaId) }
}
