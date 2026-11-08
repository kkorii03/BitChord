package com.music.bitchord.ko.ui.player

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.ArtistCredit
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.normalizedArtistName
import com.music.bitchord.ui.player.artistCredits

// Korean-fork cover credits ("원곡자 · 가수 커버"), moved out of the upstream
// ui/player/ArtistCredits.kt so the next upstream merge leaves that file alone.
// Upstream call sites use these through one-line `// [KO]` imports.

/**
 * The credits to draw for [song], with a cover's two people kept apart.
 *
 * A cover's byline reads "원곡자 · 가수 커버". Splitting that line like an
 * ordinary collaboration leaves the "커버" decoration on the singer's name, and
 * tapping it searched for the literal string "가수 커버" — which is nobody. The
 * cover reader already knows the two names and the uploader channel, so they are
 * used instead: the singer opens the upload's channel, the original artist opens
 * their own page when the row stated one, and only a name with no channel falls
 * back to a cleaned-name search. The decoration is never part of either.
 */
fun songArtistCredits(song: Song): List<ArtistCredit> {
    if (!CoverTitles.isCover(song)) {
        return artistCredits(song.artist, song.artists, song.artistId)
    }
    val fromByline = song.artist
        .split(COVER_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { coverCreditTarget(it, song) }
        .distinctBy { normalizedArtistName(it.name) }
    if (fromByline.isNotEmpty()) return fromByline
    // A row shown as its uploader channel rather than the cover byline still
    // has the cover fields. Credit them directly; creditSegments draws only the
    // names the line actually contains.
    val original = CoverTitles.creditedOriginalArtist(song)?.takeIf { it.isNotBlank() }
    val singer = CoverTitles.creditedArtist(song).trim().takeIf { it.isNotBlank() }
    return listOfNotNull(
        original?.let { ArtistCredit(it, coverOriginalId(song, it)) },
        singer?.let { ArtistCredit(it, coverUploaderId(song)) },
    ).distinctBy { normalizedArtistName(it.name) }
}

/**
 * Where one stretch of a cover's byline leads, or null when it is not one of
 * the two credited people.
 *
 * [displayed] is what the line says, decoration and all — "아카네 리제 커버".
 * The returned credit carries the bare name (never "커버" or "· ") and, where
 * the row stated one, the channel to open. This is pure: it reads only [song]
 * and [displayed], so the same line always resolves the same way.
 */
fun coverCreditTarget(displayed: String, song: Song): ArtistCredit? {
    if (!isStatedCover(song)) return null
    val shown = ORIGINAL_LABEL
        .replace(displayed.trim().removeSuffix(" $COVER_WORD").trim(), "")
        .trim()
    if (shown.isEmpty()) return null
    val singer = CoverTitles.creditedArtist(song).trim().takeIf { it.isNotBlank() }
    val original = CoverTitles.creditedOriginalArtist(song)?.takeIf { it.isNotBlank() }
    return when {
        singer != null && sameArtistName(shown, singer) ->
            ArtistCredit(name = singer, browseId = coverUploaderId(song))
        original != null && sameArtistName(shown, original) ->
            ArtistCredit(name = original, browseId = coverOriginalId(song, original))
        else -> null
    }
}

/**
 * Whether the row itself states that it is a cover. Unlike
 * [CoverTitles.isCover] this reads no title patterns and no session memory, so
 * [coverCreditTarget] answers from the row alone.
 */
private fun isStatedCover(song: Song): Boolean =
    song.originalTitle != null || song.coverSinger != null || song.coverOriginalArtist != null ||
        (song.isVideo && song.artist.trim().endsWith(" $COVER_WORD"))

/**
 * The channel of a cover's singer — its uploader — or the row's own artist id
 * for anything that is not a cover. Menus use this instead of guessing from the
 * displayed byline.
 */
fun coverSingerChannelId(song: Song): String? =
    if (CoverTitles.isCover(song)) coverUploaderId(song) else song.artistId

/**
 * The upload channel behind a cover. [Song.artistId] is the row's own channel,
 * which for a cover upload is the singer; a stated artist whose name is the
 * uploader's is trusted first, and an id already known to belong to somebody
 * else is not used.
 */
private fun coverUploaderId(song: Song): String? {
    val uploader = song.originalArtist?.trim()?.takeIf { it.isNotBlank() }
    val stated = uploader?.let { name ->
        song.artists.firstOrNull { sameArtistName(it.name, name) }?.browseId
    }
    if (stated != null) return stated
    val rowId = song.artistId ?: return null
    val belongsToAnother = uploader != null && song.artists.any { ref ->
        ref.browseId == rowId && !sameArtistName(ref.name, uploader)
    }
    return rowId.takeUnless { belongsToAnother }
}

/** The original artist's own page, when the row linked one under that name. */
private fun coverOriginalId(song: Song, original: String): String? =
    song.artists.firstOrNull { ref ->
        ref.browseId != null && sameArtistName(ref.name, original)
    }?.browseId

private fun sameArtistName(a: String, b: String): Boolean =
    normalizedArtistName(a) == normalizedArtistName(b)

private const val COVER_WORD = "커버"
private const val COVER_SEPARATOR = " · "

/** A "원곡" label a display string may still carry — never part of the name. */
private val ORIGINAL_LABEL = Regex("""^\s*원곡\s*[:：-]?\s*""")
