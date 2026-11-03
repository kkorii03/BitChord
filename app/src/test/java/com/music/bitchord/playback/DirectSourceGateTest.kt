package com.music.bitchord.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Direct Source Stream's first-audio rule: when every addon is resting after
 * repeated failures, waiting the whole direct-source window can only end in
 * "no addon", while YouTube could have started at once.
 */
class DirectSourceGateTest {

    @Test
    fun allAddonsRestingSkipsTheWindow() {
        assertFalse(DirectSourceGate.directLookupWorthWaiting(listOf(true, true)))
    }

    @Test
    fun oneAwakeAddonStillDeservesTheWindow() {
        assertTrue(DirectSourceGate.directLookupWorthWaiting(listOf(true, false)))
        assertTrue(DirectSourceGate.directLookupWorthWaiting(listOf(false)))
    }

    @Test
    fun noDirectAddonsCannotProduceAnAnswer() {
        assertFalse(DirectSourceGate.directLookupWorthWaiting(emptyList()))
    }

    /**
     * The current track is the one the listener is waiting on. It gets one
     * short chance at an addon answer; the full read-ahead window belongs to a
     * track that is still next, where the current one's audio covers the wait.
     */
    @Test
    fun theCurrentTrackDoesNotWaitTheReadAheadWindow() {
        DirectSourceGate.onPlaying(setOf("current"))
        try {
            assertEquals(
                DirectSourceGate.CURRENT_LOOKUP_GRACE_MS,
                DirectSourceGate.lookupWaitMs("current"),
            )
            assertTrue(
                DirectSourceGate.lookupWaitMs("current") < DirectSourceGate.LOOKUP_TIMEOUT_MS,
            )
            assertEquals(
                DirectSourceGate.LOOKUP_TIMEOUT_MS,
                DirectSourceGate.lookupWaitMs("next"),
            )
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }

    /**
     * Fake clock and fake lookup, measuring the order of steps: the current
     * track's caller gives up at its short grace while the addon is still
     * searching, so YouTube can start. A read-ahead track keeps waiting and
     * gets the addon's answer.
     */
    @Test
    fun aCurrentLookupGivesUpBeforeASlowAddonAnswers() = runTest {
        DirectSourceGate.onPlaying(setOf("current"))
        try {
            val events = mutableListOf<String>()
            val direct = async {
                events += "direct started"
                delay(5_000)
                events += "direct answered"
                "addon-stream"
            }
            val result = DirectSourceGate.awaitLookup(
                work = direct,
                startedAtMs = 0L,
                waitMs = DirectSourceGate.lookupWaitMs("current"),
                nowMs = { testScheduler.currentTime },
            )
            assertNull("the current track must not wait for the slow addon", result)
            assertEquals(listOf("direct started"), events)
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }

    @Test
    fun aReadAheadLookupWaitsForTheAddon() = runTest {
        DirectSourceGate.onPlaying(setOf("current"))
        try {
            val events = mutableListOf<String>()
            val direct = async {
                events += "direct started"
                delay(5_000)
                events += "direct answered"
                "addon-stream"
            }
            val result = DirectSourceGate.awaitLookup(
                work = direct,
                startedAtMs = 0L,
                waitMs = DirectSourceGate.lookupWaitMs("next"),
                nowMs = { testScheduler.currentTime },
            )
            assertEquals("addon-stream", result)
            assertEquals(listOf("direct started", "direct answered"), events)
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }

    /**
     * A song version keeps the row's mediaId but streams another video id.
     * Both are current, and neither may be held for the read-ahead window.
     */
    @Test
    fun bothIdsOfACurrentSongVersionGetTheShortGrace() {
        DirectSourceGate.onPlaying(setOf("row-id", "song-version-id"))
        try {
            assertEquals(
                DirectSourceGate.CURRENT_LOOKUP_GRACE_MS,
                DirectSourceGate.lookupWaitMs("row-id"),
            )
            assertEquals(
                DirectSourceGate.CURRENT_LOOKUP_GRACE_MS,
                DirectSourceGate.lookupWaitMs("song-version-id"),
            )
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }

    /**
     * Phone 11-03: every first play hung. The track change only told the gate
     * about the new row once it had sounded, but its loader was the one held
     * at the gate, so it never sounded. The transition must tell the gate
     * even when the row is not audible yet.
     */
    @Test
    fun aTrackChangeAdmitsTheNewRowBeforeItSounds() {
        val root = File(".").canonicalFile
        val service = listOf("app/", "").map {
            File(root, "${it}src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        }.first { it.exists() }.readText()
        val hook = Regex(
            """if \(currentAudible\) \{\s*prefetchAround\(exoPlayer\)\s*\} else if \(DirectSourceGate\.isActive\(\)\) \{[^}]*driveDirectWindow\(\)""",
        )
        assertTrue("the track change must call driveDirectWindow when not yet audible", hook.containsMatchIn(service))
        val drive = service.substringAfter("private fun driveDirectWindow()").substringBefore("if (!DirectSourceGate.isActive()")
        assertTrue("driveDirectWindow must tell the gate before its isPlaying check", "DirectSourceGate.onPlaying(" in drive)
    }
}
