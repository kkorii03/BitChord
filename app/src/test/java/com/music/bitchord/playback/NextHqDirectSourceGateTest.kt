package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
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
import com.music.bitchord.ko.KoDirectHqAhead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Direct Source Stream's window for the next track asks Telegram and JioSaavn
 * too, so the next song starts on their proved copy instead of on YouTube with
 * a swap 12-28 s in (user log 11-07/08: 'Still D.R.E.' started on YouTube in the
 * window while Telegram held the FLAC, and swapped to it at 11.7 s).
 *
 * The old lookup asked addons only, so each test that expects the
 * high-quality copy from [DirectSourceGate.lookup] fails on it.
 */
class NextHqDirectSourceGateTest {

    private val flac = StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100)
    private val aac320 = StreamFormat("aac", kbps = 320)

    @Before
    fun reset() {
        com.music.bitchord.ko.KoRefusedRows.resetForTest()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.clearSearchAnswers()
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = false
        KoDirectHqAhead.proofForTest = { true }
        KoDirectHqAhead.noteNextStream("", null)
        DirectSourceGate.onPlaying(setOf("hq-playing"))
    }

    @After
    fun restore() {
        KoDirectHqAhead.proofForTest = null
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = null
        DirectSourceGate.onPlaying(emptySet())
        install(emptyList())
    }

    @Test
    fun theWindowAsksTelegramAndTheNextSongStartsOnItsProvedCopy() = runBlocking {
        val addon = FakeSource("Addon")
        val telegram = FakeSource("Telegram", listOf(row("tg-1", "Ahead One")), flac, SourceKind.TELEGRAM)
        install(listOf(addon, telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        DirectSourceGate.openNext("hq-next-1")

        val stream = DirectSourceGate.lookup("hq-next-1", target("Ahead One"))

        assertEquals("the next song must start on Telegram's copy", "https://Telegram/tg-1", stream?.url)
        // The pin the resolver and the read-ahead write names this source, which
        // is the clock the lyrics are shown on from the first note.
        assertEquals("Telegram", stream?.sourceConfigId)
        assertTrue("the addons are still asked first", addon.searches > 0)
    }

    @Test
    fun aCopyTheProofRefusesNeverStartsTheSong() = runBlocking {
        val telegram = FakeSource("Telegram", listOf(row("tg-2", "Ahead Two")), flac, SourceKind.TELEGRAM)
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        KoDirectHqAhead.proofForTest = { false }
        DirectSourceGate.openNext("hq-next-2")

        assertNull(DirectSourceGate.lookup("hq-next-2", target("Ahead Two")))
        assertTrue("the copy must have been offered to the proof", telegram.opens > 0)
    }

    @Test
    fun theTrackAlreadyPlayingDoesNotAskTheHighQualitySources() = runBlocking {
        val telegram = FakeSource("Telegram", listOf(row("tg-3", "Ahead Three")), flac, SourceKind.TELEGRAM)
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        DirectSourceGate.onPlaying(setOf("hq-now-3"))

        assertNull(DirectSourceGate.lookup("hq-now-3", target("Ahead Three")))
        assertEquals("a playing track gets its upgrade from the second look", 0, telegram.searches)
    }

    @Test
    fun aQueueChangeDropsTheHighQualityHalf() = runBlocking {
        val telegram = FakeSource(
            "Telegram",
            listOf(row("tg-4", "Ahead Four")),
            flac,
            SourceKind.TELEGRAM,
            searchDelayMs = 8_000,
        )
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        DirectSourceGate.openNext("hq-next-4")

        val pending = async(Dispatchers.Default) { DirectSourceGate.lookup("hq-next-4", target("Ahead Four")) }
        withTimeout(3_000) { telegram.searching.await() }
        val movedAt = System.nanoTime()
        DirectSourceGate.openNext("hq-other-4")

        assertNull("a track that is no longer next must not start", withTimeout(3_000) { pending.await() })
        assertTrue(
            "the waiters are released at once, not after the search",
            (System.nanoTime() - movedAt) / 1_000_000 < 3_000,
        )
        assertTrue("the Telegram search must be stopped, not left to download", telegram.cancelled)
    }

    /** A proof still running when the song starts must not hold its first note: today's path takes over. */
    @Test
    fun theSongStartingBeforeTheProofFallsBackAtOnce() = runBlocking {
        val telegram = FakeSource(
            "Telegram",
            listOf(row("tg-11", "Ahead Eleven")),
            flac,
            SourceKind.TELEGRAM,
            searchDelayMs = 8_000,
        )
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        DirectSourceGate.openNext("hq-next-11")

        val pending = async(Dispatchers.Default) { DirectSourceGate.lookup("hq-next-11", target("Ahead Eleven")) }
        withTimeout(3_000) { telegram.searching.await() }
        DirectSourceGate.onPlaying(setOf("hq-next-11"))

        assertNull(withTimeout(3_000) { pending.await() })
        assertTrue(telegram.cancelled)
    }

    /** A row that streams another upload: a copy proved for its media id would carry the wrong clock. */
    @Test
    fun aRowStreamingAnotherUploadIsLeftOut() = runBlocking {
        val telegram = FakeSource("Telegram", listOf(row("tg-12", "Ahead Twelve")), flac, SourceKind.TELEGRAM)
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        DirectSourceGate.openNext("hq-next-12")
        KoDirectHqAhead.noteNextStream("hq-next-12", "hq-recording-12")

        assertNull(DirectSourceGate.lookup("hq-next-12", target("Ahead Twelve")))
        assertEquals(0, telegram.searches)
    }

    @Test
    fun lowPowerSkipsTheHighQualitySources() = runBlocking {
        val telegram = FakeSource("Telegram", listOf(row("tg-5", "Ahead Five")), flac, SourceKind.TELEGRAM)
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        AppSettings.lowPower.value = true
        DirectSourceGate.openNext("hq-next-5")

        assertNull(DirectSourceGate.lookup("hq-next-5", target("Ahead Five")))
        assertEquals(0, telegram.searches)
    }

    @Test
    fun aMeteredConnectionSkipsTheHighQualitySources() = runBlocking {
        val telegram = FakeSource("Telegram", listOf(row("tg-6", "Ahead Six")), flac, SourceKind.TELEGRAM)
        install(listOf(FakeSource("Addon"), telegram, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        AppSettings.meteredConnection.value = true
        DirectSourceGate.openNext("hq-next-6")

        assertNull(DirectSourceGate.lookup("hq-next-6", target("Ahead Six")))
        assertEquals(0, telegram.searches)
    }

    /** Direct Source Stream still means the addon's copy when it has one; the other half is stopped. */
    @Test
    fun anAddonAnswerStillWins() = runBlocking {
        var hqStopped = false
        val answer = KoDirectHqAhead.lookup(
            "Ahead Seven",
            Job(),
            addons = {
                // The addon walk takes a moment, so the other half is under way.
                delay(100)
                SourceStream("https://Addon/ad-7", format = flac, sourceConfigId = "Addon")
            },
            hq = {
                try {
                    delay(10_000)
                    SourceStream("https://Telegram/tg-7", format = flac, sourceConfigId = "Telegram")
                } catch (e: CancellationException) {
                    hqStopped = true
                    throw e
                }
            },
        )
        assertEquals("https://Addon/ad-7", answer?.url)
        assertTrue(hqStopped)
    }

    @Test
    fun aLosslessCopyBeatsAQuickerLossyOne() = runBlocking {
        install(
            listOf(
                FakeSource("Telegram", listOf(row("tg-8", "Ahead Eight")), flac, SourceKind.TELEGRAM, searchDelayMs = 300),
                FakeSource("JioSaavn", listOf(row("js-8", "Ahead Eight")), aac320, SourceKind.JIOSAAVN),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        assertEquals(
            "https://Telegram/tg-8",
            KoDirectHqAhead.stream(target("Ahead Eight"), verify = { true })?.url,
        )
    }

    @Test
    fun aLossyCopyStartsTheSongWhenEverySourceAnswered() = runBlocking {
        install(
            listOf(
                FakeSource("Telegram", emptyList(), flac, SourceKind.TELEGRAM),
                FakeSource("JioSaavn", listOf(row("js-9", "Ahead Nine")), aac320, SourceKind.JIOSAAVN),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        assertEquals(
            "https://JioSaavn/js-9",
            KoDirectHqAhead.stream(target("Ahead Nine"), verify = { true })?.url,
        )
    }

    /** Under Direct Source Stream nothing upgrades a substitute later, so 320 must not shut out a FLAC. */
    @Test
    fun aLossyCopyWaitsWhileALosslessSourceIsResting() = runBlocking {
        install(
            listOf(
                FakeSource("Telegram", listOf(row("tg-10", "Ahead Ten")), flac, SourceKind.TELEGRAM),
                FakeSource("JioSaavn", listOf(row("js-10", "Ahead Ten")), aac320, SourceKind.JIOSAAVN),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        repeat(5) { SourceResolver.SourceCooldown.failed("Telegram") }
        assertTrue("precondition", SourceResolver.SourceCooldown.isResting("Telegram"))

        assertNull(KoDirectHqAhead.stream(target("Ahead Ten"), verify = { true }))
    }

    @Test
    fun thePolicyNeedsTheNextTrackPowerUnmeteredAProofAndASource() {
        fun asks(
            next: Boolean = true,
            lowPower: Boolean = false,
            metered: Boolean = false,
            eligible: Boolean = true,
            proof: Boolean = true,
            sources: Int = 1,
        ) = KoDirectHqAhead.asksAhead(next, lowPower, metered, eligible, proof, sources)

        assertTrue(asks())
        assertFalse("playing", asks(next = false))
        assertFalse("low power", asks(lowPower = true))
        assertFalse("metered", asks(metered = true))
        assertFalse("video, saved, reverted or refused row", asks(eligible = false))
        assertFalse("no proof", asks(proof = false))
        assertFalse("every source resting", asks(sources = 0))
    }

    @Test
    fun theHeadEstimateFollowsTheFormat() {
        val cd = KoDirectHqAhead.estimatedHeadBytes(flac)!!
        val hiRes = KoDirectHqAhead.estimatedHeadBytes(StreamFormat("flac", bitDepth = 24, sampleRateHz = 96_000))!!
        assertTrue("16/44.1 about 19 MB, got $cd", cd in 17_000_000L..22_000_000L)
        assertTrue("24/96 about 63 MB, got $hiRes", hiRes in 58_000_000L..68_000_000L)
        assertTrue(KoDirectHqAhead.headWithinCap(flac))
        assertTrue(KoDirectHqAhead.headWithinCap(aac320))
        assertTrue(KoDirectHqAhead.headWithinCap(StreamFormat("flac", bitDepth = 24, sampleRateHz = 96_000)))
        assertFalse(KoDirectHqAhead.headWithinCap(StreamFormat("flac", bitDepth = 24, sampleRateHz = 192_000)))
        assertTrue("an unstated size is not guessed", KoDirectHqAhead.estimatedHeadBytes(StreamFormat("flac")) == null)
    }

    @Test
    fun aHiResHeadTooBigToReadAheadIsNotProved() = runBlocking {
        val hiRes = StreamFormat("flac", bitDepth = 24, sampleRateHz = 192_000)
        install(
            listOf(
                FakeSource("Telegram", listOf(row("tg-11", "Ahead Eleven")), hiRes, SourceKind.TELEGRAM),
                FakeSource("YouTube", kind = SourceKind.YOUTUBE),
            ),
        )
        var proved = false
        val answer = KoDirectHqAhead.stream(target("Ahead Eleven"), verify = { proved = true; true })
        assertNull(answer)
        assertFalse("the proof must not download a 126 MB head ahead of a skippable song", proved)
    }

    private fun target(title: String) = TrackMatcher.Target(title, "Eljohnzc", durationSec = 200)

    private fun row(id: String, title: String) = Song(
        videoId = id,
        title = title,
        artist = "Eljohnzc",
        thumbnailUrl = null,
        durationText = "3:20",
    )

    private fun install(sources: List<FakeSource>) {
        SourceRegistry.configs.value = sources.map { source ->
            SourceConfig(
                id = source.configId,
                kind = source.kind,
                label = source.configId,
                baseUrl = if (source.kind == SourceKind.YOUTUBE) "" else "https://${source.configId}",
                // Telegram and JioSaavn ship switched off.
                enabled = true,
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

        @Volatile
        var searches = 0

        @Volatile
        var cancelled = false

        val searching = CompletableDeferred<Unit>()

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            searches++
            searching.complete(Unit)
            try {
                if (searchDelayMs > 0) delay(searchDelayMs)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
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
