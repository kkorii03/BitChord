package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gated Home used to resolve one shelf's square covers, await them, then
 * move to the next. Cover lookups share no inputs and [YouTubeCoverResolver]
 * keeps its own app-wide request gate, so that serial wait only added each
 * shelf's latency to a cold start. This test holds every selected shelf's
 * lookup until all of them have started; a sequential path deadlocks and fails
 * the timeout.
 */
class FallbackHomeCoverOverlapTest {

    private fun shelf(title: String, id: String) = HomeShelf(
        title = title,
        items = listOf(
            ShelfItem(
                title = "Song $id",
                subtitle = "Artist $id",
                thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                videoId = id,
                browseId = null,
            ),
        ),
    )

    @Test
    fun `cover lookups for independent shelves start before any finishes`() = runBlocking {
        val started = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val shelves = listOf(
            shelf("Recents", "a"),
            shelf("Quick picks", "b"),
            shelf("Liked music", "c"),
        )

        val result = withTimeout(2_000) {
            YouTubeFallback.enrichCoverShelves(shelves) { songs ->
                if (started.incrementAndGet() == shelves.size) allStarted.complete(Unit)
                allStarted.await()
                songs.map { it.copy(title = "Cover ${it.videoId}") }
            }
        }

        assertEquals(
            shelves.size,
            started.get(),
            "every selected shelf started before any awaited",
        )
        assertEquals(
            listOf("Cover a", "Cover b", "Cover c"),
            result.map { it.items.single().title },
        )
    }
}
