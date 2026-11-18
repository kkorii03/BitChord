package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [KO] credit-drop. Phone 11-16 'Reality' (and 'Play Ugly', 'Will Last Forever'):
 * kkorii12 logged verdict=match, yet no stream opened and no sound check ran,
 * because the upgrade's shared-artist filter dropped the row without a word.
 */
class KoCreditDropLineTest {

    private val target = TrackMatcher.Target("Reality", "악동뮤지션", durationSec = 173)
    private val akmu = Song(videoId = "t_1", title = "Reality", artist = "AKMU", thumbnailUrl = null)
    private val other = Song(videoId = "t_2", title = "Reality", artist = "악동뮤지션", thumbnailUrl = null)

    @Test
    fun `a row the shared-artist filter drops is named`() {
        assertEquals(
            "kkorii12: 1 match(es) for 'Reality' dropped; no credit shared with '악동뮤지션': 'Reality' by 'AKMU'",
            creditDropLine("kkorii12", target, listOf(akmu, other), listOf(other)),
        )
    }

    /** The filter's answer as the phone logged it: only the Hangul credit shared. */
    private val shares: (Song) -> Boolean = { it.artist == target.artist }

    @Test
    fun `a walk with the sound check sends the unshared credit there`() {
        assertEquals(listOf(akmu), creditToSoundCheck(true, listOf(akmu, other), emptySet(), shares))
    }

    @Test
    fun `a walk without the sound check still drops it`() {
        assertEquals(emptyList<Song>(), creditToSoundCheck(false, listOf(akmu, other), emptySet(), shares))
    }

    @Test
    fun `a row already waiting for the sound check is not added twice`() {
        assertEquals(emptyList<Song>(), creditToSoundCheck(true, listOf(akmu), setOf(akmu), shares))
    }

    @Test
    fun `nothing dropped says nothing`() {
        assertNull(creditDropLine("kkorii12", target, listOf(other), listOf(other)))
    }
}
