package com.music.bitchord.telegram

import org.drinkless.tdlib.TdApi
import java.util.Locale

/**
 * Reads the `Artist - Title` form Telegram users file lossless uploads under.
 *
 * Audio messages usually carry tags, but a document — which is how most FLAC
 * and ALAC reaches Telegram — often has only its file name. The separator has
 * to be a spaced dash so a hyphenated artist or title (Jay-Z, "Spider-Man")
 * stays whole; leading track numbers are packaging, like the ones the title
 * parser already drops.
 */
// One list for parsing and message filtering, taken from the format probe so
// a suffix the player accepts can never be left on the parsed title.
private val AUDIO_EXTENSION_PATTERN = TelegramFormatProbe.AUDIO_EXTENSIONS
    .sorted()
    .joinToString("|") { Regex.escape(it) }

internal object TelegramFileNames {

    private val SEPARATOR = Regex("""\s+[-\u2013\u2014]\s+""")
    private val TRACK_NUMBER = Regex("""^\s*\d{1,3}\s*[.\-\u2013\u2014]\s*""")
    private val AUDIO_EXTENSION = Regex(
        """\.($AUDIO_EXTENSION_PATTERN)$""",
        RegexOption.IGNORE_CASE,
    )

    data class Parsed(val artist: String, val title: String)

    fun parse(fileName: String): Parsed? {
        // Only a known audio suffix is an extension. A dot inside the title or
        // artist ("Mr. Brightside") is part of the name, not something to cut.
        val stem = fileName.trim().replace(AUDIO_EXTENSION, "").trim()
        if (stem.isEmpty()) return null
        // A leading number is a track number only when removing it still leaves
        // the spaced-dash separator. Otherwise it is the artist itself, as in
        // "112 - Peaches & Cream".
        val withoutTrack = stem.replace(TRACK_NUMBER, "").trim()
        val cleaned = if (withoutTrack != stem && SEPARATOR.containsMatchIn(withoutTrack)) {
            withoutTrack
        } else {
            stem
        }
        val separator = SEPARATOR.find(cleaned) ?: return null
        val artist = cleaned.substring(0, separator.range.first).trim()
        val title = cleaned.substring(separator.range.last + 1).trim()
        if (artist.isEmpty() || title.isEmpty()) return null
        return Parsed(artist = artist, title = title)
    }
}

/**
 * Turns TDLib messages into [TelegramTrack]s.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later) TrackMetadata
 * and MediaPolicy, reduced to the audio/document filters this stage uses. The
 * safety checks are kept: protected, secret and self-destructing messages are
 * never offered, since they must not be cached or saved.
 */
object TelegramMessageMapper {

    // Which documents count as music. Container suffixes that are usually video
    // (mp4, webm) stay out: a channel's clips must not show up as songs or as
    // upgrade candidates. Parsing still strips every probe suffix.
    private val DOCUMENT_EXCLUDED = setOf("mp4", "webm")
    private val FILE_NAME = Regex(
        """.*\.(${
            TelegramFormatProbe.AUDIO_EXTENSIONS
                .filterNot { it.lowercase() in DOCUMENT_EXCLUDED }
                .sorted()
                .joinToString("|") { Regex.escape(it) }
        })$""",
        RegexOption.IGNORE_CASE,
    )

    fun chatAllowed(chat: TdApi.Chat?): Boolean =
        chat != null &&
            chat.type != null &&
            !chat.hasProtectedContent &&
            chat.type !is TdApi.ChatTypeSecret

    fun savable(message: TdApi.Message?): Boolean =
        message != null &&
            message.canBeSaved &&
            message.selfDestructType == null &&
            message.selfDestructIn <= 0 &&
            message.ephemeralContent == null &&
            message.restrictionInfo == null

    fun from(message: TdApi.Message?): TelegramTrack? {
        if (message == null || message.chatId == 0L || message.id <= 0 || !savable(message)) return null
        val file: TdApi.File
        var title = ""
        var artist = ""
        var fileName = ""
        var mimeType = ""
        var duration = 0
        when (val content = message.content) {
            is TdApi.MessageAudio -> {
                val audio = content.audio ?: return null
                file = audio.audio ?: return null
                title = audio.title.orEmpty()
                artist = audio.performer.orEmpty()
                fileName = audio.fileName.orEmpty()
                mimeType = audio.mimeType.orEmpty()
                duration = audio.duration
            }
            is TdApi.MessageDocument -> {
                val document = content.document ?: return null
                file = document.document ?: return null
                fileName = document.fileName.orEmpty()
                mimeType = document.mimeType.orEmpty()
                if (!FILE_NAME.matches(fileName.lowercase(Locale.ROOT))) return null
            }
            else -> return null
        }
        if (file.id == 0) return null
        val format = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val named = TelegramFileNames.parse(fileName)
        return TelegramTrack(
            id = "${message.chatId}_${message.id}",
            chatId = message.chatId,
            messageId = message.id,
            fileId = file.id,
            size = file.size,
            title = title.ifBlank { named?.title ?: fileName },
            artist = artist.ifBlank { named?.artist.orEmpty() },
            durationSec = duration,
            format = format,
            mimeType = mimeType,
            fileName = fileName,
            uniqueId = file.remote?.uniqueId,
        )
    }

    /** The chat/message pair inside a track id, or null when it is not one. */
    fun parseId(id: String): Pair<Long, Long>? {
        if (id.length > 42 || !id.matches(Regex("-?[0-9]+_[0-9]+"))) return null
        val split = id.indexOf('_')
        val chat = id.substring(0, split).toLongOrNull() ?: return null
        val message = id.substring(split + 1).toLongOrNull() ?: return null
        if (chat == 0L || message <= 0L || id != "${chat}_$message") return null
        return chat to message
    }
}
