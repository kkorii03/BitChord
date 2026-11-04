package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Direct Source Stream's first-audio rule: when every addon is resting after
 * repeated failures, waiting the whole direct-source window can only end in
 * "no addon", while YouTube could have started at once.
 *
 * And the other half of the same rule: an addon's URL is only handed to the
 * player once it has been opened. The lookup used to return it unopened, so a
 * dead one was discovered by the player after playback had started on it.
 */
class DirectSourceGateTest {

    @Test
    fun allAddonsRestingSkipsTheWindow() {
        assertFalse(DirectSourceGate.directLookupWorthWaiting(listOf(true, true)))
    }

    @Test
    fun oneAwakeAddonStillDeservesTheWindow() {
        assertTrue(DirectSourceGate.directLookupWorthWaiting(listOf(true, false)))
        assertTrue(DirectSourceGate.directLookupWorthWaiting(listOf(false)))
    }

    @Test
    fun noDirectAddonsCannotProduceAnAnswer() {
        assertFalse(DirectSourceGate.directLookupWorthWaiting(emptyList()))
    }

    /**
     * Phone 11-03: every first play hung. The track change only told the gate
     * about the new row once it had sounded, but its loader was the one held
     * at the gate, so it never sounded. The transition must tell the gate
     * even when the row is not audible yet.
     */
    @Test
    fun aTrackChangeAdmitsTheNewRowBeforeItSounds() {
        val root = File(".").canonicalFile
        val service = listOf("app/", "").map {
            File(root, "${it}src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        }.first { it.exists() }.readText()
        val hook = Regex(
            """if \(currentAudible\) \{\s*prefetchAround\(exoPlayer\)\s*\} else if \(DirectSourceGate\.isActive\(\)\) \{[^}]*driveDirectWindow\(\)""",
        )
        assertTrue("the track change must call driveDirectWindow when not yet audible", hook.containsMatchIn(service))
        val drive = service.substringAfter("private fun driveDirectWindow()").substringBefore("if (!DirectSourceGate.isActive()")
        assertTrue("driveDirectWindow must tell the gate before its isPlaying check", "DirectSourceGate.onPlaying(" in drive)
    }

    /**
     * Phone 11-03: the lookup returned the addon's URL without ever opening it,
     * so a 403 from the catalogue's host was discovered by the player after it
     * had already started on that stream — seven seconds of refusals, then a
     * YouTube fallback 12s after the track was selected. A candidate that
     * refuses its bytes must be dropped inside the lookup instead.
     */
    @Test
    fun aCandidateThatRefusesItsBytesIsNotHandedToThePlayer() = runBlocking {
        install(
            listOf(
                // The dead candidate is the better one on paper, so it is the one
                // the walk reaches for: refused, and the copy behind it plays.
                FakeSource("Deezer", listOf(row("dead-1")), StreamFormat("flac", bitDepth = 24, sampleRateHz = 96_000)),
                FakeSource("Qobuz", listOf(row("live-1")), StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100)),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            val probed = mutableListOf<String>()
            val stream = SourceResolver.directStream(target()) { candidate ->
                probed += candidate.url
                !candidate.url.contains("Deezer")
            }

            assertEquals("https://Qobuz/live-1", stream?.url)
            assertTrue(
                "the refused candidate must be probed rather than played: $probed",
                "https://Deezer/dead-1" in probed,
            )
        } finally {
            install(emptyList())
        }
    }

    /** With nothing left that serves its bytes, the track is YouTube's — the gate's own fallback. */
    @Test
    fun everyCandidateRefusingLeavesTheTrackToYouTube() = runBlocking {
        install(
            listOf(
                FakeSource("Deezer", listOf(row("dead-1"))),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            assertNull(SourceResolver.directStream(target()) { false })
        } finally {
            install(emptyList())
        }
    }

    /**
     * Phone 11-04: asking for Gnarls Barkley's "Crazy" played a saxophone
     * tribute. The lookup ranked a Qobuz row credited to "Gnarls Barkley
     * Tribute" off the album "Gnarls Barkley Cool Jazz Tribute" first and
     * served it, because nothing here asked whether the row was the original
     * recording — only whether its bytes opened.
     */
    @Test
    fun aTributeIsNotServedInPlaceOfTheSong() = runBlocking {
        install(
            listOf(
                FakeSource(
                    "Qobuz",
                    listOf(
                        Song(
                            videoId = "tribute-1",
                            title = "Golden Boy",
                            artist = "Eljohnzc Tribute",
                            thumbnailUrl = null,
                            durationText = "3:20",
                            albumName = "Eljohnzc Cool Jazz Tribute",
                        ),
                    ),
                ),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            assertNull(
                "a tribute is not the recording that was queued",
                SourceResolver.directStream(target()) { true },
            )
        } finally {
            install(emptyList())
        }
    }

    /** A catalogue copy credited to someone else is not this song either. */
    @Test
    fun aRowCreditedToAnotherArtistIsNotServed() = runBlocking {
        install(
            listOf(
                FakeSource(
                    "Deezer",
                    listOf(
                        Song(
                            videoId = "other-1",
                            title = "Golden Boy",
                            artist = "Someone Else",
                            thumbnailUrl = null,
                            durationText = "3:20",
                        ),
                    ),
                ),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            assertNull(SourceResolver.directStream(target()) { true })
        } finally {
            install(emptyList())
        }
    }

    private fun target() = TrackMatcher.Target("Golden Boy", "Eljohnzc", durationSec = 200)

    private fun row(id: String) = Song(
        videoId = id,
        title = "Golden Boy",
        artist = "Eljohnzc",
        thumbnailUrl = null,
        durationText = "3:20",
    )

    /** Puts just these sources in the registry, in this order — as the upgrade tests do. */
    private fun install(sources: List<FakeSource>) {
        SourceRegistry.configs.value = sources.map { source ->
            SourceConfig(
                id = source.configId,
                kind = source.kind,
                label = source.configId,
                baseUrl = if (source.kind == SourceKind.YOUTUBE) "" else "https://${source.configId}",
            )
        }
        instancesField.set(SourceRegistry, sources.associateBy { it.configId })
    }

    private val instancesField = SourceRegistry::class.java
        .getDeclaredField("instances")
        .apply { isAccessible = true }

    private class FakeSource(
        override val configId: String,
        private val rows: List<Song> = emptyList(),
        private val format: StreamFormat = StreamFormat("flac"),
        override val kind: SourceKind = SourceKind.ADDON,
    ) : MusicSource {
        override val displayName: String get() = configId

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> = rows

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(
                url = "https://$configId/$trackId",
                format = format,
                sourceConfigId = configId,
                trackId = trackId,
            )
    }
}
