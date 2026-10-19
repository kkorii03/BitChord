package com.music.bitchord.data.innertube

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the renderers `www.youtube.com` uses, for the Korea/no-Premium
 * fallback. Deliberately separate from [InnertubeParser], which reads the
 * `music.youtube.com` layouts: the two sites now disagree about almost every
 * renderer name, and mixing the two parsers would make either one a place
 * where the other's assumptions silently apply.
 *
 * Everything here is pure: no network, no Android, no state. Tests use trimmed
 * responses fetched from the real site.
 */
internal object YouTubeWebParser {

    data class HomeResult(
        val shelves: List<HomeShelf>,
        val continuation: String?,
    )

    data class PlaylistResult(
        val songs: List<Song>,
        val continuation: String?,
        val header: InnertubeParser.BrowseHeader?,
        /**
         * Video rows a music-only list hid (Liked Music). Kept beside [songs]
         * so the page can offer "N videos hidden · Show" instead of pretending
         * the account never liked them.
         */
        val hiddenSongs: List<Song> = emptyList(),
    )

    /**
     * Home, personal or channel-shaped.
     *
     * @param musicOnly keeps only rows the fallback can honestly call music:
     *   a `MUSIC` thumbnail badge, a `- Topic` owner, a music-video badge, or
     *   a `RD…` mix. The YouTube Music channel sets [musicOnly] false because
     *   everything on it is music already.
     */
    fun parseHome(root: JsonObject, musicOnly: Boolean): HomeResult {
        val shelves = collectShelves(root).mapNotNull { (title, shelf) ->
            val items = itemsIn(shelf, musicOnly = musicOnly, videosOnly = false)
            if (items.isEmpty()) null else HomeShelf(title.ifBlank { FOR_YOU }, items)
        }.toMutableList()
        if (shelves.isEmpty()) {
            val items = itemsIn(root, musicOnly = musicOnly, videosOnly = false)
            if (items.isNotEmpty()) shelves += HomeShelf(FOR_YOU, items)
        }
        return HomeResult(shelves, continuationToken(root))
    }

    /** The account's history feed, music rows first, all rows if none are tagged. */
    fun parseHistory(root: JsonObject): List<Song> {
        val songs = songsIn(root, musicOnly = true)
        return if (songs.isNotEmpty()) songs else songsIn(root, musicOnly = false)
    }

    /** A playlist page opened by browse id (`VL…`) in the ordinary web layout. */
    fun parsePlaylist(root: JsonObject): PlaylistResult {
        val listRoot = findFirst(root, "playlistVideoListRenderer") ?: root
        return PlaylistResult(
            songs = songsIn(listRoot, musicOnly = false),
            continuation = continuationToken(root),
            header = playlistHeader(root),
        )
    }

    /** The `next` watch queue, whose rows are `playlistPanelVideoRenderer`. */
    fun parseRadio(root: JsonObject): PlaylistResult {
        val playlist = root.findObject("contents")
            ?.findObject("twoColumnWatchNextResults")
            ?.findObject("playlist")
            ?.findObject("playlist")
            ?: root
        val songs = collectObjects(playlist, "playlistPanelVideoRenderer").mapNotNull(::songOfPanel)
        val title = playlist.findObject("titleText")?.text().orEmpty()
        val owner = playlist.findObject("ownerName")?.text().orEmpty()
        return PlaylistResult(
            songs = songs.distinctBy { it.videoId },
            continuation = continuationToken(playlist),
            header = title.takeIf { it.isNotBlank() }?.let {
                InnertubeParser.BrowseHeader(title = it, subtitle = owner, thumbnailUrl = null)
            },
        )
    }

    fun parsePlaylistContinuation(root: JsonObject): PlaylistResult {
        val listRoot = findFirst(root, "playlistVideoListRenderer") ?: root
        return PlaylistResult(
            songs = songsIn(listRoot, musicOnly = false),
            continuation = continuationToken(root),
            header = null,
        )
    }

    /** Playlist and mix cards from a library page (`FElibrary` and friends). */
    fun parseLibraryItems(root: JsonObject): List<ShelfItem> =
        itemsIn(root, musicOnly = false, videosOnly = false)
            .filter { it.browseId != null }

