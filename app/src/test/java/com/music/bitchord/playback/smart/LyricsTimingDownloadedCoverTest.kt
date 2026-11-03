package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.DownloadedSyncCheck
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-03: a downloaded cover video kept its answer under the file key but
 * the lyrics screen asked under the video key, so it showed `base=none`. The
 * file re-check also refused the row because it was a video, even when the
 * embedded lyrics named no provider.
 */
class LyricsTimingDownloadedCoverTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }
    private val videoId = "vRjfYdwM5L4"
    private val lines = listOf(
        LyricLine(1_000L, "one"),
        LyricLine(2_000L, "two"),
        LyricLine(3_000L, "three"),
    )

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-downloaded-cover").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun writeFileKeyAnswer() {
        val fileKey = LyricsTiming.audioSourceKey(videoId, localFile = true)
        val key = 31 * fileKey.hashCode() + lines.joinToString("\n") { it.text }.hashCode()
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"$videoId":{
              "aiTextKey":$key,
              "ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":100},
              "aiEngine":"Deepgram"
            }}}
            """.trimIndent(),
        )
    }

    @Test
    fun `a downloaded cover applies the answer kept under its file key`() = runBlocking {
        writeFileKeyAnswer()

        val timing = LyricsTiming.timing(
            context = context,
            videoId = videoId,
            lines = lines,
            baseAllowed = true,
            isVideo = true,
            audioSource = null,
        )

        assertNotNull(timing?.base)
        assertTrue(timing?.base?.byAi == true)
    }

    @Test
    fun `the sync line reports a stored answer kept under the file key`() = runBlocking {
        writeFileKeyAnswer()

        val (stored, recheck) = LyricsTiming.syncDiagnostics(
            context = context,
            videoId = videoId,
            lines = lines,
            audioSource = null,
            downloaded = true,
            providerUnknown = false,
        )

        assertTrue(stored)
        assertTrue(recheck == DownloadedSyncCheck.Recheck.UNVERIFIED.token)
    }

    @Test
    fun `an unknown-provider video file still gets the one song check`() {
        val song = Song(
            videoId = videoId,
            title = "Marigold (cover)",
            artist = "오화요",
            thumbnailUrl = null,
            isVideo = true,
        )

        // The same row's video guard refuses a song check; the file re-check
        // must ask the record-timed question instead.
        assertFalse(
            com.music.bitchord.data.lyrics.LyricsAiGuard.songTimingWanted(
                isVideo = true,
                timed = true,
                sungLines = lines.size,
                title = song.title,
                artist = song.artist,
                texts = lines.map { it.text },
            ),
        )
        assertTrue(
            LyricsTiming.downloadRecheckSongCheckWanted(
                sungLines = lines.size,
                title = song.title,
                artist = song.artist,
                texts = lines.map { it.text },
            ),
        )
    }
}
