package com.music.bitchord

import com.music.bitchord.playback.HqHostGate
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.HostHealthRecord
import com.music.bitchord.playback.HqResumeCandidate
import com.music.bitchord.playback.HqResumePolicy
import com.music.bitchord.data.net.HqRetryPolicy
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.QualityUpgrade
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure halves of the high-quality fallback work: how long to wait after a
 * 429/503, when a same-host read-ahead may proceed, and which source may carry
 * on a track that was refused mid-stream.
 */
class HqHostPolicyTest {

    @Test
    fun `retry-after is read as seconds`() {
        assertEquals(5_000L, HqRetryPolicy.retryAfterMs("5", nowMs = 1_000L) ?: -1L)
        assertEquals(0L, HqRetryPolicy.retryAfterMs("0", nowMs = 1_000L) ?: -1L)
    }

    @Test
    fun `retry-after is read as an http date`() {
        val now = 1_767_225_600_000L // Thu, 01 Jan 2026 00:00:00 GMT
        assertEquals(
            8_000L,
            HqRetryPolicy.retryAfterMs("Thu, 01 Jan 2026 00:00:08 GMT", nowMs = now) ?: -1L,
        )
    }

    @Test
    fun `a malformed retry-after falls through to the backoff`() {
        assertNull(HqRetryPolicy.retryAfterMs("soon", nowMs = 0L))
        assertEquals(1_000L, HqRetryPolicy.decide(null, attempt = 0, nowMs = 0L).waitMs)
        assertEquals(2_000L, HqRetryPolicy.decide(null, attempt = 1, nowMs = 0L).waitMs)
        assertEquals(4_000L, HqRetryPolicy.decide(null, attempt = 2, nowMs = 0L).waitMs)
        assertFalse(HqRetryPolicy.decide(null, attempt = 3, nowMs = 0L).retry)
    }

    @Test
    fun `a rate-limited host rests for at least a minute`() {
        val gate = HqHostGate { 1_000L }
        val rest = gate.noteRateLimited("tracks.example", header = "2", code = 429)
        assertEquals(HqRetryPolicy.REST_MS, rest)
        assertTrue(gate.isResting("tracks.example", nowMs = 1_000L))
        assertFalse(gate.isResting("tracks.example", nowMs = 1_000L + HqRetryPolicy.REST_MS + 1))
    }

    @Test
    fun `two automatic requests to one host start one after the other`() = runBlocking {
        val gate = HqHostGate { 0L }
        gate.begin("auto-1", "monochrome.example")

        var secondStarted = false
        val second = launch {
            gate.awaitIdle("monochrome.example", exceptTrack = "auto-2")
            secondStarted = true
            gate.begin("auto-2", "monochrome.example")
        }

        kotlinx.coroutines.yield()
        assertFalse("the second automatic request must wait for the first", secondStarted)

        gate.end("auto-1", "monochrome.example")
        second.join()
        assertTrue(secondStarted)
        gate.end("auto-2", "monochrome.example")
    }

    @Test
    fun `a resting host reports the time left before it may be asked again`() {
        val gate = HqHostGate { 1_000L }
        gate.noteRateLimited("monochrome.example", header = "120", code = 429)

        assertEquals(120_000L, gate.restRemainingMs("monochrome.example", nowMs = 1_000L))
        assertEquals(0L, gate.restRemainingMs("monochrome.example", nowMs = 121_000L))
    }

    @Test
    fun `the same host waits for the current download while another host runs`() = runBlocking {
        val gate = HqHostGate { 0L }
        gate.begin("current", "tracks.example")
        gate.begin("next", "other.example")

        assertTrue(gate.isBusy("tracks.example"))
        assertFalse(gate.isBusy("tracks.example", exceptTrack = "current"))
        assertFalse(gate.isBusy("other.example", exceptTrack = "next"))

        var sameHostProceeded = false
        var otherHostProceeded = false
        val sameHost = launch {
            gate.awaitIdle("tracks.example", exceptTrack = "next")
            sameHostProceeded = true
        }
        val otherHost = launch {
            gate.awaitIdle("other.example", exceptTrack = "next")
            otherHostProceeded = true
        }

        otherHost.join()
        assertTrue("a different host is not blocked by the current download", otherHostProceeded)
        assertFalse("the same host must wait for the current track to finish", sameHostProceeded)

        gate.end("current", "tracks.example")
        sameHost.join()
        assertTrue(sameHostProceeded)
    }

    @Test
    fun `resume never picks the failed source or host`() {
        val failedHost = HqHostTracker.hostOf("https://tracks.example/a.flac")
        val candidates = listOf(
            HqResumeCandidate(
                sourceConfigId = "monochrome",
                trackId = "a",
                url = "https://tracks.example/a.flac",
                verifiedOffsetMs = 500L,
            ),
            HqResumeCandidate(
                sourceConfigId = "qobuz",
                trackId = "b",
                url = "https://tracks.example/b.flac",
                verifiedOffsetMs = 500L,
            ),
            HqResumeCandidate(
                sourceConfigId = "qobuz",
                trackId = "c",
                url = "https://qobuz.example/c.flac",
                verifiedOffsetMs = 500L,
            ),
        )

        val chosen = HqResumePolicy.choose(
            failedSourceConfigId = "monochrome",
            failedHost = failedHost,
            failedOffsetMs = 500L,
            failedRate = 1.0,
            candidates = candidates,
        )

        assertSame(candidates[2], chosen)
    }