    /**
     * Playlist cards from a playlist-filtered youtube.com search: only the two
     * playlist shapes, so video rows and album cards that share a page cannot
     * leak in. Browse ids are `VL…`, the same form a playlist page opens with.
     */
    fun parsePlaylistResults(root: JsonObject): List<ShelfItem> {
        val out = LinkedHashMap<String, ShelfItem>()
        fun add(item: ShelfItem?) {
            val browseId = item?.browseId ?: return
            if (!browseId.startsWith("VL")) return
            out.putIfAbsent(browseId, item)
        }
        fun visit(element: JsonElement?) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    when (key) {
                        "lockupViewModel" -> (value as? JsonObject)
                            ?.takeIf { it.string("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST" }
                            ?.let { add(lockupItem(it, musicOnly = false)) }
                        "playlistRenderer", "compactPlaylistRenderer", "gridPlaylistRenderer" ->
                            (value as? JsonObject)?.let { add(playlistItem(it, musicOnly = false)) }
                        else -> visit(value)
                    }
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return out.values.toList()
    }

    fun continuationToken(root: JsonElement): String? {
        findFirst(root, "continuationItemRenderer")
            ?.findObject("continuationEndpoint")
            ?.findObject("continuationCommand")
            ?.string("token")
            ?.let { return it }
        findFirst(root, "continuationItemViewModel")
            ?.findObject("continuationCommand")
            ?.string("token")
            ?.let { return it }
        collectObjects(root, "nextContinuationData").firstOrNull()
            ?.string("continuation")
            ?.let { return it }
        return null
    }

    // ---- Shelves ------------------------------------------------------------

    private val SHELF_KEYS = setOf(
        "richShelfRenderer",
        "shelfRenderer",
        "musicCarouselShelfRenderer",
        "musicShelfRenderer",
    )

    private fun collectShelves(root: JsonElement): List<Pair<String, JsonObject>> {
        val out = mutableListOf<Pair<String, JsonObject>>()
        fun visit(element: JsonElement?) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key in SHELF_KEYS && value is JsonObject) {
                        out += shelfTitle(key, value) to value
                    } else {
                        visit(value)
                    }
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return out
    }

    private fun shelfTitle(key: String, shelf: JsonObject): String = when (key) {
        "musicCarouselShelfRenderer" -> shelf.findObject("header")
            ?.findObject("musicCarouselShelfBasicHeaderRenderer")
            ?.findObject("title")
            ?.text()
            .orEmpty()
        else -> shelf.findObject("title")?.text().orEmpty()
    }

    // ---- Item collection ----------------------------------------------------

    private fun itemsIn(root: JsonElement, musicOnly: Boolean, videosOnly: Boolean): List<ShelfItem> {
        val out = LinkedHashMap<String, ShelfItem>()
        fun add(item: ShelfItem?) {
            item ?: return
            val key = item.videoId ?: item.browseId ?: "${item.title}\n${item.subtitle}"
            out.putIfAbsent(key, item)
        }
        fun visit(element: JsonElement?) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    when (key) {
                        "lockupViewModel" -> (value as? JsonObject)?.let { add(lockupItem(it, musicOnly)) }
                        "videoRenderer" -> (value as? JsonObject)?.let { add(videoItem(it, musicOnly)) }
                        "playlistVideoRenderer" -> (value as? JsonObject)?.let { add(playlistVideoItem(it)) }
                        "playlistPanelVideoRenderer" -> (value as? JsonObject)?.let { add(panelItem(it)) }
                        "playlistRenderer", "compactPlaylistRenderer", "gridPlaylistRenderer" -> {
                            if (!videosOnly) (value as? JsonObject)?.let { add(playlistItem(it, musicOnly)) }
                        }
                        else -> visit(value)
                    }
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return out.values.toList()
    }

