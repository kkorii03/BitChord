package com.music.bitchord.data.lyrics

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phone 11-19 Sltk38pDXV8 (a Korean-sung cover): the screen's heard words refused
 * the Gemini transcript, the reload asked the repository again, and the same
 * transcript came straight back from memory 3 ms later, because a transcript was
 * never checked against a remembered rejection. No source is contacted (the only
 * one allowed is hidden); the words are made up.
 */
class LyricsRejectedTranscriptTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val sources = setOf(LyricsSource.SIMP_MUSIC)
    private val order = listOf(LyricsSource.SIMP_MUSIC)
    private val durationMs = 200_000L

    private val lines = (0 until 6).map { LyricLine(5_000L + it * 5_000L, "made up heard line $it") }

    private suspend fun ask(videoId: String) = LyricsRepository.lyrics(
        videoId = videoId,
        title = "Heard Cover",
        artist = "Nobody",
        durationMs = durationMs,
        sources = sources,
        order = order,
        isVideo = true,
        transcribe = true,
    )

    private fun key(videoId: String) = LyricsRepository.questionKey(
        videoId = videoId,
        title = "Heard Cover",
        artist = "Nobody",
        durationMs = durationMs,
        album = null,
        prioritizeSyllableSync = false,
        isrc = null,
        isVideo = true,
        artistKnown = true,
        alternatives = emptyList(),
        order = order,
        sources = sources,
    )

    private fun isolated(block: suspend () -> Unit) {
        val previousTranscriber = LyricsRepository.transcriber
        val previousKeep = LyricsTranslation.keepDir
        val previousHeard = LyricsRepository.heardWordsFor
        val directory = Files.createTempDirectory("bitchord-rejected-transcript").toFile()
        LyricsTranslation.keepDir = directory
        LyricsRepository.heardWordsFor = null
        LyricsRepository.transcriber = { _, _ -> lines }
        try {
            runBlocking { block() }
        } finally {
            LyricsRepository.transcriber = previousTranscriber
            LyricsRepository.heardWordsFor = previousHeard
            LyricsTranslation.keepDir = previousKeep
            directory.deleteRecursively()
        }
    }

    private fun refuse(videoId: String) = LyricsMismatch.rememberRejected(
        videoId,
        LyricsSource.GEMINI_TRANSCRIPT,
        lines,
        LyricsMismatch.Verdict(heardWords = 51, matchedLines = 0, totalLines = lines.size, rejected = true),
    )

    @Test
    fun aTranscriptTheWordsRefusedIsNotAnsweredAgain() = isolated {
        val videoId = "rejected-transcript-memory"
        val first = ask(videoId)
        assertEquals(LyricsSource.GEMINI_TRANSCRIPT, first?.source)
        assertEquals(lines.map { it.timeMs }, first?.lines?.map { it.timeMs })

        refuse(videoId)

        // The reload: memory, disk and the session's transcript all hold the refused sheet.
        assertNull(ask(videoId))
        assertNull(LyricsDiskCache.read(key(videoId))?.result)
    }

    @Test
    fun aTranscriptNobodyRefusedIsStillAnsweredFromMemory() = isolated {
        val videoId = "kept-transcript-memory"
        assertEquals(LyricsSource.GEMINI_TRANSCRIPT, ask(videoId)?.source)
        // Another video's refusal says nothing about this one.
        refuse("some-other-video")
        assertEquals(LyricsSource.GEMINI_TRANSCRIPT, ask(videoId)?.source)
    }
}
