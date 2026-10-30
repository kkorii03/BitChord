package com.music.bitchord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The warm/cold marker is read off disk, but its arithmetic is pure. */
class ProcessRestartWarmthTest {

    private val window = 10 * 60 * 1000L

    @Test
    fun `a missing or zero marker is a cold start`() {
        assertFalse(isWarmProcessRestart(nowMs = 1_000_000L, previousAtMs = 0L, windowMs = window))
    }

    @Test
    fun `a marker inside the window is a warm restart`() {
        assertTrue(isWarmProcessRestart(nowMs = 1_000_000L, previousAtMs = 999_999L, windowMs = window))
        assertTrue(
            isWarmProcessRestart(
                nowMs = 1_000_000L,
                previousAtMs = 1_000_000L - window,
                windowMs = window,
            ),
        )
    }

    @Test
    fun `a marker older than the window is a cold start`() {
        assertFalse(
            isWarmProcessRestart(
                nowMs = 1_000_000L,
                previousAtMs = 1_000_000L - window - 1L,
                windowMs = window,
            ),
        )
    }

    @Test
    fun `a marker from the future is not trusted`() {
        assertFalse(isWarmProcessRestart(nowMs = 1_000_000L, previousAtMs = 1_000_001L, windowMs = window))
    }

    @Test
    fun `the marker written for this process reads back its timestamp`() {
        val marker = File.createTempFile("bitchord-process", ".txt")
        try {
            val now = 1_700_000_000_000L
            marker.writeText(processMarkerLine(pid = 4321, nowMs = now))

            assertEquals(now, processMarkerTimestamp(marker.readText()))
        } finally {
            marker.delete()
        }
    }

    @Test
    fun `a legacy marker still yields the timestamp on its first line`() {
        assertEquals(1_700_000_000_000L, processMarkerTimestamp("4321 1700000000000"))
        assertEquals(0L, processMarkerTimestamp("4321"))
        assertEquals(0L, processMarkerTimestamp(""))
    }
}