    private fun songsIn(root: JsonElement, musicOnly: Boolean): List<Song> {
        val out = LinkedHashMap<String, Song>()
        fun visit(element: JsonElement?) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    val song: Song? = when (key) {
                        "lockupViewModel" -> (value as? JsonObject)?.let { lockupSong(it, musicOnly) }
                        "videoRenderer" -> (value as? JsonObject)?.let { videoSong(it, musicOnly) }
                        "playlistVideoRenderer" -> (value as? JsonObject)?.let { playlistVideoSong(it) }
                        "playlistPanelVideoRenderer" -> (value as? JsonObject)?.let { songOfPanel(it) }
                        else -> {
                            visit(value)
                            null
                        }
                    }
                    song?.let { out.putIfAbsent(it.videoId, it) }
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return out.values.toList()
    }

    private fun lockupSong(lockup: JsonObject, musicOnly: Boolean): Song? {
        val item = lockupItem(lockup, musicOnly) ?: return null
        val videoId = item.videoId ?: return null
        val duration = collectObjects(lockup, "thumbnailBadgeViewModel")
            .firstNotNullOfOrNull { badge ->
                badge.string("text")?.takeIf { DURATION.matches(it) }
                    ?: badge.findObject("rendererContext")
                        ?.findObject("accessibilityContext")
                        ?.string("label")
                        ?.let(::humanDuration)
            }
        return Song(
            videoId = videoId,
            title = item.title,
            artist = item.subtitle,
            thumbnailUrl = item.thumbnailUrl,
            durationText = duration,
        )
    }

    private fun videoSong(video: JsonObject, musicOnly: Boolean): Song? {
        val item = videoItem(video, musicOnly) ?: return null
        val videoId = item.videoId ?: return null
        return Song(
            videoId = videoId,
            title = item.title,
            artist = item.subtitle,
            thumbnailUrl = item.thumbnailUrl,
            durationText = video.findObject("lengthText")?.text()
                ?.takeIf { DURATION.matches(it) },
        )
    }

    private fun playlistVideoSong(video: JsonObject): Song? {
        val item = playlistVideoItem(video) ?: return null
        val videoId = item.videoId ?: return null
        return Song(
            videoId = videoId,
            title = item.title,
            artist = item.subtitle,
            thumbnailUrl = item.thumbnailUrl,
            durationText = video.findObject("lengthText")?.text()
                ?.takeIf { DURATION.matches(it) },
            setVideoId = video.string("setVideoId"),
        )
    }

