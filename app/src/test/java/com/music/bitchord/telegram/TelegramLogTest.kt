package com.music.bitchord.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostics may name the lifecycle event, never the secret that caused it. */
class TelegramLogTest {

    @Test
    fun lifecycleLinesContainOnlyEventAndReason() {
        val line = TelegramLog.line("client open reason=settings")
        assertTrue(line.startsWith("telegram: client open reason=settings"))
        assertFalse(line.contains("error"))
    }

    @Test
    fun secretsAreRedactedEvenIfTheyReachTheLog() {
        val hash = "0123456789abcdef0123456789abcdef"
        val line = TelegramLog.line(
            "auth state=waitCode",
            "phone=+821012345678 code=123456 password=hunter2 hash=$hash",
        )
        assertFalse(line.contains(hash, ignoreCase = true))
        assertFalse(line.contains("+821012345678"))
        assertFalse(line.contains("123456"))
        assertFalse(line.contains("hunter2"))
        assertTrue(line.startsWith("telegram: auth state=waitCode"))
    }
}
