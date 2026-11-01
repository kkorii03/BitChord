package com.music.bitchord.telegram

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.StreamFormat
import java.util.Locale

/**
 * One playable file found in a Telegram chat.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later), reduced to
 * the fields search and streaming need. The id is chat/message rather than a
 * TDLib file id: the file id changes when Telegram re-uploads a copy, while the
 * message is the thing the listener chose.
 */
data class TelegramTrack(
    val id: String,
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val size: Long,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val format: String,
    val mimeType: String,
    val fileName: String,
    val uniqueId: String? = null,
)

/** What a file name and mime type say about the audio inside, before any decoding. */
data class TelegramFormat(
    val codec: String?,
    val quality: String?,
    val lossless: Boolean,
)

/**
 * Reads a Telegram file's format from its name and mime type.
 *
 * Telegram reports neither bit depth nor sample rate, and downloading headers
 * for every search row would be exactly the kind of background work this
 * source avoids. So search labels what the file itself already says; the
 * decoder reports the real format once playback starts, and the existing
 * HQ verification handles claims that turn out to be false. The upstream
 * project's spectrum-based fake-lossless check stays out of the search path.
 */
object TelegramFormatProbe {

    private val LOSSLESS = setOf("flac", "wav", "wave", "aiff", "aif", "alac", "ape", "wv", "dsf", "dff")
    private val LOSSY = setOf("mp3", "m4a", "mp4", "aac", "ogg", "oga", "opus", "mka", "ac3", "eac3", "ec3", "ac4", "webm")

    /**
     * Every suffix this probe understands. File-name parsing strips the same
     * set instead of keeping a second, narrower list that would leave formats
     * like `.ape` or `.webm` stuck to the title.
     */
    internal val AUDIO_EXTENSIONS: Set<String> = LOSSLESS + LOSSY

    fun of(fileName: String, mimeType: String): TelegramFormat {
        val codec = codecOf(fileName, mimeType)
        return when {
            codec == null -> TelegramFormat(null, null, false)
            codec in LOSSLESS -> TelegramFormat(codec, "LOSSLESS", true)
            codec in LOSSY -> TelegramFormat(codec, "HIGH", false)
            else -> TelegramFormat(codec, null, false)
        }
    }

    /** What the name and the file's size say about depth and rate; nulls mean "not said". */
    data class Estimate(val bitDepth: Int?, val sampleRateHz: Int?, val kbps: Int?)

    private val DEPTH = Regex("""(?<![0-9])(16|24|32)[\s_-]?bits?(?![a-z])""", RegexOption.IGNORE_CASE)
    private val RATE = Regex("""(?<![0-9.])(44[.,]1|48|88[.,]2|96|176[.,]4|192|352[.,]8|384)\s?khz""", RegexOption.IGNORE_CASE)

    /**
     * Bit depth and sample rate for a lossless file, from what the channel
     * already printed — `24bit`, `96kHz` in the file name or title — or, when
     * the name is silent, from its average bitrate. Telegram files were all
     * ranked as plain 16-bit FLAC, so a 24-bit upload could never beat a
     * 24-bit addon row. The bitrate fallback is deliberately cautious: a loud
     * 16-bit master can pass 1100 kbps, so only clearly hi-res rates count,
     * and a claim the audio does not back is still caught by the existing
     * fake-hi-res rule on [kbps] and by the sound comparison.
     */
    fun estimate(track: TelegramTrack, format: TelegramFormat): Estimate {
        if (!format.lossless) return Estimate(null, null, null)
        val kbps = if (track.durationSec > 0 && track.size > 0) {
            (track.size * 8 / 1000 / track.durationSec).toInt()
        } else {
            null
        }
        val text = "${track.fileName} ${track.title}"
        val namedDepth = DEPTH.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val namedRate = RATE.find(text)?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull()
            ?.let { (it * 1000).toInt() }
        if (namedDepth != null || namedRate != null) {
            val depth = namedDepth ?: if (namedRate != null && namedRate >= 88_200) 24 else null
            val rate = namedRate ?: if (depth != null) 44_100 else null
            return Estimate(depth, rate, kbps)
        }
        return when {
            kbps == null -> Estimate(null, null, null)
            kbps >= HI_RES_HIGH_RATE_KBPS -> Estimate(24, 96_000, kbps)
            kbps >= HI_RES_BASE_RATE_KBPS -> Estimate(24, 44_100, kbps)
            else -> Estimate(16, 44_100, kbps)
        }
    }

    /** Above anything 16-bit/44.1 stereo FLAC reaches, cover art included. */
    private const val HI_RES_BASE_RATE_KBPS = 1_600

    /** 24-bit at 88.2 kHz and up; 24/44.1 rarely passes about 2300. */
    private const val HI_RES_HIGH_RATE_KBPS = 2_900

    /** The extension Telegram printed, else the mime type's own name for the audio. */
    fun codecOf(fileName: String, mimeType: String): String? {
        // ALAC usually lives in an .m4a container; when its name or mime says
        // so, that is stronger evidence than the container alone.
        if ("$fileName $mimeType".contains("alac", ignoreCase = true)) return "alac"
        val extension = fileName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() && it.length <= 5 }
        if (extension != null && (extension in LOSSLESS || extension in LOSSY)) return extension
        return when (mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)) {
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/wav", "audio/wave", "audio/x-wav" -> "wav"
            "audio/aiff", "audio/x-aiff" -> "aiff"
            "audio/alac", "audio/x-alac" -> "alac"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/mpeg" -> "mp3"
            "audio/aac", "audio/x-aac" -> "aac"
            "audio/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/x-matroska" -> "mka"
            "audio/ac3" -> "ac3"
            else -> null
        }
    }
}

/** Converts a Telegram file into the [Song] shape every other source returns. */
object TelegramTrackMapper {

    fun toSong(track: TelegramTrack, configId: String): Song {
        val format = TelegramFormatProbe.of(track.fileName, track.mimeType)
        val estimate = TelegramFormatProbe.estimate(track, format)
        return Song(
            videoId = SourceRegistry.trackKey(configId, track.id),
            title = track.title.ifBlank { track.fileName.ifBlank { "Telegram audio" } },
            artist = track.artist.ifBlank { "Unknown Artist" },
            thumbnailUrl = null,
            durationText = durationText(track.durationSec),
            sourceQuality = format.quality,
            sourceHiRes = (estimate.bitDepth ?: 0) >= 24,
        )
    }

    fun streamFormat(track: TelegramTrack): StreamFormat {
        val format = TelegramFormatProbe.of(track.fileName, track.mimeType)
        val estimate = TelegramFormatProbe.estimate(track, format)
        return StreamFormat(
            codec = format.codec,
            kbps = estimate.kbps,
            sampleRateHz = estimate.sampleRateHz,
            bitDepth = estimate.bitDepth,
        )
    }

    fun durationText(seconds: Int): String? {
        if (seconds <= 0) return null
        val minutes = seconds / 60
        val rest = seconds % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, rest)
    }
}
