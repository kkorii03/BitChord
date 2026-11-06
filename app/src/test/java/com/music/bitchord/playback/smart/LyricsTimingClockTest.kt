package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.ko.KoAppSettings
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A stored lyric correction is only meaningful on the audio clock it was
 * measured on. These tests pin the eight conversions the mid-song substitute
 * makes reachable: YouTube↔HQ, one HQ copy↔another, the same clock, a missing
 * map, a hand nudge made on HQ, and old untagged records under both sides of
 * the HQ safety switch.
 */
class LyricsTimingClockTest {

    private lateinit var directory: File
    private lateinit var prefs: FakePreferences
    private lateinit var context: Context

    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }
    private val hqCacheField = HqVerificationStore::class.java.getDeclaredField("cached").apply {
        isAccessible = true
    }

    private val line = LyricLine(timeMs = 10_000L, text = "one")

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-lyrics-clock").toFile()
        prefs = FakePreferences()
        context = FakeContext.create(prefs, directory)
        storeField.set(LyricsTiming, null)
        hqCacheField.set(HqVerificationStore, null)
        KoAppSettings.syncSafetyHqSync.value = false
        listOf("v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "vA", "vB", "vC", "vD", "vE", "vF").forEach(StreamChoice::forget)
        Downloads.setSavedMetadata(emptyMap())
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        hqCacheField.set(HqVerificationStore, null)
        KoAppSettings.syncSafetyHqSync.value = false
        listOf("v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "vA", "vB", "vC", "vD", "vE", "vF").forEach(StreamChoice::forget)
        Downloads.setSavedMetadata(emptyMap())
        directory.deleteRecursively()
    }

    private fun write(entries: String) {
        File(directory, "lyrics_timing_v1.json").writeText("""{"entries":$entries}""")
    }

    private fun map(shiftMs: Long) =
        """{"knots":[0,$shiftMs],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":90}"""

    private fun entry(
        autoMs: Long? = null,
        autoClock: String? = null,
        manualMs: Long? = null,
        manualClock: String? = null,
    ): String = buildString {
        append('{')
        val fields = buildList {
            autoMs?.let {
                add("\"autoVersion\":4")
                add("\"auto\":${map(it)}")
            }
            autoClock?.let { add("\"autoClock\":\"$it\"") }
            manualMs?.let {
                add("\"manualMs\":$it")
            }
            manualClock?.let { add("\"manualClock\":\"$it\"") }
        }
        append(fields.joinToString(","))
        append('}')
    }

    private fun playing(
        videoId: String,
        sourceConfigId: String,
        trackId: String,
    ) {
        StreamChoice.remember(
            videoId,
            SourceStream(
                url = "https://example.test/$trackId",
                sourceConfigId = sourceConfigId,
                trackId = trackId,
            ),
            substituted = true,
        )
    }

    private fun verified(videoId: String, offsetMs: Long, rate: Double = 1.0) {
        HqVerificationStore.put(
            context,
            videoId,
            "src",
            "track",
            HqVerificationStore.Verified(offsetMs, rate, 90, HqTier.LOSSLESS_16),
        )
    }

    private fun shown(videoId: String): Long = runBlocking {
        val timing = LyricsTiming.timing(context, videoId, listOf(line), baseAllowed = true)
            ?: return@runBlocking 10_000L
        LyricsTiming.apply(listOf(line), timing).single().timeMs
    }

    @Test
    fun `youtube measured base is mapped while the substitute plays`() {
        write("""{"v1":${entry(autoMs = 500L, autoClock = "yt")}}""")
        verified("v1", offsetMs = 1_000L)
        playing("v1", "src", "track")

        assertEquals(11_500L, shown("v1"))
    }

    @Test
    fun `hq measured base is inverted for youtube playback`() {
        write("""{"v2":${entry(autoMs = 500L, autoClock = "alt\u0001src\u0001track")}}""")
        verified("v2", offsetMs = 1_000L)

        assertEquals(9_500L, shown("v2"))
    }

    @Test
    fun `one substitute is converted to another substitute`() {
        write("""{"v3":${entry(autoMs = 500L, autoClock = "alt\u0001src\u0001a")}}""")
        HqVerificationStore.put(
            context,
            "v3",
            "src",
            "a",
            HqVerificationStore.Verified(1_000L, 1.0, 90, HqTier.LOSSLESS_16),
        )
        HqVerificationStore.put(
            context,
            "v3",
            "src",
            "b",
            HqVerificationStore.Verified(2_000L, 1.0, 90, HqTier.LOSSLESS_16),
        )
        playing("v3", "src", "b")

        assertEquals(11_500L, shown("v3"))
    }

    @Test
    fun `a correction already on the playing clock is unchanged`() {
        write("""{"v4":${entry(autoMs = 500L, autoClock = "yt")}}""")

        assertEquals(10_500L, shown("v4"))
    }

    @Test
    fun `a correction with no verified map is dropped`() {
        write("""{"v5":${entry(autoMs = 500L, autoClock = "alt\u0001src\u0001track")}}""")

        assertEquals(10_000L, shown("v5"))
    }

    @Test
    fun `a hand nudge made on hq is shown on youtube`() {
        write("""{"v6":${entry(manualMs = 500L, manualClock = "alt\u0001src\u0001track")}}""")
        HqVerificationStore.put(
            context,
            "v6",
            "src",
            "track",
            HqVerificationStore.Verified(1_000L, 1.02, 90, HqTier.LOSSLESS_16),
        )

        // A constant move is a duration: inverse at rate 1.02 is 500 / 1.02 ms.
        assertEquals(10_490L, shown("v6"))
    }

    @Test
    fun `words heard on hq are converted before the refine moves youtube lines`() {
        val lines = (0 until 8).map { LyricLine(10_000L + it * 10_000L, "alpha$it bravo$it") }
        val key = lines.joinToString("\n") { it.text }.hashCode()
        val words = lines.flatMap { line ->
            listOf(
                """{"t":"alpha${(line.timeMs - 10_000) / 10_000}","s":${line.timeMs + 1_500},"e":${line.timeMs + 1_600}}""",
                """{"t":"bravo${(line.timeMs - 10_000) / 10_000}","s":${line.timeMs + 1_900},"e":${line.timeMs + 2_000}}""",
            )
        }.joinToString(",")
        write(
            """{"v8":{""" +
                """"refineHeardKey":$key,""" +
                """"refineHeardWords":[$words],""" +
                """"refineHeardClock":"alt\u0001src\u0001track"}}""",
        )
        verified("v8", offsetMs = 1_000L)

        val timing = runBlocking {
            LyricsTiming.timing(context, "v8", lines, baseAllowed = true)
        }
        assertEquals(10_500L, timing?.let { LyricsTiming.apply(lines, it) }?.first()?.timeMs)
    }

    @Test
    fun `a saved hq download is the playing clock when no stream is chosen`() {
        write("""{"v9":${entry(autoMs = 500L, autoClock = "yt")}}""")
        verified("v9", offsetMs = 1_000L)
        KoDownloadRecords.ensureOpen()
        Downloads.setSavedMetadata(
            mapOf(
                "v9" to SavedSongMetadata(
                    videoId = "v9",
                    title = "t",
                    artist = "a",
                    uri = "file:///v9.flac",
                    hqSourceConfigId = "src",
                    hqTrackId = "track",
                ),
            ),
        )

        assertEquals(11_500L, shown("v9"))
    }

    /** The stream's YouTube choice still remembered, and the verified download playing in its place. */
    private fun fileTookOver(videoId: String) {
        StreamChoice.remember(videoId, SourceStream(url = "https://example.test/yt"), substituted = false)
        KoDownloadRecords.ensureOpen()
        Downloads.setSavedMetadata(
            mapOf(
                videoId to SavedSongMetadata(
                    videoId = videoId,
                    title = "t",
                    artist = "a",
                    uri = "file:///$videoId.flac",
                    hqSourceConfigId = "src",
                    hqTrackId = "track",
                ),
            ),
        )
    }

    private fun shownFrom(videoId: String, localFile: Boolean): Long = runBlocking {
        val timing = LyricsTiming.timing(
            context,
            videoId,
            listOf(line),
            baseAllowed = true,
            audioSource = LyricsTiming.audioSourceKey(videoId, localFile),
        ) ?: return@runBlocking 10_000L
        LyricsTiming.apply(listOf(line), timing).single().timeMs
    }

    /** 11-06 MUny_GDYIDM: `playing=youtube base=alt dropped=1` for an answer heard off the file playing. */
    @Test
    fun `an answer heard off the file stands after the file took over from its stream`() {
        write("""{"vD":${entry(autoMs = 500L, autoClock = "alt\u0001src\u0001track")}}""")
        fileTookOver("vD")

        assertEquals(10_500L, shownFrom("vD", localFile = true))
    }

    @Test
    fun `a stream's answer is carried onto the file that took over`() {
        write("""{"vE":${entry(autoMs = 500L, autoClock = "yt")}}""")
        verified("vE", offsetMs = 1_000L)
        fileTookOver("vE")

        assertEquals(11_500L, shownFrom("vE", localFile = true))
    }

    @Test
    fun `the stream still playing keeps its own clock whatever is saved`() {
        write("""{"vF":${entry(autoMs = 500L, autoClock = "yt")}}""")
        verified("vF", offsetMs = 1_000L)
        fileTookOver("vF")

        assertEquals(10_500L, shownFrom("vF", localFile = false))
    }

    @Test
    fun `a substitute playing the answer's own clock applies it`() {
        // The copy is what the answer was measured on, so it is already on the
        // playing clock — no verified map is needed to place it.
        write("""{"vA":${entry(autoMs = 500L, autoClock = "alt\u0001src\u0001track")}}""")
        playing("vA", "src", "track")

        assertEquals(10_500L, shown("vA"))
    }

    @Test
    fun `an untagged answer on a copy is refused rather than shown on the wrong clock`() {
        // No clock tag and no verified map: the upload's answer cannot be put
        // on the copy's clock, so it is dropped and the provider's times stand.
        write("""{"vB":${entry(autoMs = 500L)}}""")
        playing("vB", "src", "track")

        assertEquals(10_000L, shown("vB"))
    }

    @Test
    fun `an untagged answer on the upload is applied as before`() {
        write("""{"vC":${entry(autoMs = 500L)}}""")

        assertEquals(10_500L, shown("vC"))
    }

    @Test
    fun `untagged legacy data is carried onto a copy only by the hq map`() {
        write("""{"v7":${entry(autoMs = 500L, manualMs = -250L)}}""")
        verified("v7", offsetMs = 1_000L)
        playing("v7", "src", "track")

        // The switch off is no map at all, and an untagged answer cannot be
        // placed without one: only the hand's correction stands.
        KoAppSettings.syncSafetyHqSync.value = false
        assertEquals(9_750L, shown("v7"))

        KoAppSettings.syncSafetyHqSync.value = true
        assertEquals(11_250L, shown("v7"))
    }
}
