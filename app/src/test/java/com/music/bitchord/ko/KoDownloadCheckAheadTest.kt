package com.music.bitchord.ko

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.playback.smart.HqVerificationStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-18 16:10-16:31 (vc213): Opalite JCcUifiu3UU, Sucker 73ouhDibImA,
 * Bones V2SVRJ6dcE4 and There's Nothing Holdin' Me Back Ztdc7TcPdAA had saved,
 * unchecked files. Each started on YouTube ('started on the fallback', quality
 * label opus) and moved onto its file 5.6-22 s in, when the start's download
 * check answered ('recording verified; using the downloaded file at=19279').
 *
 * The same check now runs a minute ahead off the playing tick and the next row
 * is rebuilt as the file before it plays. Old code has none of it: this class
 * does not compile against it, and the source checks below fail on its
 * driveDirectWindow, its start check (a checked file answered null and the row
 * stayed on YouTube) and its standby, which reopened any pinned row as a stream.
 */
class KoDownloadCheckAheadTest {

    @Before
    fun clean() = KoDownloadCheckAhead.reset()

    @After
    fun restore() = KoDownloadCheckAhead.reset()

    @Test
    fun theCheckIsOncePerPairAnywhereInsideTheLastMinute() {
        assertFalse("too early", KoDownloadCheckAhead.inLead(KoDownloadCheckAhead.LEAD_MS + 1))
        assertTrue(KoDownloadCheckAhead.inLead(KoDownloadCheckAhead.LEAD_MS))
        assertTrue("inside Direct's window too: a late check still answers the start", KoDownloadCheckAhead.inLead(20_000))
        assertFalse("past the transition", KoDownloadCheckAhead.inLead(0))

        assertTrue(KoDownloadCheckAhead.due("cur", "next"))
        assertFalse("once per pair", KoDownloadCheckAhead.due("cur", "next"))
        assertTrue("another next track is checked", KoDownloadCheckAhead.due("cur", "other"))
        assertTrue("the next song's own next track", KoDownloadCheckAhead.due("other", "third"))
    }

    @Test
    fun savingPowerOrAMeteredConnectionLeavesTheCheckToTheStart() {
        assertTrue(KoDownloadCheckAhead.allowed(metered = false, lowPower = false))
        assertFalse(KoDownloadCheckAhead.allowed(metered = true, lowPower = false))
        assertFalse(KoDownloadCheckAhead.allowed(metered = false, lowPower = true))
    }

    @Test
    fun aVerifiedRowMovesOntoItsFileOnlyOutsideABlendAndOnTheStreamsClock() {
        fun verified(offsetMs: Long) = HqVerificationStore.Verified(offsetMs, 1.0, 100, HqTier.LOSSLESS_16)

        assertTrue("YouTube's own bytes", KoDownloadCheckAhead.mayMoveNext(false, youTubeBytes = true, verified = null))
        assertTrue("Opalite: offset=1", KoDownloadCheckAhead.mayMoveNext(false, youTubeBytes = false, verified = verified(1)))
        assertFalse("the standby already holds the stream", KoDownloadCheckAhead.mayMoveNext(true, youTubeBytes = true, verified = verified(0)))
        assertFalse("the mix cue was measured on the stream", KoDownloadCheckAhead.mayMoveNext(false, youTubeBytes = false, verified = verified(1_500)))
        assertFalse("no known clock", KoDownloadCheckAhead.mayMoveNext(false, youTubeBytes = false, verified = null))
    }

