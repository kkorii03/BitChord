package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The lyric clock while a verified download plays (11-06 listener's log: MUny_GDYIDM, w58uXpyHWes, Vyo2m2CbdUs). */
class LyricsTimingFileClockTest {

    private lateinit var directory: File
    private lateinit var context: Context

    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val hqCacheField = HqVerificationStore::class.java.getDeclaredField("cached").apply { isAccessible = true }

    private val id = "file-clock"
    private val lines = listOf(
        LyricLine(10_000L, "alpha"),
        LyricLine(20_000L, "bravo"),
        LyricLine(30_000L, "charlie"),
        LyricLine(40_000L, "delta"),
        LyricLine(50_000L, "echoes"),
        LyricLine(60_000L, "foxtrot"),
        LyricLine(70_000L, "golfing"),
        LyricLine(80_000L, "hotels"),
    )
    private val fileSource = LyricsTiming.audioSourceKey(id, localFile = true)
    private val streamSource = LyricsTiming.audioSourceKey(id, localFile = false)
    private val altClock = "alt\\u0001src\\u0001track"
    private val shift800 = LyricsTiming.Adjustment.Shift(800L, byAi = true)

    private fun keyOf(source: String) = 31 * source.hashCode() + lines.joinToString("\n") { it.text }.hashCode()

    @Before
    fun setUp() {
        LyricsTiming.resetForTest()
        directory = Files.createTempDirectory("bitchord-file-clock").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
        hqCacheField.set(HqVerificationStore, null)
        StreamChoice.forget(id)
        KoDownloadRecords.ensureOpen()
        Downloads.setSavedMetadata(emptyMap())
    }

    @After
    fun tearDown() {
        storeField.set(LyricsTiming, null)
        hqCacheField.set(HqVerificationStore, null)
        StreamChoice.forget(id)
        Downloads.setSavedMetadata(emptyMap())
        com.music.bitchord.ko.KoFileSameAsStream.clearForTest()
        directory.deleteRecursively()
    }

    private fun answer(source: String, clock: String?) {
        val tag = clock?.let { ""","aiClock":"$it"""" }.orEmpty()
        File(directory, "lyrics_timing_v1.json").writeText(
            """{"entries":{"$id":{"aiTextKey":${keyOf(source)},"aiShiftMs":800$tag}}}""",
        )
    }

    private fun streamChosen() {
        StreamChoice.remember(id, SourceStream("https://googlevideo.example/a"), substituted = false)
    }

    /** What koUseVerifiedDownload leaves: the stream's choice gone, the file recorded. */
    private fun fileTookOver(pair: Boolean = true) {
        streamChosen()
        StreamChoice.forget(id)
        savedFile(pair)
    }

    private fun savedFile(
        pair: Boolean = true,
        soundSource: String? = null,
        wrong: Boolean = false,
        offsetMs: Long? = null,
        rate: Double? = null,
    ) {
        Downloads.setSavedMetadata(
            mapOf(
                id to SavedSongMetadata(
                    videoId = id,
                    title = "t",
                    artist = "a",
                    uri = "file:///$id.flac",
                    hqSourceConfigId = if (pair) "src" else null,
                    hqTrackId = if (pair) "track" else null,
                    hqOffsetMs = offsetMs,
                    hqRate = rate,
                    recordingVerified = true,
                    soundSourceVideoId = soundSource,
                    wrongRecording = wrong,
                ),
            ),
        )
    }

    private fun verdict(offsetMs: Long) {
        HqVerificationStore.put(context, id, "src", "track", HqVerificationStore.Verified(offsetMs, 1.0, 100, HqTier.LOSSLESS_16))
    }

    private fun timing(source: String) = runBlocking {
        LyricsTiming.timing(context, id, lines, baseAllowed = true, audioSource = source)
    }

    @Test
    fun `an answer heard on the file stays on the file, with or without a map`() {
        fileTookOver()
        answer(fileSource, altClock)
        assertEquals(shift800, timing(fileSource)?.base)

        verdict(offsetMs = 1_000L)
        assertEquals(shift800, timing(fileSource)?.base)
    }

    @Test
    fun `the stream's answer is carried onto the file by its verdict and dropped without one`() {
        fileTookOver()
        answer(fileSource, "yt")
        assertNull(timing(fileSource)?.base)

        verdict(offsetMs = 1_000L)
        assertEquals(11_800L, LyricsTiming.apply(lines, timing(fileSource)!!).first().timeMs)
    }

    @Test
    fun `stream words are for a file only when a map reaches it`() {
        savedFile()
        assertEquals(false, LyricsTiming.streamWordsReachFile(context, id))

        verdict(offsetMs = 15L)
        assertEquals(true, LyricsTiming.streamWordsReachFile(context, id))

        HqVerificationStore.clear(context)
        savedFile(pair = false)
        assertEquals(true, LyricsTiming.streamWordsReachFile(context, id))
    }

    /** Phone 11-10 4rsheRU_bdQ: a cover video playing a file cut from another upload. */
    /**
     * Review 11-20 LIBRARY-4: the verdict store lost the pair (an addon address edit,
     * a source-list import, the 2000-pair eviction) while the saved file's record still
     * holds the offset and rate it was proved with. Old code dropped the answer.
     */
    @Test
    fun `the record's alignment carries the stream's answer once the store lost the pair`() {
        fileTookOver()
        savedFile(offsetMs = 1_000L, rate = 1.0)
        answer(fileSource, "yt")

        assertEquals(11_800L, LyricsTiming.apply(lines, timing(fileSource)!!).first().timeMs)
        assertEquals(true, LyricsTiming.streamWordsReachFile(context, id))
    }

    @Test
    fun `a recorded alignment is not used for a refused pair, a wrong file or a broken rate`() {
        savedFile(offsetMs = 1_000L, rate = 1.0, wrong = true)
        assertEquals(false, LyricsTiming.streamWordsReachFile(context, id))
        savedFile(offsetMs = 1_000L, rate = Double.NaN)
        assertEquals(false, LyricsTiming.streamWordsReachFile(context, id))
        savedFile(offsetMs = 1_000L, rate = 0.0)
        assertEquals(false, LyricsTiming.streamWordsReachFile(context, id))
        savedFile(offsetMs = null, rate = 1.0)
        assertEquals(false, LyricsTiming.streamWordsReachFile(context, id))

        savedFile(offsetMs = 1_000L, rate = 1.0)
        assertEquals(true, LyricsTiming.streamWordsReachFile(context, id))
        HqVerificationStore.reject(context, id, "src", "track")
        assertEquals(false, LyricsTiming.streamWordsReachFile(context, id))
    }

    @Test
    fun `a recorded alignment answers only its own pair`() {
        val meta = SavedSongMetadata(
            videoId = id, title = "t", artist = "a", uri = "file:///$id.flac",
            hqSourceConfigId = "src", hqTrackId = "track", hqOffsetMs = 250L, hqRate = 1.001,
        )
        assertEquals(250L to 1.001, com.music.bitchord.ko.KoRecordedClock.alignment(meta, "src", "track"))
        assertNull(com.music.bitchord.ko.KoRecordedClock.alignment(meta, "src", "other"))
        assertNull(com.music.bitchord.ko.KoRecordedClock.alignment(meta, "other", "track"))
        assertNull(com.music.bitchord.ko.KoRecordedClock.alignment(null, "src", "track"))
    }

    @Test
    fun `a file cut from another upload is heard from the file, never from this id's stream`() {
        savedFile(pair = false, soundSource = "other-upload")
        assertEquals(false, LyricsTiming.streamWordsReachFile(context, id))
        assertEquals("file:///$id.flac", LyricsTiming.fileOfAnotherUpload(id))

        // The id's own sound, an old record and a file proved wrong keep the stream as before.
        savedFile(pair = false, soundSource = id)
        assertEquals(true, LyricsTiming.streamWordsReachFile(context, id))
        assertNull(LyricsTiming.fileOfAnotherUpload(id))
        savedFile(pair = false)
        assertNull(LyricsTiming.fileOfAnotherUpload(id))
        savedFile(pair = false, soundSource = "other-upload", wrong = true)
        assertNull(LyricsTiming.fileOfAnotherUpload(id))

        // A chosen stream plays the stream, so the stream is what to hear.
        savedFile(pair = false, soundSource = "other-upload")
        streamChosen()
        assertNull(LyricsTiming.fileOfAnotherUpload(id))
    }

    @Test
    fun `the AI ask hears the file a foreign download plays`() {
        val ask = source("playback/smart/LyricsTiming.kt")
            .substringAfter("private suspend fun askAi(")
            .substringBefore("quickVideoAnswer(")
        assertTrue("localUri = fileOfAnotherUpload(videoId)" in ask)
    }

    @Test
    fun `a file of YouTube's own bytes stays on YouTube's clock`() {
        fileTookOver(pair = false)
        answer(fileSource, "yt")

        assertEquals(shift800, timing(fileSource)?.base)
    }

    @Test
    fun `an untagged answer kept under the file key is the file's`() {
        fileTookOver()
        answer(fileSource, clock = null)

        assertEquals(shift800, timing(fileSource)?.base)
    }

    /**
     * log1819: 11-19 Dance The Night Z1yDjhXIDSc. The stream played its untagged answer
     * (`base=ai shift=5ms src=stream`), the check heard the HQ download at offset 0, and the
     * file played `base=none ... sibling=untagged src=file` until a new hearing 18 s later.
     */
    @Test
    fun `the stream's untagged answer carries onto a file just heard to be the stream`() {
        fileTookOver()
        answer(streamSource, clock = null)
        val uri = "file:///$id.flac"
        com.music.bitchord.ko.KoFileSameAsStream.measured(
            id, uri, KoDownloadRecords.fileVersion(context, uri), fresh = true, ok = true, offsetMs = 0L, rate = 1.0,
        )

        assertEquals(shift800, timing(fileSource)?.base)
    }

    @Test
    fun `the stream's untagged answer stays off a file not heard to be the stream`() {
        fileTookOver()
        answer(streamSource, clock = null)
        assertNull(timing(fileSource)?.base)

        // Heard, but another cut: still off.
        val uri = "file:///$id.flac"
        com.music.bitchord.ko.KoFileSameAsStream.measured(
            id, uri, KoDownloadRecords.fileVersion(context, uri), fresh = true, ok = true, offsetMs = 900L, rate = 1.0,
        )
        LyricsTiming.resetForTest()
        storeField.set(LyricsTiming, null)
        assertNull(timing(fileSource)?.base)
    }

    @Test
    fun `a stream row is on the stream's clock while a verified file waits`() {
        streamChosen()
        savedFile()
        answer(streamSource, "yt")

        assertEquals(shift800, timing(streamSource)?.base)
    }

    @Test
    fun `an upgrade swapped over a file row plays the upgrade's clock`() {
        StreamChoice.remember(id, SourceStream("https://alt.example/a", sourceConfigId = "up", trackId = "grade"), substituted = true)
        savedFile()
        answer(fileSource, "alt\\u0001up\\u0001grade")

        assertEquals(shift800, timing(fileSource)?.base)
    }

    @Test
    fun `a verdict arriving tells the screens to place the answer again`() {
        val got = runBlocking {
            val seen = mutableListOf<String>()
            val job = launch(Dispatchers.Unconfined) { LyricsTiming.changes.collect { seen += it } }
            verdict(offsetMs = 15L)
            yield()
            job.cancel()
            seen.toList()
        }

        assertEquals(listOf(id), got)
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }.readText()
    }

    @Test
    fun `a download taking over clears the choice, keeps the sound's place, and tells the lock screen`() {
        val file = source("ko/KoPlaybackTranslationAhead.kt")
        val takeover = file.substringAfter("internal fun PlaybackService.koUseVerifiedDownload(")
            .substringBefore("* Replaces a condemned downloaded file")
        assertTrue("StreamChoice.forget(id)" in takeover)
        assertTrue("HqSwapPolicy.swapStartMs(" in takeover)
        assertTrue("loadLyricsForCurrentTrack()" in takeover)

        val trackStart = file.substringAfter("internal fun PlaybackService.koScheduleDownloadHealthCheck(")
            .substringBefore("downloadHealthJob?.cancel()")
        assertTrue("StreamChoice.forget(song.videoId)" in trackStart)
    }

    @Test
    fun `the lock screen takes the corrections the screen takes on a file's own sheet`() {
        val fileBranch = source("playback/PlaybackService.kt")
            .substringAfter("EmbeddedLyrics.withSource(this@PlaybackService, localUri)?.let { file ->")
            .substringBefore("if (lines == null)")
        assertTrue("recheckOverride = true" in fileBranch)
    }
}
