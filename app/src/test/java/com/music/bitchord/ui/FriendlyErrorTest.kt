package com.music.bitchord.ui

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A failure on a Korean screen must never show the exception's English text.
 *
 * The stubbed android.jar makes Context.getString final and returns null, which
 * Kotlin turns into a null-check failure, so the chosen resource id is not
 * observable in a plain JVM test. The pure pieces that are observable — the
 * Korean passthrough and the HTTP status recogniser — are checked here, and the
 * Korean catalogue behind every branch is checked separately. A full branch
 * test needs Robolectric or an instrumentation test.
 */
class FriendlyErrorTest {

    private val context = ContextWrapper(null)

    @Test
    fun koreanMessagesAreReturnedUnchanged() {
        val messages = listOf(
            "인터넷 연결을 확인하세요.",
            "잠시 후 다시 시도하세요.",
            "YouTube Music에서 요청을 거부했습니다.",
        )

        for (message in messages) {
            assertEquals(message, FriendlyError.message(context, RuntimeException(message)))
            assertEquals(message, FriendlyError.message(context, UnknownHostException(message)))
        }
    }

    @Test
    fun koreanTextWinsEvenWhenTheExceptionIsTechnical() {
        val message = "인터넷 연결을 확인하세요 (timeout 503, SSL)"
        assertEquals(message, FriendlyError.message(context, SocketTimeoutException(message)))
        assertEquals(message, FriendlyError.message(context, SSLException(message)))
    }


    @Test
    fun theStringOverloadKeepsKoreanReasonsUnchanged() {
        val message = "소스에 연결할 수 없습니다."
        assertEquals(message, FriendlyError.message(context, message))
    }

    @Test
    fun statusRegexOnlyFinds4xxAnd5xxCodes() {
        assertEquals(429, statusOf("HTTP 429"))
        assertEquals(400, statusOf("HTTP 400"))
        assertEquals(403, statusOf("code 403 and 500"))
        assertEquals(500, statusOf("HTTP 500"))
        assertEquals(599, statusOf("HTTP 599"))
        assertNull(statusOf("HTTP 399"))
        assertNull(statusOf("HTTP 600"))
        assertNull(statusOf("HTTP 4040"))
        assertNull(statusOf("abc503xyz"))
    }

    @Test
    fun everyFriendlyBranchHasAKoreanMessage() {
        val base = strings("values")
        val korean = strings("values-ko")
        val keys = listOf(
            "no_internet_connection",
            "connection_timed_out",
            "secure_connection_failed",
            "proxy_unreachable",
            "server_busy",
            "youtube_request_rejected",
            "connection_failed",
            "something_went_wrong",
        )

        for (key in keys) {
            val english = base[key]
            val koreanText = korean[key]

            assertNotNull("values/strings.xml is missing $key", english)
            assertNotNull("values-ko/strings.xml is missing $key", koreanText)
            assertFalse("$key must not already be Korean in values", english!!.hasHangul())
            assertTrue("$key must be Korean in values-ko", koreanText!!.hasHangul())
        }
    }

    private fun String.hasHangul(): Boolean = any { it in '\uAC00'..'\uD7A3' }

    private fun statusOf(raw: String): Int? {
        val field = FriendlyError::class.java.getDeclaredField("HTTP_STATUS")
        field.isAccessible = true
        val regex = field.get(FriendlyError) as Regex
        return regex.find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun strings(qualifier: String): Map<String, String> {
        val file = File(resDir, "$qualifier/strings.xml")
        assertTrue("${file.path} must exist", file.exists())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            if (name.isNotEmpty()) result[name] = element.textContent
        }
        return result
    }

    private companion object {
        val resDir: File by lazy {
            val projectRoot = File(".").canonicalFile
            if (File(projectRoot, "app/src/main/res").exists()) {
                File(projectRoot, "app/src/main/res")
            } else {
                File(projectRoot, "src/main/res")
            }
        }
    }
}
