package com.music.bitchord.ko

import com.music.bitchord.data.CatalogSongMapper
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.DownloadedMvPolicy
import com.music.bitchord.data.sources.TrackMatcher

/**
 * Review VER6: a saved song row (library, liked, playlist, download) that is
 * really another version of the song it names, and the strict rule that may
 * point it at the official one.
 *
 * A playlist row of an official music video with square artwork is parsed as
 * a song (the responsive-row parser used to ignore `musicVideoType`), and a
 * download of it records `isVideo = false`. From then on the song-version
 * lookup is skipped ("not a video" / "local file") and the HQ length check
 * compares sources against the video's runtime: bad guy DyDfgMOUjCI 206 s for
 * a 194 s song, greedy To4SWGZkEPk 155 s for 131 s, Love Yourself
 * oyEuk8j8imI 273 s for 233 s (phone 11-17).
 *
 * Detection reads only what the row already carries, and the correction is
 * accepted only for the same title and take, the same lead artist, and a row
 * that runs the way a video runs over its song. Everything else keeps the
 * row as it is.
 */
object KoStoredVersionFix {

    /** A music video's visuals add at least this much over its song (bad guy: 12 s). */
    const val MIN_MV_OVERHANG_SEC = 6

    /** More than this is a different recording or a medley, not intro and outro. */
    const val MAX_MV_OVERHANG_SEC = 120

    /** One decision, with the line of evidence the log prints for it. */
    data class Verdict(
        val accepted: Boolean,
        /** `mv`, `length`, `cover` or `artist`: why the row is wrong, or why the version was refused. */
        val reason: String,
        val evidence: String,
        /** For an accepted correction: how much longer the row runs, the length verdict a repair keeps. */
        val overhangMs: Long = 0L,
    ) {
        /**
         * The refused version is another recording (a cover, another act, a
         * video, another take) and must not stay cached as this row's song.
         * A refusal on length alone keeps it: the row may simply be that song
         * (same length), or the lengths could not prove the correction.
         */
        val dropsVersion: Boolean get() = !accepted && reason != "length"
    }

    /**
     * Why [row] may be another version of its song, or null when it is not
     * suspect. Only a row that reads as a known song is judged: a video or a
     * row of unknown type already gets the song-version lookup, and a cover is
     * the performance the listener chose.
     *
     * - `type`: YouTube typed the row as a video (OMV/UGC) though it was
     *   filed as a song.
     * - `mv-title`: the title itself says music video.
     * - `no-album`: a downloaded row that names no release. Only for a
     *   download, whose record keeps the release of every catalogue track it
     *   saved; a streamed row without an album is far too common to look up.
     */
    fun suspicion(row: Song, downloaded: Boolean): String? {
        if (row.isVideo || row.typeUnknown || row.isEpisode || row.videoRadio) return null
        if (row.title.isBlank() || row.artist.isBlank()) return null
        if (CatalogSongMapper.isCover(row)) return null
        val type = row.musicVideoType
        if (type != null) {
            return if (type.contains("OMV", ignoreCase = true) || type.contains("UGC", ignoreCase = true)) {
                "type"
            } else {
                null
            }
        }
        if (DownloadedMvPolicy.hasMvTitleMarker(row.title)) return "mv-title"
        if (downloaded && row.albumName.isNullOrBlank() && row.albumId.isNullOrBlank()) return "no-album"
        return null
    }

    /**
     * Whether [version] may replace the suspect [row].
     *
     * The lookup's own gates have already passed it; this asks the narrower
     * question for a row the listener saved: the same title and take, the
     * same lead artist, not a cover or a video, and the row longer than the
     * song by what a video's visuals add — [MIN_MV_OVERHANG_SEC] to
     * [MAX_MV_OVERHANG_SEC]. A row that is not longer is probably the song
     * itself, and is left alone.
     *
     * @param suspicion what [suspicion] said about [row]; it names the reason
     *   an accepted correction is logged with.
     */
    fun judge(row: Song, version: Song, suspicion: String): Verdict {
        val rowSec = TrackMatcher.secondsOf(row.durationText)
        val versionSec = TrackMatcher.secondsOf(version.durationText)
        val lengths = "row=${rowSec ?: "?"}s version=${versionSec ?: "?"}s"
        fun refuse(reason: String, why: String) = Verdict(false, reason, "$why $lengths")
        // The lookup named the row itself: it is the song, and nothing is dropped.
        if (version.videoId == row.videoId) return refuse("length", "same-id")
        if (version.isVideo) return refuse("mv", "version-is-video")
        val target = TrackMatcher.targetOf(row).copy(isVideo = true)
        if (CatalogSongMapper.isCover(version) || KoTrackMatcher.isCoverOf(version, target)) {
            return refuse("cover", "version-is-cover")
        }
        val wanted = KoTrackMatcher.parseTitle(row.title, row.artist)
        val got = KoTrackMatcher.parseTitle(version.title, version.artist)
        if (wanted.core.isBlank() || !KoTrackMatcher.coresEqual(wanted.cores(), got.cores())) {
            return refuse("cover", "title-differs")
        }
        if (wanted.versions != got.versions) return refuse("cover", "take-differs")
        if (!KoTrackMatcher.namesTheSameArtist(row.artist, version.artist)) return refuse("artist", "artist-differs")
        if (KoTrackMatcher.billsForeignLead(target, version)) return refuse("artist", "lead-differs")
        if (rowSec == null || versionSec == null) return refuse("length", "length-unknown")
        val overhang = rowSec - versionSec
        if (overhang < MIN_MV_OVERHANG_SEC) return refuse("length", "not-longer")
        if (overhang > MAX_MV_OVERHANG_SEC) return refuse("length", "too-long")
        val reason = if (suspicion == "no-album") "length" else "mv"
        return Verdict(true, reason, "why=$suspicion overhang=${overhang}s $lengths", overhangMs = overhang * 1000L)
    }

    /**
     * Why a download record's saved song version ([alias], see
     * `Downloads.savedAliasFor`) is not [row]'s recording, or null when it may
     * still be. Only what the record states is read: the alias's own title and
     * credit. Its release and channels are not known, so they prove nothing.
     */
    fun aliasRefusal(row: Song, alias: Song): String? {
        if (alias.videoId == row.videoId) return null
        if (CatalogSongMapper.isCover(row)) return null
        val claimed = alias.copy(albumId = null, artistId = null, artists = emptyList())
        if (CatalogSongMapper.isCover(claimed)) return "cover"
        val target = TrackMatcher.targetOf(row).copy(albumId = null)
        if (KoTrackMatcher.billsForeignLead(target, claimed)) return "artist"
        val wanted = KoTrackMatcher.parseTitle(row.title, row.artist)
        val got = KoTrackMatcher.parseTitle(claimed.title, claimed.artist)
        if (wanted.core.isNotBlank() && got.core.isNotBlank() && wanted.versions != got.versions) return "cover"
        return null
    }

    /** The one line per correction: `version-fix: old -> new reason=… download=…`. */
    fun fixLine(oldId: String, newId: String, reason: String, download: String, evidence: String): String =
        "version-fix: $oldId -> $newId reason=$reason download=$download $evidence"

    /** The one line per refusal, so a kept row says why. */
    fun keptLine(oldId: String, refusedId: String, verdict: Verdict): String =
        "version-fix: $oldId kept ($refusedId refused reason=${verdict.reason} ${verdict.evidence})"
}
