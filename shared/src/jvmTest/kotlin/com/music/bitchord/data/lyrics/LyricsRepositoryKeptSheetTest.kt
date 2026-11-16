package com.music.bitchord.data.lyrics

import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The answer kept for a song (memory and [LyricsDiskCache]) against the lookup's own
 * second thoughts about it (review 11-15, L1 and L2). No source is contacted: the only
 * one allowed is hidden, so a search misses at once. The words are made up.
 */
class LyricsRepositoryKeptSheetTest {

    private val sources = setOf(LyricsSource.SIMP_MUSIC)
    private val order = listOf(LyricsSource.SIMP_MUSIC)
    private val artist = "Nobody"

    private fun key(videoId: String, title: String, durationMs: Long, bucket: String? = null) =
        LyricsRepository.questionKey(
            videoId = videoId,
            title = title,
            artist = artist,
            durationMs = durationMs,
            album = null,
            prioritizeSyllableSync = false,
            isrc = null,
            isVideo = false,
            artistKnown = true,
            alternatives = emptyList(),
            order = order,
            sources = sources,
            bucketOverride = bucket,
        )

    private suspend fun ask(videoId: String, title: String, durationMs: Long) =
        LyricsRepository.lyrics(
            videoId = videoId,
            title = title,
            artist = artist,
            durationMs = durationMs,
            sources = sources,
            order = order,
        )

    /** A keep directory of its own and the app's hooks put back afterwards. */
    private fun kept(block: suspend () -> Unit) {
        val previousKeep = LyricsTranslation.keepDir
        val previousHeard = LyricsRepository.heardWordsFor
        val previousFit = LyricsRepository.measuredFitFor
        val directory = Files.createTempDirectory("bitchord-kept-sheet").toFile()
        LyricsTranslation.keepDir = directory
        LyricsRepository.heardWordsFor = null
        LyricsRepository.measuredFitFor = null
        try {
            runBlocking { block() }
        } finally {
            LyricsRepository.heardWordsFor = previousHeard
            LyricsRepository.measuredFitFor = previousFit
            LyricsTranslation.keepDir = previousKeep
            directory.deleteRecursively()
        }
    }

    // ---- L1: a better sheet found for a neighbouring length bucket ----------------

    @Test
    fun aBetterSheetFoundForANeighbourBucketAnswersTheCanonicalKeyToo() = kept {
        val videoId = "kept-neighbour-bucket"
        val title = "Neighbour Bucket"
        // The prefetch stored it with the catalogue's length; the player asks with the decoder's.
        val durationMs = 294_900L
        val canonical = key(videoId, title, durationMs)
        val neighbour = key(videoId, title, durationMs, LyricsRepository.durationBuckets(durationMs).last())
        assertNotEquals(canonical, neighbour)
        val lineSynced = LyricsRepository.Result(
            source = LyricsSource.LRCLIB,
            lines = (0 until 6).map { LyricLine(5_000L + it * 5_000L, "made up line $it") },
            searchedAs = null,
        )
        val wordSynced = lineSynced.copy(
            lines = lineSynced.lines.map { line ->
                line.copy(
                    words = line.text.split(' ').mapIndexed { i, word ->
                        LyricWord(line.timeMs + i * 500L, line.timeMs + i * 500L + 400L, word)
                    },
                )
            },
        )
        LyricsDiskCache.write(neighbour, lineSynced)

        // Answered from the neighbour's file, and remembered under the canonical key.
        assertEquals(1, ask(videoId, title, durationMs)?.let(LyricsDiskCache::rank))

        LyricsRepository.checkAgain(neighbour, canonical, videoId, lineSynced) { wordSynced }
        withTimeout(5_000L) {
            while (LyricsDiskCache.read(neighbour)?.result?.let(LyricsDiskCache::rank) != 2) delay(20L)
        }

        // Leaving and coming back in the same session: the better sheet, not the old one.
        assertEquals(2, ask(videoId, title, durationMs)?.let(LyricsDiskCache::rank))
    }

    // ---- L2: a sheet the app measured against a later, thinner listen -------------

    private val syllables = listOf(
        "ka", "lo", "mi", "ren", "sa", "tu", "vo", "ne", "ri", "da", "fe", "go", "hu", "ji", "pe", "ba",
    )

    /** Deterministic made-up words, as in [LyricsMismatchWordsTest]. */
    private fun words(seed: Int, count: Int): List<String> {
        var state = seed
        fun next(limit: Int): Int {
            state = (state * 1_103_515_245 + 12_345) and 0x7fffffff
            return (state ushr 8) % limit
        }
        return List(count) { List(2 + next(2)) { syllables[next(syllables.size)] }.joinToString("") }
    }

    private fun sheet(seed: Int, lineCount: Int = 24): List<LyricLine> =
        words(seed, lineCount * 5).chunked(5).mapIndexed { i, line ->
            LyricLine(5_000L + i * 5_000L, line.joinToString(" "))
        }

    private fun sung(lines: List<LyricLine>): List<LyricsWordAlign.Heard> =
        lines.flatMap { line ->
            line.text.split(' ').mapIndexed { i, word -> word to line.timeMs + i * 800L }
        }.map { (word, at) -> LyricsWordAlign.Heard(word, at, at + 600L) }

    private val durationMs = 200_000L

    @Test
    fun aKeptSheetTheAppMeasuredIsNotRejectedByAThinnerListen() = kept {
        val videoId = "kept-measured-fit"
        val title = "Measured Fit"
        val keptSheet = LyricsRepository.Result(source = LyricsSource.LRCLIB, lines = sheet(1), searchedAs = null)
        val listen = sung(sheet(2))
        assertTrue(LyricsMismatch.judgeSheet(keptSheet.lines, listen).rejected, "the listen alone rejects the sheet")
        LyricsDiskCache.write(key(videoId, title, durationMs), keptSheet)
        LyricsRepository.heardWordsFor = { listen }
        var askedFor: String? = null
        LyricsRepository.measuredFitFor = { id, lines ->
            askedFor = "$id ${lines.size}"
            99
        }

        val found = ask(videoId, title, durationMs)

        assertEquals(keptSheet.lines.map { it.timeMs }, found?.lines?.map { it.timeMs })
        assertEquals("$videoId ${keptSheet.lines.size}", askedFor)
        assertNull(LyricsMismatch.wrongSound(videoId))
    }

    @Test
    fun aWeakMeasureIsNoProofAndTheKeptSheetIsStillRejected() = kept {
        val videoId = "kept-weak-fit"
        val title = "Weak Fit"
        val keptSheet = LyricsRepository.Result(source = LyricsSource.LRCLIB, lines = sheet(1), searchedAs = null)
        val question = key(videoId, title, durationMs)
        LyricsDiskCache.write(question, keptSheet)
        LyricsRepository.heardWordsFor = { sung(sheet(2)) }
        LyricsRepository.measuredFitFor = { _, _ -> 40 }

        assertNull(ask(videoId, title, durationMs))
        assertNull(LyricsDiskCache.read(question))
        assertTrue(LyricsMismatch.isRejected(videoId, LyricsSource.LRCLIB, keptSheet.lines))
    }
}