    private fun humanDuration(label: String): String? {
        val hours = HOURS.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = MINUTES.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = SECONDS.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (hours == 0 && minutes == 0 && seconds == 0) return null
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    // ---- Individual renderers ----------------------------------------------

    private fun lockupItem(lockup: JsonObject, musicOnly: Boolean): ShelfItem? {
        val contentId = lockup.string("contentId") ?: return null
        val contentType = lockup.string("contentType").orEmpty()
        val metadata = lockup.findObject("metadata")?.findObject("lockupMetadataViewModel")
        val title = metadata?.findObject("title")?.text().orEmpty()
        if (title.isBlank()) return null
        val rows = metadata?.findObject("metadata")
            ?.findObject("contentMetadataViewModel")
            ?.findArray("metadataRows")
            .orEmpty()
        val parts = rows.mapNotNull { row ->
            (row as? JsonObject)?.findArray("metadataParts")
                ?.mapNotNull { part ->
                    (part as? JsonObject)?.findObject("text")?.string("content")
                }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }
        val channel = parts.firstOrNull()?.firstOrNull()
        val subtitle = parts.flatMap { it }.distinct().take(3).joinToString(" · ")
        val thumbnail = lockupThumbnail(lockup, contentId)
        val badges = collectObjects(lockup, "thumbnailBadgeViewModel")
        val badgeTexts = badges.mapNotNull { it.string("text") }
        val iconNames = badges.mapNotNull {
            it.findObject("icon")
                ?.findArray("sources")
                ?.firstOrNull()
                ?.let { source -> (source as? JsonObject)?.findObject("clientResource")?.string("imageName") }
        }
        val isMix = contentId.startsWith("RD")
        val isStation = isMix
        val isMusic = isStation ||
            iconNames.any { it.equals("MUSIC", ignoreCase = true) } ||
            badgeTexts.any { MUSIC_BADGE.containsMatchIn(it) } ||
            channel?.trim()?.endsWith("- Topic", ignoreCase = true) == true

        if (musicOnly && !isMusic) return null
        return when (contentType) {
            "LOCKUP_CONTENT_TYPE_VIDEO" -> {
                if (contentId.isBlank()) return null
                ShelfItem(
                    title = title,
                    subtitle = channel.orEmpty(),
                    thumbnailUrl = thumbnail,
                    videoId = contentId,
                    browseId = null,
                )
            }
            "LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_STATION", "LOCKUP_CONTENT_TYPE_ALBUM" -> {
                if (musicOnly && !isStation) return null
                ShelfItem(
                    title = title,
                    subtitle = subtitle,
                    thumbnailUrl = thumbnail,
                    videoId = null,
                    browseId = if (contentType == "LOCKUP_CONTENT_TYPE_ALBUM") {
                        albumBrowseId(contentId)
                    } else {
                        playlistsBrowseId(contentId)
                    },
                )
            }
            else -> null
        }
    }

    private fun lockupThumbnail(
        lockup: JsonObject,
        contentId: String,
    ): String? {
        lockup.findObject("contentImage")
            ?.let(::lockupImageUrl)
            ?.let { return it }
        val videoId = contentId.takeIf(::isVideoId) ?: firstVideoId(lockup)
        return videoId?.let(::videoThumbnailUrl)
    }

    private fun lockupImageUrl(contentImage: JsonObject): String? {
        contentImage.findObject("thumbnailViewModel")
            ?.let(::thumbnailViewModelUrl)
            ?.let { return it }
        val collection = contentImage.findObject("collectionThumbnailViewModel")
        if (collection != null) {
            for (key in listOf("primaryThumbnail", "secondaryThumbnail")) {
                val thumbnail = collection.findObject(key) ?: continue
                thumbnail.findObject("thumbnailViewModel")
                    ?.let(::thumbnailViewModelUrl)
                    ?.let { return it }
                thumbnailViewModelUrl(thumbnail)?.let { return it }
            }
            val stacked = collection.findObject("stackedThumbnailViewModel")
                ?: collection.findObject("stackThumbnailViewModel")
            stacked?.findObject("primaryThumbnail")?.let { thumbnail ->
                thumbnail.findObject("thumbnailViewModel")
                    ?.let(::thumbnailViewModelUrl)
                    ?.let { return it }
                thumbnailViewModelUrl(thumbnail)?.let { return it }
            }
        }
        contentImage.findObject("thumbnail")
            ?.let(::thumbnailViewModelUrl)
            ?.let { return it }
        return contentImage.imageUrl()
    }

    private fun thumbnailViewModelUrl(model: JsonObject): String? =
        model.findObject("image")?.imageUrl()
            ?: model.findObject("thumbnail")?.imageUrl()
            ?: model.imageUrl()

    private fun firstVideoId(lockup: JsonObject): String? =
        firstVideoIdKey(lockup) ?: firstVideoIdInUrl(lockup)

    private fun firstVideoIdKey(element: JsonElement?): String? = when (element) {
        is JsonObject -> (element["videoId"] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(::isVideoId)
            ?: element.values.firstNotNullOfOrNull(::firstVideoIdKey)
        is JsonArray -> element.firstNotNullOfOrNull(::firstVideoIdKey)
        else -> null
    }

    private fun firstVideoIdInUrl(element: JsonElement?): String? = when (element) {
        is JsonObject -> element.values.firstNotNullOfOrNull(::firstVideoIdInUrl)
        is JsonArray -> element.firstNotNullOfOrNull(::firstVideoIdInUrl)
        is JsonPrimitive -> element.contentOrNull
            ?.let { VIDEO_ID_IN_URL.find(it)?.groupValues?.getOrNull(1) }
            ?.takeIf(::isVideoId)
        else -> null
    }

    private fun videoThumbnailUrl(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    private fun videoItem(video: JsonObject, musicOnly: Boolean): ShelfItem? {
        val videoId = video.string("videoId") ?: return null
        val title = video.findObject("title")?.text().orEmpty()
        if (title.isBlank()) return null
        val owner = video.findObject("ownerText")?.firstRun()
            ?: video.findObject("longBylineText")?.firstRun()
        val badges = collectObjects(video, "metadataBadgeRenderer")
            .mapNotNull { it.string("label") }
        val isMusic = owner?.trim()?.endsWith("- Topic", ignoreCase = true) == true ||
            video.string("category") == "Music" ||
            badges.any {
                it.equals("Music", ignoreCase = true) ||
                    it.equals("뮤직", ignoreCase = true) ||
                    MUSIC_BADGE.containsMatchIn(it)
            }
        if (musicOnly && !isMusic) return null
        return ShelfItem(
            title = title,
            subtitle = owner.orEmpty(),
            thumbnailUrl = video.findObject("thumbnail")?.thumbnails(),
            videoId = videoId,
            browseId = null,
        )
    }

    private fun playlistVideoItem(video: JsonObject): ShelfItem? {
        val videoId = video.string("videoId") ?: return null
        val title = video.findObject("title")?.text().orEmpty()
        if (title.isBlank()) return null
        return ShelfItem(
            title = title,
            subtitle = video.findObject("shortBylineText")?.firstRun().orEmpty(),
            // Some playlist rows (`VLLM`, clipped lists) carry no `thumbnail`
            // renderer at all; the fallback layer adds the video still and
            // logs the row (see YouTubeFallback.ensureArtwork).
            thumbnailUrl = video.findObject("thumbnail")?.thumbnails(),
            videoId = videoId,
            browseId = null,
        )
    }

    private fun panelItem(panel: JsonObject): ShelfItem? {
        val videoId = panel.string("videoId") ?: return null
        val title = panel.findObject("title")?.text().orEmpty()
        if (title.isBlank()) return null
        return ShelfItem(
            title = title,
            subtitle = panel.findObject("shortBylineText")?.firstRun()
                ?: panel.findObject("longBylineText")?.firstRun().orEmpty(),
            thumbnailUrl = panel.findObject("thumbnail")?.thumbnails(),
            videoId = videoId,
            browseId = null,
        )
    }

    private fun playlistItem(playlist: JsonObject, musicOnly: Boolean): ShelfItem? {
        val playlistId = playlist.string("playlistId") ?: return null
        val title = playlist.findObject("title")?.text().orEmpty()
        if (title.isBlank()) return null
        if (musicOnly && !playlistId.startsWith("RD")) return null
        return ShelfItem(
            title = title,
            subtitle = playlist.findObject("shortBylineText")?.firstRun()
                ?: playlist.findObject("videoCountText")?.text().orEmpty(),
            thumbnailUrl = playlist.findObject("thumbnail")?.thumbnails(),
            videoId = null,
            browseId = playlistsBrowseId(playlistId),
        )
    }

    private fun songOfPanel(panel: JsonObject): Song? {
        val videoId = panel.string("videoId") ?: return null
        val title = panel.findObject("title")?.text().orEmpty()
        if (title.isBlank()) return null
        val byline = panel.findObject("longBylineText")
        val artist = panel.findObject("shortBylineText")?.firstRun()
            ?: byline?.firstRun()
            ?: ""
        return Song(
            videoId = videoId,
            title = title,
            artist = artist.substringBefore(" · ").trim(),
            thumbnailUrl = panel.findObject("thumbnail")?.thumbnails(),
            durationText = panel.findObject("lengthText")?.text()
                ?.takeIf { DURATION.matches(it) },
            setVideoId = panel.string("playlistSetVideoId") ?: panel.string("setVideoId"),
        )
    }

    private fun playlistHeader(root: JsonObject): InnertubeParser.BrowseHeader? {
        val primary = findFirst(root, "playlistSidebarPrimaryInfoRenderer")
            ?: findFirst(root, "pageHeaderRenderer")
            ?: return null
        val title = primary.findObject("title")?.text()
            ?: primary.findObject("pageTitle")?.text()
            ?: return null
        if (title.isBlank()) return null
        val stats = primary.findArray("stats")
            ?.mapNotNull { (it as? JsonObject)?.text() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(" · ")
            .orEmpty()
        val description = primary.findObject("description")?.text()
            ?: primary.findObject("descriptionPreviewViewModel")?.findObject("description")?.text()
        val thumbnail = primary.findObject("thumbnailRenderer")
            ?.findObject("playlistVideoThumbnailRenderer")
            ?.findObject("thumbnail")
            ?.thumbnails()
            ?: primary.findObject("thumbnail")?.thumbnails()
        return InnertubeParser.BrowseHeader(
            title = title,
            subtitle = stats.ifBlank { description?.substringBefore('\n').orEmpty() },
            thumbnailUrl = thumbnail,
        )
    }

    // ---- JSON helpers -------------------------------------------------------

    private val MUSIC_BADGE = Regex(
        """(?i)(music\s*video|official\s*music\s*video|뮤직\s*비디오|뮤직비디오)""",
    )

    private val DURATION = Regex("""\d{1,2}(?::\d{2}){1,2}""")

    private val HOURS = Regex("""(\d+)\s*hour""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d+)\s*minute""", RegexOption.IGNORE_CASE)
    private val SECONDS = Regex("""(\d+)\s*second""", RegexOption.IGNORE_CASE)

    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
    private val VIDEO_ID_IN_URL = Regex(
        """(?:[?&]v=|youtu\.be/|/embed/|/vi/)([A-Za-z0-9_-]{11})""",
    )

    private const val FOR_YOU = "For you"

    private fun playlistsBrowseId(id: String): String = when {
        id.startsWith("VL") || id.startsWith("UC") || id.startsWith("RD") -> id
        else -> "VL$id"
    }

    private fun albumBrowseId(id: String): String = when {
        id.startsWith("VL") || id.startsWith("MPRE") -> id
        else -> playlistsBrowseId(id)
    }

    private fun isVideoId(value: String): Boolean = VIDEO_ID.matches(value)

    private fun normalizeImageUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val absolute = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://www.youtube.com$value"
            else -> value
        }
        return absolute.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun JsonArray.lastImageUrl(): String? =
        mapNotNull {
            (it as? JsonObject)?.string("url")?.let(::normalizeImageUrl)
        }.lastOrNull()

    private fun collectImageUrls(element: JsonElement?): List<String> {
        val out = mutableListOf<String>()
        fun visit(value: JsonElement?) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if ((key == "sources" || key == "thumbnails") && child is JsonArray) {
                        child.forEach { source ->
                            (source as? JsonObject)?.string("url")
                                ?.let(::normalizeImageUrl)
                                ?.let(out::add)
                        }
                    } else {
                        visit(child)
                    }
                }
                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(element)
        return out
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.findObject(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.findArray(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonElement.findObject(key: String): JsonObject? =
        findFirst(this, key)

    private fun JsonElement.findArray(key: String): JsonArray? =
        (this as? JsonObject)?.get(key) as? JsonArray

    private fun JsonElement.text(): String = when (this) {
        is JsonObject -> string("simpleText")
            ?: findArray("runs")?.joinToString("") { run ->
                (run as? JsonObject)?.string("text").orEmpty()
            }?.takeIf { it.isNotBlank() }
            ?: string("content")
            ?: ""
        is JsonPrimitive -> contentOrNull.orEmpty()
        else -> ""
    }

    private fun JsonElement.firstRun(): String? = when (this) {
        is JsonObject -> findArray("runs")?.firstOrNull()
            ?.let { (it as? JsonObject)?.string("text") }
            ?: string("simpleText")
        else -> null
    }

    private fun JsonElement.thumbnails(): String? =
        findArray("thumbnails")?.lastImageUrl()

    private fun JsonElement.imageUrl(): String? =
        findArray("thumbnails")?.lastImageUrl()
            ?: findArray("sources")?.lastImageUrl()
            ?: collectImageUrls(this).lastOrNull()

    private fun findFirst(element: JsonElement?, key: String): JsonObject? = when (element) {
        is JsonObject -> {
            (element[key] as? JsonObject)
                ?: element.values.firstNotNullOfOrNull { findFirst(it, key) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { findFirst(it, key) }
        else -> null
    }

    private fun collectObjects(element: JsonElement?, key: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun visit(value: JsonElement?) {
            when (value) {
                is JsonObject -> value.forEach { (name, child) ->
                    if (name == key && child is JsonObject) out += child else visit(child)
                }
                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(element)
        return out
    }
}
