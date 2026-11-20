package com.music.bitchord.ko

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.playback.smart.HqVerificationStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review NR-2: automix opened the next song's lossless copy at a cue, beat grid
 * and mix-in point measured on the YouTube copy, with no regard for the copy's
 * verified offset. A copy off YouTube's clock now stays YouTube in the standby.
 */
class KoLosslessAheadTest {

    private fun verified(offsetMs: Long, rate: Double = 1.0) = HqVerificationStore.Verified(
        offsetMs = offsetMs,
        rate = rate,
        matchPercent = 90,
        tier = HqTier.LOSSLESS_16,
    )

    @Test
    fun `a copy on YouTube's clock is opened ahead`() {
        assertTrue(KoLosslessAhead.cueSafe(verified(0L)))
        assertTrue(KoLosslessAhead.cueSafe(verified(-30L, 1.0005)))
        assertTrue(KoLosslessAhead.cueSafe(verified(30L, 0.9995)))
    }

    @Test
    fun `a copy with an offset or a different rate is not`() {
        assertFalse(KoLosslessAhead.cueSafe(verified(1_500L)))
        assertFalse(KoLosslessAhead.cueSafe(verified(-31L)))
        assertFalse(KoLosslessAhead.cueSafe(verified(0L, 1.002)))
        assertFalse(KoLosslessAhead.cueSafe(verified(0L, Double.NaN)))
    }

    @Test
    fun `a copy with no verdict has no known clock`() {
        assertFalse(KoLosslessAhead.cueSafe(null))
    }

    // Review SW7A-1: the standby refusal left the FLAC pinned, and the plain
    // key served it anyway at the YouTube cue.
    @Test
    fun `automix never pins an off-clock lossless copy as the next row`() {
        assertFalse(KoLosslessAhead.mayPinAhead(automix = true, lossless = true, verified(450L)))
        assertFalse(KoLosslessAhead.mayPinAhead(automix = true, lossless = true, null))
        assertTrue(KoLosslessAhead.mayPinAhead(automix = true, lossless = true, verified(0L)))
        assertTrue(KoLosslessAhead.mayPinAhead(automix = false, lossless = true, verified(450L)))
        assertTrue(KoLosslessAhead.mayPinAhead(automix = true, lossless = false, verified(450L)))
    }

    // Review SW7A-2: a complete #hifi copy from an earlier play.
    @Test
    fun `a song with any off-clock verdict is not all cue safe`() {
        assertTrue(KoLosslessAhead.allCueSafe(listOf(verified(0L), verified(10L))))
        assertFalse(KoLosslessAhead.allCueSafe(listOf(verified(0L), verified(450L))))
        assertFalse(KoLosslessAhead.allCueSafe(emptyList()))
    }

    @Test
    fun `both pin sites and the standby path ask the clock first`() {
        val prefetch = source("src/main/java/com/music/bitchord/ko/KoPrefetchQueue.kt")
        val pin = prefetch.indexOf("koMayPinAhead(context, next, candidate)")
        assertTrue("read-ahead pin must ask the clock", pin >= 0)
        assertTrue(pin < prefetch.indexOf("StreamChoice.remember(next, candidate, substituted = true)"))
        val prep = source("src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt")
        assertTrue("next-prep re-pin must ask the clock", "koMayPinAhead(context, song.videoId, found)" in prep)
        val body = source("src/main/java/com/music/bitchord/ko/KoPlaybackLosslessSwap.kt")
            .substringAfter("internal fun MediaItem.koLosslessAheadItem(context: Context, late: SourceStream? = null): MediaItem? {")
        val defer = body.indexOf("koDeferOffClockUpgradeKey(context, id)")
        assertTrue("the standby must defer an off-clock complete copy", defer >= 0)
        assertTrue("before the no-pin early return", defer < body.indexOf("StreamChoice.of(id) ?: return null"))
        val cache = source("src/main/java/com/music/bitchord/ko/KoLosslessCache.kt")
        assertTrue("upgradeKeyDeferred += videoId\n    }\n\n    fun onTrackBecameCurrent" in cache)
    }

    // Review 11-18: the arming defer asked the cache for the complete copy on
    // the player thread (SimpleCache lock a loader may hold, a file head read).
    @Test
    fun `the arming defer decides on the verdicts alone`() {
        assertTrue(KoLosslessAhead.defersPlainKey(automix = true, listOf(verified(0L), verified(450L))))
        assertTrue(KoLosslessAhead.defersPlainKey(automix = true, emptyList()))
        assertFalse(KoLosslessAhead.defersPlainKey(automix = true, listOf(verified(0L))))
        assertFalse(KoLosslessAhead.defersPlainKey(automix = false, listOf(verified(450L))))
        val defer = source("src/main/java/com/music/bitchord/ko/KoLosslessAhead.kt")
            .substringAfter("internal fun koDeferOffClockUpgradeKey(context: Context, videoId: String) {")
        val body = defer.substringBefore("\n}\n")
        assertTrue("defers through the plain set", "AudioCache.deferUpgradeKey(videoId)" in body)
        assertFalse("no cache lookup at arming", "deferUpgradeKeyIf" in body || "completeUpgradeRendition" in body)
        val audioCache = source("src/main/java/com/music/bitchord/playback/AudioCache.kt")
        assertFalse("the cache-reading defer is gone", "fun deferUpgradeKeyIf(" in audioCache)
    }

