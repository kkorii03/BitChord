package com.music.bitchord.ko

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** LOG-1117 G7: `kept (none)` twice ~3 s apart for every upcoming row. */
class KoRepeatLogTest {

    @Test
    fun `the same line for the same row inside the window is written once`() {
        val line = "song version: repeatA001 kept (none)"
        assertTrue(KoRepeatLog.shouldLog("repeatA001", line, nowMs = 1_000L))
        assertFalse(KoRepeatLog.shouldLog("repeatA001", line, nowMs = 4_000L))
    }

    @Test
    fun `a replay past the window logs again and the window does not slide`() {
        val line = "song version: repeatB001 kept (none)"
        assertTrue(KoRepeatLog.shouldLog("repeatB001", line, nowMs = 0L))
        assertFalse(KoRepeatLog.shouldLog("repeatB001", line, nowMs = KoRepeatLog.WINDOW_MS - 1))
        assertTrue(KoRepeatLog.shouldLog("repeatB001", line, nowMs = KoRepeatLog.WINDOW_MS))
    }

    @Test
    fun `a changed line or another row is written`() {
        assertTrue(KoRepeatLog.shouldLog("repeatC001", "song version: repeatC001 kept (none)", nowMs = 0L))
        assertTrue(
            KoRepeatLog.shouldLog("repeatC001", "song version: repeatC001 kept (local file, none)", nowMs = 1L),
        )
        assertTrue(KoRepeatLog.shouldLog("repeatC002", "song version: repeatC002 kept (none)", nowMs = 2L))
    }
}
