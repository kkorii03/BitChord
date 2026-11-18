package com.music.bitchord.ko

import com.music.bitchord.data.sources.SourceStream
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review 2, night 11-19 (ship/lanes/review2-night1119.md).
 *
 * R2-1: the cold-prefix rescue played the addon under the row's `#hifi` key
 * without the clean-slot rule every other writer of that key follows: bytes of
 * another candidate under it were continued with the addon's file, and an
 * audition or another parked upgrade filling it was written over. Now the
 * rescue refuses while the slot is someone else's, discards stale bytes on IO
 * (keeping them only when they are this same URL's) and skips when the
 * discard finds the slot still held.
 *
 * R2-2: a rescue declined because an Automix blend was running was dropped;
 * now it is offered once more on the crossfade's settled event.
 *
 * Old code: [KoCachedPrefixStart.slotRefusal], offerAfterBlend and
 * onBlendSettled do not exist (this class does not compile), and the source
 * checks fail (no discard, no slot check, no settled hook).
 */
class KoRescueSlotTest {
    @get:org.junit.Rule val koTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val id = "rescue-slot-row"
    private val addon = SourceStream(url = "https://addon.example/t.flac", sourceConfigId = "src", trackId = "trk")

    @Before
    fun reset() = KoCachedPrefixStart.resetForTest()

    @After
    fun restore() = KoCachedPrefixStart.resetForTest()

    // ---- R2-1 ----

    @Test
    fun anAuditionOwnsTheSlot() {
        assertNotNull(KoCachedPrefixStart.slotRefusal(auditioning = true, parkedUrl = null, rescueUrl = addon.url))
        assertNotNull(KoCachedPrefixStart.slotRefusal(auditioning = true, parkedUrl = addon.url, rescueUrl = addon.url))
    }

    @Test
    fun anotherParkedStreamOwnsTheSlot() {
        assertNotNull(
            KoCachedPrefixStart.slotRefusal(auditioning = false, parkedUrl = "https://other.example/x.flac", rescueUrl = addon.url),
        )
    }

    @Test
    fun anEmptyOrSameUrlSlotIsTheRescues() {
        assertNull(KoCachedPrefixStart.slotRefusal(auditioning = false, parkedUrl = null, rescueUrl = addon.url))
        assertNull(KoCachedPrefixStart.slotRefusal(auditioning = false, parkedUrl = addon.url, rescueUrl = addon.url))
    }

    @Test
    fun theRescueCleansTheSlotBeforeItParksTheAddon() {
        val rescue = source("src/main/java/com/music/bitchord/ko/KoCachedPrefixStart.kt")
            .substringAfter("private suspend fun PlaybackService.koRescueCachedPrefix(")
            .substringBefore("private fun PlaybackService.koRescueSlot(")
        val filled = rescue.indexOf("QualityUpgrade.forcedStream(Uri.parse(upgraded))?.url == stream.url")
        val discard = rescue.indexOf("filled || AudioCache.discardRendition(Uri.parse(upgraded))")
        val held = rescue.indexOf("if (!clean)")
        val recheck = rescue.indexOf("if (koRescueSlot(id, stream) != upgraded) return")
        val force = rescue.indexOf("QualityUpgrade.force(id, stream)")
        assertTrue("asked before force overwrites the record", filled in 0 until discard)
        assertTrue("a held slot stops the rescue", discard in 0 until held)
        assertTrue("checked again after the IO hop", held in 0 until recheck)
        assertTrue("all before the addon is parked", recheck in 0 until force)
        val io = rescue.substringAfter("withContext(Dispatchers.IO) {").substringBefore("if (!clean)")
        assertTrue("the discard runs off the main thread", io.contains("AudioCache.discardRendition("))
        val slot = source("src/main/java/com/music/bitchord/ko/KoCachedPrefixStart.kt")
            .substringAfter("private fun PlaybackService.koRescueSlot(")
        assertTrue(slot.contains("auditioning = QualityUpgrade.isAuditioning(id)"))
        assertTrue(slot.contains("parkedUrl = upgraded?.let { QualityUpgrade.forcedStream(Uri.parse(it))?.url }"))
    }

    // ---- R2-2 ----

    @Test
    fun aRescueDeclinedMidBlendIsOfferedOnceWhenItSettles() = runBlocking {
        val got = mutableListOf<KoCachedPrefixStart.Settled>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { KoCachedPrefixStart.settled.collect { got += it } }
        assertTrue(KoCachedPrefixStart.offerAfterBlend(id, addon))
        KoCachedPrefixStart.onBlendSettled()
        yield()
        assertEquals(listOf<KoCachedPrefixStart.Settled>(KoCachedPrefixStart.Settled.Rescue(id, addon)), got)
        assertFalse("one retry per play", KoCachedPrefixStart.offerAfterBlend(id, addon))
        KoCachedPrefixStart.onBlendSettled()
        yield()
        assertEquals("nothing parked twice", 1, got.size)
        job.cancel()
    }

    @Test
    fun aRowNoLongerAudibleLosesItsParkedRescue() = runBlocking {
        val got = mutableListOf<KoCachedPrefixStart.Settled>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { KoCachedPrefixStart.settled.collect { got += it } }
        assertTrue(KoCachedPrefixStart.offerAfterBlend(id, addon))
        KoCachedPrefixStart.keepOnly(setOf("another-row"))
        KoCachedPrefixStart.onBlendSettled()
        yield()
        assertTrue(got.isEmpty())
        assertTrue("a new play of the row gets its own retry", KoCachedPrefixStart.offerAfterBlend(id, addon))
        job.cancel()
    }

    @Test
    fun theCrossfadeSettledEventCarriesTheRetry() {
        val service = source("src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        val settled = service.substringAfter("            onSettled = {\n").substringBefore("            versionSwapActive = {")
        assertTrue(settled.contains("KoCachedPrefixStart.onBlendSettled()"))
        val slot = source("src/main/java/com/music/bitchord/ko/KoCachedPrefixStart.kt")
            .substringAfter("private fun PlaybackService.koRescueSlot(")
        assertTrue(slot.contains("transitioning && KoCachedPrefixStart.offerAfterBlend(id, stream)"))
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        val candidates = listOf(File(root, "app/$path"), File(root, path))
        return candidates.first { it.exists() }.readText().replace("\r\n", "\n")
    }
}
