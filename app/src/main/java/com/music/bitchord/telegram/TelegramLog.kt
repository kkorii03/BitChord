package com.music.bitchord.telegram

import com.music.bitchord.data.TrackLog

/**
 * Diagnostic lines for the Telegram source.
 *
 * Only the client lifecycle and the authorization stage are written. Phone
 * numbers, codes, passwords, api hashes and session contents are never passed
 * here; [redact] is a second line of defence for a detail that slips in.
 */
object TelegramLog {

    private const val TAG = "BitChord"

    private val namedSecret = Regex(
        "(?i)\\b(password|passcode|code|phone|api_?hash|hash)=([^\\s,;]+)",
    )
    private val apiHash = Regex("(?i)\\b[0-9a-f]{32}\\b")
    private val phone = Regex("\\+?[0-9][0-9 ()-]{6,}[0-9]")
    private val numericCode = Regex("\\b[0-9]{4,8}\\b")

    fun line(event: String, detail: String? = null): String {
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " ${redact(it)}" }.orEmpty()
        return "telegram: ${redact(event)}$suffix"
    }

    fun redact(value: String): String =
        value
            .replace(namedSecret) { "${it.groupValues[1]}=***" }
            .replace(apiHash, "***")
            .replace(phone, "***")
            .replace(numericCode, "***")

    fun clientOpened(reason: String) = write(line("client open reason=$reason"))
    fun clientClosed(reason: String) = write(line("client close reason=$reason"))
    fun authState(state: String) = write(line("auth state=$state"))

    private fun write(message: String) {
        runCatching { TrackLog.d(TAG, message, null) }
    }
}
