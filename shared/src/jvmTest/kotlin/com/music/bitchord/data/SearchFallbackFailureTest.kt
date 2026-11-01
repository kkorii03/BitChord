package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The youtube.com fallback used to turn every transport/parse failure into an
 * empty list, so the Songs/VIDEOS search tabs showed "no results" for a broken
 * connection. Only a fallback that actually answered with nothing is a real
 * empty result; a failed one has to reach the screen as an error with a retry.
 */
class SearchFallbackFailureTest {

    private val emptyMusicPage = Result.success(
        YtMusicRepository.SearchPage(rows = emptyList(), continuation = null),
    )

    @Test
    fun `a failed fallback is reported when music had no rows`() {
        val failure = IOException("connect timed out")

        val reported = YtMusicRepository.fallbackFailureWhenMusicHasNoRows(
            emptyMusicPage,
            Result.failure(failure),
        )

        assertSame(failure, reported)
    }

    @Test
    fun `a fallback that answered with no rows stays a real empty result`() {
        assertNull(
            YtMusicRepository.fallbackFailureWhenMusicHasNoRows(
                emptyMusicPage,
                Result.success(emptyList()),
            ),
        )
    }

    @Test
    fun `a fallback with rows never becomes a failure`() {
        val song = Song(
            videoId = "fallback-song",
            title = "Fallback song",
            artist = "Artist",
            thumbnailUrl = null,
        )

        assertNull(
            YtMusicRepository.fallbackFailureWhenMusicHasNoRows(
                emptyMusicPage,
                Result.success(listOf(song)),
            ),
        )
    }

    @Test
    fun `music's own failure is the one that gets reported`() {
        assertNull(
            YtMusicRepository.fallbackFailureWhenMusicHasNoRows(
                Result.failure(IllegalStateException("music failed")),
                Result.failure(IOException("fallback failed")),
            ),
        )
    }
}
