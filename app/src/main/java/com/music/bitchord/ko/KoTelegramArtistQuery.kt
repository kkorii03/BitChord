package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher

/**
 * [KO] TG2: one artist-only Telegram search for a Hangul title.
 *
 * The owner's chats are mostly foreign channels that file K-pop under the Latin
 * title: ILLIT's '체포하겠어' is the file 'You're Under Arrest' by 'ILLIT'. TDLib's
 * message search is literal, so the title queries ("체포하겠어 illit", "체포하겠어")
 * answer nothing and the file never reaches the matcher, whose
 * `title-unverified` grade would have offered it to hq-verify.
 *
 * Asked last and at most once per walk, only when every title query was a
 * conclusive miss, only for Telegram (other catalogues index both titles), and
 * only on a walk that may offer a `title-unverified` row at all
 * (`strictOriginal` + `allowArtistUnverified`: the upgrade, hq-ahead and the
 * tap prefetch, each with hq-verify behind it). The rows it finds take exactly
 * the matcher's usual checks; nothing is admitted on this search alone.
 */
internal object KoTelegramArtistQuery {

    private const val TAG = "BitChord"

    /** The artist-only query for this walk, or null when it must not be asked. */
    fun query(
        source: MusicSource,
        target: TrackMatcher.Target,
        strictOriginal: Boolean,
        allowArtistUnverified: Boolean,
        asked: List<String>,
    ): String? {
        if (source.kind != SourceKind.TELEGRAM || !strictOriginal || !allowArtistUnverified) return null
        return TrackMatcher.latinArtistQuery(target)?.takeIf { it !in asked }
    }

    /**
     * After the title queries missed: the artist-only search through [search],
     * or [last] when it is not asked. A miss keeps [last], so the walk's verdict
     * still describes the title queries; a stream or an interrupted answer is
     * returned as found.
     */
    suspend fun after(
        source: MusicSource,
        target: TrackMatcher.Target,
        strictOriginal: Boolean,
        allowArtistUnverified: Boolean,
        asked: List<String>,
        last: SourceResolver.SearchOutcome,
        search: suspend (String) -> SourceResolver.SearchOutcome,
    ): SourceResolver.SearchOutcome {
        val artist = query(source, target, strictOriginal, allowArtistUnverified, asked) ?: return last
        TrackLog.d(TAG, "hq-search artist-only: '${target.title}' ${source.displayName} asks '$artist'")
        val more = search(artist)
        return if (more.stream != null || !more.conclusive) more else last
    }

    /**
     * The rows of an artist-only answer that [KoVersionGuard.noteSearch] may mark:
     * the one song's own rows, not the act's other songs. Any other answer is
     * passed through whole.
     */
    fun versionRows(
        source: MusicSource,
        query: String,
        target: TrackMatcher.Target,
        rows: List<Song>,
    ): List<Song> {
        if (source.kind != SourceKind.TELEGRAM || query != TrackMatcher.latinArtistQuery(target)) return rows
        return rows.filter {
            TrackMatcher.isTitleUnverifiedOriginal(it, target) ||
                KoVersionGuard.namesOtherVersion(target.title, target.artist, it.title, it.artist)
        }
    }
}
