package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoDiagnosticsTest {

    @Test
    fun `the target is the callback that ran, not the handler wrapper`() {
        assertEquals(
            "com.music.bitchord.MainActivity.recompose",
            messageTarget(
                ">>>>> Dispatching to Handler (android.os.Handler) {abc123} " +
                    "com.music.bitchord.MainActivity.recompose: 0",
            ),
        )
    }

    @Test
    fun `a line without a handler still names what was dispatched`() {
        assertEquals("something", messageTarget(">>>>> Dispatching to something: 1"))
        assertEquals("unknown", messageTarget(null))
    }

    @Test
    fun `a stall line starts with the prefix featurehealth reads`() {
        assertEquals("main-stall: 245ms Choreographer#doFrame", stallLine(245, "Choreographer#doFrame"))
    }

    @Test
    fun `a message running past the threshold is reported from another thread`() {
        assertTrue(shouldReportHang(onMain = false, nowMs = 16_000, startedAtMs = 10_000, reportedFor = 0))
        assertEquals("main-hang: 29000ms Choreographer#doFrame", hangLine(29_000, "Choreographer#doFrame"))
    }

    @Test
    fun `a short message, an idle loop or the main thread itself report nothing`() {
        // Still under the threshold.
        assertFalse(shouldReportHang(onMain = false, nowMs = 14_999, startedAtMs = 10_000, reportedFor = 0))
        // No message running (the loop is idle between messages).
        assertFalse(shouldReportHang(onMain = false, nowMs = 60_000, startedAtMs = 0, reportedFor = 0))
        // The main thread's own lines cannot be written while it is stuck, and
        // a main-thread line must never sample its own stack as a hang.
        assertFalse(shouldReportHang(onMain = true, nowMs = 16_000, startedAtMs = 10_000, reportedFor = 0))
    }

    @Test
    fun `one stuck message is reported once and the next stuck one again`() {
        // The hang line itself comes back through the same check: it must stop here.
        assertFalse(shouldReportHang(onMain = false, nowMs = 20_000, startedAtMs = 10_000, reportedFor = 10_000))
        assertTrue(shouldReportHang(onMain = false, nowMs = 40_000, startedAtMs = 30_000, reportedFor = 10_000))
    }
}
