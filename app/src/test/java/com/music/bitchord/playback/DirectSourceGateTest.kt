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
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

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

    /**
     * A row refused for being the wrong file is remembered by source and row id
     * ([com.music.bitchord.ko.KoRefusedRows]), so one test's refused row would
     * be skipped by the next one that reuses the same id. Every test here starts
     * with that memory empty.
     */
    @Before
    fun resetRefusedRows() {
        com.music.bitchord.ko.KoRefusedRows.resetForTest()
    }

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
     * The 403 the phone reports is a host refusing a URL it minted for this
     * very track. Asking it again is the same refused request, so the walk
     * answers from the record the playback path left behind instead of spending
     * another round trip on a host that has already said no.
     */
    @Test
    fun aStreamThatAlreadyRefusedIsNotAskedAgainForTheSameTrack() = runBlocking {
        install(
            listOf(
                FakeSource("Deezer", listOf(row("dead-2", "Already Refused"))),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            StreamRefusalRecovery.noteRefusedStream(
                "https://Deezer/dead-2",
                "Deezer",
                IOException("HTTP 403"),
            )
            val probed = mutableListOf<String>()
            val stream = SourceResolver.directStream(target("Already Refused")) { candidate ->
                probed += candidate.url
                true
            }
            assertNull(stream)
            assertTrue("a refused URL must not be probed again: $probed", probed.isEmpty())
        } finally {
            StreamRefusalRecovery.resetForTest()
            install(emptyList())
        }
    }

    /**
     * A 403 is the host's failure, not a wrong file. The walk used to ask the
     * same source again for it, on the listener's first note.
     *
     * Counted in stream opens, not searches: the resolver memoises each
     * source's search answer, so the re-ask costs the next row's `/stream`
     * call. Each source holds two copies so a re-ask has a row to open.
     */
    @Test
    fun aHostRefusalCostsFewerSearchesThanAWrongFile() = runBlocking {
        val refusing = FakeSource(
            "Deezer",
            listOf(row("dead-4", "Host Refused"), row("dead-4b", "Host Refused")),
        )
        install(listOf(refusing, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        try {
            val stream = SourceResolver.directStream(target("Host Refused")) { candidate ->
                StreamRefusalRecovery.noteRefusedStream(candidate.url, "Deezer", IOException("HTTP 403"))
                false
            }
            assertNull(stream)
        } finally {
            StreamRefusalRecovery.resetForTest()
            install(emptyList())
        }
        // A wrong file is the row's: the same source is asked for another one.
        val wrongFile = FakeSource(
            "Deezer",
            listOf(row("wrong-4", "Wrong File"), row("wrong-4b", "Wrong File")),
        )
        install(listOf(wrongFile, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        try {
            assertNull(SourceResolver.directStream(target("Wrong File")) { false })
        } finally {
            install(emptyList())
        }
        assertTrue(
            "refused=${refusing.opens} wrong file=${wrongFile.opens}",
            refusing.opens < wrongFile.opens,
        )
    }

    /**
     * The other half: a refusal of one source's copy must not cost the listener
     * the copy another source can still serve. The refused URL is skipped, not
     * asked again, and the walk goes on to the one that serves.
     */
    @Test
    fun aRefusedStreamIsSkippedAndAnotherSourceStillPlays() = runBlocking {
        install(
            listOf(
                FakeSource("Deezer", listOf(row("dead-3", "Second Chance"))),
                FakeSource("Qobuz", listOf(row("live-3", "Second Chance"))),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            StreamRefusalRecovery.noteRefusedStream(
                "https://Deezer/dead-3",
                "Deezer",
                IOException("HTTP 403"),
            )
            val probed = mutableListOf<String>()
            val stream = SourceResolver.directStream(target("Second Chance")) { candidate ->
                probed += candidate.url
                true
            }
            assertEquals("https://Qobuz/live-3", stream?.url)
            assertTrue(
                "the refused URL must not be probed again: $probed",
                probed.none { it.contains("Deezer") },
            )
        } finally {
            StreamRefusalRecovery.resetForTest()
            install(emptyList())
        }
    }

    /**
     * A refusal also stops the *next* open of that track from walking the
     * addons on the loader thread. Once a substitution has broken a track the
     * resolver takes YouTube for it — see [StreamChoice.refuseSubstitutes] — so
     * the settle in [AudioCache] must not spend a search in front of the first
     * note whose answer is thrown away, nor pin the stream that was just
     * refused.
     */
    @Test
    fun theLoaderThreadSettleDoesNotWalkTheAddonsAfterARefusal() {
        val root = File(".").canonicalFile
        val cache = listOf("app/", "").map {
            File(root, "${it}src/main/java/com/music/bitchord/playback/AudioCache.kt")
        }.first { it.exists() }.readText()
        val settle = cache.substringAfter("private fun settleDirectEntry(")
            .substringBefore("dropYouTubeEntry(key, videoId)")
        assertTrue(
            "the settle must skip the addon walk once this track's substitutions are refused",
            Regex("""val addon = if \(StreamChoice\.substitutesRefused\(videoId\)\)""")
                .containsMatchIn(settle),
        )
    }

    /** The ordinary path is untouched: a candidate that serves its bytes still wins. */
    @Test
    fun aServingCandidateStillWinsWithoutWaitingForTheSlowSource() = runBlocking {
        install(
            listOf(
                FakeSource("Deezer", listOf(row("live-4", "Serving Copy"))),
                FakeSource("Qobuz", listOf(row("live-4", "Serving Copy")), searchDelayMs = 3_000),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            val stream = SourceResolver.directStream(target("Serving Copy")) { true }
            assertEquals("https://Deezer/live-4", stream?.url)
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
     *
     * Each of these uses its own title and artist: the search memo is keyed by
     * the target, and a shared one made these tests read each other's answers.
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
                            title = "Jazz Probe",
                            artist = "Original Act Tribute",
                            thumbnailUrl = null,
                            durationText = "3:20",
                            albumName = "Original Act Cool Jazz Tribute",
                        ),
                    ),
                ),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            assertNull(
                "a tribute is not the recording that was queued",
                SourceResolver.directStream(TrackMatcher.Target("Jazz Probe", "Original Act", durationSec = 200)) { true },
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
                            title = "Cover Probe",
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
            assertNull(
                SourceResolver.directStream(TrackMatcher.Target("Cover Probe", "Original Act", durationSec = 200)) { true },
            )
        } finally {
            install(emptyList())
        }
    }

    /**
     * Opening is not being the recording. A FLAC of a *different* song, of a
     * similar length, serves its bytes perfectly and passed every check this
     * path used to make — so it played in place of the queued track from its
     * first note. The direct stream now clears the same sound comparison an
     * upgrade does, and a candidate the comparison refuses is not served: the
     * track falls back to YouTube, where the second look upgrades it properly.
     */
    @Test
    fun aCandidateTheComparisonRefusesIsNotServed() = runBlocking {
        install(
            listOf(
                FakeSource("Deezer", listOf(song("other-song", "Unproved Probe"))),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            val compared = mutableListOf<String>()
            val stream = SourceResolver.directStream(
                TrackMatcher.Target("Unproved Probe", "Eljohnzc", durationSec = 200),
                verify = { candidate ->
                    compared += candidate.url
                    false
                },
                probe = { true },
            )

            assertNull("an unproved stream must not play in place of the track", stream)
            assertEquals("the candidate must be compared, not played: $compared", 1, compared.size)
        } finally {
            install(emptyList())
        }
    }

    /** The same candidate plays once the comparison accepts it. */
    @Test
    fun aProvedCandidateIsServed() = runBlocking {
        install(
            listOf(
                FakeSource("Deezer", listOf(song("proved-1", "Proved Probe"))),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            val stream = SourceResolver.directStream(
                TrackMatcher.Target("Proved Probe", "Eljohnzc", durationSec = 200),
                verify = { true },
                probe = { true },
            )

            assertEquals("https://Deezer/proved-1", stream?.url)
        } finally {
            install(emptyList())
        }
    }

    /** The cheap refusal comes first: a candidate that cannot serve its bytes is not decoded for comparison. */
    @Test
    fun aCandidateThatRefusesItsBytesIsNotCompared() = runBlocking {
        install(
            listOf(
                FakeSource("Deezer", listOf(song("unopened-1", "Unopened Probe"))),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        try {
            var compared = false
            val stream = SourceResolver.directStream(
                TrackMatcher.Target("Unopened Probe", "Eljohnzc", durationSec = 200),
                verify = {
                    compared = true
                    true
                },
                probe = { false },
            )

            assertNull(stream)
            assertFalse("a stream that does not open is not worth decoding", compared)
        } finally {
            install(emptyList())
        }
    }

    private fun target() = TrackMatcher.Target("Golden Boy", "Eljohnzc", durationSec = 200)

    /** A row for [title] that the matcher accepts as the original recording. */
    private fun song(id: String, title: String) = Song(
        videoId = id,
        title = title,
        artist = "Eljohnzc",
        thumbnailUrl = null,
        durationText = "3:20",
    )

    private fun target(title: String = "Golden Boy") =
        TrackMatcher.Target(title, "Eljohnzc", durationSec = 200)

    private fun row(id: String, title: String = "Golden Boy") = Song(
        videoId = id,
        title = title,
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
        private val searchDelayMs: Long = 0L,
    ) : MusicSource {
        override val displayName: String get() = configId

        @Volatile
        var opens = 0

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            if (searchDelayMs > 0) kotlinx.coroutines.delay(searchDelayMs)
            return rows
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream {
            opens++
            return SourceStream(
                url = "https://$configId/$trackId",
                format = format,
                sourceConfigId = configId,
                trackId = trackId,
            )
        }
    }
}
