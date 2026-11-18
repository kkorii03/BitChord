package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [KO] clock1119 (log1819 A3): the next row is an unchecked HQ download built on its
 * stream. Next-prep timed it with [LyricsTiming.playingClock], whose saved-file fallback
 * named the download's HQ pair before any stream was chosen, so the stream's untagged
 * answer was `clock dropped` ahead (11-18 Espresso, 11-19 Dance The Night) and only
 * applied once the row started on the stream. Next-prep now passes the clock the row
 * opens with ([LyricsTiming.nextRowClock]); [LyricsTiming.playingClock] is unchanged.
 */
class KoNextPrepClockTest {

    private lateinit var directory: File
    private lateinit var context: Context

    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }
    private val hqCacheField = HqVerificationStore::class.java.getDeclaredField("cached").apply {
        isAccessible = true
    }

    private val line = LyricLine(timeMs = 10_000L, text = "one")
    private val ids = listOf("np1", "np2", "np3", "np4", "np5")
    private val alt = LyricsTiming.Clock.Substitute("src", "track")

    @Before
    fun setUp() {
        LyricsTiming.resetForTest()
        directory = Files.createTempDirectory("bitchord-next-prep-clock").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
        hqCacheField.set(HqVerificationStore, null)
        KoAppSettings.syncSafetyHqSync.value = false
        ids.forEach(StreamChoice::forget)
        KoDownloadRecords.ensureOpen()
        Downloads.setSavedMetadata(ids.associateWith(::hqDownload))
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        hqCacheField.set(HqVerificationStore, null)
        ids.forEach(StreamChoice::forget)
        Downloads.setSavedMetadata(emptyMap())
        directory.deleteRecursively()
    }

    /** An HQ download, saved with the source pair it was fetched under, not yet checked. */
    private fun hqDownload(id: String) = SavedSongMetadata(
        videoId = id,
        title = "t",
        artist = "a",
        uri = "file:///$id.flac",
        hqSourceConfigId = "src",
        hqTrackId = "track",
    )

    private fun stream(id: String) = LyricsTiming.audioSourceKey(id, localFile = false)

    /** An untagged measurement of +500 ms: made on the upload, as old and Groq answers are. */
    private fun writeUntagged(id: String) {
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"$id":{"autoVersion":4,"auto":{"knots":[0,500],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":90}}}}""",
        )
    }

    @Test
    fun `an unchecked download's stream row opens on youtube's clock`() {
        // The playing-clock rule itself is untouched: with no choice it names the file.
        assertEquals(alt, LyricsTiming.playingClock("np1"))
        assertEquals(LyricsTiming.Clock.YouTube, LyricsTiming.nextRowClock("np1", stream("np1")))
    }

    @Test
    fun `next-prep keeps the stream's untagged answer instead of dropping it`() {
        writeUntagged("np2")
        val shown = runBlocking {
            val timing = LyricsTiming.timing(
                context, "np2", listOf(line), baseAllowed = true,
                audioSource = stream("np2"),
                rowClock = LyricsTiming.nextRowClock("np2", stream("np2")),
            ) ?: error("the answer was dropped")
            LyricsTiming.apply(listOf(line), timing).single().timeMs
        }
        // What the row shows once it starts on the stream (StreamChoice YouTube).
        assertEquals(10_500L, shown)
    }

    @Test
    fun `a stream row pinned to a copy opens on that copy's clock`() {
        StreamChoice.remember(
            "np3",
            SourceStream(url = "https://example.test/other", sourceConfigId = "src", trackId = "other"),
            substituted = true,
        )
        assertEquals(LyricsTiming.Clock.Substitute("src", "other"), LyricsTiming.nextRowClock("np3", stream("np3")))
    }

    @Test
    fun `a file row and a video keep the playing clock`() {
        assertEquals(alt, LyricsTiming.nextRowClock("np4", LyricsTiming.audioSourceKey("np4", localFile = true)))
        assertEquals(alt, LyricsTiming.nextRowClock("np5", null))
    }

    @Test
    fun `every next-prep timing pass names the row's clock`() {
        val prep = File("src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt").readText()
        assertTrue(prep.contains("rowClock = LyricsTiming.nextRowClock(song.videoId, audioSource)"))
        val timing = File("src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt").readText()
        val free = timing.substringAfter("suspend fun prepareNextFree(").substringBefore("suspend fun prepareNextPaid(")
        val paid = timing.substringAfter("suspend fun prepareNextPaid(").substringBefore("private fun nextPrepNames(")
        assertEquals(1,Regex("""rowClock = nextRowClock\(song\.videoId, audioSource\)""").findAll(free).count())
        // Each of the paid half's timing passes, none left reading the file's clock.
        assertEquals(
            Regex("""result = timing\(""").findAll(paid).count(),
            Regex("""rowClock = nextRowClock\(song\.videoId, audioSource\)""").findAll(paid).count(),
        )
        assertEquals(4, Regex("""result = timing\(""").findAll(paid).count())
    }
}
