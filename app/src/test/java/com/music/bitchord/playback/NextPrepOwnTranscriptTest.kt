package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.ko.KoLockSheet
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * The next row's prepared timing is the one the player and the lyrics screen apply when it
 * plays: a Gemini transcript sheet keeps its own-transcript placement (no per-line snaps), so
 * the prepared first frame does not jump once the screen re-times it.
 */
class NextPrepOwnTranscriptTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val lines = listOf(
        LyricLine(1_000L, "alpha"),
        LyricLine(2_000L, "bravo"),
        LyricLine(3_000L, "charlie"),
        LyricLine(4_000L, "delta"),
    )

    @Before
    fun setUp() {
        LyricsTiming.resetForTest()
        directory = Files.createTempDirectory("bitchord-next-prep-own").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun song(id: String) = Song(videoId = id, title = "Song", artist = "Artist", thumbnailUrl = null)

    /** A kept AI answer for the streamed song, with the words it heard (so the per-line layer runs). */
    private fun writeAnswerWithWords(videoId: String) {
        val streamKey = LyricsTiming.audioSourceKey(videoId, localFile = false)
        val key = 31 * streamKey.hashCode() + lines.joinToString("\n") { it.text }.hashCode()
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"$videoId":{"aiTextKey":$key,"aiShiftMs":300,""" +
                """"heardWords":[{"t":"alpha","s":1300,"e":1500},{"t":"bravo","s":2300,"e":2500},""" +
                """{"t":"charlie","s":3300,"e":3500},{"t":"delta","s":4300,"e":4500}]}}}""",
        )
        storeField.set(LyricsTiming, null)
    }

    /** Exactly what PlaybackService's publish() asks for the same sheet once the row plays. */
    private suspend fun playerTiming(videoId: String, source: LyricsSource): LyricsTiming.Timing? {
        val sheet = KoLockSheet.Sheet(lines, source, fromFile = false, recheckOverride = false, isVideo = false)
        return LyricsTiming.timing(
            context,
            videoId,
            lines,
            baseAllowed = sheet.timeable,
            measuredAllowed = sheet.recordTimed,
            deniedBy = "file-own-timing@player",
            ownTranscript = sheet.source == LyricsSource.GEMINI_TRANSCRIPT,
            isVideo = false,
            audioSource = LyricsTiming.audioSourceKey(videoId, false),
        )
    }

    private suspend fun prepared(videoId: String, source: LyricsSource): LyricsTiming.Timing? =
        LyricsTiming.prepareNextFree(
            context = context,
            song = song(videoId),
            lines = lines,
            fromFile = false,
            source = source,
            searchedAs = null,
        )

    @Test
    fun `a Gemini transcript is prepared as its own transcript, as the player times it`() = runBlocking {
        val id = "gemini"
        writeAnswerWithWords(id)

        val timing = prepared(id, LyricsSource.GEMINI_TRANSCRIPT)

        assertNotNull(timing?.perLine) // precondition: the per-line layer is in play
        // Old code: false (prepareNextFree left ownTranscript at its default), so apply() snapped lines.
        assertEquals(true, timing?.perLine?.ownTranscript)
        assertEquals(playerTiming(id, LyricsSource.GEMINI_TRANSCRIPT), timing)
    }

    @Test
    fun `another source is prepared as before, as the player times it`() = runBlocking {
        val id = "lrclib"
        writeAnswerWithWords(id)

        val timing = prepared(id, LyricsSource.LRCLIB)

        assertNotNull(timing?.perLine)
        assertEquals(false, timing?.perLine?.ownTranscript)
        assertEquals(playerTiming(id, LyricsSource.LRCLIB), timing)
    }
}
