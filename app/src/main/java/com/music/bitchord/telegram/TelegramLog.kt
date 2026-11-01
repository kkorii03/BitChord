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

    /**
     * Why a step failed: TDLib's own error code and text (such as
     * "API_ID_INVALID" or "Wrong database encryption key"), or the exception
     * class for a local failure. TDLib error text names the problem, never
     * the values sent, and it still goes through [redact].
     */
    fun failure(step: String, detail: String) = write(line("$step failed", detail.take(160)))

    /**
     * Query text, chat names and phone numbers never reach this line.
     *
     * Built outside [line]'s numeric redaction on purpose: the fields here are
     * already only lengths, counts and milliseconds, and running them through
     * it turned a required `ms=1234` into `ms=***`.
     */
    internal fun searchLine(
        queryLength: Int,
        chats: Int,
        results: Int,
        millis: Long,
        mode: String = "global",
    ): String =
        "telegram: search q=$queryLength chats=$chats results=$results ms=$millis mode=$mode"

    fun search(queryLength: Int, chats: Int, results: Int, millis: Long, mode: String = "global") =
        write(searchLine(queryLength, chats, results, millis, mode))

    /** The file id is hashed, so a numeric-only hash must not be redacted either. */
    internal fun streamLine(event: String, fileId: Int): String =
        "telegram: stream ${fileHash(fileId)} $event"

    fun stream(event: String, fileId: Int) = write(streamLine(event, fileId))

    /** A stable short hash, so log lines can be matched without naming a file id. */
    fun fileHash(fileId: Int): String =
        Integer.toHexString(fileId * -1640531535).padStart(8, '0')

    private fun write(message: String) {
        runCatching { TrackLog.d(TAG, message, null) }
    }
}
