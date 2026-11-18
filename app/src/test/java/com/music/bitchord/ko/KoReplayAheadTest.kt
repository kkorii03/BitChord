package com.music.bitchord.ko

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
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-18 16:00, WKZO-CWeOVA (Direct Source Stream on, Private Hi-Res
 * addon): the song had a YouTube pin from a play ten minutes earlier, still
 * inside StreamChoice's 15-minute TTL. At the window opening the read-ahead
 * skipped its lookup because something was pinned, so the Private Hi-Res FLAC
 * was looked for only after the first note and the listener tapped upgrade.
 *
 * The old prefetchDirect had no else branch for a pinned track, so
 * [theWindowReadAheadStartsTheReplayLookup] fails on it (and this class does
 * not compile against it).
 */
class KoReplayAheadTest {

    private val id = "ra-next-1"
    private val target = TrackMatcher.Target("Replay One", "Eljohnzc", durationSec = 200)
    private val defaultCouldStill = KoReplayAhead.couldStillUpgrade

    @Before
    fun reset() {
        KoRefusedRows.resetForTest()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.clearSearchAnswers()
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = false
        KoDirectHqAhead.proofForTest = { true }
        KoDirectHqAhead.noteNextStream("", null)
        // android.net.Uri is a stub here; the real rule is QualityUpgrade.couldStillUpgrade.
        KoReplayAhead.couldStillUpgrade = { true }
        DirectSourceGate.onPlaying(setOf("ra-playing"))
    }

    @After
    fun restore() {
        QualityUpgrade.forget(id)
        StreamChoice.forget(id)
        KoReplayAhead.couldStillUpgrade = defaultCouldStill
        KoDirectHqAhead.proofForTest = null
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = null
        DirectSourceGate.onPlaying(emptySet())
        install(emptyList())
    }

    @Test
    fun aNextTrackStillPinnedToYouTubeHasItsLookupRunInTheWindow() = runBlocking<Unit> {
        val addon = FakeSource("Addon", searchDelayMs = 5_000)
        install(listOf(addon, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        StreamChoice.remember(id, SourceStream("https://yt/opus"), substituted = false)
        DirectSourceGate.openNext(id)

        assertTrue("a pinned next track must get its lookup", KoReplayAhead.start(id, target))

        withTimeout(3_000) { addon.searching.await() }
        assertTrue("the second look is told to wait for it", QualityUpgrade.isPending(id))
        assertNotNull("the running lookup is what the second look reads", DirectSourceGate.stillLooking(id))
        assertEquals("the YouTube pin and its cache entry are left alone", "https://yt/opus", StreamChoice.of(id)?.url)
    }

    @Test
    fun anAddonPinIsLeftAlone() = runBlocking<Unit> {
        val addon = FakeSource("Addon")
        install(listOf(addon, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        StreamChoice.remember(id, SourceStream("https://Addon/a-1", sourceConfigId = "Addon"), substituted = true)
        DirectSourceGate.openNext(id)

        assertFalse(KoReplayAhead.start(id, target))
        assertEquals(0, addon.searches)
        assertFalse(QualityUpgrade.isPending(id))
    }

    @Test
    fun aTrackWhoseSecondLookAnsweredIsNotSearchedAgain() = runBlocking<Unit> {
        val addon = FakeSource("Addon")
        install(listOf(addon, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        StreamChoice.remember(id, SourceStream("https://yt/opus"), substituted = false)
        DirectSourceGate.openNext(id)
        KoReplayAhead.couldStillUpgrade = { false }

        assertFalse(KoReplayAhead.start(id, target))
        assertEquals(0, addon.searches)
    }

    @Test
    fun lowPowerAndVideoRowsStartNothing() = runBlocking<Unit> {
        val addon = FakeSource("Addon")
        install(listOf(addon, FakeSource("YouTube", kind = SourceKind.YOUTUBE)))
        StreamChoice.remember(id, SourceStream("https://yt/opus"), substituted = false)
        DirectSourceGate.openNext(id)

        AppSettings.lowPower.value = true
        assertFalse("low power", KoReplayAhead.start(id, target))
        AppSettings.lowPower.value = false
        assertFalse("video row", KoReplayAhead.start(id, target.copy(isVideo = true)))
        assertEquals(0, addon.searches)
        assertFalse(QualityUpgrade.isPending(id))
    }

    @Test
    fun thePolicy() {
        assertTrue(KoReplayAhead.wanted(pinnedToYouTube = true, heavyWorkAllowed = true, alreadyPending = false, couldStillUpgrade = true))
        assertFalse("nothing or an addon pinned", KoReplayAhead.wanted(false, true, false, true))
        assertFalse("low power", KoReplayAhead.wanted(true, false, false, true))
        assertFalse("already handed to the second look", KoReplayAhead.wanted(true, true, true, true))
        assertFalse("answered, refused or retry held", KoReplayAhead.wanted(true, true, false, false))
    }

    /** The hook sits on the pinned branch of the window read-ahead, before its head fetch. */
    @Test
    fun theWindowReadAheadStartsTheReplayLookup() {
        val body = source("src/main/java/com/music/bitchord/playback/AudioCache.kt")
            .substringAfter("fun prefetchDirect(upcoming: Upcoming) {")
            .substringBefore("fun cancelDirect()")
        val unpinned = body.indexOf("if (StreamChoice.of(id) == null) {")
        val hook = body.indexOf("else com.music.bitchord.ko.KoReplayAhead.start(id, upcoming.target)")
        val head = body.indexOf("fetch(id, 0, PRELOAD_BYTES)")
        assertTrue("unpinned branch", unpinned >= 0)
        assertTrue("a pinned next track must start its lookup in the window", hook > unpinned)
        assertTrue("before the head fetch", head > hook)
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "$it$path") }.first { it.exists() }
            .readText().replace("\r\n", "\n")
    }

    private fun install(sources: List<FakeSource>) {
        SourceRegistry.configs.value = sources.map { source ->
            SourceConfig(
                id = source.configId,
                kind = source.kind,
                label = source.configId,
                baseUrl = if (source.kind == SourceKind.YOUTUBE) "" else "https://${source.configId}",
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
        override val kind: SourceKind = SourceKind.ADDON,
        private val searchDelayMs: Long = 0L,
    ) : MusicSource {
        override val displayName: String get() = configId

        @Volatile
        var searches = 0

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
            if (searchDelayMs > 0) delay(searchDelayMs)
            return rows
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId/$trackId", format = StreamFormat("flac"), sourceConfigId = configId, trackId = trackId)
    }
}
