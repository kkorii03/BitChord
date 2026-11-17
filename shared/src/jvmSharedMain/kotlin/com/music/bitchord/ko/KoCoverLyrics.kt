package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricsQueryVariants
import com.music.bitchord.data.lyrics.OriginalNames
import kotlinx.coroutines.withTimeoutOrNull

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
 * The order after the first round is [retryWithoutArtist]: the swapped reading,
 * then the resolved original, then the title alone; the singer's names never.
 *
 * Every retry still goes through the same provider matching and the heard-words
 * check ([com.music.bitchord.data.lyrics.LyricsMismatch]), so a different song
 * of the same name is refused exactly as before.
 */
internal object KoCoverLyrics {

    /**
     * How long, once the swapped reading has missed, the original the title
     * resolved to is still waited for. Its search started with the lookup and is
     * normally done by then; a slow YouTube Music (the KR gate) must not spend
     * the rest of the 12 s deadline on it (review RG6 COV6-A).
     */
    const val RESOLVED_WAIT_MS = 2_000L

    /**
     * The original the title resolved to, as a name to retry. Null when the
     * artist is known (nothing changes) or nothing was resolved.
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

    /**
     * The retries of a cover whose original's artist is not known, once its
     * first round missed. The first answer wins:
     * 1. [alternatives], the swapped artist/song reading. It names an act, so it
     *    is sharper than any title-only search, which can find another song of
     *    the same name (11-07 5nxb792c-b4). The resolved original is itself a
     *    title-only catalogue guess, so it comes after (review RG6 COV6-A).
     * 2. The original [resolve] gives for the title, waited for at most [waitMs].
     * 3. The title-only [variants] ([variantsToRetry]); never the singer's names.
     *
     * [onResolved] hears what step 2 got, and whether it was cut off by [waitMs].
     * Null when every one missed: the catalogue is asked next.
     */
    suspend fun <R : Any> retryWithoutArtist(
        alternatives: List<Pair<String, String>>,
        resolve: suspend () -> OriginalNames.Name?,
        variants: List<LyricsQueryVariants.Name>,
        retry: suspend (Pair<String, String>) -> R?,
        waitMs: Long = RESOLVED_WAIT_MS,
        onResolved: (resolved: OriginalNames.Name?, late: Boolean) -> Unit = { _, _ -> },
    ): R? {
        for (alternative in alternatives) retry(alternative)?.let { return it }
        var answered = false
        val resolved = withTimeoutOrNull(waitMs) { resolve().also { answered = true } }
        onResolved(resolved, !answered)
        resolvedFirst(artistKnown = false, resolved = resolved)?.let { name ->
            retry(name)?.let { return it }
        }
        for (variant in variantsToRetry(variants, artistKnown = false)) {
            retry(variant.title to variant.artist)?.let { return it }
        }
        return null
    }

    /**
     * The one log line saying what a cover without its original's artist is
     * searched as. No lyric text. `original=late` when the title's search was
     * still running after [RESOLVED_WAIT_MS].
     */
    fun logLine(title: String, singer: String, resolved: OriginalNames.Name?, late: Boolean = false): String =
        "cover-lyrics: title='$title' artist=none singer='$singer' original=" +
            (resolved?.let { "'${it.title}'/'${it.artist.ifBlank { "none" }}'" } ?: if (late) "late" else "none")
}
