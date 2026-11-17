package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LOG-1117 G2 and G3: D5 counted every `kept (none)` (a row that already is
 * the song) as a failed swap and every repeated check twice (1/270 on the
 * phone); D10 counted every process start, including system relaunches and
 * widget starts with no window, and failed a launch whose window came up 79 s
 * after the process did.
 */
class FeatureHealthVersionLaunchTest {

    private val now = 1_700_000_000_000L

    private fun counts(vararg lines: Pair<String, Long>): Map<String, Long> {
        val counts = HashMap<String, Long>()
        val observer = FeatureHealthObserver { key, amount -> counts[key] = (counts[key] ?: 0L) + amount }
        for ((line, at) in lines) observer.observe("BitChord", line, at)
        return counts
    }

    private fun Map<String, Long>.of(key: String) = this[key] ?: 0L

    @Test
    fun `kept none is not an attempt and a repeated check is one row`() {
        val c = counts(
            "song version: XcGZtlPVjM8 kept (none)" to now,
            "song version: XcGZtlPVjM8 kept (none)" to now + 3_000,
            "song version: Yyyyyyyyyyy -> Zzzzzzzzzzz via search" to now + 4_000,
            "song version: Yyyyyyyyyyy -> Zzzzzzzzzzz via cache before-audio" to now + 9_000,
            "song version: Wwwwwwwwwww kept (local file, none)" to now + 10_000,
            "song version: Ccccccccccc -> none via none (cover)" to now + 11_000,
            "song version: Ddddddddddd -> none via cache" to now + 12_000,
        )
        assertEquals(1L, c.of(FeatureHealthCounterKeys.D5_ATTEMPTS))
        assertEquals(1L, c.of(FeatureHealthCounterKeys.D5_SWAPS))
    }

    @Test
    fun `a wanted swap that did not happen is an attempt without success`() {
        val c = counts(
            "song version: Aaaaaaaaaaa kept (timeout)" to now,
            "song version: Aaaaaaaaaaa kept (timeout)" to now + 1_000,
            "song version: Bbbbbbbbbbb -> Mmmmmmmmmmm late swap refused (too far)" to now + 2_000,
            "song version: Eeeeeeeeeee -> none via retry" to now + 3_000,
        )
        assertEquals(3L, c.of(FeatureHealthCounterKeys.D5_ATTEMPTS))
        assertEquals(0L, c.of(FeatureHealthCounterKeys.D5_SWAPS))
    }

    @Test
    fun `a timeout answered by the retry and a late swap are one row each`() {
        val c = counts(
            "song version: Aaaaaaaaaaa kept (timeout)" to now,
            "song version: Aaaaaaaaaaa -> Sssssssssss via retry" to now + 5_000,
            "song version: Bbbbbbbbbbb -> Mmmmmmmmmmm late swap starting at mv=4000ms budget=8000ms" to now + 6_000,
            "song version: Bbbbbbbbbbb -> Mmmmmmmmmmm late swap done mv=9000ms song=8800ms offset=0ms" to now + 9_000,
        )
        // Today: 4 attempts, 3 successes ("starting" counted as a swap).
        assertEquals(2L, c.of(FeatureHealthCounterKeys.D5_ATTEMPTS))
        assertEquals(2L, c.of(FeatureHealthCounterKeys.D5_SWAPS))
    }

    @Test
    fun `the same row on another day is counted again`() {
        val day = 24L * 60L * 60L * 1000L
        val c = counts(
            "song version: Aaaaaaaaaaa kept (timeout)" to now,
            "song version: Aaaaaaaaaaa kept (timeout)" to now + 2 * day,
        )
        assertEquals(2L, c.of(FeatureHealthCounterKeys.D5_ATTEMPTS))
    }

    @Test
    fun `a launch is counted at its first foreground and timed from it`() {
        // 11-17 09:04:25 process start (widget), 09:05:44.902 foreground,
        // Home content 0.36 s later.
        val c = counts(
            "lifecycle: process start pid=31401 cold" to now,
            "lifecycle: foreground" to now + 79_238,
            "startup first-home-content: 364 ms since-process=79602 ms" to now + 79_602,
            "lifecycle: background" to now + 120_000,
            "lifecycle: foreground" to now + 180_000,
        )
        assertEquals(1L, c.of(FeatureHealthCounterKeys.D10_ATTEMPTS))
        assertEquals(1L, c.of(FeatureHealthCounterKeys.D10_SUCCESS))
    }

    @Test
    fun `a process that never comes to the front is not a launch`() {
        val c = counts(
            "lifecycle: process start pid=1234 warm" to now,
            "lifecycle: app created" to now + 10,
        )
        assertEquals(0L, c.of(FeatureHealthCounterKeys.D10_ATTEMPTS))
        assertEquals(0L, c.of(FeatureHealthCounterKeys.D10_SUCCESS))
    }

    @Test
    fun `a launch whose home takes too long is an attempt without success`() {
        val c = counts(
            "lifecycle: foreground" to now,
            "startup first-home-content: 15000 ms since-process=15200 ms" to now + 15_000,
        )
        assertEquals(1L, c.of(FeatureHealthCounterKeys.D10_ATTEMPTS))
        assertEquals(0L, c.of(FeatureHealthCounterKeys.D10_SUCCESS))
    }
}