    // Review OPT1 (hq-path-join-foreign-prefetch): the playing song's swap
    // download waited for the next row's whole-FLAC prefetch from any host.
    @Test
    fun `a swap waits only for its own row's or its host's prefetch`() {
        assertTrue(koSwapWaitsForPrefetch("cur", "a.example", "cur", "b.example"))
        assertTrue(koSwapWaitsForPrefetch("next", "a.example", "cur", "a.example"))
        assertTrue(koSwapWaitsForPrefetch("next", null, "cur", "a.example"))
        assertTrue(koSwapWaitsForPrefetch("next", "a.example", "cur", null))
        assertTrue(koSwapWaitsForPrefetch(null, "a.example", "cur", "b.example"))
        assertFalse(koSwapWaitsForPrefetch("next", "a.example", "cur", "b.example"))
        val download = source("src/main/java/com/music/bitchord/ko/KoLosslessCache.kt")
            .substringAfter("suspend fun downloadVerifiedRendition(")
            .substringBefore("private suspend fun fetchLosslessRendition(")
        val asked = download.indexOf("koSwapWaitsForPrefetch(prefetchFor, losslessPrefetchHost, mediaId, streamHost)")
        assertTrue("the join asks whose prefetch it is", asked >= 0)
        assertTrue(asked < download.indexOf("running.join()"))
        assertFalse("no unconditional join", "?.join()" in download)
        assertTrue("for prefetch of \$prefetchFor" in download)
    }

    // Review 11-20 (swap, missed 1): the automatic look's forget emptied the slot
    // before the swap, so a complete FLAC of the very same stream was always
    // discarded and fetched again (a refused swap's retry, a joined prefetch).
    @Test
    fun `a slot emptied by the look still knows the stream that filled it`() {
        assertTrue(koFilledByStream(parkedNowUrl = null, parkedBeforeUrl = "https://a.example/f", streamUrl = "https://a.example/f"))
        assertFalse("another candidate is discarded", koFilledByStream(null, "https://a.example/f", "https://b.example/g"))
        assertFalse("nothing known", koFilledByStream(null, null, "https://a.example/f"))
        assertFalse("the slot's own record wins", koFilledByStream("https://b.example/g", "https://a.example/f", "https://a.example/f"))
        assertTrue(koFilledByStream("https://a.example/f", null, "https://a.example/f"))
        val service = source("src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        val reads = Regex("""val parkedBefore = QualityUpgrade\.forced\[mediaId\][^\n]*\n\s*QualityUpgrade\.forget\(mediaId\)""")
        assertEquals("both automatic swaps read the slot before the forget", 2, reads.findAll(service).count())
        assertEquals(2, Regex("""swapLosslessMidSong\(mediaId, uri, \w+, targetTitle, verified, parkedBefore\)""").findAll(service).count())
        val swap = source("src/main/java/com/music/bitchord/ko/KoPlaybackLosslessSwap.kt")
        assertTrue("parkedBefore = parkedBefore)" in swap.substringAfter("suspend fun PlaybackService.koSwapLosslessMidSong("))
        val download = source("src/main/java/com/music/bitchord/ko/KoLosslessCache.kt")
            .substringAfter("suspend fun downloadVerifiedRendition(")
            .substringBefore("private suspend fun fetchLosslessRendition(")
        assertTrue("koFilledByStream(parkedNow?.url, parkedBefore?.url, stream.url)" in download)
        assertTrue("if (!alreadyFilled) discardRendition(uri)" in download)
    }

    // Review 11-20 (swap, missed 3): a reverted manual swap left the refused
    // copy's whole-file download running after its entry was discarded.
    @Test
    fun `a revert stops the refused copy's whole-file download first`() {
        val revert = source("src/main/java/com/music/bitchord/playback/PlaybackService.kt")
            .substringAfter("\"upgrade reverted: replacement reports")
        val cancel = revert.indexOf("item.localConfiguration?.uri?.let(AudioCache::cancelWholeRenditionOf)")
        assertTrue(cancel >= 0)
        assertTrue(cancel < revert.indexOf("QualityUpgrade.forgetReverted(mediaId)"))
        val cache = source("src/main/java/com/music/bitchord/ko/KoLosslessCache.kt")
        assertTrue("wholeRenditionKeyJobs[key] = job" in cache)
        assertTrue("wholeRenditionKeyJobs.remove(key, job)" in cache)
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "$it$path") }.first { it.exists() }
            .readText().replace("\r\n", "\n")
    }

    @Test
    fun `the lossless-ahead item asks the gate before forcing the copy`() {
        val root = File(".").canonicalFile
        val file = listOf("app/", "")
            .map { File(root, "${it}src/main/java/com/music/bitchord/ko/KoPlaybackLosslessSwap.kt") }
            .first { it.exists() }
        val body = file.readText()
            .substringAfter("internal fun MediaItem.koLosslessAheadItem(context: Context, late: SourceStream? = null): MediaItem? {")
            .substringBefore("internal fun PlaybackService.koLogHqAhead(")
        val gate = body.indexOf("KoLosslessAhead.cueSafe(")
        assertTrue("gate missing", gate >= 0)
        assertTrue(body.contains("HqVerificationStore.get(context, id, sourceId, choice.trackId)"))
        assertTrue("gate must run before the copy is forced", gate < body.indexOf("QualityUpgrade.force(id, choice)"))
    }
}
