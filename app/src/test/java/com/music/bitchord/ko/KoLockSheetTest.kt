package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3: the lock screen and the car subtitle follow the sheet the lyrics screen chose for
 * the same video — a heard-words rejection, SheetChoice, a provider picked by hand, an
 * upgrade — instead of keeping the service's first lookup for the whole track.
 */
class KoLockSheetTest {

    private val sheetA = listOf(LyricLine(1_000L, "a one"), LyricLine(5_000L, "a two"))
    private val sheetB = listOf(LyricLine(1_200L, "b one"), LyricLine(6_000L, "b two"))

    @After
    fun reset() {
        KoLockSheet.forgetOtherThan("__none__")
    }

    private fun choice(id: String, lines: List<LyricLine>) =
        KoLockSheet.Choice(id, lines, LyricsSource.entries.first())

    @Test
    fun `the screen's sheet for the same video replaces the service's own`() {
        val chosen = choice("vid", sheetB)
        assertSame(chosen, KoLockSheet.pick("vid", sheetA, chosen))
    }

    @Test
    fun `the screen's sheet is taken when the service found none`() {
        val chosen = choice("vid", sheetB)
        assertSame(chosen, KoLockSheet.pick("vid", null, chosen))
    }

    @Test
    fun `a choice for another video is never shown`() {
        assertNull(KoLockSheet.pick("vid", sheetA, choice("other", sheetB)))
    }

    @Test
    fun `no choice keeps the service's own sheet`() {
        assertNull(KoLockSheet.pick("vid", sheetA, null))
    }

    @Test
    fun `the same lines keep the service's sheet and its timing`() {
        assertNull(KoLockSheet.pick("vid", sheetA, choice("vid", sheetA.toList())))
    }

    @Test
    fun `a rejection drops the sheet only when it is the one shown`() {
        val rejectA = KoLockSheet.Choice("vid", lines = null, rejected = sheetA)
        assertSame(rejectA, KoLockSheet.pick("vid", sheetA, rejectA))
        assertNull(KoLockSheet.pick("vid", sheetB, rejectA))
        assertNull(KoLockSheet.pick("vid", null, rejectA))
    }

    @Test
    fun `a choice for another video is forgotten when a video is loaded`() {
        KoLockSheet.chose("x", sheetB, null)
        KoLockSheet.forgetOtherThan("y")
        assertNull(KoLockSheet.chosen.value)

        KoLockSheet.chose("y", sheetB, null)
        KoLockSheet.forgetOtherThan("y")
        assertEquals("y", KoLockSheet.chosen.value?.videoId)
    }

    /** The service job: what it publishes, in order, as timing changes and screen choices arrive. */
    @Test
    fun `the service job adopts the screen's sheet and keeps re-timing it`() = runBlocking {
        val changes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val choices = MutableStateFlow<KoLockSheet.Choice?>(null)
        val published = mutableListOf<List<LyricLine>?>()
        val first = KoLockSheet.Sheet(sheetA, null, fromFile = false, recheckOverride = false, isVideo = true)
        suspend fun settle() = repeat(20) { yield() }

        val job = launch {
            KoLockSheet.follow("vid", isVideo = true, first = first, changes = changes, choices = choices) {
                published += it.raw
            }
        }
        settle()
        assertTrue("nothing new to publish yet", published.isEmpty())

        changes.tryEmit("vid")
        settle()
        assertEquals("a timing change re-times the service's sheet", listOf(sheetA), published.toList())

        choices.value = choice("other", sheetB)
        changes.tryEmit("other")
        settle()
        assertEquals("another video's choice and timing are ignored", 1, published.size)

        choices.value = choice("vid", sheetB)
        settle()
        assertEquals("the screen's sheet goes up", sheetB, published.last())

        changes.tryEmit("vid")
        settle()
        assertEquals("a later timing change re-times the adopted sheet", listOf(sheetA, sheetB, sheetB), published.toList())

        choices.value = KoLockSheet.Choice("vid", lines = null, rejected = sheetA)
        settle()
        assertEquals("rejecting a sheet that is not shown changes nothing", 3, published.size)

        choices.value = KoLockSheet.Choice("vid", lines = null, rejected = sheetB)
        settle()
        assertEquals("rejecting the shown sheet takes it down", 4, published.size)
        assertNull(published.last())

        changes.tryEmit("vid")
        settle()
        assertEquals("no sheet, nothing to re-time", 4, published.size)
        job.cancel()
    }

    @Test
    fun `an untimed sheet is not re-timed, but a chosen timed one is`() = runBlocking {
        val changes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val choices = MutableStateFlow<KoLockSheet.Choice?>(null)
        val published = mutableListOf<List<LyricLine>?>()
        val plain = listOf(LyricLine(0L, "plain"))
        val first = KoLockSheet.Sheet(plain, null, fromFile = false, recheckOverride = false, isVideo = false)
        suspend fun settle() = repeat(20) { yield() }

        val job = launch {
            KoLockSheet.follow("vid", isVideo = false, first = first, changes = changes, choices = choices) {
                published += it.raw
            }
        }
        settle()
        changes.tryEmit("vid")
        settle()
        assertTrue(published.isEmpty())

        choices.value = choice("vid", sheetB)
        settle()
        changes.tryEmit("vid")
        settle()
        assertEquals(listOf(sheetB, sheetB), published.toList())
        job.cancel()
    }

    @Test
    fun `a provider sheet gets the controller's timing rules`() {
        // publishLyrics: baseAllowed = timed, measuredAllowed = timed && !timedToVideo(source).
        val record = KoLockSheet.Sheet(sheetB, LyricsSource.entries.first { it != LyricsSource.YOUTUBE_TRANSCRIPT && it != LyricsSource.GEMINI_TRANSCRIPT }, false, false, isVideo = true)
        assertTrue(record.timeable && record.recordTimed)
        val captions = KoLockSheet.Sheet(sheetB, LyricsSource.YOUTUBE_TRANSCRIPT, false, false, isVideo = true)
        assertTrue(captions.timeable)
        assertTrue(!captions.recordTimed)
    }
}
