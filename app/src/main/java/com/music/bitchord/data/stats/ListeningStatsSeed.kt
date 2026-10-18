package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song

/** One play the debug seed places in the listening record. */
internal data class SeedPlay(
    val song: Song,
    val playedAtMs: Long,
    val playedMs: Long,
    val countsAsPlay: Boolean = true,
)

/**
 * The listening history the emulator tour seeds on a fresh install.
 *
 * Twelve public videos across six artists, three plays each, spread over the
 * last sixty days. Six songs were last heard within a day (so Recents and the
 * Quick picks exclusion window have something to work with) and six were last
 * heard 16–26 days ago (so rediscovery and the 14-day Replay windows do too).
 * The plan is a pure function of "now" so the dates can be tested without a
 * device.
 */
internal object ListeningStatsSeed {

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS
    private const val PLAY_MS = 3L * 60L * 1000L + 30_000L
    private const val PLAYS_PER_SONG = 3
    private const val RECENT_SONGS = 6

    private data class SeedSong(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String?,
    )

    private val SONGS = listOf(
        SeedSong("BzYnNdJhZQw", "밤편지", "IU", "밤편지"),
        SeedSong("D1PvIWdJ8xo", "Blueming", "IU", "Love Poem"),
        SeedSong("pSUydWEqKwE", "Ditto", "NewJeans", "Ditto"),
        SeedSong("sVTy_wmn5SU", "OMG", "NewJeans", "OMG"),
        SeedSong("phJLcXJpGBw", "Supernova", "aespa", "Armageddon"),
        SeedSong("4TWR90KJl84", "Next Level", "aespa", "Next Level"),
        SeedSong("gdZLi9oWNZg", "Dynamite", "BTS", "Dynamite"),
        SeedSong("WMweEpGlu_U", "Butter", "BTS", "Butter"),
        SeedSong("JGwWNGJdvx8", "Shape of You", "Ed Sheeran", "÷ (Divide)"),
        SeedSong("2Vv-BfVoq4g", "Perfect", "Ed Sheeran", "÷ (Divide)"),
        SeedSong("dvgZkm1xWPE", "Viva La Vida", "Coldplay", "Viva la Vida or Death and All His Friends"),
        SeedSong("yKNxeF4KMsY", "Yellow", "Coldplay", "Parachutes"),
    )

    /** The 36 plays, newest first within each song, as a function of [nowMs]. */
    fun plan(nowMs: Long): List<SeedPlay> {
        val out = ArrayList<SeedPlay>(SONGS.size * PLAYS_PER_SONG)
        SONGS.forEachIndexed { index, seed ->
            val agoHours = if (index < RECENT_SONGS) {
                listOf(
                    1L + index,             // within 24 h
                    7L * 24L + index * 3L,  // this week
                    35L * 24L + index * 5L, // older than 14 days
                )
            } else {
                val baseDays = 16L + (index - RECENT_SONGS) * 2L
                listOf(
                    baseDays,               // 16..26 days ago: rediscovery
                    baseDays + 15L,         // 31..41 days ago
                    baseDays + 34L,         // 50..60 days ago
                ).map { it * 24L }
            }
            val song = Song(
                videoId = seed.videoId,
                title = seed.title,
                artist = seed.artist,
                thumbnailUrl = "https://i.ytimg.com/vi/${seed.videoId}/hqdefault.jpg",
                albumName = seed.album,
            )
            agoHours.forEach { hours ->
                out += SeedPlay(
                    song = song,
                    playedAtMs = nowMs - hours * HOUR_MS,
                    playedMs = PLAY_MS,
                )
            }
        }
        return out
    }
}
