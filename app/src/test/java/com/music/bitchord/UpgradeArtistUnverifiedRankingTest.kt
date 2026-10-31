package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The upgrade race must rank by request satisfaction before artist
 * verification: a localised row that only settled below the request cannot
 * outrank a verified copy that meets it, and an alternate opened only for
 * hq-verify must not fail the whole walk when that alternate cannot open.
 */
class UpgradeArtistUnverifiedRankingTest {

    @Before
    fun reset() {
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        HqHostTracker.resetForTest()
    }

    private class FakeSource(
        override val configId: String,
        var rows: List<Song>,
        var formatFor: (String) -> StreamFormat = { StreamFormat("flac") },
        var failingIds: Set<String> = emptySet(),
    ) : MusicSource {
        override val displayName: String get() = configId
        override val kind: SourceKind get() = SourceKind.ADDON

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> = rows

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream {
            if (trackId in failingIds) throw IllegalStateException("alternate stream failed")
            return SourceStream(url = "https://$configId/$trackId", format = formatFor(trackId))
        }
    }

    private fun target() = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)

    private fun flowers(id: String, artist: String, duration: String = "3:20") = Song(
        videoId = id,
        title = "Flowers",
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    private fun walk(
        sources: List<MusicSource>,
        request: StreamRequest = StreamRequest.Best,
    ): Pair<SourceStream?, SourceResolver.SearchOutcome?> = runBlocking {
        val report = SourceResolver.SearchReport()
        val found = SourceResolver.bestAcross(
            sources,
            target(),
            request,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            strictOriginal = true,
            allowArtistUnverified = true,
            report = report,
        ) { _, _ -> true }?.second
        found to report.outcomes().singleOrNull()
    }

    @Test
    fun `a verified below-request copy outranks a localised below-request one`() {
        val localised = FakeSource(
            "Qobuz",
            listOf(flowers("localised", "マイリー・サイラス")),
            formatFor = { StreamFormat("aac", 320) },
        )
        val credited = FakeSource(
            "Jio",
            listOf(flowers("credited", "Miley Cyrus")),
            formatFor = { StreamFormat("aac", 320) },
        )
        val (stream, _) = walk(listOf(localised, credited), request = StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/credited"))
    }

    @Test
    fun `a localised copy that meets the request outranks a closer settle-for`() {
        val settleFor = FakeSource(
            "Qobuz",
            listOf(flowers("near", "マイリー・サイラス", "3:20")),
            formatFor = { StreamFormat("aac", 320) },
        )
        val lossless = FakeSource(
            "Tidal",
            listOf(flowers("far", "マイリー・サイラス", "3:23")),
            formatFor = { StreamFormat("flac") },
        )
        val (stream, _) = walk(listOf(settleFor, lossless), request = StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/far"))
    }

    @Test
    fun `an unused alternate that cannot open does not retry the track`() {
        val source = FakeSource(
            "Qobuz",
            listOf(
                flowers("credited", "Miley Cyrus"),
                flowers("localised", "マイリー・サイラス"),
            ),
            failingIds = setOf("localised"),
        )
        val (stream, outcome) = walk(listOf(source))
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/credited"))
        assertEquals("match", outcome!!.verdict)
        assertTrue("the verified stream opened, so the walk is conclusive", outcome.conclusive)
    }

    @Test
    fun `a needed localised copy that cannot open still leaves the walk inconclusive`() {
        val source = FakeSource(
            "Qobuz",
            listOf(
                flowers("credited", "Miley Cyrus"),
                flowers("localised", "マイリー・サイラス"),
            ),
            formatFor = { StreamFormat("aac", 320) },
            failingIds = setOf("localised"),
        )
        val (stream, outcome) = walk(listOf(source), request = StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/credited"))
        assertFalse("the localised copy was needed but never opened", outcome!!.conclusive)
    }
}
