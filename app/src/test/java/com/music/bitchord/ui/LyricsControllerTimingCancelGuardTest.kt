package com.music.bitchord.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lyric-timing job cancelled between steps (a new track, or the owner going
 * away) must not go on to start another measurement or to write its answer.
 * The guard was added without a test in the v1.9 merge cleanup; these keep it
 * from being dropped as "unused" again.
 */
class LyricsControllerTimingCancelGuardTest {
    private val source = listOf(
        "app/src/main/java/com/music/bitchord/ui/LyricsController.kt",
        "../app/src/main/java/com/music/bitchord/ui/LyricsController.kt",
    ).map(::File).first { it.isFile }.readText()

    @Test
    fun `a cancelled job stops before the next measurement step`() {
        val measureOnce = Regex("""suspend fun measureOnce\(\): Boolean \{""").find(source)
        assertTrue("measureOnce moved; update this guard test", measureOnce != null)
        val firstStep = source.substring(measureOnce!!.range.last + 1)
            .lineSequence()
            .map(String::trim)
            .first { it.isNotEmpty() && !it.startsWith("//") }
        assertTrue(
            "measureOnce must check cancellation before reading or storing timing; first step was: $firstStep",
            firstStep == "currentCoroutineContext().ensureActive()",
        )
    }

    @Test
    fun `a cancelled job does not fall through to the AI fallback`() {
        val fallback = source.indexOf("if (!calibration && !LyricsTiming.measuredTiming(")
        assertTrue("the AI fallback moved; update this guard test", fallback >= 0)
        assertTrue(
            "the last step before the AI fallback must be the cancellation check",
            source.substring(0, fallback).trimEnd().endsWith("currentCoroutineContext().ensureActive()"),
        )
    }
}