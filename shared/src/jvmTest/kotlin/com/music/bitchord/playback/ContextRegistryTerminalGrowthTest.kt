package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A continuation that ends in [ContinuationState.BLOCKED] without adding songs
 * is still a terminal state change. AutoPlay's recheck hangs off that event:
 * when the last row is already current there is no item transition left, so a
 * silent rejection leaves an enabled AutoPlay queue empty.
 */
class ContextRegistryTerminalGrowthTest {
    @Test
    fun blockedTerminalPageNotifiesListenersEvenWithoutGrowth() {
        val growth = mutableListOf<ContextSnapshot>()
        val registry = ContextRegistry { growth += it }
        registry.register("context-a", "playlist:one", "VLone", listOf(song("a")), "page-1")
        val request = assertNotNull(registry.claimContinuation())

        val rejected = registry.complete(
            request,
            ContinuationPage(listOf(song("a")), "page-2"),
        )

        assertEquals(ContinuationResultStatus.REJECTED_NO_PROGRESS, rejected.status)
        assertEquals(ContinuationDiagnostic.DUPLICATE_PAGE, rejected.diagnostic)
        assertEquals(ContinuationState.BLOCKED, rejected.snapshot!!.continuationState)
        assertTrue(rejected.addedSongs.isEmpty())
        assertEquals(1, growth.size)
        assertEquals(ContinuationState.BLOCKED, growth.single().continuationState)
        assertEquals(listOf("a"), growth.single().songs.map(Song::videoId))
    }

    @Test
    fun noProgressTerminalPageAlsoNotifiesListeners() {
        val growth = mutableListOf<ContextSnapshot>()
        val registry = ContextRegistry { growth += it }
        registry.register("context-a", "playlist:one", "VLone", listOf(song("a")), "page-1")
        val request = assertNotNull(registry.claimContinuation())

        val rejected = registry.complete(request, ContinuationPage(emptyList(), "page-2"))

        assertEquals(ContinuationResultStatus.REJECTED_NO_PROGRESS, rejected.status)
        assertEquals(ContinuationDiagnostic.NO_PROGRESS, rejected.diagnostic)
        assertEquals(1, growth.size)
        assertEquals(ContinuationState.BLOCKED, growth.single().continuationState)
    }

    private fun song(id: String) = Song(
        videoId = id,
        title = id,
        artist = "Artist",
        thumbnailUrl = null,
    )
}
