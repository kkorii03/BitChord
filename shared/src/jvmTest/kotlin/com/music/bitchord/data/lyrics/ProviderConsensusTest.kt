package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderConsensusTest {

    /** A sheet of [count] lines from [startMs], a line every [stepMs]. */
    private fun sheet(startMs: Long, count: Int = 6, stepMs: Long = 4_000L): List<LyricLine> =
        (0 until count).map { LyricLine(startMs + it * stepMs, "line $it") }

    private fun answer(provider: String, startMs: Long) =
        ProviderConsensus.Candidate(provider, startMs, sheet(startMs))

    @Test
    fun `a used provider the other two disagree with is the outlier`() {
        // KuGou's answer used, two hosts agreeing 4.4 s earlier.
        val verdict = ProviderConsensus.judge(
            listOf(
                answer("KUGOU", 10_000L),
                answer("PAXSENIX", 5_541L),
                answer("LRC_RED", 5_561L),
            ),
            used = "KUGOU",
        )
        assertEquals(ProviderConsensus.Verdict.Outlier(-4_449L, 2), verdict)
        assertEquals(
            "lyrics consensus: v59g4HRNIkc used=KUGOU verdict=outlier(-4449ms,2)",
            ProviderConsensus.logLine("v59g4HRNIkc", "KUGOU", verdict),
        )
    }

    @Test
    fun `the agreeing answer is not an outlier when it is the one used`() {
        val verdict = ProviderConsensus.judge(
            listOf(
                answer("KUGOU", 10_000L),
                answer("PAXSENIX", 10_040L),
                answer("LRC_RED", 9_880L),
            ),
            used = "KUGOU",
        )
        assertEquals(ProviderConsensus.Verdict.Agree, verdict)
    }

    @Test
    fun `two hosts serving the same file are one vote`() {
        // Same timings copied about: nothing independent to agree with.
        val verdict = ProviderConsensus.judge(
            listOf(
                answer("KUGOU", 10_000L),
                answer("LRC_RED", 10_000L),
                answer("PAXSENIX", 5_541L),
            ),
            used = "KUGOU",
        )
        assertEquals(ProviderConsensus.Verdict.Unknown, verdict)
    }

    @Test
    fun `sheets sharing too few lines cannot be compared`() {
        val used = ProviderConsensus.Candidate(
            provider = "KUGOU",
            firstLineMs = 10_000L,
            lines = sheet(10_000L),
        )
        // Only two of its lines are the same words as the used sheet's.
        val other = ProviderConsensus.Candidate(
            provider = "PAXSENIX",
            firstLineMs = 5_541L,
            lines = listOf(
                LyricLine(5_541L, "line 0"),
                LyricLine(9_541L, "line 1"),
                LyricLine(13_541L, "another song"),
                LyricLine(17_541L, "another song 2"),
                LyricLine(21_541L, "another song 3"),
            ),
        )
        assertEquals(ProviderConsensus.Verdict.Unknown, ProviderConsensus.judge(listOf(used, other), "KUGOU"))
    }

    @Test
    fun `one answer on its own is unknown`() {
        assertEquals(
            ProviderConsensus.Verdict.Unknown,
            ProviderConsensus.judge(listOf(answer("KUGOU", 10_000L)), "KUGOU"),
        )
    }
}
