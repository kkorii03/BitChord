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
     * [KO] lyricsmiss: the share of the lookup's deadline the first round, made
     * under the singer's name, may take. One slow provider held it to its 9 s
     * budget, so the resolved original got the last 2-3 s of the 12 s deadline
     * and was cut before it answered (phone 11-20 ySQCgv_uw-A "창귀": first round
     * 9 s, KuGou under 'CHANGGWI'/'ahn ye eun' 3 s later at the deadline;
     * RVtVN18Kf4U "180도": first round 9.7 s, deadline during the retry).
     */
    fun firstRoundMs(deadlineMs: Long): Long = deadlineMs / 2

    /**
     * [round] waited for at most [capMs]. When it is cut, [onCut] says what to
     * keep instead (the best a provider already delivered); a round that ends in
     * time is returned as it is, a miss included.
     */
    suspend fun <R : Any> cappedFirstRound(capMs: Long, round: suspend () -> R?, onCut: () -> R?): R? {
        var ended = false
        val found = withTimeoutOrNull(capMs) { round().also { ended = true } }
        return if (ended) found else onCut()
    }

    /** The log line of a cut first round. No lyric text. */
    fun cutLine(capMs: Long, kept: Boolean): String =
        "cover-lyrics: first round cut after ${capMs}ms (singer's name), kept=" + if (kept) "y" else "n"

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
     * [KO] herolyr: the names the resolved original is retried under, in order.
     *
     * YouTube Music lists many Korean songs by an English title ("히어로" by LUCY
     * as "Hero", "개화" as "Flowering"), and the title search takes that row as
     * the original, while Bugs, VIBE and Melon file the song under its Hangul
     * title (phone 11-19 oZ4Tr4bINNY, pip-QThrNwE: "Hero"/"lucy" missed everywhere
     * and the 12 s deadline ended the lookup). So a Hangul row title the resolved
     * one does not share is also asked under the resolved artist. A Latin resolved
     * title goes second: there the Hangul title is likely the catalogue's own. A
     * Japanese or Chinese one ("晩餐歌" for "만찬가") goes first, as before: the
     * Hangul is then only a spelling of the original. The resolved artist stays in
     * both names, so this is no looser than the resolved name itself.
     *
     * [KO] lyricsmiss: a resolved title that only carries the Hangul one in brackets
     * ("180 Degree (180도)" for BEN's "180도", phone 11-20 RVtVN18Kf4U) is no Hangul
     * title the catalogues file: the row's own is still asked. Only the same title
     * adds nothing.
     */
    fun resolvedNames(rowTitle: String, resolved: OriginalNames.Name?): List<Pair<String, String>> {
        val name = resolvedFirst(artistKnown = false, resolved = resolved) ?: return emptyList()
        val row = rowTitle.trim()
        if (row.isEmpty() || name.second.isEmpty() || !OriginalNames.hasHangul(row) ||
            row.equals(name.first, ignoreCase = true)
        ) {
            return listOf(name)
        }
        val ownTitle = row to name.second
        return if (name.first.any(::isHanOrKana)) listOf(name, ownTitle) else listOf(ownTitle, name)
    }

    private fun isHanOrKana(char: Char): Boolean = when (Character.UnicodeScript.of(char.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        -> true
        else -> false
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
     * 2. The original [resolve] gives for the title, waited for at most [waitMs],
     *    also under [rowTitle] when that is the Hangul title ([resolvedNames]).
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
        rowTitle: String = "",
    ): R? {
        for (alternative in alternatives) retry(alternative)?.let { return it }
        var answered = false
        val resolved = withTimeoutOrNull(waitMs) { resolve().also { answered = true } }
        onResolved(resolved, !answered)
        for (name in resolvedNames(rowTitle, resolved)) retry(name)?.let { return it }
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
