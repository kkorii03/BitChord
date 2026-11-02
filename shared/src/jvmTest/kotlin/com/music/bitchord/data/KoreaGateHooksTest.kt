package com.music.bitchord.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [KO] korea-gate: behind the music.youtube.com gate every InnerTube music call
 * must hand over to [YouTubeFallback]. The v1.9 merge rewrote `radio()` and
 * dropped its hand-over, so AutoPlay came back empty on every track (smoke
 * 11-02, 1.8 filled 13/14). Upstream merges rewrite these bodies wholesale, so
 * the hooks are checked in the source itself.
 */
class KoreaGateHooksTest {
    private val source = listOf(
        "src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt",
        "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt",
    ).map(::File).first { it.isFile }.readText()

    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "missing $signature")
        val next = source.indexOf("\n    suspend fun ", start + signature.length)
            .let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }

    @Test
    fun radioFallsBackBehindTheGate() {
        val radio = body("suspend fun radio(")
        assertTrue("ensureGateKnown()" in radio, "radio() lost its gate check")
        assertTrue("YouTubeFallback.radio(" in radio, "radio() lost its YouTube fallback")
    }

    @Test
    fun everyGatedFallbackIsStillWired() {
        listOf(
            "YouTubeFallback.radio(",
            "YouTubeFallback.history(",
            "YouTubeFallback.quickPicks(",
            "YouTubeFallback.exploreSections(",
            "YouTubeFallback.library(",
            "YouTubeFallback.libraryPlaylistItems(",
            "YouTubeFallback.playlistPage(",
            "YouTubeFallback.home(",
        ).forEach { call -> assertTrue(call in source, "lost $call") }
    }
}
