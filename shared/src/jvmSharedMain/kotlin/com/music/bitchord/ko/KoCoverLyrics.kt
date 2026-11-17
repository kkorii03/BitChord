package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricsQueryVariants
import com.music.bitchord.data.lyrics.OriginalNames

/**
 * [KO] cover-lyrics: the lyrics retries for a cover whose title names the song
 * and the singer but never the original's artist ("공백 - 타비 Cover(11.23)").
 *
 * The search artist of such a row is only the singer, and its name variants
 * are the singer's and the channel's ("타비", "타비, 아라하시타비 노래 저장소").
 * No catalogue files the song under them, yet those variants were retried
 * first and spent the whole 12 s lookup deadline, so the title-only name and
 * the original YouTube Music had already found for the title
 * ([OriginalNames.fromTitle], asked alongside the first round) were never
 * searched (phone 11-17 QskmVRqgW68: two rounds, both not_found, deadline,
 * then two minutes of Gemini that timed out).
 *
 * Every retry still goes through the same provider matching and the heard-words
 * check ([com.music.bitchord.data.lyrics.LyricsMismatch]), so a different song
 * of the same name is refused exactly as before.
 */
internal object KoCoverLyrics {

    /**
     * The name to retry before anything else when the artist is not known: the
     * original the title resolved to. Null when the artist is known (nothing
     * changes) or nothing was resolved.
     */
    fun resolvedFirst(artistKnown: Boolean, resolved: OriginalNames.Name?): Pair<String, String>? {
        if (artistKnown || resolved == null) return null
        val title = resolved.title.trim()
        if (title.isEmpty()) return null
        return title to resolved.artist.trim()
    }

    /**
     * The name variants worth a retry. With the artist known, all of them, as
     * before. Without it, only the title-only ones: every other variant names
     * the singer or the channel, which the first round already asked.
     */
    fun variantsToRetry(
        variants: List<LyricsQueryVariants.Name>,
        artistKnown: Boolean,
    ): List<LyricsQueryVariants.Name> =
        if (artistKnown) variants else variants.filter { it.artist.isBlank() }

    /** The one log line saying what a cover without its original's artist is searched as. No lyric text. */
    fun logLine(title: String, singer: String, resolved: OriginalNames.Name?): String =
        "cover-lyrics: title='$title' artist=none singer='$singer' original=" +
            (resolved?.let { "'${it.title}'/'${it.artist.ifBlank { "none" }}'" } ?: "none")
}
