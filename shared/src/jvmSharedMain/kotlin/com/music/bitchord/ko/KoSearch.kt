package com.music.bitchord.ko

import com.music.bitchord.data.YtMusicRepository.SearchPage
import com.music.bitchord.data.model.Song

/** Search fallback helpers kept out of the upstream file. */
internal object KoSearch {

    /**
     * The failure to report when Music answered with an empty page and the
     * youtube.com fallback failed.
     *
     * A failed fallback used to be indistinguishable from a fallback that
     * genuinely matched nothing, so the Songs/VIDEOS tabs showed "no results"
     * for a connection that never completed. An actual empty answer still stays
     * a successful empty page. When Music itself failed, its own failure is the
     * one [searchPage] reports, so nothing is chosen here.
     */
    fun fallbackFailureWhenMusicHasNoRows(
        music: Result<SearchPage>,
        fallback: Result<List<Song>>,
    ): Throwable? = if (music.isSuccess && fallback.isFailure) fallback.exceptionOrNull() else null
}
