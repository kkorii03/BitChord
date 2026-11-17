package com.music.bitchord.telegram

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 11-17, C3b-1. TDLib kept every Telegram track that was played or
 * auditioned under `telegram-native/files` forever: nothing asked it to delete
 * and its own optimizer is off by default. The pass is TDLib's optimizeStorage,
 * sent when the last lease ends (no reader in a file) and at most once a day.
 *
 * The manager's TDLib client is native and its lease release is private, so the
 * request and the once-a-day rule are tested directly and the wiring is pinned on
 * the source, as HqVersionGuardWiringTest does.
 */
class TelegramStorageCapTest {

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
    }

    @Test
    fun `the request caps size and age and keeps TDLib's immunity for fresh files`() {
        val request = TelegramStorageCap.request()
        assertEquals(2L * 1024 * 1024 * 1024, request.size)
        assertEquals(30 * 86_400, request.ttl)
        // -1 is TDLib's default: a file written in the last hour is never deleted.
        assertEquals(-1, request.immunityDelay)
        assertEquals(-1, request.count)
        // Empty lists mean TDLib's own default set of file types and every chat.
        assertEquals(0, request.fileTypes.size)
        assertEquals(0, request.chatIds.size)
        assertEquals(0, request.excludeChatIds.size)
        assertFalse(request.returnDeletedFileStatistics)
    }

    @Test
    fun `a pass is due once and then not again for a day`() {
        val cap = TelegramStorageCap()
        val day = TelegramStorageCap.MIN_INTERVAL_MS
        assertTrue(cap.takeDue(1_000L))
        assertFalse(cap.takeDue(1_000L))
        assertFalse(cap.takeDue(1_000L + day - 1))
        assertTrue(cap.takeDue(1_000L + day))
        assertFalse(cap.takeDue(1_000L + day + 60_000L))
    }

    @Test
    fun `the last lease asks TDLib to trim before it may close the client`() {
        val manager = source("telegram/TelegramClientManager.kt")
        val release = manager.substringAfter("private fun releaseCountedLease(")
            .substringBefore("private fun ")
        assertTrue(release.contains("if (last) trimStorageWhileIdle()"))
        // Before the holder is released: that release can close the client, and a
        // Close aborts a storage pass that is still running.
        assertTrue(
            release.indexOf("trimStorageWhileIdle()") < release.indexOf("gate.release(STREAM_HOLDER)"),
        )
        val trim = manager.substringAfter("private fun trimStorageWhileIdle(")
            .substringBefore("private fun ")
        assertTrue(trim.contains("storageCap.takeDue("))
        assertTrue(trim.contains("call(target, TelegramStorageCap.request(), TelegramStorageCap.TIMEOUT_MS)"))
        assertTrue(trim.contains("auth !is TdApi.AuthorizationStateReady"))
        // Never fire-and-forget on a timer or a coroutine: the event is the lease release.
        assertFalse(manager.contains("TelegramStorageCap.request(), null"))
    }

    @Test
    fun `the storage line carries counts unredacted`() {
        assertEquals(
            "telegram: storage kept bytes=1234567 files=42",
            TelegramLog.storageLine(1_234_567L, 42),
        )
    }
}
