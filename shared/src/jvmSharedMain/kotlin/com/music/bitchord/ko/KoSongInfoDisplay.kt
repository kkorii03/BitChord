package com.music.bitchord.ko

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.compactCount
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceTrackKeys
import java.util.Locale

/**
 * Which YouTube video the Song Info request belongs to.
 *
 * The queue's id is not always a video id: an addon/JioSaavn row carries
 * `src:<config>::<track>` and a local file can carry a content URI. Sending
 * either to YouTube can only fail, and it used to make the whole panel look
 * broken. A real 11-character id is used as-is (a song version already has its
 * own id); a source-backed row is matched to the YouTube song behind it.
 */
internal object SongInfoTarget {
    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    fun youtubeId(song: Song): String? = song.videoId.takeIf { YOUTUBE_ID.matches(it) }

    fun needsCatalogueLookup(song: Song): Boolean =
        youtubeId(song) == null && SourceTrackKeys.parse(song.videoId) != null
}

/** One line of the Song Info panel, in Apple Music's order. */
enum class SongInfoField {
    ALBUM,
    RELEASE_DATE,
    GENRE,
    COMPOSER,
    LYRICIST,
    QUALITY,
    VIEWS,
    LIKES,
    COMMENTS,
}

/**
 * A line the panel should draw. [value] is null only while the request is
 * still in flight; [unknown] marks a known-missing Apple-style field, which is
 * shown grey rather than as an error.
 */
data class SongInfoLine(
    val field: SongInfoField,
    val value: String?,
    val unknown: Boolean = false,
)

/**
 * Chooses which lines the panel shows.
 *
 * Apple Music's song page leads with the release facts and drops the
 * YouTube-style tally when it has nothing to say. So album and release date
 * are always present (as "No information" when genuinely missing), while
 * genre, credits, quality and the three YouTube counts only appear when they
 * have a value. Kept pure so the rule is testable without a network or a
 * screen.
 */
object SongInfoDisplay {
    fun loadingLines(song: Song): List<SongInfoLine> = listOf(
        SongInfoLine(SongInfoField.ALBUM, song.albumName?.takeIf { it.isNotBlank() }),
        SongInfoLine(SongInfoField.RELEASE_DATE, null),
    )

    fun lines(
        info: SongInfo,
        song: Song,
        quality: String?,
        unknownLabel: String,
    ): List<SongInfoLine> {
        val out = mutableListOf<SongInfoLine>()

        fun required(field: SongInfoField, value: String?) {
            val text = value?.trim()?.takeIf { it.isNotBlank() }
            out += SongInfoLine(field, text ?: unknownLabel, unknown = text == null)
        }

        fun known(field: SongInfoField, value: String?) {
            value?.trim()?.takeIf { it.isNotBlank() }?.let {
                out += SongInfoLine(field, it)
            }
        }

        required(
            SongInfoField.ALBUM,
            info.album?.takeIf { it.isNotBlank() } ?: song.albumName,
        )
        required(
            SongInfoField.RELEASE_DATE,
            info.releaseDate?.takeIf { it.isNotBlank() }
                ?: info.releaseYear?.takeIf { it.isNotBlank() },
        )
        known(SongInfoField.GENRE, info.genre)
        known(SongInfoField.COMPOSER, info.composer)
        known(SongInfoField.LYRICIST, info.lyricist)
        known(SongInfoField.QUALITY, quality)
        known(SongInfoField.VIEWS, info.views?.let(::compactCount))
        known(SongInfoField.LIKES, info.likes)
        known(SongInfoField.COMMENTS, info.comments)
        return out
    }
}

/**
 * The release year inside an album header's subtitle.
 *
 * English headers say "Album • 2010"; Korean ones say "앨범 • 2010년". The
 * year is therefore a token in the line, not the whole line — matching the
 * whole line dropped every Korean album's year.
 */
internal fun releaseYearFromSubtitle(subtitle: String?): String? =
    subtitle?.split('•', '·')
        ?.map { it.trim() }
        ?.firstNotNullOfOrNull { part ->
            YEAR_TOKEN.matchEntire(part)?.value?.take(4)
        }

/** The whole part is a year, optionally with Korea's "년" suffix ("2010년"). */
private val YEAR_TOKEN = Regex("""(?:19|20)[0-9]{2}(?:년)?""")

/**
 * The playback format the listener is actually hearing, or the format a
 * download was saved as. A catalogue's advertised tier is deliberately not
 * used: "LOSSLESS" on a row says what was asked for, not what arrived.
 */
object SongInfoQuality {
    fun label(song: Song, snapshot: NerdStats.Snapshot?): String? {
        val current = snapshot?.takeIf {
            it.mediaId == song.videoId || (song.queueEntryId != null && it.mediaId == song.queueEntryId)
        }
        if (current != null) {
            val lossless = current.isLossless
            val parts = listOfNotNull(
                NerdStats.codecLabel(current.mimeType) ?: current.container,
                current.bitDepth?.takeIf { lossless }?.let { "$it-bit" },
                current.sampleRateHz?.let(::rateLabel),
                current.bitrateKbps?.takeUnless { lossless }?.let { "$it kbps" },
                // Which catalogue actually served these bytes. A YouTube
                // substitution and a downloaded local file otherwise show the
                // same codec and rate, and the source is the only thing that
                // says where the copy came from.
                current.sourceName?.trim()?.takeIf { it.isNotBlank() },
            )
            if (parts.isNotEmpty()) return parts.joinToString(" · ")
        }
        return song.downloadFormat?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun rateLabel(hz: Int): String =
        NerdStats.megahertzLabel(hz)
            ?: "${String.format(Locale.ROOT, "%.1f", hz / 1000f).removeSuffix(".0")} kHz"
}
