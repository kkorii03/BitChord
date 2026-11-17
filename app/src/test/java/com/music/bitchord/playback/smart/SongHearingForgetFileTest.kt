package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TR2 F2/T3: when a download is replaced, [SongHearing.forgetFile] drops the words
 * heard off the old file. It used to launch the delete and return, and a listen of
 * the old file still in flight saved its words afterwards under the same `file|`
 * key: the new file was then aligned to words that are not on it.
 */
class SongHearingForgetFileTest {

    private val videoId = "forget-file"
    private lateinit var directory: File
    private lateinit var context: Context
    private val song = Song(videoId = videoId, title = "Title", artist = "Artist", thumbnailUrl = null)
    private val fileCopy = VideoAudio.SongAudio(bytes = ByteArray(16), renditionKey = null, fromFile = true)
    private val streamCopy = VideoAudio.SongAudio(bytes = ByteArray(16), renditionKey = null, fromFile = false, mime = "audio/ogg")
    private val answer = Deepgram.Answer(
        words = listOf(
            LyricsWordAlign.Heard("one", 1_000L, 1_200L),
            LyricsWordAlign.Heard("two", 2_000L, 2_200L),
        ),
        httpCode = 200,
        audioMs = 90_000L,
    )

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-forget-file").toFile()
        context = FakeContext.create(FakePreferences(), directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** Whether any transcript of a file is kept for the video (the store's own JSON). */
    private fun fileWordsKept(): Boolean {
        val stored = File(File(directory, "song-hearing"), "$videoId.json")
        return stored.isFile && stored.readText().contains("\"file|")
    }

    private fun keep(copy: VideoAudio.SongAudio, generation: Long) =
        SongHearing.kept(context, song, copy, answer, 90.0, "en", LyricsTiming.Clock.YouTube, generation)

    @Test
    fun `a listen of the old file that lands after the replacement keeps nothing`() = runBlocking {
        // The listen begins on the old file...
        val started = SongHearing.fileGenerationOf(videoId)
        // ...the download is replaced...
        SongHearing.forgetFile(context, videoId)
        // ...and only then do its words come back.
        keep(fileCopy, started)

        // Old code saved them under file|: covering() served them to the new file.
        assertFalse(fileWordsKept())
    }

    @Test
    fun `the old file's words are gone when forgetFile returns`() = runBlocking {
        keep(fileCopy, SongHearing.fileGenerationOf(videoId))
        assertTrue(fileWordsKept())

        SongHearing.forgetFile(context, videoId)

        assertFalse(fileWordsKept())
    }

    // Review 11-18: the flight base carried the file's generation for a stream
    // recording too, so a replaced file made a second paid request for the
    // same stream audio.
    @Test
    fun `a replaced file starts a new flight for the file only`() {
        val stream = SongHearing.streamRecording("yt")
        assertEquals(
            SongHearing.flightBase(videoId, stream, "en", false, 0L),
            SongHearing.flightBase(videoId, stream, "en", false, 1L),
        )
        assertNotEquals(
            SongHearing.flightBase(videoId, "file|yt", "en", false, 0L),
            SongHearing.flightBase(videoId, "file|yt", "en", false, 1L),
        )
        assertNotEquals(
            SongHearing.flightBase(videoId, stream, "en", false, 0L),
            SongHearing.flightBase(videoId, "file|yt", "en", false, 0L),
        )
    }

    @Test
    fun `the stream's words and the new file's are kept`() = runBlocking {
        val started = SongHearing.fileGenerationOf(videoId)
        SongHearing.forgetFile(context, videoId)
        // The stream is the same recording whatever file is on disk.
        keep(streamCopy, started)
        assertNotNull(SongHearing.keptYouTubeWords(context, videoId))

        // A listen that began on the new file.
        keep(fileCopy, SongHearing.fileGenerationOf(videoId))
        assertTrue(fileWordsKept())
    }
}
