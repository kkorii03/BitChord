package com.music.bitchord.ko.downloads

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPT2 F4: one progress publish per whole percent, not one per 8 KiB read. */
class KoPercentGateTest {

    private fun publishes(total: Long, step: Long): List<Float> {
        val gate = KoPercentGate()
        val out = mutableListOf<Float>()
        var written = 0L
        while (written < total) {
            written = minOf(total, written + step)
            gate.next(written, total)?.let(out::add)
        }
        return out
    }

    @Test
    fun `many reads inside one percent publish once`() {
        // 8 MiB in 8 KiB reads: 1024 callbacks, about 10 per percent.
        val total = 8L * 1024 * 1024
        val gate = KoPercentGate()
        var published = 0
        for (written in (8_192L..total / 100 - 1) step 8_192L) {
            if (gate.next(written, total) != null) published++
        }
        assertEquals(1, published)
    }

    @Test
    fun `a whole download publishes each percent once and ends at one`() {
        val out = publishes(total = 8L * 1024 * 1024, step = 8_192L)
        assertEquals(101, out.size) // 0..100
        assertEquals(1f, out.last())
        assertTrue(out.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `the first read still publishes so the bar stops being indeterminate`() {
        val fraction = KoPercentGate().next(8_192L, 8L * 1024 * 1024)
        assertTrue(fraction != null && fraction > 0f)
    }

    @Test
    fun `a few segments publish every segment`() {
        // HLS/DASH report segments: each one moves the percent.
        assertEquals(listOf(1 / 3f, 2 / 3f, 1f), publishes(total = 3L, step = 1L))
    }

    @Test
    fun `both download progress lambdas go through the gate`() {
        val root = File(".").canonicalFile
        val text = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/download/Downloads.kt") }
            .first { it.exists() }.readText()
        // Old code divided and published on every callback in both places.
        assertEquals(0, Regex("""val fraction = written\.toFloat\(\) / total""").findAll(text).count())
        assertEquals(2, Regex("""Gate\.next\(written, total\) \?: return@""").findAll(text).count())
    }
}
