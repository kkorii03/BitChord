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
    ): List<Name> {
        // A fan clip's uploader is not the performer, and an official upload
        // may put the artist after a bar. CoverTitles already reads both, so
        // the title/artist that reach the providers are the song's own.
        val upload = runCatching {
            CoverTitles.lyricsQuery(
                Song(
                    videoId = "",
                    title = title,
                    artist = artist,
                    thumbnailUrl = null,
                    albumName = album,
                    isVideo = true,
                ),
            )
        }.getOrNull()
        val wantedTitle = upload?.title?.takeIf { it.isNotBlank() } ?: title
        val wantedArtist = upload?.artist?.takeIf { it.isNotBlank() } ?: artist
        val subtitles = upload?.subtitles.orEmpty()

        val parts = TrackMatcher.parseTitle(wantedTitle, wantedArtist)
        val primary = (parts.words + parts.versions).joinToString(" ")
            .trim()
            .ifBlank { wantedTitle.forLyricsSearch() }
        val altTitles = buildList {
            parts.altWords.forEach { words ->
                val alt = (words + parts.versions).joinToString(" ").trim()
                if (alt.isNotBlank()) add(alt)
            }
            subtitles.forEach { subtitle ->
                val alt = (TrackMatcher.parseTitle(subtitle, wantedArtist).words +
                    parts.versions).joinToString(" ").trim()
                if (alt.isNotBlank() && alt != primary) add(alt)
            }
        }.distinct()
        val names = ArtistAliases.forArtist(
            wantedArtist,
            album,
            extra = artistAliases + upload?.artistAliases.orEmpty(),
        )
        val first = names.firstOrNull().orEmpty().ifBlank { wantedArtist.artistForLyricsSearch() }
        val all = names.distinct().joinToString(", ")
        val out = LinkedHashSet<Name>()
        fun add(t: String, a: String) {
            if (t.isNotBlank()) out += Name(t.trim(), a.trim())
        }
        add(primary, first)
        if (all.isNotBlank() && all != first) add(primary, all)
        altTitles.forEach { add(it, first) }
        add(primary, "")
        altTitles.forEach { add(it, "") }
        return out.toList().take(1 + MAX_EXTRA)
    }
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
