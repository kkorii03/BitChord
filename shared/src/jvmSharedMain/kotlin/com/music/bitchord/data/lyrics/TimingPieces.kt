package com.music.bitchord.data.lyrics

/** Stub: the tests come first and must fail on it. */
object TimingPieces {
    const val MIN_SHARE = 0.6
    const val MIN_LETTERS = 4
    const val MAX_JUMP_MS = 120_000L

    data class Placed(val timeMs: Long, val startMs: Long?, val share: Double, val letters: Int)

    class Result(
        val map: LyricsTimeMap,
        val rate: Double,
        val sizes: List<Int>,
        val jumpsMs: List<Long>,
        val agreePercent: Int,
    )

    class Outcome(val result: Result?, val refused: String?)

    fun fit(
        timesMs: List<Long>,
        texts: List<String>,
        placement: LyricsWordAlign.Placement,
        spellable: Int = texts.size,
    ): Outcome = Outcome(null, "stub")

    fun fit(lines: List<Placed>, spellable: Int = Int.MAX_VALUE): Outcome = Outcome(null, "stub")

    fun overrules(straight: LyricsTimeMap, result: Result): Boolean = false
}
