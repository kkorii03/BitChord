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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The upgrade search must judge every result, keep a localised-artist row as
 * `artist-unverified`, and still put a normally credited match first.
 */
class UpgradeArtistScriptSearchTest {

    @Before
    fun reset() {
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        HqHostTracker.resetForTest()
    }

    private class FakeSource(
        override val configId: String,
        var rows: List<Song>,
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
            SourceStream(url = "https://$configId/$trackId", format = StreamFormat("flac"))
    }

    private fun target() = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)

    private fun flowers(id: String, artist: String, duration: String = "3:20") = Song(
        videoId = id,
        title = "Flowers",
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    private fun walk(source: MusicSource, allowArtistUnverified: Boolean): Pair<SourceStream?, SourceResolver.SearchOutcome?> =
        runBlocking {
            val report = SourceResolver.SearchReport()
            val found = SourceResolver.bestAcross(
                listOf(source),
                target(),
                StreamRequest.Best,
                waitForAll = true,
                strictLength = true,
                requireSharedArtist = true,
                strictOriginal = true,
                allowArtistUnverified = allowArtistUnverified,
                report = report,
            )?.second
            found to report.outcomes().singleOrNull()
        }

    @Test
    fun `a localised artist row is found and logged as artist-unverified`() {
        val source = FakeSource("Qobuz", listOf(flowers("localised", "マイリー・サイラス")))
        val (stream, outcome) = walk(source, allowArtistUnverified = true)
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/localised"))
        assertEquals("artist-unverified", outcome!!.verdict)
    }

    @Test
    fun `without the upgrade flag the localised row is still an artist miss`() {
        val source = FakeSource("Qobuz", listOf(flowers("localised", "マイリー・サイラス")))
        val (stream, outcome) = walk(source, allowArtistUnverified = false)
        assertNull(stream)
        assertEquals("artist", outcome!!.verdict)
    }

    @Test
    fun `a normally credited row is chosen before a closer localised one`() {
        val source = FakeSource(
            "Qobuz",
            listOf(
                flowers("localised", "マイリー・サイラス", "3:20"),
                flowers("credited", "Miley Cyrus", "3:22"),
            ),
        )
        val (stream, outcome) = walk(source, allowArtistUnverified = true)
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/credited"))
        assertEquals("match", outcome!!.verdict)
    }

    @Test
    fun `a same-script different artist is never an unverified candidate`() {
        val source = FakeSource("Qobuz", listOf(flowers("other", "Bruno Mars")))
        val (stream, outcome) = walk(source, allowArtistUnverified = true)
        assertNull(stream)
        assertEquals("artist", outcome!!.verdict)
    }

    @Test
    fun `a same-script cover is never offered even with the unverified flag`() {
        val source = FakeSource(
            "Qobuz",
            listOf(
                Song(
                    videoId = "cover",
                    title = "Cruel Summer",
                    artist = "Midnite String Quartet",
                    thumbnailUrl = null,
                    durationText = "2:58",
                ),
            ),
        )
        val found = runBlocking {
            SourceResolver.bestAcross(
                listOf(source),
                TrackMatcher.Target("Cruel Summer", "Taylor Swift", durationSec = 178),
                StreamRequest.Best,
                waitForAll = true,
                strictLength = true,
                requireSharedArtist = true,
                strictOriginal = true,
                allowArtistUnverified = true,
            )?.second
        }
        assertNull(found)
    }

    @Test
    fun `a localised stream is marked artist-unverified and a credited one is not`() {
        val localised = FakeSource("Qobuz", listOf(flowers("localised", "マイリー・サイラス")))
        val credited = FakeSource("Jio", listOf(flowers("credited", "Miley Cyrus")))
        val (localisedStream, _) = walk(localised, allowArtistUnverified = true)
        val (creditedStream, _) = walk(credited, allowArtistUnverified = true)
        assertTrue(localisedStream!!.artistUnverified)
        assertFalse(creditedStream!!.artistUnverified)
    }

    @Test
    fun `without the flag a first-play style call never returns an unverified stream`() {
        val source = FakeSource(
            "Qobuz",
            listOf(
                flowers("credited", "Miley Cyrus", "3:22"),
                flowers("localised", "マイリー・サイラス", "3:20"),
            ),
        )
        val (stream, _) = walk(source, allowArtistUnverified = false)
        assertNotNull(stream)
        assertFalse("directStream-style search must stay on credited rows", stream!!.artistUnverified)
        assertTrue(stream.url.endsWith("/credited"))
    }
}
