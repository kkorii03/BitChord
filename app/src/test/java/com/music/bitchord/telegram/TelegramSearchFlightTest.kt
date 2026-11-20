package com.music.bitchord.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The search flight owns the lease and the client: an abandoned flight must
 * stop starting TDLib requests, and the per-chat fallback must run only when
 * the global result is actually incomplete.
 */
class TelegramSearchFlightTest {

    private val chats = setOf(101L)

    @Before
    fun reset() {
        TelegramClientManager.installSearchTestHooks(null, null)
        TelegramClientManager.setSelectedChatIds(emptySet())
    }

    @After
    fun cleanUp() {
        TelegramClientManager.installSearchTestHooks(null, null)
        TelegramClientManager.setSelectedChatIds(emptySet())
    }

    @Test
    fun `an abandoned flight stops starting requests`() = runBlocking {
        val firstRequest = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondRequest = CountDownLatch(1)
        val requests = AtomicInteger()

        TelegramClientManager.installSearchTestHooks(
            awaitReady = {},
            request = { function, _ ->
                if (function !is TdApi.SearchMessages) {
                    throw AssertionError("unexpected request ${function::class.simpleName}")
                }
                if (requests.incrementAndGet() == 1) {
                    firstRequest.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                } else {
                    secondRequest.countDown()
                }
                TdApi.FoundMessages(0, emptyArray<TdApi.Message>(), "")
            },
        )
        TelegramClientManager.setSelectedChatIds(chats)

        val caller = launch(Dispatchers.IO) {
            runCatching { TelegramClientManager.searchTracks("abandoned", 10) }
        }
        assertTrue(firstRequest.await(5, TimeUnit.SECONDS))
        // The track that asked for this search is gone; the flight now has no
        // waiter and must not start the next filter/page.
        caller.cancelAndJoin()
        releaseFirst.countDown()

        assertFalse(
            "an abandoned flight asked TDLib again",
            secondRequest.await(1, TimeUnit.SECONDS),
        )
        assertEquals(1, requests.get())
    }

    /**
     * Phone 11-20 15:40: the walk gave Telegram 15 s and moved on, but the TDLib search
     * was still blocked in its own 15 s wait, so the lease and the client stayed open
     * with nobody asking. The walk's timeout must end the hung request and the lease now.
     */
    @Test
    fun `a walk that times out ends a hung search and its lease at once`() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val interrupted = CountDownLatch(1)

        TelegramClientManager.installSearchTestHooks(
            awaitReady = {},
            request = { _, _ ->
                requestStarted.countDown()
                try {
                    // A TDLib answer that never comes on a dead network.
                    CountDownLatch(1).await(20, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
                TdApi.FoundMessages(0, emptyArray<TdApi.Message>(), "")
            },
        )
        TelegramClientManager.setSelectedChatIds(chats)

        val walk = launch(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(300) { TelegramClientManager.searchTracks("hung", 10) }
        }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        assertTrue("the search holds the client while it runs", TelegramClientManager.searchGateOpenForTest())
        walk.join()

        assertTrue("the walk's timeout must interrupt the hung TDLib wait", interrupted.await(3, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (TelegramClientManager.searchGateOpenForTest() && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse("a timed-out search kept the Telegram client open", TelegramClientManager.searchGateOpenForTest())
    }

    @Test
    fun `an exhausted global search does not fall back for other chats' rows`() = runBlocking {
        val perChatRequests = AtomicInteger()
        val foreign = TdApi.Message().apply {
            chatId = 999L
            id = 1L
        }

        TelegramClientManager.installSearchTestHooks(
            awaitReady = {},
            request = { function, _ ->
                when (function) {
                    is TdApi.SearchMessages ->
                        TdApi.FoundMessages(0, arrayOf(foreign), "")
                    is TdApi.SearchChatMessages -> {
                        perChatRequests.incrementAndGet()
                        TdApi.FoundChatMessages(0, emptyArray<TdApi.Message>(), 0L)
                    }
                    else -> throw AssertionError("unexpected request ${function::class.simpleName}")
                }
            },
        )
        TelegramClientManager.setSelectedChatIds(chats)

        assertTrue(TelegramClientManager.searchTracks("missing", 10).isEmpty())
        assertEquals(
            "a complete global answer must not trigger the per-chat walk",
            0,
            perChatRequests.get(),
        )
    }

    @Test
    fun `a page-capped global search still falls back with selected rows`() = runBlocking {
        val perChatRequests = AtomicInteger()
        val pages = AtomicInteger()
        val selected = audioMessage(chatId = 101L)

        TelegramClientManager.installSearchTestHooks(
            awaitReady = {},
            request = { function, _ ->
                when (function) {
                    is TdApi.SearchMessages ->
                        TdApi.FoundMessages(0, arrayOf(selected), "page${pages.incrementAndGet()}")
                    is TdApi.SearchChatMessages -> {
                        perChatRequests.incrementAndGet()
                        TdApi.FoundChatMessages(0, emptyArray<TdApi.Message>(), 0L)
                    }
                    else -> throw AssertionError("unexpected request ${function::class.simpleName}")
                }
            },
        )
        TelegramClientManager.setSelectedChatIds(chats)

        val found = TelegramClientManager.searchTracks("page-capped", 10)
        assertTrue(
            "a page-capped global search must ask the selected chats",
            perChatRequests.get() > 0,
        )
        assertTrue(
            "the rows the global pass found must survive an empty per-chat walk",
            found.isNotEmpty(),
        )
    }

    @Test
    fun `a page-capped global search that filled the limit does not walk the chats`() = runBlocking {
        val perChatRequests = AtomicInteger()
        val pages = AtomicInteger()

        TelegramClientManager.installSearchTestHooks(
            awaitReady = {},
            request = { function, _ ->
                when (function) {
                    is TdApi.SearchMessages -> {
                        val page = pages.incrementAndGet()
                        TdApi.FoundMessages(0, arrayOf(audioMessage(chatId = 101L, messageId = page.toLong())), "page$page")
                    }
                    is TdApi.SearchChatMessages -> {
                        perChatRequests.incrementAndGet()
                        TdApi.FoundChatMessages(0, emptyArray<TdApi.Message>(), 0L)
                    }
                    else -> throw AssertionError("unexpected request ${function::class.simpleName}")
                }
            },
        )
        TelegramClientManager.setSelectedChatIds(chats)

        val found = TelegramClientManager.searchTracks("filled", 1)
        assertEquals(1, found.size)
        assertEquals("a full answer needs no per-chat walk", 0, perChatRequests.get())
    }

    private fun audioMessage(chatId: Long, messageId: Long = 1L): TdApi.Message {
        val file = TdApi.File().apply {
            id = 1
            size = 1024L
        }
        val audio = TdApi.Audio().apply {
            this.audio = file
            title = "Title"
            performer = "Artist"
            fileName = "Artist - Title.flac"
            mimeType = "audio/flac"
            duration = 180
        }
        return TdApi.Message().apply {
            this.chatId = chatId
            id = messageId
            canBeSaved = true
            content = TdApi.MessageAudio(audio, null)
        }
    }
}
