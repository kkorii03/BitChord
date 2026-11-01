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
                        TdApi.FoundMessages(0, arrayOf(audioMessage(chatId = 101L, id = page.toLong())), "page$page")
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

    private fun audioMessage(chatId: Long, id: Long = 1L): TdApi.Message {
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
            this.id = id
            canBeSaved = true
            content = TdApi.MessageAudio(audio, null)
        }
    }
}