    @Test
    fun theTickRunsTheStartsCheckBeforeDirectOnlyReturnsAndNeverWhilePausedOrSeeking() {
        val drive = source("playback/PlaybackService.kt")
            .substringAfter("private fun driveDirectWindow() {")
            .substringBefore("private fun cancelPrefetch()")
        val hook = drive.indexOf("koCheckNextDownloadAhead(exoPlayer, lastSeekAt)")
        assertTrue("hooked on the window's tick", hook >= 0)
        assertTrue("before the Direct-only return", hook < drive.indexOf("if (!DirectSourceGate.isActive() || !exoPlayer.isPlaying) return"))

        val tick = source("ko/KoDownloadCheckAhead.kt")
            .substringAfter("internal fun PlaybackService.koCheckNextDownloadAhead(")
            .substringBefore("internal fun PlaybackService.koMoveNextOntoFile(")
        val check = tick.indexOf("DownloadHealth.onPlayback(")
        assertTrue("the start's own check, not a new one", check >= 0)
        for (gate in listOf(
            "if (!exoPlayer.isPlaying) return",
            "< PlaybackService.SEEK_SETTLE_MS) return",
            "getBoolean(EXTRA_UNVERIFIED_DOWNLOAD) != true) return",
            "if (!KoDownloadCheckAhead.inLead(remaining)) return",
            "KoDownloadCheckAhead.allowed(",
            "if (!KoDownloadCheckAhead.due(current.mediaId, id)) return",
        )) {
            val at = tick.indexOf(gate)
            assertTrue("missing gate: $gate", at >= 0)
            assertTrue("gate after the check: $gate", at < check)
        }
        assertTrue(
            "the move of an already verified row is once per pair, not every tick",
            tick.indexOf("KoDownloadCheckAhead.due(") < tick.indexOf("koMoveNextOntoFile(id)"),
        )
        assertTrue("a decode for a row not playing yet","AudioDecoder.aheadOfTime.asContextElement(true)" in tick)
        assertTrue("landing after the start goes the start's way", "koApplyDownloadHealth(song, outcome)" in tick)
    }

    @Test
    fun theStartMovesARowOntoAFileVerifiedAfterTheRowWasBuilt() {
        val file = source("ko/KoPlaybackTranslationAhead.kt")
        val start = file.substringAfter("internal fun PlaybackService.koScheduleDownloadHealthCheck(")
            .substringBefore("internal fun PlaybackService.koApplyDownloadHealth(")
        assertFalse("a null answer no longer ends it", "DownloadHealth.onPlayback(service, song) ?: return@launch" in start)
        assertTrue("koApplyDownloadHealth(song, outcome)" in start)
        val apply = file.substringAfter("internal fun PlaybackService.koApplyDownloadHealth(")
            .substringBefore("internal fun PlaybackService.koUseVerifiedDownload(")
        val nullBranch = apply.substringAfter("if (outcome == null) {").substringBefore("return\n")
        assertTrue(
            "a checked file (null) still takes over its waiting row",
            "unverifiedItem && Downloads.isRecordingVerified(song.videoId)) koUseVerifiedDownload(live, item, position)" in nullBranch,
        )
        assertTrue("item.mediaId != song.videoId) return" in apply)
    }

    @Test
    fun theNextRowIsRebuiltAsItsFileAndTheStandbyNeverReopensAFileAsAStream() {
        val move = source("ko/KoDownloadCheckAhead.kt")
            .substringAfter("internal fun PlaybackService.koMoveNextOntoFile(")
        assertTrue("crossfade?.isTransitioning() == true" in move)
        assertTrue("Downloads.verifiedSavedUri(id)" in move)
        assertTrue("queue identity kept", "QueueShuffle.withQueueMetadata(" in move)
        val replaced = move.indexOf("live.replaceMediaItem(index, replacement)")
        assertTrue(replaced >= 0 && replaced < move.indexOf("StreamChoice.forget(id)"))

        val standby = source("ko/KoPlaybackLosslessSwap.kt")
            .substringAfter("internal fun MediaItem.koLosslessAheadItem(context: Context): MediaItem? {")
            .substringBefore("internal fun PlaybackService.koLogHqAhead(")
        val guard = standby.indexOf("if (uri.scheme == \"file\" || uri.scheme == \"content\") return null")
        assertTrue("a file row is not rewritten", guard >= 0)
        assertTrue(guard < standby.indexOf("QualityUpgrade.upgradedUri("))
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }.readText().replace("\r\n", "\n")
    }
}
