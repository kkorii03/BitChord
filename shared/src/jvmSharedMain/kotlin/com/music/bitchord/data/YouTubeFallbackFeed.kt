package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf

/**
 * The pure rules behind the youtube.com Home built for a gated listener.
 *
 * The feed is meant to read like the YouTube Music Home the same account would
 * have had with Premium: a short Quick picks radio first, the local recents,
 * the account's seeded mixes, its liked collection, and only then YouTube's
 * own home and Music-channel shelves. Ordering and seed selection are separate
 * from the network so they can be tested without a player or a request.
 */
internal object YouTubeFallbackFeed {

    /** Resolved to the listener's language by the UI's shelf-title localiser. */
    const val QUICK_PICKS_TITLE = "Quick picks"

    /** The app's own recents key, shared with the UI's shelf-title localiser. */
    const val RECENT_TITLE = "Recents"

    /** YouTube's own recents title, still de-duplicated against [RECENT_TITLE]. */
    const val LISTEN_AGAIN_TITLE = "Listen again"

    /** No existing resource key: the task's plain Korean title. */
    const val MY_MIXES_TITLE = "내 믹스"

    /** No existing resource key: the task's plain Korean title for Liked Music. */
    const val LIKED_TITLE = "좋아요 표시한 음악"

    /** The size of the Quick picks radio and of a recents page. */
    const val QUICK_PICKS_LIMIT = 20

    /** At most this many seeded RD mixes, matching the official Home's row. */
    const val MIX_LIMIT = 6

    const val LAST_HOUR_MS = 60L * 60L * 1000L

    /**
     * Whether the cached gated Home can answer a request instead of rebuilding.
     *
     * [force] is the pull-to-refresh bypass: it always rebuilds, even while a
     * fresh feed is held. Otherwise the entry is used only for the same
     * signed-in session and only inside [maxAgeMs].
     */
    fun shouldUseCachedHome(
        force: Boolean,
        cachedKey: String?,
        sessionKey: String,
        cachedAtMs: Long,
        nowMs: Long,
        maxAgeMs: Long,
    ): Boolean = !force &&
        cachedKey != null &&
        cachedKey == sessionKey &&
        nowMs - cachedAtMs < maxAgeMs

    /** A song that seeds one `RD<videoId>` mix card. */
    data class MixSeed(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String?,
    )

    /** Video ids played within [windowMs] of [now]; Quick picks must not repeat them. */
    fun lastHourIds(
        plays: List<LocalPlay>,
        now: Long,
        windowMs: Long = LAST_HOUR_MS,
    ): Set<String> = plays.asSequence()
        .filter { it.song.videoId.isNotBlank() }
        .filter { now - it.playedAtMs < windowMs }
        .mapTo(HashSet()) { it.song.videoId }

    /**
     * The most played recent song, newest play breaking a tie.
     *
     * The device's source is usually distinct by video id, so in practice this
     * is the most recent song; counting as well keeps the rule honest if a
     * source ever reports repeat plays.
     */
    fun mostPlayed(plays: List<LocalPlay>): LocalPlay? = plays.asSequence()
        .filter { it.song.videoId.isNotBlank() }
        .groupBy { it.song.videoId }
        .values
        .map { group -> group.maxByOrNull { it.playedAtMs } to group.size }
        .filter { it.first != null }
        .maxWithOrNull(
            compareBy<Pair<LocalPlay?, Int>> { it.second }
                .thenBy { it.first?.playedAtMs ?: Long.MIN_VALUE },
        )
        ?.first

    /**
     * Four-to-six different recent artists, then songs, used as RD radio seeds.
     *
     * Artists are ranked by how often they were played (most recent play
     * breaking a tie), so the row holds several different radios rather than
     * six takes on one favourite. When the history names fewer artists than
     * [limit], the newest remaining songs fill the rest, still one video per
     * card.
     */
    fun mixSeeds(
        plays: List<LocalPlay>,
        limit: Int = MIX_LIMIT,
    ): List<MixSeed> {
        if (limit <= 0) return emptyList()
        val valid = plays.filter { it.song.videoId.isNotBlank() }
        if (valid.isEmpty()) return emptyList()

        val artistGroups = valid.groupBy { artistKey(it.song.artist, it.song.videoId) }
            .map { (_, group) -> group to group.size }
            .sortedWith(
                compareByDescending<Pair<List<LocalPlay>, Int>> { it.second }
                    .thenByDescending { pair -> pair.first.maxOf { it.playedAtMs } },
            )

        val chosen = mutableListOf<LocalPlay>()
        val seenVideos = HashSet<String>()
        val seenArtists = HashSet<String>()
        for ((group, _) in artistGroups) {
            if (chosen.size >= limit) break
            val latest = group.maxByOrNull { it.playedAtMs } ?: continue
            if (!seenVideos.add(latest.song.videoId)) continue
            chosen += latest
            seenArtists += artistKey(latest.song.artist, latest.song.videoId)
        }

        val remaining = valid.asSequence()
            .filterNot { it.song.videoId in seenVideos }
            .sortedWith(
                compareByDescending<LocalPlay> {
                    artistKey(it.song.artist, it.song.videoId) !in seenArtists
                }.thenByDescending { it.playedAtMs },
            )
        for (play in remaining) {
            if (chosen.size >= limit) break
            if (!seenVideos.add(play.song.videoId)) continue
            chosen += play
        }

        return chosen.map { play ->
            MixSeed(
                videoId = play.song.videoId,
                title = play.song.title,
                artist = play.song.artist,
                thumbnailUrl = play.song.thumbnailUrl,
            )
        }
    }

    /**
     * The gated Home in the order a Premium listener expects, dropping empty
     * shelves and any later shelf whose title would repeat an earlier one
     * (YouTube's own home may carry a stale Quick picks or Listen again).
     */
    fun orderedShelves(
        quickPicks: HomeShelf?,
        listenAgain: HomeShelf?,
        myMixes: HomeShelf?,
        liked: HomeShelf?,
        youtubeHome: List<HomeShelf>,
        musicChannel: List<HomeShelf>,
    ): List<HomeShelf> {
        val out = mutableListOf<HomeShelf>()
        val seenTitles = HashSet<String>()
        fun add(shelf: HomeShelf?) {
            if (shelf == null || shelf.items.isEmpty()) return
            val key = titleKey(shelf.title)
            if (key.isEmpty() || !seenTitles.add(key)) return
            out += shelf
        }

        add(quickPicks)
        add(listenAgain)
        add(myMixes)
        add(liked)
        youtubeHome.forEach(::add)
        musicChannel.forEach(::add)
        return out
    }

    /** Recognises YouTube's own translations of the three built shelves. */
    private fun titleKey(title: String): String = when (title.trim().lowercase()) {
        "quick picks", "빠른 선곡", "クイック ミックス",
        "lựa chọn nhanh", "chọn nhanh đài phát" -> "quick picks"
        "listen again", "다시 듣기", "もう一度聴く", "nghe lại", "recents" -> "listen again"
        "mixed for you", "my mix", "내 믹스", "나를 위한 믹스", "ミックス", "dành riêng cho bạn" -> "my mixes"
        "liked music", "좋아요 표시한 음악" -> "liked"
        else -> title.trim().lowercase()
    }

    private fun artistKey(artist: String, videoId: String): String =
        artist.trim().lowercase().ifBlank { "video:$videoId" }
}
