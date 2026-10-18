package com.music.bitchord.data.stats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteThrottleTest {

    @Test
    fun `the first write always goes through`() {
        val throttle = WriteThrottle(intervalMs = 120_000L)
        assertTrue(throttle.shouldWrite(1_000L))
    }

    @Test
    fun `a write inside the interval is refused`() {
        val throttle = WriteThrottle(intervalMs = 120_000L)
        throttle.wrote(10_000L)
        assertFalse(throttle.shouldWrite(10_001L))
        assertFalse(throttle.shouldWrite(129_999L))
    }

    @Test
    fun `the boundary itself is allowed`() {
        val throttle = WriteThrottle(intervalMs = 120_000L)
        throttle.wrote(10_000L)
        assertTrue(throttle.shouldWrite(130_000L))
    }

    @Test
    fun `a later write moves the window`() {
        val throttle = WriteThrottle(intervalMs = 120_000L)
        throttle.wrote(10_000L)
        assertTrue(throttle.shouldWrite(130_000L))
        throttle.wrote(130_000L)
        assertFalse(throttle.shouldWrite(200_000L))
        assertTrue(throttle.shouldWrite(250_000L))
    }
}
