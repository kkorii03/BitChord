package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KO] autoplay-cover: a rejected cover stays out of the top-ups, and the check is wired where it can act. */
class KoAutoplayCoversTest {

    private fun song(id: String) = Song(videoId = id, title = "Placeholder $id", artist = "Act $id", thumbnailUrl = null)

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
    }

    @Test
    fun `a rejected cover is not added again and a kept row is`() {
        KoAutoplayCovers.remember("autoCoverBad", KoAutoplayCover.Verdict(true, "shared=30/36:40"))
        KoAutoplayCovers.remember("autoCoverGood", KoAutoplayCover.Verdict(false, "shared=3/36:40"))
        val rows = listOf(song("autoCoverBad"), song("autoCoverGood"), song("autoCoverNew"))
        assertEquals(
            listOf("autoCoverGood", "autoCoverNew"),
            KoAutoplayCovers.withoutRejected(rows, song("seed")).map { it.videoId },
        )
        assertEquals("shared=30/36:40", KoAutoplayCovers.counted("autoCoverBad"))
    }

    @Test
    fun `a station the listener chose is left alone`() {
        KoAutoplayCovers.remember("autoCoverBad2", KoAutoplayCover.Verdict(true, "shared=30/36:40"))
        val rows = listOf(song("autoCoverBad2"))
        assertEquals(rows, KoAutoplayCovers.withoutRejected(rows, song("seed").copy(radioName = "My station")))
    }

    @Test
    fun `a refill is asked for once per row`() {
        assertTrue(KoAutoplayCovers.refillDue("autoRow1"))
        assertEquals(false, KoAutoplayCovers.refillDue("autoRow1"))
        assertTrue(KoAutoplayCovers.refillDue("autoRow2"))
    }

    @Test
    fun `the check runs after the lyrics and before the paid steps and every top-up is filtered`() {
        val prep = source("playback/NextTrackPreparation.kt")
        assertTrue(prep.indexOf("if (coverDropped()) return") in 1 until prep.indexOf("for (step in plan.steps)"))
        assertTrue(prep.contains("if (step == Step.LYRICS && coverDropped()) return"))
        val service = source("playback/PlaybackService.kt")
        assertTrue(service.contains("dropCoverNext = { koDropCoverNext() }"))
        assertTrue(service.substringAfter("override fun onTimelineChanged").substringBefore("override fun").contains("koDropCoverNext()"))
        assertTrue(source("ko/KoAutoplayTopUp.kt").contains("KoAutoplayCovers.withoutRejected(autoplayRowsAfterCoverFilter(extra, seedSong), seedSong)"))
    }

    @Test
    fun `the removal is posted and never takes the only row ahead`() {
        val drop = source("ko/KoAutoplayCovers.kt").substringAfter("fun PlaybackService.koDropCoverNext")
        val guards = drop.substringBefore("removeMediaItem(index)")
        assertTrue(guards.contains("scope.launch {"))
        assertEquals(2, Regex("mediaItemCount - \\w+\\.currentMediaItemIndex <= 2").findAll(guards).count())
        listOf("versionSwapJob?.isActive", "hqSwapOwnsStandby", "crossfade?.isTransitioning()", "isQueueUndoRestoring").forEach {
            assertTrue(it, drop.contains(it))
        }
    }
}
