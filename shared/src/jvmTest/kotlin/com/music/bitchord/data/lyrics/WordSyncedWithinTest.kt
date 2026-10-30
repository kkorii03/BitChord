package com.music.bitchord.data.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The grace wait takes answers in priority order and wakes on completion. */
class WordSyncedWithinTest {

    private fun word(source: LyricsSource, text: String) = LyricsRepository.Result(
        source = source,
        lines = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = text,
                words = listOf(LyricWord(1_000L, 2_000L, text)),
            ),
        ),
    )

    private fun line(source: LyricsSource, text: String) = LyricsRepository.Result(
        source = source,
        lines = listOf(LyricLine(timeMs = 1_000L, text = text)),
    )

    @Test
    fun aHigherPriorityAnswerThatArrivesLaterWins() = runBlocking {
        val high = CompletableDeferred<LyricsRepository.Result?>()
        val low = CompletableDeferred<LyricsRepository.Result?>()
        low.complete(word(LyricsSource.KUGOU, "low"))
        launch {
            delay(20)
            high.complete(word(LyricsSource.LRCLIB, "high"))
        }
        val found = LyricsRepository.wordSyncedWithin(
            listOf(LyricsSource.LRCLIB to high, LyricsSource.KUGOU to low),
            graceMs = 500,
        )
        assertEquals("high", found?.lines?.single()?.text)
    }

    @Test
    fun onceTimeIsUpTheBestAnsweredOneWins() = runBlocking {
        val high = CompletableDeferred<LyricsRepository.Result?>()
        val low = CompletableDeferred<LyricsRepository.Result?>()
        low.complete(word(LyricsSource.KUGOU, "low"))
        val found = LyricsRepository.wordSyncedWithin(
            listOf(LyricsSource.LRCLIB to high, LyricsSource.KUGOU to low),
            graceMs = 60,
        )
        assertEquals("low", found?.lines?.single()?.text)
    }

    @Test
    fun aHigherPriorityLineOnlyAnswerDoesNotBlockALowerWordTimedOne() = runBlocking {
        val high = CompletableDeferred<LyricsRepository.Result?>()
        val low = CompletableDeferred<LyricsRepository.Result?>()
        high.complete(line(LyricsSource.LRCLIB, "high"))
        low.complete(word(LyricsSource.KUGOU, "low"))
        val started = System.currentTimeMillis()
        val found = LyricsRepository.wordSyncedWithin(
            listOf(LyricsSource.LRCLIB to high, LyricsSource.KUGOU to low),
            graceMs = 500,
        )
        assertEquals("low", found?.lines?.single()?.text)
        assertTrue(System.currentTimeMillis() - started < 400, "returned only after the grace period")
    }

    @Test
    fun noWordTimedAnswerMeansNothing() = runBlocking {
        val high = CompletableDeferred<LyricsRepository.Result?>()
        val low = CompletableDeferred<LyricsRepository.Result?>()
        high.complete(line(LyricsSource.LRCLIB, "high"))
        low.complete(line(LyricsSource.KUGOU, "low"))
        assertNull(
            LyricsRepository.wordSyncedWithin(
                listOf(LyricsSource.LRCLIB to high, LyricsSource.KUGOU to low),
                graceMs = 60,
            ),
        )
    }
}
