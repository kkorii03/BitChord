package com.music.bitchord.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The audible row must not sit out the whole addon window when YouTube is
     * already ready. Order is recorded so a fix that awaits the addon first
     * (the old resolveDirect) fails this test: it would reach addon-end and
     * return the addon even though YouTube answered in the same turn.
     */
    @Test
    fun theAudibleRowTakesYouTubeWhenItAnswersBeforeTheAddon() = runBlocking {
        val order = mutableListOf<String>()
        val addon = async {
            order += "addon-start"
            delay(5_000)
            order += "addon-end"
            "addon"
        }
        val youtube = async {
            order += "youtube-start"
            Result.success("youtube")
        }
        val first = DirectSourceGate.firstAnswer(addon, youtube)
        assertTrue(first is DirectSourceGate.FirstAnswer.YouTube)
        assertEquals(listOf("addon-start", "youtube-start"), order)
        addon.cancel()
    }

    @Test
    fun aFastAddonStillWinsTheAudibleRow() = runBlocking {
        val order = mutableListOf<String>()
        val youtube = async {
            order += "youtube-start"
            delay(5_000)
            Result.success("youtube")
        }
        val addon = async {
            order += "addon-start"
            "addon"
        }
        val first = DirectSourceGate.firstAnswer(addon, youtube)
        assertTrue(first is DirectSourceGate.FirstAnswer.Addon)
        assertEquals("addon", (first as DirectSourceGate.FirstAnswer.Addon).stream)
        assertEquals(listOf("youtube-start", "addon-start"), order)
        youtube.cancel()
    }

    /** Only the row that is audible now races; the next one still waits for its addon. */
    @Test
    fun onlyTheAudibleRowIsCurrent() {
        DirectSourceGate.onPlaying(setOf("current"))
        try {
            DirectSourceGate.openNext("next")
            assertTrue(DirectSourceGate.isCurrent("current"))
            assertFalse(DirectSourceGate.isCurrent("next"))
        } finally {
            DirectSourceGate.onPlaying(emptySet())
        }
    }

    /**
     * A cached YouTube copy is ready to read now. The direct gate must not hold
     * its first note for an addon: the lookup is started beside playback and
     * the ordinary second look replaces the bytes if it finds a better copy.
     */
    @Test
    fun aCachedCurrentTrackStartsBeforeWaitingForAnAddon() {
        val root = File(".").canonicalFile
        val cache = listOf("app/", "").map {
            File(root, "${it}src/main/java/com/music/bitchord/playback/AudioCache.kt")
        }.first { it.exists() }.readText()
        val settle = cache.substringAfter("private fun settleDirectEntry(uri: Uri)")
            .substringBefore("try {")
        assertTrue(
            "the cached current row must be admitted before the blocking lookup",
            "DirectSourceGate.isCurrent(videoId)" in settle,
        )
        assertTrue(
            "the cached current row must start its lookup without awaiting it",
            "DirectSourceGate.startLookupNow(" in settle,
        )
    }
}
