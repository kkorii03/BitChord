package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles

/**
 * The call arguments and kept answer of a downloaded file's sync re-check,
 * shared by the lyrics screen and the next-row bundle so both ask the same
 * question and read the same outcome.
 */
object DownloadedRecheck {

    data class Args(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
        val alternatives: List<Pair<String, String>>,
        val artistKnown: Boolean,
        val artistAliases: List<String>,
        val providerUnknown: Boolean,
    )

    fun args(
        query: CoverTitles.LyricsQuery,
        durationMs: Long,
        album: String?,
        providerUnknown: Boolean,
    ): Args = Args(
        title = query.title,
        artist = query.artist,
        durationMs = durationMs,
        album = album,
        alternatives = query.alternatives,
        artistKnown = query.artistKnown,
        artistAliases = query.artistAliases,
        providerUnknown = providerUnknown,
    )

    class Kept(
        val lines: List<LyricLine>?,
        val source: LyricsSource?,
        val searchedAs: Pair<String, String>?,
        val recheckOverride: Boolean,
    )

    /**
     * What next-prep keeps after the check. A fresh sheet wins only when the
     * check actually recorded it; a no-answer, a skipped guard or a replaced
     * file leaves the file's own lines untouched. The override may only claim
     * the file was re-checked when an answer was recorded now or by an earlier
     * run, so an unverified file keeps its `file-timed` refusal.
     */
    fun keep(
        originalLines: List<LyricLine>?,
        originalSource: LyricsSource?,
        originalSearchedAs: Pair<String, String>?,
        originalRecheckOverride: Boolean,
        replacement: LyricsRepository.Result?,
        checkRecorded: Boolean,
    ): Kept = Kept(
        lines = replacement?.lines ?: originalLines,
        source = replacement?.source ?: originalSource,
        searchedAs = replacement?.searchedAs ?: originalSearchedAs,
        recheckOverride = originalRecheckOverride || checkRecorded,
    )
}
