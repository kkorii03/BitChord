package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.telegram.TelegramClientManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OPT2 (phone 11-17 20:13-20:19 download batch): the download path walked
 * every source for video rows that playback never substitutes, asked parked
 * sources on every row (each refusal pushing their rest later), and opened
 * TDLib once per Telegram query.
 */
class KoDownloadWalkGateTest {

    @Before
    fun setUp() {
        SourceResolver.clearSearchAnswers()
        KoSourceCooldown.resetForTest()
        HqHostTracker.resetForTest()
    }

    @After
    fun tearDown() {
        SourceRegistry.activeForTest = null
        TelegramClientManager.walkHoldForTest = null
        SourceResolver.clearSearchAnswers()
        KoSourceCooldown.resetForTest()
    }

    private class FakeSource(
        override val configId: String,
        private val sourceKind: SourceKind = SourceKind.ADDON,
        private val rows: List<Song> = emptyList(),
    ) : MusicSource {
        override val displayName: String get() = configId
        override val kind: SourceKind get() = sourceKind
        var asked = 0
            private set
        var onSearch: () -> Unit = {}

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
            target: TrackMatcher.Target?,
        ): List<Song> {
            asked += 1
            onSearch()
            return rows
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId/$trackId", format = StreamFormat("flac"))
    }

    private fun target(isVideo: Boolean = false) =
        TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200, isVideo = isVideo)

    private fun flowers(id: String) = Song(
        videoId = id,
        title = "Flowers",
        artist = "Miley Cyrus",
        thumbnailUrl = null,
        durationText = "3:20",
    )

    private fun rest(id: String) {
        KoSourceCooldown.failed(id)
        KoSourceCooldown.failed(id)
        assertTrue(KoSourceCooldown.isResting(id))
    }

    // F1
    @Test
    fun `a video row keeps its own stream and no source is asked`() = runBlocking {
        val addon = FakeSource("Qobuz", rows = listOf(flowers("flowers-1")))
        SourceRegistry.activeForTest = listOf(addon)

        assertNull(SourceResolver.forDownload(target(isVideo = true), StreamRequest.Lossless))
        assertEquals("a video row must not walk the sources", 0, addon.asked)

        // The same recording as a song row still walks them.
        SourceResolver.forDownload(target(), StreamRequest.Lossless)
        assertTrue(addon.asked > 0)
    }

    // F2
    @Test
    fun `the download ladder skips a resting source without a probe turn`() = runBlocking {
        val addon = FakeSource("Qobuz", rows = listOf(flowers("flowers-1")))
        rest(addon.configId)
        // Another walk already spent this source's probe turn.
        assertTrue(KoSourceCooldown.takeRestProbe(addon.configId))
        val failures = KoSourceCooldown.failureMark(addon.configId)
        SourceRegistry.activeForTest = listOf(addon)

        assertNull(SourceResolver.forDownload(target(), StreamRequest.Lossless))
        assertEquals("a resting source must not be searched", 0, addon.asked)
        assertEquals(failures, KoSourceCooldown.failureMark(addon.configId))
        assertTrue(KoSourceCooldown.isResting(addon.configId))
    }

    @Test
    fun `the download ladder probes a resting source on its turn and a complete answer ends the rest`() =
        runBlocking {
            val addon = FakeSource("Qobuz")
            rest(addon.configId)
            SourceRegistry.activeForTest = listOf(addon)

            assertNull(SourceResolver.forDownload(target(), StreamRequest.Lossless))
            assertEquals(TrackMatcher.queries(target()).size, addon.asked)
            assertFalse("a conclusive probe answer clears the rest", KoSourceCooldown.isResting(addon.configId))
        }

    @Test
    fun `the pre-sound lossless walk never waits on a resting source`() = runBlocking {
        val addon = FakeSource("Qobuz", rows = listOf(flowers("flowers-1")))
        rest(addon.configId)

        // resolve()'s walk: no probe, even with the turn free.
        assertNull(matchAndStreamWithin(addon, target(), StreamRequest.Lossless, strictOriginal = true))
        assertEquals(0, addon.asked)
        assertTrue(KoSourceCooldown.isResting(addon.configId))
    }

    // F3
    @Test
    fun `a Telegram ladder walk holds one walk lease across its queries`() = runBlocking {
        val events = mutableListOf<Boolean>()
        TelegramClientManager.walkHoldForTest = { synchronized(events) { events += it } }
        val telegram = FakeSource("Telegram", SourceKind.TELEGRAM)
        val heldDuringSearch = mutableListOf<Boolean>()
        telegram.onSearch = { synchronized(events) { heldDuringSearch += events == listOf(true) } }
        val addon = FakeSource("Qobuz")
        SourceRegistry.activeForTest = listOf(telegram, addon)
        assertTrue("the walk must ask more than one query", TrackMatcher.queries(target()).size >= 2)

        assertNull(SourceResolver.forDownload(target(), StreamRequest.Lossless))
        assertEquals(TrackMatcher.queries(target()).size, telegram.asked)
        assertTrue(addon.asked > 0)
        assertEquals("one begin/end pair, only for the Telegram source", listOf(true, false), events)
        assertTrue("every Telegram query ran inside the hold", heldDuringSearch.all { it })
    }
}
