package com.music.bitchord.startup

import com.music.bitchord.data.FeatureHealthCounterKeys
import com.music.bitchord.data.FeatureHealthObserver
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LOG-1117 G3: 11-17 09:04:25 the widget started the process, the window came
 * up at 09:05:44.902 and Home had content 0.36 s later, but the line said
 * `first-home-content: 79602 ms` because it measured from process creation.
 */
class StartupTraceHomeLineTest {

    @Test
    fun `home content is measured from the first foreground`() {
        val start = 1_000L
        val foreground = start + 79_238L
        val now = start + 79_602L
        assertEquals(
            "startup first-home-content: 364 ms since-process=79602 ms",
            StartupTrace.homeLine(now, start, foreground),
        )
    }

    @Test
    fun `with no foreground recorded it falls back to the process start`() {
        assertEquals(
            "startup first-home-content: 500 ms since-process=500 ms",
            StartupTrace.homeLine(nowUptime = 1_500L, processStartUptime = 1_000L, foregroundUptime = 0L),
        )
    }

    @Test
    fun `the launch counter reads the line it writes`() {
        val counts = HashMap<String, Long>()
        val observer = FeatureHealthObserver { key, amount -> counts[key] = (counts[key] ?: 0L) + amount }
        observer.observe("BitChord", "lifecycle: process start pid=31401 cold", 0L)
        observer.observe("BitChord", "lifecycle: foreground", 79_238L)
        observer.observe("BitChord", StartupTrace.homeLine(79_602L, 0L, 79_238L), 79_602L)
        assertEquals(1L, counts[FeatureHealthCounterKeys.D10_ATTEMPTS])
        assertEquals(1L, counts[FeatureHealthCounterKeys.D10_SUCCESS])
    }
}
