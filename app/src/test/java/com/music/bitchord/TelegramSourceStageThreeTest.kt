package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.telegram.TelegramAuthStage
import com.music.bitchord.telegram.TelegramFileNames
import com.music.bitchord.telegram.TelegramTrack
import com.music.bitchord.telegram.TelegramTrackMapper
import com.music.bitchord.telegram.TelegramUpgradeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage three wiring: Telegram joins automatic quality walks only for a signed-in
 * account with a chosen chat, and its file names carry the title and artist the
 * existing strict matcher needs.
 */
class TelegramSourceStageThreeTest {

    private fun track(
        title: String,
        artist: String,
        fileName: String = "$artist - $title.flac",
        duration: Int = 260,
    ) = TelegramTrack(
        id = "-100123_42",
        chatId = -100123,
        messageId = 42,
        fileId = 77,
        size = 30_000_000,
        title = title,
        artist = artist,
        durationSec = duration,
        format = "flac",
        mimeType = "audio/flac",
        fileName = fileName,
    )

    @Test
    fun `telegram is eligible only after sign in chat selection and the source switch`() {
        assertTrue(
            TelegramUpgradeGate.isEligible(
                stage = TelegramAuthStage.READY,
                selectedChats = 2,
                enabled = true,
            ),
        )
        assertEquals(
            TelegramUpgradeGate.NO_CHATS,
            TelegramUpgradeGate.ineligibleReason(
                stage = TelegramAuthStage.READY,
                selectedChats = 0,
                enabled = true,
            ),
        )
        assertEquals(
            TelegramUpgradeGate.SIGNED_OUT,
            TelegramUpgradeGate.ineligibleReason(
                stage = TelegramAuthStage.WAIT_CODE,
                selectedChats = 2,
                enabled = true,
            ),
        )
        assertEquals(
            TelegramUpgradeGate.SIGNED_OUT,
            TelegramUpgradeGate.ineligibleReason(
                stage = TelegramAuthStage.WAIT_PHONE,
                selectedChats = 2,
                enabled = true,
            ),
        )
        assertTrue(
            "an idle client is still signed in once chats are chosen",
            TelegramUpgradeGate.isEligible(
                stage = TelegramAuthStage.CLOSED,
                selectedChats = 2,
                enabled = true,
            ),
        )
        assertEquals(
            TelegramUpgradeGate.SIGNED_OUT,
            TelegramUpgradeGate.ineligibleReason(
                stage = TelegramAuthStage.CLOSED,
                selectedChats = 0,
                enabled = true,
            ),
        )
        assertEquals(
            TelegramUpgradeGate.DISABLED,
            TelegramUpgradeGate.ineligibleReason(
                stage = TelegramAuthStage.READY,
                selectedChats = 2,
                enabled = false,
            ),
        )
    }

    @Test
    fun `automatic walks include telegram only when its gate is ready`() {
        val ready = FakeSource("telegram", SourceKind.TELEGRAM, eligible = true)
        val signedOut = FakeSource(
            "telegram",
            SourceKind.TELEGRAM,
            eligible = false,
            reason = TelegramUpgradeGate.SIGNED_OUT,
        )
        val addon = FakeSource("addon", SourceKind.ADDON)

        assertEquals(
            listOf(ready, addon),
            SourceResolver.automaticUpgradeCandidates(listOf(ready, addon)),
        )
        assertEquals(
            listOf(addon),
            SourceResolver.automaticUpgradeCandidates(listOf(signedOut, addon)),
        )
    }

    @Test
    fun `signed out telegram is not a substitute or upgrade candidate`() {
        val signedOut = FakeSource(
            "telegram",
            SourceKind.TELEGRAM,
            eligible = false,
            reason = TelegramUpgradeGate.SIGNED_OUT,
        )
        val addon = FakeSource("addon", SourceKind.ADDON)

        val candidates = SourceResolver.automaticUpgradeCandidates(
            listOf(signedOut, addon),
            TrackMatcher.Target("Paniyon Sa", "Atif Aslam", durationSec = 245),
        )

        assertFalse(candidates.any { it.kind == SourceKind.TELEGRAM })
        assertTrue(candidates.any { it.kind == SourceKind.ADDON })
    }

    @Test
    fun `artist and title are read from a lossless file name`() {
        val parsed = TelegramFileNames.parse("Atif Aslam - Paniyon Sa.flac")

        assertNotNull(parsed)
        assertEquals("Atif Aslam", parsed?.artist)
        assertEquals("Paniyon Sa", parsed?.title)
    }

    @Test
    fun `track numbers and dashed artist names leave the real credit intact`() {
        val numbered = TelegramFileNames.parse("01. Jay-Z - 99 Problems.flac")
        assertEquals("Jay-Z", numbered?.artist)
        assertEquals("99 Problems", numbered?.title)

        val enDash = TelegramFileNames.parse("Atif Aslam \u2013 Paniyon Sa.flac")
        assertEquals("Atif Aslam", enDash?.artist)
        assertEquals("Paniyon Sa", enDash?.title)

        assertNull(TelegramFileNames.parse("Paniyon Sa.flac"))
    }

    @Test
    fun `a cover file name is refused by the existing original recording rule`() {
        val parsed = TelegramFileNames.parse("Atif Aslam - Paniyon Sa (Cover).flac")!!
        val song = TelegramTrackMapper.toSong(
            track(parsed.title, parsed.artist),
            configId = "telegram-config",
        )
        val target = TrackMatcher.Target("Paniyon Sa", "Atif Aslam", durationSec = 260)

        assertFalse(TrackMatcher.isOriginalRecording(song, target))
    }

    @Test
    fun `the original file name clears the same strict match`() {
        val parsed = TelegramFileNames.parse("Atif Aslam - Paniyon Sa.flac")!!
        val song = TelegramTrackMapper.toSong(
            track(parsed.title, parsed.artist),
            configId = "telegram-config",
        )
        val target = TrackMatcher.Target("Paniyon Sa", "Atif Aslam", durationSec = 260)

        assertTrue(TrackMatcher.isOriginalRecording(song, target))
    }

    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind,
        private val eligible: Boolean = true,
        private val reason: String? = null,
    ) : MusicSource {
        override val displayName: String get() = configId
        override val automaticUpgradeEligible: Boolean get() = eligible
        override val automaticUpgradeIneligibleReason: String? get() = reason
        override suspend fun health(): SourceHealth = SourceHealth.Ok()
        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> = emptyList()

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
    }
}
