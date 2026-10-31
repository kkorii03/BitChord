package com.music.bitchord.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tally behind the export's feature scorecard: aggregation, the exact
 * 24-hour boundary, persistence across a restart, and the header format the
 * Python reader parses.
 */
class FeatureHealthCountersTest {

    private val hour = 60L * 60L * 1000L

    @Test
    fun `counts aggregate per key and old events fall out of the window`() {
        var now = 1_700_000_000_000L
        val store = FeatureHealthCounterStore(clock = { now })
        store.record("a", now - 25 * hour, 3)
        store.record("a", now - 2 * hour, 2)
        store.record("b", now, 1)

        assertEquals(mapOf("a" to 2L, "b" to 1L), store.snapshot(now))
    }

    @Test
    fun `the twenty four hour boundary is exact`() {
        val start = 1_700_000_000_000L
        var now = start
        val store = FeatureHealthCounterStore(clock = { now })
        store.record("k", start, 2)

        now = start + 24 * hour - 1
        assertEquals(2L, store.snapshot(now)["k"])

        now = start + 24 * hour
        assertEquals(2L, store.snapshot(now)["k"])

        now = start + 24 * hour + 1
        assertEquals(null, store.snapshot(now)["k"])
    }

    @Test
    fun `a restart reads the last day back from its daily file`() {
        val directory = Files.createTempDirectory("bitchord-health").toFile()
        try {
            var now = 1_700_000_000_000L
            val first = FeatureHealthCounterStore(clock = { now })
            first.attach(directory)
            first.record("kept", now - hour, 4)
            first.record("expired", now - 30 * hour, 9)
            first.flush()
            first.close()

            val second = FeatureHealthCounterStore(clock = { now })
            second.attach(directory)
            assertEquals(4L, second.snapshot(now)["kept"])
            assertEquals(null, second.snapshot(now)["expired"])
            second.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `observer counts next prep stages and lists skip reasons`() {
        val now = 1_700_000_000_000L
        val counts = HashMap<String, Long>()
        val observer = FeatureHealthObserver { key, amount ->
            counts[key] = (counts[key] ?: 0L) + amount
        }
        observer.observe(
            "BitChord",
            "next-prep: A version=a lyrics=lrc.red hq=qobuz " +
                "sync=skipped(no-match) mix=ready in 100ms",
            now,
        )
        observer.observe(
            "BitChord",
            "next-prep: B version=b lyrics=none hq=none " +
                "sync=skipped(no-lyrics) mix=none in 200ms",
            now,
        )
        observer.observe(
            "BitChord",
            "next-prep: C version=c lyrics=lrc hq=none " +
                "sync=applied mix=ready in 300ms",
            now,
        )

        assertEquals(3L, counts[FeatureHealthCounterKeys.D1_ATTEMPTS])
        assertEquals(2L, counts[FeatureHealthCounterKeys.D1_LYRICS])
        assertEquals(1L, counts[FeatureHealthCounterKeys.D1_HQ])
        assertEquals(2L, counts[FeatureHealthCounterKeys.D1_SYNC_ATTEMPTS])
        assertEquals(1L, counts[FeatureHealthCounterKeys.D1_SYNC_SUCCESS])
        assertEquals(1L, counts[FeatureHealthCounterKeys.D1_SYNC_SKIP])
        assertEquals(1L, counts[FeatureHealthCounterKeys.D1_SYNC_SKIP_REASON + "no-match"])
        assertEquals(2L, counts[FeatureHealthCounterKeys.D1_MIX])
    }

    @Test
    fun `header uses the reader's metric keys and numeric fields only`() {
        val now = 1_700_000_000_000L
        val store = FeatureHealthCounterStore(clock = { now })
        val observer = FeatureHealthObserver { key, amount -> store.record(key, now, amount) }
        observer.observe(
            "BitChord",
            "track-summary: id 'A Title' video=n source=browse resolve=direct " +
                "firstAudio=1200 played=200 lyrics=lrc.red sync=ai shift=0 match=90 " +
                "manualOffset=0 ended=natural",
            now,
        )
        observer.observe(
            "BitChord",
            "track-summary: id 'B Title' video=n source=browse resolve=direct " +
                "firstAudio=4200 played=200 lyrics=none sync=none shift=- match=- " +
                "manualOffset=0 ended=natural",
            now,
        )
        observer.observe("BitChord", "crossfade: start out=a in=b fade=500ms", now)
        observer.observe("BitChord", "crossfade: end out=a in=b overlap=480ms", now)
        observer.observe("BitChord", "crossfade: abort out=a in=b phase=FADING", now)

        val lines = store.headerLines(now)
        assertTrue(lines.all { it.startsWith("feature-health: ") })
        assertTrue(lines.any { it == "feature-health: D6-lyrics attempts=2 successes=1" })
        assertTrue(lines.any { it == "feature-health: D6-sync attempts=1 successes=1" })
        assertTrue(lines.any { it == "feature-health: D7-crossfade attempts=1 successes=1" })
        assertTrue(lines.any { it == "feature-health: D7-abort attempts=1" })
        assertTrue(lines.any { it == "feature-health: D8-firstaudio attempts=2 successes=1" })
        assertTrue(lines.none { it.contains("A Title") || it.contains("B Title") })
    }
}
