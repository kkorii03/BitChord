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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Follow-up to [UpgradeArtistScriptSearchTest]: the upgrade race must keep a
 * normally credited match ahead of a localised row, still try the localised
 * row when the credited one only settled below the request, choose the closest
 * runtime among localised rows, and never adopt one unless the sound
 * comparison accepts it.
 */
class UpgradeArtistUnverifiedFallbackTest {

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

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId/$trackId", format = formatFor(trackId))
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
        allowArtistUnverified: Boolean = true,
        accept: (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
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
            allowArtistUnverified = allowArtistUnverified,
            report = report,
        ) { source, stream -> accept(source, stream) }?.second
        found to report.outcomes().singleOrNull()
    }

    @Test
    fun `a three-second localised row is still an artist-unverified candidate`() {
        val source = FakeSource("Qobuz", listOf(flowers("localised", "マイリー・サイラス", "3:23")))
        val (stream, outcome) = walk(listOf(source))
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/localised"))
        assertEquals("artist-unverified", outcome!!.verdict)
    }

    @Test
    fun `a credited match wins over a better localised rendition across sources`() {
        val localised = FakeSource("Qobuz", listOf(flowers("localised", "マイリー・サイラス")))
        val credited = FakeSource(
            "Jio",
            listOf(flowers("credited", "Miley Cyrus", "3:20")),
            formatFor = { StreamFormat("aac", 320) },
        )
        val (stream, _) = walk(listOf(localised, credited))
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/credited"))
    }

    @Test
    fun `a below-request credited row does not hide the localised lossless one`() {
        val source = FakeSource(
            "Qobuz",
            listOf(
                flowers("credited", "Miley Cyrus", "3:22"),
                flowers("localised", "マイリー・サイラス", "3:20"),
            ),
            formatFor = { id ->
                if (id == "credited") StreamFormat("aac", 320) else StreamFormat("flac")
            },
        )
        val (stream, outcome) = walk(listOf(source), request = StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/localised"))
        assertEquals("artist-unverified", outcome!!.verdict)
    }

    @Test
    fun `the localised row closest in runtime wins over a better advertised tier`() {
        val source = FakeSource(
            "Qobuz",
            listOf(
                flowers("far", "マイリー・サイラス", "3:23"),
                flowers("near", "マイリー・サイラス", "3:20"),
            ),
            formatFor = { id ->
                if (id == "far") StreamFormat("flac") else StreamFormat("aac", 320)
            },
        )
        val (stream, outcome) = walk(listOf(source))
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/near"))
        assertEquals("artist-unverified", outcome!!.verdict)
    }

    @Test
    fun `a localised row is tried when the credited one fails the accept gate`() {
        val source = FakeSource(
            "Qobuz",
            listOf(
                flowers("credited", "Miley Cyrus"),
                flowers("localised", "マイリー・サイラス"),
            ),
            formatFor = { id ->
                if (id == "credited") StreamFormat("aac", 320) else StreamFormat("flac")
            },
        )
        val (stream, _) = walk(
            listOf(source),
            accept = { _, stream -> stream.format.isLossless == true },
        )
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/localised"))
    }

    @Test
    fun `an artist-unverified row is not adopted when the sound comparison rejects it`() {
        val source = FakeSource("Qobuz", listOf(flowers("localised", "マイリー・サイラス")))
        val (stream, outcome) = walk(listOf(source), accept = { _, _ -> false })
        assertNull(stream)
        assertEquals("artist-unverified", outcome!!.verdict)
    }

    @Test
    fun `an artist-unverified row is kept when the sound comparison cannot measure it`() {
        val source = FakeSource("Qobuz", listOf(flowers("localised", "マイリー・サイラス")))
        val (stream, _) = walk(listOf(source), accept = { _, _ -> false })
        assertNull("unmeasured is not an acceptance", stream)
    }

    @Test
    fun `an artist-unverified row is adopted when the sound comparison accepts it`() {
        val source = FakeSource("Qobuz", listOf(flowers("localised", "マイリー・サイラス")))
        val (stream, outcome) = walk(listOf(source), accept = { _, _ -> true })
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/localised"))
        assertEquals("artist-unverified", outcome!!.verdict)
    }
}
