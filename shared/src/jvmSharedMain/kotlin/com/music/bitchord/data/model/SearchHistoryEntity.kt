package com.music.bitchord.data.model

import com.music.bitchord.data.cover.CoverTitles
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An entity the user tapped in search results — a track, album, artist, or playlist.
 *
 * Unlike raw query strings, these carry the exact identity (id, artwork, type) of what was
 * clicked, so recents render instantly with real cover art and tapping one navigates directly
 * to that entity instead of re-running a text search.
 */
@Serializable
data class SearchHistoryEntity(
    /** Unique id: videoId for tracks, browseId for albums/artists/playlists. */
    val id: String,
    /** Display title — song name, album name, artist name, etc. */
    val title: String,
    /** Subtitle — e.g. "Song • Drake", "Album • Artist", or just the type label. */
    val subtitle: String,
    /** Artwork URL for the cover/thumbnail. */
    val artworkUrl: String?,
    /** The kind of entity this represents. */
    val entityType: EntityType,
    /** Whether a saved TRACK row was a video upload. */
    val isVideo: Boolean = false,
    /** Timestamp (epoch millis) — updated on each re-tap to bubble it to the top. */
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        /** A TRACK row for [song], keeping its upload type. */
        fun fromSong(song: Song): SearchHistoryEntity = SearchHistoryEntity(
            id = song.videoId,
            title = song.title,
            subtitle = song.artist,
            artworkUrl = song.thumbnailUrl,
            entityType = EntityType.TRACK,
            isVideo = song.isVideo,
        )
    }
}

enum class EntityType {
    @SerialName("TRACK") TRACK,
    @SerialName("ALBUM") ALBUM,
    @SerialName("ARTIST") ARTIST,
    @SerialName("PLAYLIST") PLAYLIST,
    /** Any other page that opens rather than plays — a podcast show, for one. */
    @SerialName("OTHER") OTHER,
}

/**
 * This entry with its type put right, if an older build got it wrong.
 *
 * Those builds filed every page that wasn't an album, artist or playlist as a
 * TRACK, so a podcast show already in someone's history would go on trying to
 * play its browse id as a video.
 */
fun SearchHistoryEntity.withCorrectedType(): SearchHistoryEntity =
    if (entityType == EntityType.TRACK && id.startsWith("MPSPP")) copy(entityType = EntityType.OTHER) else this

/**
 * The track this entry plays, or null when it isn't one. A raw search term is
 * also stored as a TRACK (id "q:<term>") for backwards compatibility, and has
 * no video behind it.
 */
fun SearchHistoryEntity.toSong(): Song? = if (entityType == EntityType.TRACK && !id.startsWith("q:")) {
    Song(
        videoId = id,
        title = title,
        artist = subtitle,
        thumbnailUrl = artworkUrl,
        // A row saved before the flag existed is still a cover when its title
        // says so outright.
        isVideo = isVideo || CoverTitles.read(
            title,
            subtitle,
            null,
            unambiguousOnly = true,
        ) != null,
    )
} else null

/**
 * What tapping this recent entry should do.
 *
 * A saved track plays directly. An album, artist or playlist opens its page.
 * A raw query from an old build (`q:<term>`, see [toSong]) has no video behind
 * it, so it must run the words again rather than hand a fake video id to the
 * player — which used to fail silently for anyone upgrading with history from
 * an earlier version.
 */
sealed interface SearchHistoryAction {
    data class Play(val song: Song) : SearchHistoryAction
    data class Open(
        val browseId: String,
        val title: String,
        val subtitle: String,
        val artworkUrl: String?,
        val entityType: EntityType,
    ) : SearchHistoryAction
    data class Search(val term: String) : SearchHistoryAction
}

fun SearchHistoryEntity.historyAction(): SearchHistoryAction = when (entityType) {
    EntityType.TRACK -> toSong()?.let(SearchHistoryAction::Play)
        ?: SearchHistoryAction.Search(title.ifBlank { id.removePrefix("q:") })
    EntityType.ALBUM, EntityType.ARTIST, EntityType.PLAYLIST, EntityType.OTHER ->
        SearchHistoryAction.Open(id, title, subtitle, artworkUrl, entityType)
}
