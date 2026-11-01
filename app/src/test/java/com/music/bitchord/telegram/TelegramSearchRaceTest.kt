package com.music.bitchord.telegram

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Drives real concurrent [TelegramClientManager.searchTracks] calls through a
 * fake TDLib transport, so the lease race is exercised end to end instead of
 * only checking the gate counter.
 */
class TelegramSearchRaceTest {

    private val chats = setOf(101L, 202L)

    @Before
    fun reset() {
        SourceResolver.SourceCooldown.resetForTest()
        TelegramClientManager.setSelectedChatIds(emptySet())
    }

    @After
    fun cleanUp() {
        TelegramClientManager.installSearchTestHooks(null, null)
        TelegramClientManager.setSelectedChatIds(emptySet())
        SourceRegistry.configs.value = emptyList()
        SourceResolver.SourceCooldown.resetForTest()
    }

    @Test
    fun `one search finishing does not break a concurrent search`() = runBlocking {
        val bothInFirstFilter = CyclicBarrier(2)
        val releaseSecond = CountDownLatch(1)
        TelegramClientManager.installSearchTestHooks(
            awaitReady = {},
            request = { function, _ ->
                if (function !is TdApi.SearchMessages) {
                    throw AssertionError("unexpected request ${function::class.simpleName}")
                }
                val search = function
                if (search.filter is TdApi.SearchMessagesFilterAudio) {
                    bothInFirstFilter.await(5, TimeUnit.SECONDS)
                    if (search.query == "second") {
                        releaseSecond.await(5, TimeUnit.SECONDS)
                    }
                }
                // The real request() cannot run without an open client. The
                // gate must still hold the lease taken by the other search
                // after this one's caller has finished.
                if (!TelegramClientManager.searchGateOpenForTest()) {
                    throw IOException("The Telegram client is not open.")
                }
                TdApi.FoundMessages(0, emptyArray<TdApi.Message>(), "")
            },
        )
        TelegramClientManager.setSelectedChatIds(chats)

        val first = async(Dispatchers.IO) { TelegramClientManager.searchTracks("first", 10) }
        val second = async(Dispatchers.IO) { TelegramClientManager.searchTracks("second", 10) }

        // Both searches are inside their first TDLib request at this point.
        // The first then finishes and releases its lease while the second is
        // still holding one; that must not make the second throw.
        assertTrue(first.await().isEmpty())
        releaseSecond.countDown()
        assertTrue(second.await().isEmpty())
    }

    @Test
    fun `changing telegram chats clears its rest`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(id = "telegram", kind = SourceKind.TELEGRAM),
        )
        repeat(2) { SourceResolver.SourceCooldown.failed("telegram") }
        assertTrue(SourceResolver.SourceCooldown.isResting("telegram"))

        TelegramClientManager.setSelectedChatIds(chats)

        assertFalse(SourceResolver.SourceCooldown.isResting("telegram"))
    }
}
