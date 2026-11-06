package com.music.bitchord.playback

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The moment the queue moves on is also the moment the bundle starts on the row
 * after. On a crossfade both happen before the new row reads what was prepared
 * for it (PlaybackService schedules the next bundle, then loads the lyrics): 77
 * of the listener's starts on 11-06 went this way, and each found the cache empty.
 */
class NextPrepHandoverTest {

    private fun ready(id: String) = NextPrepCache.Ready(
        videoId = id,
        raw = emptyList(),
        shown = emptyList(),
        source = null,
        searchedAs = null,
        isVideo = false,
        fromFile = false,
        localFile = false,
        lyricsSettings = "s",
        audioSource = "$id|stream",
        substituted = false,
    )

    private fun read(id: String) = NextPrepCache.get(
        id,
        localFile = false,
        isVideo = false,
        lyricsSettings = "s",
        audioSource = "$id|stream",
        substituted = false,
    )

    @After
    fun tearDown() = NextPrepCache.clear()

    @Test
    fun `the row that just became current keeps what was prepared for it`() {
        NextPrepCache.put(ready("aaaaaaaaaaa"))
        NextPrepCache.clearUnlessFor("aaaaaaaaaaa")
        assertNotNull(read("aaaaaaaaaaa"))
    }

    @Test
    fun `a row neither next nor playing loses it`() {
        NextPrepCache.put(ready("aaaaaaaaaaa"))
        NextPrepCache.clearUnlessFor("bbbbbbbbbbb")
        assertNull(read("aaaaaaaaaaa"))
    }

    @Test
    fun `nothing playing clears it`() {
        NextPrepCache.put(ready("aaaaaaaaaaa"))
        NextPrepCache.clearUnlessFor(null)
        assertNull(read("aaaaaaaaaaa"))
    }
}
