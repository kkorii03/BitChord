package com.music.bitchord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [KO] Work the 1.10 merge and the fork each did, kept to one of them: source wiring no pure test can see. */
class KoUpgradeOverlapWiringTest {

    // Phone logs: 32 of 35 mixes were armed behind a closed screen, and each fetched its Canvas and cover.
    @Test
    fun `a hidden app prepares no transition artwork`() {
        val body = source("playback/PlaybackService.kt")
            .substringAfter("private fun prefetchAutoMixIncoming(").substringBefore("\n    private fun ")
        val visible = body.indexOf("AppForeground.foreground.first { it }")
        val armed = body.indexOf("AutoMixVisualFeed.pending(coverGeneration)?.phase != AutoMixVisualPhase.ARMED", visible)
        assertTrue(visible >= 0)
        assertTrue("the mix must be asked about again once the app is seen", armed > visible)
        assertTrue(body.indexOf("CanvasRepository.canvasFor(") > armed)
        assertTrue(body.indexOf("prefetchAutoMixArtwork(") > armed)
    }

    @Test
    fun `one suggestions request answers the completions and the cards`() {
        val model = source("ui/MainViewModel.kt")
        assertEquals(1, Regex("suggestRequests\\s+\\.debounce").findAll(model).count())
        assertEquals(1, Regex("YtMusicRepository\\.searchSuggestions\\(").findAll(model).count())
        val repository = file("shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt")
        assertEquals(1, Regex("Innertube\\.searchSuggestions\\(").findAll(repository).count())
        val body = model.substringAfter("private fun startSuggestPipeline()").substringBefore("\n    /**")
        assertTrue(
            "completions show before the slower card search",
            body.indexOf("_suggestions.value = rows") in 0 until body.indexOf("YtMusicRepository.searchTypeahead("),
        )
    }

    @Test
    fun `home shows the device's own recents before the session page`() {
        val load = source("ui/MainViewModel.kt").substringAfter("fun loadHome(").substringBefore("\n    /**")
        val early = load.indexOf("koEarlyRecents()")
        assertTrue(early >= 0)
        assertTrue(load.indexOf("YtMusicRepository.homeRecentlyPlayed()") > early)
        val ko = source("ko/ui/KoRecents.kt").substringAfter("suspend fun koEarlyRecents()").substringBefore("\n}")
        assertTrue(ko.contains("MusicGateFallback.storedOpen()"))
    }

    @Test
    fun `top picks reads the core feed, not the first shelf on screen`() {
        val model = source("ui/MainViewModel.kt")
        assertTrue(model.contains("homeFeedJob = launch {"))
        val picks = model.substringAfter("suspend fun buildTopPicks()").substringBefore("\n    }")
        val join = picks.indexOf("homeFeedJob?.join()")
        assertTrue(picks.indexOf("withTimeoutOrNull(8_000L)") in 0 until join)
        assertTrue(picks.indexOf("_home.first {") > join)
    }

    @Test
    fun `the recording check searches once and asks the reference before decoding`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        assertEquals(1, Regex("KoRecordingPrint\\.").findAll(verify.substringAfter("private fun otherRecording(").substringBefore("\n    }").replace("KoRecordingPrint.Sound", "")).count())
        assertFalse(verify.contains("KoRecordingPrint.figures("))
        val cached = verify.substringAfter("suspend fun cachedCopyIsOtherRecording(").substringBefore("\n    /**")
        assertTrue(cached.indexOf("completeEnough(reference") in 0 until cached.indexOf("featuresOf(source"))
        assertTrue(cached.indexOf("completeEnough(candidate") > cached.indexOf("featuresOf(source"))
    }

    private fun source(path: String) = file("app/src/main/java/com/music/bitchord/$path")

    private fun file(path: String): String {
        val root = File(".").canonicalFile
        return (if (File(root, path).isFile) File(root, path) else File(root.parentFile, path))
            .readText().replace("\r\n", "\n")
    }
}