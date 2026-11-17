package com.music.bitchord.data.innertube

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Review RG5-1: a version swap while the YouTube history entry was still
 * opening renamed the claim, so the open in flight saw itself "superseded" and
 * dropped the session, and its cleanup never released the renamed claim: the
 * new version could not open one either, and the listen sent no watchtime.
 */
class PlaybackTrackerSwapWhileOpeningTest {

    private val openingField = PlaybackTracker::class.java.getDeclaredField("opening").apply { isAccessible = true }
    private val renamedField = PlaybackTracker::class.java.getDeclaredField("openingRenamedFrom").apply { isAccessible = true }

    private val mv = "MVMVMVMVMV1"
    private val song = "SONGSONGSO1"

    @Before
    fun setUp() {
        openingField.set(null, null)
        renamedField.set(null, null)
    }

    @After
    fun tearDown() {
        setUp()
    }

    @Test
    fun `the open in flight publishes under the version now playing`() {
        openingField.set(null, mv) // onPlaying(mv) claimed it

        PlaybackTracker.onVersionSwapped(mv, song)

        assertEquals(song, PlaybackTracker.openTargetFor(mv))
        assertEquals(song, PlaybackTracker.openTargetFor(song))
    }

    @Test
    fun `the renamed claim is released when the open ends`() {
        openingField.set(null, mv)
        PlaybackTracker.onVersionSwapped(mv, song)

        PlaybackTracker.finishOpening(mv)

        // Released, so the song's own onPlaying can open a session if this one failed.
        assertNull(openingField.get(null))
    }

    @Test
    fun `another track still supersedes the open`() {
        openingField.set(null, "OTHERTRACK1") // a real transition's onPlaying

        assertNull(PlaybackTracker.openTargetFor(mv))
        PlaybackTracker.finishOpening(mv)
        assertEquals("OTHERTRACK1", openingField.get(null))
    }
}
