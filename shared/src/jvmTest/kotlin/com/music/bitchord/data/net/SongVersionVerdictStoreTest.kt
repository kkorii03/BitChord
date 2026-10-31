package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The repository-side verdict bookkeeping: a lookup cut short by a caller's
 * deadline must never become a cached "no version", while a search that really
 * ran to completion may be kept.
 */
class SongVersionVerdictStoreTest {

    private fun song(id: String = "song") = Song(
        videoId = id,
        title = "Nanana",
        artist = "Peggy Gou",
        thumbnailUrl = null,
    )

    @Test
    fun `a timed out lookup is not cached as a miss`() {
        val store = SongVersionVerdictStore()
        // The waiter gave up while the shared lookup was still running.
        assertFalse(store.onWaiterCancelled("video", answerCompleted = false))
        // The lookup then finishes with a provisional miss.
        assertTrue(store.store("video", null, definitive = false))
        assertFalse(store.isMissing("video"), "a cut-short miss must stay retryable")
        assertNull(store.cached("video"))
    }

    @Test
    fun `a miss reported as definitive after the waiter gave up is not cached`() {
        val store = SongVersionVerdictStore()
        // The lookup read "not waited out", then the waiter's deadline fired
        // before the verdict was written.
        store.onWaiterCancelled("video", answerCompleted = false)
        assertTrue(store.store("video", null, definitive = true))
        assertFalse(store.isMissing("video"), "a waived miss must stay retryable")
    }

    @Test
    fun `a found version still lands after the waiter gave up`() {
        val store = SongVersionVerdictStore()
        store.onWaiterCancelled("video", answerCompleted = false)
        assertFalse(store.store("video", song("audio"), definitive = true))
        assertNotNull(store.cached("video"))
    }

    @Test
    fun `a cancelled lookup is not cached as a miss`() {
        val store = SongVersionVerdictStore()
        store.onWaiterCancelled("video", answerCompleted = false)
        store.store("video", null, definitive = false)
        assertEquals(SongVersionVerdictStore.Miss.NONE, store.settleMiss("video"))
    }

    @Test
    fun `a completed search with zero candidates is cached`() {
        val store = SongVersionVerdictStore()
        assertFalse(store.store("video", null, definitive = true))
        assertTrue(store.isMissing("video"))
        assertEquals(SongVersionVerdictStore.Miss.SETTLED, store.settleMiss("video"))
    }

    @Test
    fun `a found recording is kept even after the waiter gave up`() {
        val store = SongVersionVerdictStore()
        store.onWaiterCancelled("video", answerCompleted = false)
        store.store("video", song("catalogue"), definitive = true)
        assertNotNull(store.cached("video"))
        assertFalse(store.isMissing("video"))
    }

    @Test
    fun `a provisional answer owes its one retry`() {
        val store = SongVersionVerdictStore()
        store.recordAnswer("video", null, definitive = false)
        assertTrue(store.isRetrySuggested("video"))
        store.recordAnswer("video", null, definitive = true)
        assertFalse(store.isRetrySuggested("video"))
    }

    @Test
    fun `the retry search does not inherit the old lookup's waiver`() {
        val store = SongVersionVerdictStore()
        store.onWaiterCancelled("video", answerCompleted = false)
        store.beginFreshSearch("video")
        store.store("video", null, definitive = true)
        assertTrue(store.isMissing("video"), "the retry's completed search is a verdict")
    }

    @Test
    fun `an answer landing at the deadline reopens its stored miss`() {
        val store = SongVersionVerdictStore()
        store.store("video", null, definitive = true)
        assertTrue(store.onWaiterCancelled("video", answerCompleted = true))
        assertFalse(store.isMissing("video"))
    }
}
