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

    /** The 11-05 case: Deezer files ILLIT's "체포하겠어" as "You're Under Arrest". */
    private fun arrestTarget() = TrackMatcher.Target("체포하겠어", "ILLIT", durationSec = 181)

    private fun row(id: String, title: String, artist: String, duration: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    private fun walk(source: MusicSource, allowArtistUnverified: Boolean): Pair<SourceStream?, SourceResolver.SearchOutcome?> =
        walk(source, target(), allowArtistUnverified)

    private fun walk(
        source: MusicSource,
        target: TrackMatcher.Target,
        allowArtistUnverified: Boolean,
    ): Pair<SourceStream?, SourceResolver.SearchOutcome?> =
        runBlocking {
            val report = SourceResolver.SearchReport()
            val found = SourceResolver.bestAcross(
                listOf(source),
                target,
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

    // ── A title filed under another script's name (11-05 '체포하겠어') ────────
    //
    // The FLAC was in the search results and the search said "no better copy"
    // because the exact-title gate dropped the row before hq-verify was asked.

    @Test
    fun `an other-script title row reaches the sound comparison as title-unverified`() {
        val source = FakeSource(
            "Deezer",
            listOf(row("deezer-1", "You're Under Arrest", "ILLIT", "3:01")),
        )
        val (stream, outcome) = walk(source, arrestTarget(), allowArtistUnverified = true)
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/deezer-1"))
        assertEquals("title-unverified", outcome!!.verdict)
    }

    @Test
    fun `without the upgrade flag an other-script title row is not offered`() {
        val source = FakeSource(
            "Deezer",
            listOf(row("deezer-1", "You're Under Arrest", "ILLIT", "3:01")),
        )
        val (stream, outcome) = walk(source, arrestTarget(), allowArtistUnverified = false)
        assertNull(stream)
        assertEquals("title", outcome!!.verdict)
    }

    @Test
    fun `a different song from the same search never becomes the HQ copy`() {
        // LastWave answered '체포하겠어' with 'Takedown' by HUNTR/X at 3:02.
        // Another script and the right length, so only the credit rules it out.
        val source = FakeSource(
            "LastWave",
            listOf(
                row("takedown", "Takedown", "HUNTR/X, EJAE, AUDREY NUNA, REI AMI", "3:02"),
            ),
        )
        val (stream, outcome) = walk(source, arrestTarget(), allowArtistUnverified = true)
        assertNull(stream)
        assertEquals("title", outcome!!.verdict)
    }

    @Test
    fun `a normally credited row is chosen before an other-script title row`() {
        val source = FakeSource(
            "Deezer",
            listOf(
                row("localised", "You're Under Arrest", "ILLIT", "3:01"),
                row("credited", "체포하겠어", "ILLIT", "3:02"),
            ),
        )
        val (stream, outcome) = walk(source, arrestTarget(), allowArtistUnverified = true)
        assertNotNull(stream)
        assertTrue(stream!!.url.endsWith("/credited"))
        assertEquals("match", outcome!!.verdict)
    }
}
