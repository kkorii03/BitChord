package com.music.bitchord.telegram

import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives [TelegramClientManager.chats] through a fake TDLib transport.
 *
 * The chat list is one `GetChat` per chat and each of those is a blocking round
 * trip, so asking for them one at a time left the settings screen on its
 * spinner for the sum of every chat's latency. What is checked here is that
 * more than one is in flight at once, and that the answers still come back in
 * the order `GetChats` gave — which is what keeps the list from reshuffling.
 */
class TelegramChatListTest {

    /** Deliberately out of id order: the list must follow this, not sort it. */
    private val ids = longArrayOf(30L, 10L, 20L)
    private val inFlight = AtomicInteger()
    private val peak = AtomicInteger()

    @After
    fun cleanUp() {
        TelegramClientManager.installSearchTestHooks(null, null)
    }

    @Test
    fun `chats are asked for together and come back in the list's order`() = runBlocking {
        // Two have to be inside GetChat at the same moment to open this. A
        // serial loop times it out, peaks at one, and fails the assertion.
        val overlapping = CountDownLatch(2)
        TelegramClientManager.installSearchTestHooks(
            awaitReady = {},
            request = { function, _ ->
                when (function) {
                    is TdApi.LoadChats -> TdApi.Ok()
                    is TdApi.GetChats -> TdApi.Chats(ids.size, ids)
                    is TdApi.GetChat -> {
                        peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                        overlapping.countDown()
                        runCatching { overlapping.await(2, TimeUnit.SECONDS) }
                        inFlight.decrementAndGet()
                        TdApi.Chat().apply {
                            id = function.chatId
                            title = "chat ${function.chatId}"
                            type = TdApi.ChatTypePrivate()
                        }
                    }
                    else -> throw AssertionError("unexpected request ${function::class.simpleName}")
                }
            },
        )

        val chats = TelegramClientManager.chats()

        assertEquals(listOf(30L, 10L, 20L), chats.map { it.id })
        assertTrue("GetChat is still asked for one at a time", peak.get() >= 2)
    }
}
