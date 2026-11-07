package com.music.bitchord.ui.player

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ko.ui.player.KoTapReading
import com.music.bitchord.ko.ui.player.KoTapSync
import com.music.bitchord.ko.ui.player.koTapEventNanos
import com.music.bitchord.ko.ui.player.koTapLine
import com.music.bitchord.ko.ui.player.koTapReadingAgeMs
import com.music.bitchord.ko.ui.player.koTapSheetKey
import com.music.bitchord.ko.ui.player.koTapSheetLines
import com.music.bitchord.ko.ui.player.koTapSongMs
import com.music.bitchord.ko.ui.player.koTapSourceLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KoTapSyncTest {

    @Test
    fun `a playing reading is carried forward to the tap at the playing speed`() {
        val sampledAt = 10_000_000_000L
        val tap = sampledAt + 300_000_000L // 300 ms later
        assertEquals(60_300L, koTapSongMs(60_000L, sampledAt, tap, moving = true, speed = Float.NaN))
        assertEquals(60_450L, koTapSongMs(60_000L, sampledAt, tap, moving = true, speed = 1.5f))
        assertEquals(300L, koTapReadingAgeMs(sampledAt, tap))
    }

    @Test
    fun `a reading taken after the tap is carried back`() {
        val tap = 10_000_000_000L
        val sampledAt = tap + 200_000_000L
        assertEquals(59_800L, koTapSongMs(60_000L, sampledAt, tap, moving = true, speed = 1f))
        assertEquals(-200L, koTapReadingAgeMs(sampledAt, tap))
    }

    @Test
    fun `a stopped song or no reading at all is the reading as it stands`() {
        val sampledAt = 10_000_000_000L
        val tap = sampledAt + 5_000_000_000L
        assertEquals(60_000L, koTapSongMs(60_000L, sampledAt, tap, moving = false, speed = 1f))
        assertEquals(60_000L, koTapSongMs(60_000L, 0L, tap, moving = true, speed = 1f))
        assertEquals(-1L, koTapReadingAgeMs(0L, tap))
    }

    @Test
    fun `the event stamp is used when it is on the nanoTime clock`() {
        val now = 50_000_000_000L
        assertEquals(49_990_000_000L, koTapEventNanos(49_990L, now))
        // A desktop stamp on another clock, or one from the future: the handling time.
        assertEquals(now, koTapEventNanos(1_700_000_000_000L, now))
        assertEquals(now, koTapEventNanos(10L, now))
    }

    @Test
    fun `a tap line is numbers only and in the agreed shape`() {
        val line = koTapLine("abc123", 12, KoTapReading(posMs = 61_234, drawnMs = 61_100, line = 7, sampledMs = 180), "LRCLIB")
        assertEquals("abc123 n=12 pos=61234 drawn=61100 line=7 src=LRCLIB sampled=180", line)
        assertEquals("Better_Lyrics", koTapSourceLabel(" Better Lyrics "))
        assertEquals("none", koTapSourceLabel(null))
        assertEquals("none", koTapSourceLabel("  "))
    }

    @Test
    fun `the sheet is logged as line times and never as words`() {
        val lines = listOf(LyricLine(0L, ""), LyricLine(12_340L, "secret words"), LyricLine(15_000L, "more words"))
        val out = koTapSheetLines("vid", lines)
        assertEquals(
            listOf("vid sheet lines=3", "vid sheet n=0 t=0 gap", "vid sheet n=1 t=12340", "vid sheet n=2 t=15000"),
            out,
        )
        assertTrue(out.none { it.contains("words") })
        // Same times, other words (a translation): the same sheet. Other times: a new one.
        val translated = lines.map { it.copy(text = if (it.isGap) "" else "x") }
        assertEquals(koTapSheetKey(lines), koTapSheetKey(translated))
        assertNotEquals(koTapSheetKey(lines), koTapSheetKey(lines.map { it.copy(timeMs = it.timeMs + 100) }))
    }

    @Test
    fun `a session logs start, sheet, taps and stop for its own song only`() {
        val lines = listOf(LyricLine(1_000L, "never logged"), LyricLine(4_000L, "also never logged"))
        val tap = KoTapReading(posMs = 1_010, drawnMs = 990, line = 0, sampledMs = 120)
        KoTapSync.source = "LRCLIB"
        // Off: a tap records nothing.
        KoTapSync.record("tsA", lines, 0, tap)
        assertEquals(0, KoTapSync.taps)

        KoTapSync.set(true)
        KoTapSync.ensureSession("tsA", lines, 0)
        KoTapSync.ensureSession("tsA", lines, 0) // a second panel on the same song: nothing new
        KoTapSync.record("tsA", lines, 0, tap)
        KoTapSync.record("tsA", lines, 0, tap.copy(posMs = 4_020, line = 1))
        assertEquals(2, KoTapSync.taps)
        // The next song closes the first.
        KoTapSync.ensureSession("tsB", lines, 0)
        assertEquals(0, KoTapSync.taps)
        KoTapSync.set(false)
        assertFalse(KoTapSync.on)

        val log = TrackLog.everything().filter { it.contains("tap-sync:") }
        assertEquals(1, log.count { it.endsWith("tap-sync: tsA start off=0") })
        assertEquals(1, log.count { it.endsWith("tap-sync: tsA sheet lines=2") })
        assertTrue(log.any { it.endsWith("tap-sync: tsA n=1 pos=1010 drawn=990 line=0 src=LRCLIB sampled=120") })
        assertTrue(log.any { it.endsWith("tap-sync: tsA n=2 pos=4020 drawn=990 line=1 src=LRCLIB sampled=120") })
        assertTrue(log.any { it.endsWith("tap-sync: tsA stop taps=2") })
        assertTrue(log.any { it.endsWith("tap-sync: tsB start off=0") })
        assertTrue(log.any { it.endsWith("tap-sync: tsB stop taps=0") })
        assertTrue(log.none { it.contains("never logged") })
    }
}
