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
    fun `automatic upgrade walks leave telegram out until stage three`() {
        val candidates = SourceResolver.automaticUpgradeCandidates(
            listOf(
                FakeSource("telegram", SourceKind.TELEGRAM),
                FakeSource("addon", SourceKind.ADDON),
                FakeSource("youtube", SourceKind.YOUTUBE),
            ),
        )

        assertEquals(listOf(SourceKind.ADDON, SourceKind.YOUTUBE), candidates.map { it.kind })
    }

    @Test
    fun `search log keeps the required numeric fields instead of redacting timings`() {
        val line = TelegramLog.searchLine(queryLength = 12, chats = 3, results = 7, millis = 1234)

        assertEquals("telegram: search q=12 chats=3 results=7 ms=1234", line)
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
    ) : MusicSource {
        override val displayName: String get() = configId
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
