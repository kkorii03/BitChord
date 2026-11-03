package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.ko.KoAppSettings
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A measurement read straight off a downloaded file is on that file's clock.
 * An HQ download's file is the substitute it was verified against; tagging it
 * YouTube made [LyricsTiming.timing] apply the verified YouTube→HQ map again,
 * turning a correct cover measurement into a confident whole-song shift.
 */
class LyricsTimingMeasurementClockTest {

    private val videoId = "cover"
    private val line = LyricLine(10_000L, "one")

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }
    private val hqCacheField = HqVerificationStore::class.java.getDeclaredField("cached").apply {
        isAccessible = true
    }

    @Before
    @After
    fun reset() {
        KoDownloadRecords.ensureOpen()
        Downloads.setSavedMetadata(emptyMap())
        KoAppSettings.syncSafetyHqSync.value = false
        StreamChoice.forget(videoId)
        storeField.set(LyricsTiming, null)
        hqCacheField.set(HqVerificationStore, null)
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun download(sourceConfigId: String?, trackId: String?) {
        Downloads.setSavedMetadata(
            mapOf(
                videoId to SavedSongMetadata(
                    videoId = videoId,
                    title = "t",
                    artist = "a",
                    uri = "file:///$videoId.flac",
                    hqSourceConfigId = sourceConfigId,
                    hqTrackId = trackId,
                ),
            ),
        )
    }

    private fun scratch() {
        directory = Files.createTempDirectory("bitchord-measured-clock").toFile()
        context = FakeContext.create(FakePreferences(), directory)
    }

    /** The entry a measurement off the local file leaves behind, tagged with [clock]. */
    private fun writeMeasured(clock: LyricsTiming.Clock, shiftMs: Long) {
        val saved = when (clock) {
            LyricsTiming.Clock.YouTube -> "yt"
            is LyricsTiming.Clock.Substitute -> "alt\\u0001${clock.sourceConfigId}\\u0001${clock.trackId}"
            LyricsTiming.Clock.Unknown -> "unknown"
        }
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"$videoId":{
              "clockVersion":1,
              "autoVersion":4,
              "auto":{"knots":[0,$shiftMs],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":98},
              "autoClock":"$saved"
            }}}
            """.trimIndent(),
        )
    }

    private fun shown(): Long = runBlocking {
        val timing = LyricsTiming.timing(context, videoId, listOf(line), baseAllowed = true)
            ?: return@runBlocking line.timeMs
        LyricsTiming.apply(listOf(line), timing).single().timeMs
    }

    @Test
    fun `a measurement read off an hq download is on that file's clock`() {
        download("src", "track")

        assertEquals(
            LyricsTiming.Clock.Substitute("src", "track"),
            LyricsTiming.clockForRendition(videoId, "local:$videoId"),
        )
    }

    /**
     * The same measurement, applied: it was taken on the HQ file that is now
     * playing, so timing must not move it across the YouTube→HQ map a second
     * time. On the old clock tag the line landed at 11 500 ms instead of
     * 10 500 ms.
     */
    @Test
    fun `an hq download measurement is not mapped again when it plays`() {
        download("src", "track")
        scratch()
        HqVerificationStore.put(
            context = context,
            youtubeId = videoId,
            sourceConfigId = "src",
            trackId = "track",
            verified = HqVerificationStore.Verified(1_000L, 1.0, 90, HqTier.LOSSLESS_16),
        )
        writeMeasured(LyricsTiming.clockForRendition(videoId, "local:$videoId"), shiftMs = 500L)

        assertEquals(10_500L, shown())
    }
}