    @Test
    fun `resume refuses a different H1 offset`() {
        val chosen = HqResumePolicy.choose(
            failedSourceConfigId = "monochrome",
            failedHost = "tracks.example",
            failedOffsetMs = 500L,
            failedRate = 1.0,
            candidates = listOf(
                HqResumeCandidate(
                    sourceConfigId = "qobuz",
                    trackId = "b",
                    url = "https://qobuz.example/b.flac",
                    verifiedOffsetMs = 9_000L,
                ),
            ),
        )

        assertNull(chosen)
    }

    @Test
    fun `resume requires an offset when the failed stream had one`() {
        val chosen = HqResumePolicy.choose(
            failedSourceConfigId = "monochrome",
            failedHost = "tracks.example",
            failedOffsetMs = 500L,
            failedRate = 1.0,
            candidates = listOf(
                HqResumeCandidate(
                    sourceConfigId = "qobuz",
                    trackId = "b",
                    url = "https://qobuz.example/b.flac",
                    verifiedOffsetMs = null,
                ),
            ),
        )

        assertNull(chosen)
    }

    @Test
    fun `resume keeps the same rung on another source`() {
        val cd = StreamFormat("flac", 1411, 44_100, 16)
        val chosen = HqResumePolicy.choose(
            failedSourceConfigId = "monochrome",
            failedHost = "tracks.example",
            failedOffsetMs = null,
            failedRate = null,
            failedFormat = cd,
            candidates = listOf(
                HqResumeCandidate(
                    sourceConfigId = "qobuz",
                    trackId = "b",
                    url = "https://qobuz.example/b.flac",
                    format = cd,
                ),
            ),
        )

        assertEquals("qobuz", chosen?.sourceConfigId)
    }

    @Test
    fun `resume refuses a lower rung even from a healthy host`() {
        val chosen = HqResumePolicy.choose(
            failedSourceConfigId = "monochrome",
            failedHost = "tracks.example",
            failedOffsetMs = null,
            failedRate = null,
            failedFormat = StreamFormat("flac", 1411, 44_100, 16),
            candidates = listOf(
                HqResumeCandidate(
                    sourceConfigId = "qobuz",
                    trackId = "b",
                    url = "https://qobuz.example/b.m4a",
                    format = StreamFormat("aac", 320),
                ),
            ),
        )

        assertNull(chosen)
    }

    @Test
    fun `resume accepts an unknown format when the other rules agree`() {
        val chosen = HqResumePolicy.choose(
            failedSourceConfigId = "monochrome",
            failedHost = "tracks.example",
            failedOffsetMs = null,
            failedRate = null,
            failedFormat = StreamFormat("flac", 1411, 44_100, 16),
            candidates = listOf(
                HqResumeCandidate("qobuz", "b", "https://qobuz.example/b.flac"),
            ),
        )

        assertEquals("qobuz", chosen?.sourceConfigId)
    }

    @Test
    fun `a host with a worse recent record sorts behind a healthy one`() {
        val chosen = HqResumePolicy.choose(
            failedSourceConfigId = "monochrome",
            failedHost = "tracks.example",
            failedOffsetMs = null,
            failedRate = null,
            candidates = listOf(
                HqResumeCandidate("slow", "a", "https://slow.example/a.flac"),
                HqResumeCandidate("fast", "b", "https://fast.example/b.flac"),
            ),
            hostPenalty = { host -> if (host == "slow.example") 3 else 0 },
        )

        assertEquals("fast", chosen?.sourceConfigId)
    }

    @Test
    fun `a broken upgrade is refused only for that playback`() {
        val mediaId = "refused-track"
        QualityUpgrade.refuseUpgrades(mediaId)
        assertTrue(QualityUpgrade.hasRefusedFor(mediaId))

        QualityUpgrade.onPlaybackStarted(mediaId, rendition = null)

        assertFalse("the next play gets another attempt", QualityUpgrade.hasRefusedFor(mediaId))
    }

    @Test
    fun `a slow but working addon sorts behind a fast one`() {
        val gate = HqHostGate { 0L }
        repeat(3) { gate.noteResponse("slow.example", 6_000L, ok = true, nowMs = it.toLong()) }
        repeat(3) { gate.noteResponse("fast.example", 300L, ok = true, nowMs = it.toLong()) }

        assertTrue(
            "a 6s average response must be worse than a 300ms one",
            gate.penalty("slow.example", nowMs = 10L) > gate.penalty("fast.example", nowMs = 10L),
        )
    }

    @Test
    fun `host health and rest survive a restart`() {
        val before = HqHostGate { 1_000L }
        repeat(3) { before.noteResponse("slow.example", 6_000L, ok = true, nowMs = 1_000L) }
        before.noteRateLimited("tired.example", header = "120", code = 429, nowMs = 1_000L)

        val after = HqHostGate { 1_000L }
        after.restore(before.snapshot(), nowMs = 1_000L)

        assertEquals(1, after.penalty("slow.example", nowMs = 1_000L))
        assertTrue(after.isResting("tired.example", nowMs = 1_000L))
    }

    @Test
    fun `a health record older than a week is not restored`() {
        val old = HostHealthRecord(
            failures = 9,
            successes = 0,
            lastActivityMs = 0L,
        )
        val gate = HqHostGate { 10L * 24 * 60 * 60 * 1000 }
        gate.restore(mapOf("forgotten.example" to old), nowMs = 10L * 24 * 60 * 60 * 1000)

        assertEquals(0, gate.penalty("forgotten.example", nowMs = 10L * 24 * 60 * 60 * 1000))
    }
}
