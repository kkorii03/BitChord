package com.music.bitchord.ko

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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [KO] TG2: the owner's Telegram chats file ILLIT's '체포하겠어' as the file
 * 'You're Under Arrest' by 'ILLIT'. TDLib's search is literal, so the title
 * queries answer nothing and the file never reached the `title-unverified`
 * grade. A walk that may offer such a row now asks Telegram once more, by the
 * act's Latin name, after every title query missed.
 */
class KoTelegramArtistQueryTest {

    @Before
    fun setUp() {
        SourceResolver.clearSearchAnswers()
        KoSourceCooldown.resetForTest()
        HqHostTracker.resetForTest()
    }

    @After
    fun tearDown() {
        SourceResolver.clearSearchAnswers()
        KoSourceCooldown.resetForTest()
    }

    /** Answers [byQuery] for a query it names (case-insensitively), nothing otherwise; records every query. */
    private class FakeSource(
        override val configId: String,
        private val sourceKind: SourceKind = SourceKind.TELEGRAM,
        private val byQuery: Map<String, List<Song>> = emptyMap(),
    ) : MusicSource {
        override val displayName: String get() = configId
        override val kind: SourceKind get() = sourceKind
        val asked = mutableListOf<String>()

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            asked += query
            return byQuery.entries.firstOrNull { it.key.equals(query, ignoreCase = true) }?.value.orEmpty()
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId/$trackId", format = StreamFormat("flac"))
    }

    private val arrest = TrackMatcher.Target("체포하겠어", "ILLIT", durationSec = 181)

    private fun row(id: String, title: String, artist: String, duration: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    /** One artist-only page: the act's other songs, one of them as long as the target, and the target. */
    private fun illitPage(prefix: String) = listOf(
        row("$prefix-magnetic", "Magnetic", "ILLIT", "2:40"),
        row("$prefix-lucky", "Lucky Girl Syndrome", "ILLIT", "3:04"),
        row("$prefix-arrest", "You're Under Arrest", "ILLIT", "3:01"),
        row("$prefix-cherish", "Cherish (My Love)", "ILLIT", "2:47"),
    )

    private suspend fun walk(
        source: MusicSource,
        target: TrackMatcher.Target = arrest,
        allowArtistUnverified: Boolean = true,
    ) = SourceResolver.matchAndStreamForKo(
        source,
        target,
        StreamRequest.Best,
        waitForAll = true,
        strictLength = true,
        requireSharedArtist = true,
        strictOriginal = true,
        allowArtistUnverified = allowArtistUnverified,
    )

    @Test
    fun `the Latin-filed file reaches the ladder as title-unverified through one artist-only query`() = runBlocking {
        val telegram = FakeSource("Telegram", byQuery = mapOf("ILLIT" to illitPage("tg2a")))

        val outcome = walk(telegram)

        assertEquals("title-unverified", outcome.verdict)
        assertEquals("You're Under Arrest", outcome.best?.title)
        assertTrue(outcome.stream != null)
        val titleQueries = TrackMatcher.queries(arrest)
        assertEquals("the title queries first, then exactly one artist-only query", titleQueries + "illit", telegram.asked)
    }

    @Test
    fun `a page of the act's other songs marks only the target's own rows for the words check`() = runBlocking {
        val page = illitPage("tg2v") + row("tg2v-remix", "체포하겠어 (Remix)", "ILLIT", "2:55")
        val telegram = FakeSource("Telegram", byQuery = mapOf("ILLIT" to page))

        walk(telegram)

        assertTrue(KoVersionGuard.risky("Telegram", "tg2v-arrest"))
        assertFalse("another song of the act is not a version of this one", KoVersionGuard.risky("Telegram", "tg2v-magnetic"))
        assertFalse(KoVersionGuard.risky("Telegram", "tg2v-cherish"))
    }

    @Test
    fun `no artist-only query for a source other than Telegram`() = runBlocking {
        val addon = FakeSource("Qobuz", SourceKind.ADDON, mapOf("ILLIT" to illitPage("tg2b")))

        val outcome = walk(addon)

        assertEquals(TrackMatcher.queries(arrest), addon.asked)
        assertNull(outcome.stream)
    }

    @Test
    fun `no artist-only query when the walk may not offer a title-unverified row`() = runBlocking {
        val telegram = FakeSource("Telegram", byQuery = mapOf("ILLIT" to illitPage("tg2c")))

        val outcome = walk(telegram, allowArtistUnverified = false)

        assertEquals(TrackMatcher.queries(arrest), telegram.asked)
        assertNull(outcome.stream)
    }

    @Test
    fun `no artist-only query for a Latin title`() = runBlocking {
        val target = TrackMatcher.Target("Tick-Tack", "ILLIT", durationSec = 181)
        val telegram = FakeSource("Telegram", byQuery = mapOf("ILLIT" to illitPage("tg2d")))

        walk(telegram, target)

        assertEquals(TrackMatcher.queries(target), telegram.asked)
    }

    @Test
    fun `no artist-only query for a Hangul-only artist`() = runBlocking {
        val target = TrackMatcher.Target("체포하겠어", "아일릿", durationSec = 181)
        val telegram = FakeSource("Telegram", byQuery = mapOf("ILLIT" to illitPage("tg2e")))

        walk(telegram, target)

        assertEquals(TrackMatcher.queries(target), telegram.asked)
    }

    @Test
    fun `no artist-only query once a title query found the recording`() = runBlocking {
        val hangul = row("tg2f-hangul", "체포하겠어", "ILLIT", "3:01")
        val titleQuery = TrackMatcher.queries(arrest).first()
        val telegram = FakeSource(
            "Telegram",
            byQuery = mapOf(titleQuery to listOf(hangul), "ILLIT" to illitPage("tg2f")),
        )

        val outcome = walk(telegram)

        assertEquals("match", outcome.verdict)
        assertEquals(listOf(titleQuery), telegram.asked)
    }

    @Test
    fun `an artist-only miss keeps the title queries' verdict`() = runBlocking {
        val others = illitPage("tg2g").filterNot { it.title == "You're Under Arrest" || it.title == "Lucky Girl Syndrome" }
        val telegram = FakeSource("Telegram", byQuery = mapOf("ILLIT" to others))

        val outcome = walk(telegram)

        assertEquals(TrackMatcher.queries(arrest) + "illit", telegram.asked)
        assertEquals("title", outcome.verdict)
        assertEquals(0, outcome.results)
        assertNull(outcome.stream)
    }

    @Test
    fun `the Latin name comes from the act's own credit`() {
        assertEquals("illit", TrackMatcher.latinArtistQuery(arrest))
        assertEquals("illit", TrackMatcher.latinArtistQuery(arrest.copy(artist = "아일릿 (ILLIT)")))
        assertNull(TrackMatcher.latinArtistQuery(arrest.copy(artist = "아일릿")))
        // A title that already carries its Latin half asks for it through the title queries.
        assertNull(TrackMatcher.latinArtistQuery(arrest.copy(title = "체포하겠어 (You're Under Arrest)")))
        // No runtime, no title-unverified row: the query could not help.
        assertNull(TrackMatcher.latinArtistQuery(arrest.copy(durationSec = null)))
    }
}
