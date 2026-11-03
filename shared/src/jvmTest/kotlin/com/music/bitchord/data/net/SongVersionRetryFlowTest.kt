package com.music.bitchord.data.net

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.ko.KoSongVersion
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real before-audio timeout and post-first-sound retry, not just the
 * verdict store: the timed-out lookup must stay retryable and the retry must
 * run a second search whose found release is cached.
 */
class SongVersionRetryFlowTest {

    @Test
    fun `a timed out before-audio lookup is retried and the found release is cached`() = runBlocking {
        val videoId = "retry-flow-video"
        val releaseId = "retry-flow-release"
        val albumId = "retry-flow-album"
        // typeUnknown with a known album keeps the catalogue fallback off the
        // network; this test is about the lookup and retry, not the album pass.
        val song = Song(
            videoId = videoId,
            title = "Nanana",
            artist = "Peggy Gou",
            thumbnailUrl = null,
            typeUnknown = true,
            albumId = albumId,
        )
        val release = Song(
            videoId = releaseId,
            title = "Nanana",
            artist = "Peggy Gou",
            thumbnailUrl = null,
            albumId = albumId,
        )

        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val phase = AtomicReference("first")
        val firstCalls = AtomicInteger()
        val retryCalls = AtomicInteger()
        YtMusicRepository.audioVersionCache.remove(videoId)
        YtMusicRepository.searchOverride = { _, _, _ ->
            when (phase.get()) {
                "first" -> {
                    firstCalls.incrementAndGet()
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    Result.success(emptyList())
                }
                "retry" -> {
                    retryCalls.incrementAndGet()
                    Result.success(listOf(SearchResult.Track(release)))
                }
                else -> Result.success(emptyList())
            }
        }
        try {
            val timedOut = runCatching {
                withTimeout(500) {
                    YtMusicRepository.songVersion(song, beforeAudio = true)
                }
            }
            assertTrue(
                timedOut.exceptionOrNull() is TimeoutCancellationException,
                "the before-audio budget ends the wait, not the shared lookup",
            )
            withTimeout(5_000) { firstStarted.await() }
            releaseFirst.complete(Unit)
            // Let the shared lookup record its provisional miss and leave the
            // shared map, so the retry starts a fresh search instead of joining.
            withTimeout(5_000) {
                while (KoSongVersion.isSongVersionLookupRunning(videoId)) delay(10)
            }
            assertNull(YtMusicRepository.cachedSongVersionFor(song))

            // The lookup that outlived its waiter can still record a settled
            // miss before the retry runs; the retry has to clear that verdict.
            assertNull(YtMusicRepository.songVersion(song))
            assertTrue(YtMusicRepository.isSongVersionMissing(videoId))

            phase.set("retry")
            val found = YtMusicRepository.songVersion(song, retryTimedOut = true)

            assertEquals(releaseId, found?.videoId)
            assertEquals(releaseId, YtMusicRepository.cachedSongVersionFor(song)?.videoId)
            assertEquals(releaseId, YtMusicRepository.audioVersionCache[videoId]?.videoId)
            assertTrue(firstCalls.get() >= 1, "the before-audio lookup searched")
            assertTrue(retryCalls.get() >= 1, "the retry ran a second search")
        } finally {
            // Keep the fake in place until the shared lookup has finished its
            // own searches; then drop it so no later test sees it.
            releaseFirst.complete(Unit)
            withTimeoutOrNull(5_000) {
                while (KoSongVersion.isSongVersionLookupRunning(videoId)) delay(10)
            }
            YtMusicRepository.searchOverride = null
            YtMusicRepository.audioVersionCache.remove(videoId)
        }
    }
}
