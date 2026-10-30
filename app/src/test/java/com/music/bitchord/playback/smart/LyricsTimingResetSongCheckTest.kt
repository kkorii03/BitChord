package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.DownloadedSyncCheck
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Replacing a downloaded file has to make it checkable again: the version that
 * said "already checked" is dropped, and the automatic override that still
 * held the old file's sheet goes with it. A listener's own override is kept.
 */
class LyricsTimingResetSongCheckTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }
    private val gateField = LyricsTiming::class.java.getDeclaredField("songSyncGate").apply {
        isAccessible = true
    }
    private val lines = listOf(
        LyricLine(1_000L, "one"),
        LyricLine(2_000L, "two"),
        LyricLine(3_000L, "three"),
    )

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-lyrics-reset-check").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
        AppSettings.syncSafetyDownloadRecheck.value = true
    }

    @After
    fun tearDown() {
        AppSettings.syncSafetyDownloadRecheck.value = false
        Deepgram.apiKey = ""
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun writeCheckedEntry(videoId: String) {
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"$videoId":{"syncCheckVersion":1}}}""",
        )
    }

    private fun writeMeasuredAndRefinedEntry(videoId: String) {
        val key = lines.joinToString("\n") { it.text }.hashCode()
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"$videoId":{
              "autoVersion":4,
              "autoComplete":true,
              "auto":{"knots":[0,4200],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":90},
              "syncCheckVersion":1,
              "refineMs":250,
              "refineLines":9,
              "refineHeardKey":$key,
              "refineHeardWords":[{"t":"one","s":1250,"e":1500}],
              "refineHeardSeconds":90.0,
              "refineHeardExtended":true
            }}}
            """.trimIndent(),
        )
    }

    @Test
    fun `a replaced file is unverified again and its automatic override is gone`() = runBlocking {
        writeCheckedEntry("video")
        LyricsOverrideStore.write(
            context,
            "video",
            lines,
            LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.RECHECK,
        )

        LyricsTiming.resetSongCheck(context, "video")

        assertNull(LyricsOverrideStore.read(context, "video"))
        val recheck = LyricsTiming.syncDiagnostics(
            context = context,
            videoId = "video",
            lines = lines,
            audioSource = null,
            downloaded = true,
            providerUnknown = false,
        ).second
        assertEquals(DownloadedSyncCheck.Recheck.UNVERIFIED.token, recheck)
    }

    @Test
    fun `a listener override is not removed by the file reset`() = runBlocking {
        writeCheckedEntry("video")
        LyricsOverrideStore.write(
            context,
            "video",
            lines,
            LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.USER,
        )

        LyricsTiming.resetSongCheck(context, "video")

        assertEquals(
            LyricsOverrideFile.Kind.USER,
            LyricsOverrideStore.read(context, "video")?.kind,
        )
    }

    @Test
    fun `a replaced file does not reuse the old measurement or refine transcript`() = runBlocking {
        writeMeasuredAndRefinedEntry("video")

        // The old file's answer is what applies before the replacement.
        val before = LyricsTiming.timing(
            context,
            "video",
            lines,
            baseAllowed = true,
            measuredAllowed = true,
        )
        assertEquals(4200L, LyricsTiming.shownShiftMs(lines, before!!.base!!))

        LyricsTiming.resetSongCheck(context, "video")

        val after = LyricsTiming.timing(
            context,
            "video",
            lines,
            baseAllowed = true,
            measuredAllowed = true,
        )
        assertNull(after?.base)
        assertFalse(LyricsTiming.measured(context, "video"))
        assertFalse(
            LyricsTiming.refineHeardKnown(
                context,
                "video",
                lines,
                LyricsTiming.Timing(base = null, manualMs = 0L),
            ),
        )
    }

    @Test
    fun `a reset waits for an in-flight song check before clearing`() = runBlocking {
        writeCheckedEntry("video")
        val gate = gateField.get(LyricsTiming) as Mutex
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            gate.withLock {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()

        val reset = async { LyricsTiming.resetSongCheck(context, "video") }
        delay(200)
        // Before the gate was shared with recheckDownloaded this returned at
        // once, so an in-flight check could write the old answer afterwards.
        assertFalse(reset.isCompleted)

        release.complete(Unit)
        reset.await()
        holder.join()

        val recheck = LyricsTiming.syncDiagnostics(
            context = context,
            videoId = "video",
            lines = lines,
            audioSource = null,
            downloaded = true,
            providerUnknown = false,
        ).second
        assertEquals(DownloadedSyncCheck.Recheck.UNVERIFIED.token, recheck)
    }

    @Test
    fun `a check that waited through a reset does not write the old file answer`() = runBlocking {
        // A stored low-match answer makes the pending check say WHY it runs;
        // after the reset the token must be gone, not the old reason.
        val audioSource = LyricsTiming.audioSourceKey("video", localFile = true)
        val key = 31 * audioSource.hashCode() + lines.joinToString("\n") { it.text }.hashCode()
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"video":{"aiTextKey":$key,"ai":{"match":40}}}}""",
        )
        Deepgram.apiKey = "test-key"
        val gate = gateField.get(LyricsTiming) as Mutex
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            gate.withLock {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()

        // The reset is queued first but cannot run while the gate is held. The
        // re-check starts meanwhile and captures the generation of the old
        // file; without that token it would see the reset's version 0 as
        // "unverified" and spend a request on the old bytes.
        val reset = async { LyricsTiming.resetSongCheck(context, "video") }
        val recheck = async {
            LyricsTiming.recheckDownloaded(
                context = context,
                song = Song(
                    videoId = "video",
                    title = "Test Song",
                    artist = "Test Artist",
                    thumbnailUrl = null,
                    localUri = File(directory, "replaced.m4a").absolutePath,
                ),
                lines = lines,
                providerUnknown = false,
                title = "Test Song",
                artist = "Test Artist",
                durationMs = 180_000L,
                album = null,
                alternatives = emptyList(),
                artistKnown = true,
                artistAliases = emptyList(),
            )
        }
        delay(100)
        release.complete(Unit)
        assertNull(recheck.await())
        reset.await()
        holder.join()

        val recheckAfter = LyricsTiming.syncDiagnostics(
            context = context,
            videoId = "video",
            lines = lines,
            audioSource = null,
            downloaded = true,
            providerUnknown = false,
        ).second
        assertEquals(DownloadedSyncCheck.Recheck.UNVERIFIED.token, recheckAfter)
    }
}
