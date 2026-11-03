package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.ArtistNameIndex
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher

/**
 * The names one song can be looked up under, for the lyrics sources that
 * match on a name rather than a recording id.
 *
 * A playlist row is not a catalogue row: it prints a track number in front of
 * the title, a translation after a dash, a production credit in brackets and
 * every collaborator in the byline. Asked verbatim, every provider misses —
 * the bug behind "3.여수밤바다 - Yeosu Night Sea" by 장범준 and
 * "눈 (Prod. by 기리보이)" by 강현준(Lil tachi) 및 김호진(HOTCHKISS).
 *
 * So the title is normalised once by [TrackMatcher], and the artist by
 * [ArtistAliases]. The first name is the one the player would ask for; the
 * rest are only tried after that has missed, at most [MAX_EXTRA] of them.
 */
internal object LyricsQueryVariants {

    data class Name(val title: String, val artist: String)

    /** Three variants after the first search, never more. */
    private const val MAX_EXTRA = 3

    fun names(
        title: String,
        artist: String,
        album: String? = null,
        artistAliases: List<String> = emptyList(),
        isVideo: Boolean = false,
    ): List<Name> {
        // A cover title can state the original's artist in the title itself
        // ("(원곡 : 한로로)", "(Original by X)"). Searching the song under that
        // name is much sharper than searching it with a blank artist, which
        // lets any other song with the same title win. The lyrics-only shapes
        // are read for uploads only: an ordinary catalogue title like
        // "Bohemian Rhapsody (Remastered 2011)" is not a cover.
        val cover = runCatching { CoverTitles.lyricsCover(title, artist, isVideo) }.getOrNull()
        val coveredTitle = cover?.title?.takeIf { it.isNotBlank() }
        val titleForParts = coveredTitle ?: title
        val parts = TrackMatcher.parseTitle(titleForParts, artist)
        val primary = (parts.words + parts.versions).joinToString(" ")
            .trim()
            .ifBlank { titleForParts.forLyricsSearch() }
        val altTitles = parts.altWords
            .map { (it + parts.versions).joinToString(" ").trim() }
            .filter { it.isNotBlank() }
        val original = cover?.originalArtist?.trim()?.takeIf { it.isNotBlank() }
        val names = ArtistAliases.forArtist(artist, album, extra = artistAliases)
            .map { it.artistForLyricsSearch() }
            .filter { it.isNotBlank() }
        val known = listOfNotNull(original) + names
        val first = known.firstOrNull().orEmpty().ifBlank { artist.artistForLyricsSearch() }
        val all = known.distinct().joinToString(", ")
        // "0+0", "2!3!", "#1": identity filtering drops the symbols and turns
        // them into other titles ("00", "23", "1"). Keep the spelling the
        // upload used as the first name, and the cleaned one as the next.
        val symbolic = titleKeepingSymbols(coveredTitle.orEmpty(), artist)
            ?: titleKeepingSymbols(primary, artist)
            ?: titleKeepingSymbols(title, artist)
        val out = LinkedHashSet<Name>()
        fun add(t: String, a: String) {
            if (t.isNotBlank()) out += Name(t.trim(), a.trim())
        }
        add(symbolic ?: primary, first)
        if (symbolic != null && symbolic != primary) add(primary, first)
        if (all.isNotBlank() && all != first) add(primary, all)
        altTitles.forEach { add(it, first) }
        add(primary, "")
        altTitles.forEach { add(it, "") }
        return out.toList().take(1 + MAX_EXTRA)
    }

    /**
     * [raw] in the spelling a catalogue search should see when the symbols in
     * it are the title, with a leading "Artist - " taken off. Null for an
     * ordinary title, whose cleaned form is already right.
     */
    private fun titleKeepingSymbols(raw: String, artist: String): String? {
        if (raw.isBlank()) return null
        val text = stripArtistPrefix(raw.trim(), artist) ?: raw.trim()
        val candidate = text.trim(' ', '"', '\'', '“', '”', '「', '」', '『', '』')
        return candidate.takeIf { it.isSymbolicLyricsTitle() }
    }

    private fun stripArtistPrefix(text: String, artist: String): String? {
        if (artist.isBlank()) return null
        val (left, right) = CoverTitles.splitTopLevelDash(text) ?: return null
        val keys = (TrackMatcher.artistVariants(artist).map(::nameKey) + nameKey(artist.artistForLyricsSearch()))
            .filter { it.isNotBlank() }
            .toSet()
        return when {
            nameKey(left) in keys -> right
            nameKey(right) in keys -> left
            else -> null
        }
    }

    private fun nameKey(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}

/**
 * Every name a row's artist is known by, from evidence the app already holds.
 *
 * The printed credit is split into its collaborators and their bracketed stage
 * names ("강현준(Lil tachi)"). Then the channel ids the row carries are looked
 * up in [ArtistNameIndex], which has learned which names YouTube has stated
 * for the same channel; and the album's own credits supply the group name when
 * a solo member's row and the group's row share a release. Nothing is guessed:
 * a name is only returned when one of those facts states it.
 */
internal object ArtistAliases {

    /** Enough to cover a collaboration plus one group alias; never a dictionary. */
    private const val MAX_NAMES = 8

    fun forArtist(
        artist: String,
        album: String? = null,
        albumId: String? = null,
        channelIds: List<String> = emptyList(),
        extra: List<String> = emptyList(),
    ): List<String> {
        val out = LinkedHashSet<String>()
        fun add(name: String) {
            TrackMatcher.artistVariants(name).forEach { if (it.isNotBlank()) out += it }
        }
        add(artist)
        extra.forEach(::add)
        TrackMatcher.artistVariants(artist).forEach { name ->
            ArtistNameIndex.lookup(name)?.let { id ->
                ArtistNameIndex.namesFor(id).forEach(::add)
            }
        }
        channelIds.filter { it.isNotBlank() }.forEach { id ->
            ArtistNameIndex.namesFor(id).forEach(::add)
        }
        // A release credited to two or three acts is evidence of a member's
        // other name; a compilation credited to everyone is not.
        ArtistNameIndex.namesOnAlbum(albumId, album)
            .takeIf { it.size in 1..3 }
            ?.forEach(::add)
        return out.toList().take(MAX_NAMES)
    }

    fun forSong(song: Song): List<String> = forArtist(
        artist = song.artist,
        album = song.albumName,
        albumId = song.albumId,
        channelIds = (song.artists.mapNotNull { it.browseId } + listOfNotNull(song.artistId))
            .filter { it.isNotBlank() }
            .distinct(),
        extra = song.artists.map { it.name },
    )
}
