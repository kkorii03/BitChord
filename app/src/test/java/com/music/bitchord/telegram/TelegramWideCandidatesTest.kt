package com.music.bitchord.telegram

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.TelegramSource
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [KO] TG1: TDLib's global search answers newest first, not by relevance. For
 * a common title ("Be With You") the first 15 rows of the page are other
 * artists' files and the right one sits further down the page that was
 * already downloaded. The HQ matcher must see the whole fetched page, with
 * exactly the TDLib requests it cost before; the Search tab keeps its count.
 */
class TelegramWideCandidatesTest {

    private val source = TelegramSource(SourceConfig(id = "tg", kind = SourceKind.TELEGRAM))
    private val target = TrackMatcher.Target(title = "Be With You", artist = "AKMU", durationSec = 236)
    private val global = AtomicInteger()
    private val perChat = AtomicInteger()

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
    fun `the matcher sees the right file deep in the first page without another request`() = runBlocking {
        // 60 rows, newest first: 44 other artists' "Be With You", the AKMU file
        // at position 45, then more of the others. A next page exists.
        val page = (0 until 60).map { index ->
            if (index == 44) {
                audio(101L, 1_000L - index, "Be With You", "AKMU", 236)
            } else {
                audio(101L, 1_000L - index, "Be With You", "Other Artist $index", 150 + index)
            }
        }.toTypedArray()
        install { function ->
            when (function) {
                is TdApi.SearchMessages -> TdApi.FoundMessages(500, page, "page2")
                else -> null
            }
        }
        TelegramClientManager.setSelectedChatIds(setOf(101L))

        // The old matcher call (and the Search tab shape): the 15 newest rows.
        val capped = source.search("Be With You", 15, false, null)
        assertEquals(15, capped.size)
        assertTrue("the old cap never reached the AKMU row", capped.none { it.artist == "AKMU" })
        val cappedRequests = global.getAndSet(0)
        assertEquals("a full first page needs no second page", 1, cappedRequests)

        val wide = source.matchCandidates("Be With You", 15)
        assertEquals("every selected row of the fetched page", 60, wide.size)
        assertEquals("the first rows keep TDLib's order", capped.map { it.videoId }, wide.take(15).map { it.videoId })
        val best = TrackMatcher.best(wide, target)
        assertNotNull("the right file is in the matcher's list", best)
        assertEquals("AKMU", best!!.artist)
        assertEquals("no extra TDLib request for the wider list", cappedRequests, global.get())
        assertEquals("no per-chat walk", 0, perChat.get())
    }

    @Test
    fun `the per-chat walk runs exactly as before and keeps every row it fetched`() = runBlocking {
        val chats = (101L..105L).toSet()
        // Three selected rows per global page and always another page: the
        // page cap stops each filter with fewer than 15 rows, so the per-chat
        // walk runs. Its first chunk of four chats fills 15, so it stops there.
        val sparse = arrayOf(
            audio(101L, 1L, "Song", "Artist A", 200),
            audio(101L, 2L, "Song", "Artist B", 201),
            audio(101L, 3L, "Song", "Artist C", 202),
        )
        install { function ->
            when (function) {
                is TdApi.SearchMessages ->
                    TdApi.FoundMessages(0, sparse, "p${global.get()}")
                is TdApi.SearchChatMessages -> {
                    val base = if (function.filter is TdApi.SearchMessagesFilterAudio) 10_000L else 20_000L
                    val rows = (0 until 30).map { k ->
                        audio(function.chatId, base + k, "Song", "Chat ${function.chatId} $k", 210 + k)
                    }.toTypedArray()
                    TdApi.FoundChatMessages(30, rows, 0L)
                }
                else -> null
            }
        }
        TelegramClientManager.setSelectedChatIds(chats)

        val capped = source.search("Song", 15, false, null)
        assertEquals("the Search tab shape keeps its row count", 15, capped.size)
        val oldGlobal = global.getAndSet(0)
        val oldPerChat = perChat.getAndSet(0)
        assertEquals("two filters, five pages each", 10, oldGlobal)
        assertEquals("one chunk of four chats, two filters each", 8, oldPerChat)

        val wide = source.matchCandidates("Song", 15)
        assertEquals("capped at 100 rows", 100, wide.size)
        assertEquals("same global requests", oldGlobal, global.get())
        assertEquals("the per-chat walk is not widened", oldPerChat, perChat.get())
    }

    private fun install(answer: (TdApi.Function<*>) -> TdApi.Object?) {
        TelegramClientManager.installSearchTestHooks(
            awaitReady = {},
            request = { function, _ ->
                when (function) {
                    is TdApi.SearchMessages -> global.incrementAndGet()
                    is TdApi.SearchChatMessages -> perChat.incrementAndGet()
                    else -> Unit
                }
                answer(function) ?: throw AssertionError("unexpected request ${function::class.simpleName}")
            },
        )
    }

    private fun audio(chatId: Long, messageId: Long, title: String, performer: String, seconds: Int): TdApi.Message {
        val file = TdApi.File().apply {
            id = messageId.toInt() + 1
            size = 30_000_000L
        }
        val audio = TdApi.Audio().apply {
            this.audio = file
            this.title = title
            this.performer = performer
            fileName = "$performer - $title.flac"
            mimeType = "audio/flac"
            duration = seconds
        }
        return TdApi.Message().apply {
            this.chatId = chatId
            id = messageId
            canBeSaved = true
            content = TdApi.MessageAudio(audio, null)
        }
    }
}
