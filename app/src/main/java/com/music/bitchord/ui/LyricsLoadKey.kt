package com.music.bitchord.ui

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.playback.smart.LyricsTiming

/**
 * The question a loaded lyrics answer belongs to.
 *
 * The player must re-run the lookup when any of these change: a different
 * track, a different sound for it (downloaded file vs stream), a source
 * switched on or off, the source priority, or "음절 우선". Leaving the order
 * and the syllable preference out meant changing them while a song played did
 * nothing until the next track.
 */
internal data class LyricsLoadKey(
    val videoId: String,
    val audioSource: String?,
    val sources: Set<LyricsSource>,
    val order: List<LyricsSource>,
    val prioritizeSyllableSync: Boolean,
) {
    companion object {
        fun of(
            videoId: String,
            isVideo: Boolean,
            localUri: String?,
            sources: Set<LyricsSource>,
            order: List<LyricsSource>,
            prioritizeSyllableSync: Boolean,
        ): LyricsLoadKey = LyricsLoadKey(
            videoId = videoId,
            audioSource = if (isVideo) null else LyricsTiming.audioSourceKey(videoId, localUri != null),
            sources = sources,
            order = order,
            prioritizeSyllableSync = prioritizeSyllableSync,
        )
    }
}

/** The settings a lyrics lookup for [videoId] was made under. */
internal fun lyricsLookupKey(
    videoId: String,
    sources: Set<LyricsSource>,
    order: List<LyricsSource>,
    prioritizeSyllableSync: Boolean,
): List<Any> = listOf(videoId, sources, order, prioritizeSyllableSync)

