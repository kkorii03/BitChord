package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.telegram.TelegramLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The review corrections for stage two: automatic walks and required log lines. */
class TelegramSourceStageTwoReviewTest {

    @Test
    fun `automatic upgrade walks include telegram once it is ready to search`() {
        val candidates = SourceResolver.automaticUpgradeCandidates(
            listOf(
                FakeSource("telegram", SourceKind.TELEGRAM, eligible = true),
                FakeSource("addon", SourceKind.ADDON),
                FakeSource("youtube", SourceKind.YOUTUBE),
            ),
        )

        assertEquals(
            listOf(SourceKind.TELEGRAM, SourceKind.ADDON, SourceKind.YOUTUBE),
            candidates.map { it.kind },
        )
    }

    @Test
    fun `automatic upgrade walks still skip telegram without a session`() {
        val candidates = SourceResolver.automaticUpgradeCandidates(
            listOf(
                FakeSource("telegram", SourceKind.TELEGRAM, eligible = false),
                FakeSource("addon", SourceKind.ADDON),
            ),
        )

        assertEquals(listOf(SourceKind.ADDON), candidates.map { it.kind })
    }

    @Test
    fun `search log keeps the required numeric fields instead of redacting timings`() {
        val line = TelegramLog.searchLine(
            queryLength = 12,
            chats = 3,
            results = 7,
            millis = 1234,
            mode = "global",
        )

        assertEquals("telegram: search q=12 chats=3 results=7 ms=1234 mode=global", line)
    }

    @Test
    fun `stream log keeps its hashed file id and event`() {
        val line = TelegramLog.streamLine("open", 123456)

        assertEquals("telegram: stream ${TelegramLog.fileHash(123456)} open", line)
        assertFalse(line.contains("123456"))
    }

    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind,
        private val eligible: Boolean = true,
    ) : MusicSource {
        override val displayName: String get() = configId
        override val automaticUpgradeEligible: Boolean get() = eligible
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
