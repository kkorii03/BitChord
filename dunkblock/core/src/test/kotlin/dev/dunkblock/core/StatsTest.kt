package dev.dunkblock.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsTest {
    @Test fun percentilesInterpolate() {
        val s = Stats.summarize(DoubleArray(101) { it.toDouble() })
        assertEquals(50.0, s.median, 1e-9)
        assertEquals(95.0, s.p95, 1e-9)
        assertEquals(99.0, s.p99, 1e-9)
        assertEquals(100.0, s.max, 1e-9)
        assertEquals(0.0, s.min, 1e-9)
    }

    @Test fun emptyAndSingle() {
        assertEquals(0, Stats.summarize(DoubleArray(0)).count)
        val one = Stats.summarize(doubleArrayOf(7.0))
        assertEquals(7.0, one.p95, 1e-9)
    }

    @Test fun deltasUseScale() {
        val d = Stats.deltas(longArrayOf(0, 8_333_333, 16_666_666), 3, 1e-6)
        assertEquals(2, d.size)
        assertEquals(8.333333, d[0], 1e-6)
    }
}
