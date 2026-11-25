package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder

/**
 * [KO] autoplay-cover: whether a row AutoPlay queued is an unmarked cover of a
 * better known song.
 *
 * Phone 11-25: the station after "Golden" queued "Viva La Vida" by Cale, HALUNA
 * and Deep Mage (RKLdfpVu2Qk), Coldplay's song with nothing in its title saying
 * so; every marker rule passed it. A shared title alone proves nothing (two
 * songs are called "Golden"), so this asks for both halves: the catalogue's
 * first answer to the title is another act, and the two sheets are the same
 * words. Anything that cannot be compared keeps the row.
 *
 * The network half is the app's KoAutoplayCovers.
 */
object KoAutoplayCover {

    /** One catalogue search: the bare title, in the listing it is filed under. */
    data class Ask(val query: String, val hl: String, val gl: String)

    /** [original] is the better known recording to compare with; null = keep the row, for [reason]. */
    data class Candidate(val original: Song?, val reason: String)

    data class Verdict(val cover: Boolean, val detail: String)

    fun ask(song: Song): Ask? {
        val query = QueueBuilder.baseTitle(song.title).takeIf { it.isNotBlank() } ?: return null
        return if (KoTrackMatcher.hasHangul(song.title)) Ask(query, "ko", "KR") else Ask(query, "en", "US")
    }

    /**
     * Whom [queued] would be a cover of, from the title's Songs page. Null is no
     * verdict: the search failed, or the page came back empty (a Korean address
     * without Premium gets empty Songs pages).
     */
    fun candidate(queued: Song, page: List<Song>?): Candidate? {
        if (page.isNullOrEmpty()) return null
        val title = QueueBuilder.baseTitle(queued.title)
        val named = page.filter {
            !it.isVideo && QueueBuilder.baseTitle(it.title) == title && !KoTrackMatcher.isCoverArrangement(it, queued)
        }.take(TOP_ROWS)
        val first = named.firstOrNull() ?: return Candidate(null, "no-original")
        if (named.any { sameAct(queued, it) }) return Candidate(null, "same-artist")
        if (KoTrackMatcher.isGenericCredit(queued.artist) || KoTrackMatcher.isGenericCredit(first.artist)) {
            return Candidate(null, "generic-credit")
        }
        if (notComparable(queued.artist, first.artist)) return Candidate(null, "artist-scripts")
        return Candidate(first, "")
    }

    /**
     * The sheet of [queued] was found under another act's name, so it would match
     * that act's song whatever this recording sings.
     */
    fun borrowedSheet(queued: Song, searchedArtist: String?): Boolean = searchedArtist != null &&
        searchedArtist.isNotBlank() && !sameName(queued.artist, searchedArtist) &&
        !notComparable(queued.artist, searchedArtist)

    fun words(lines: List<String>): Set<String> = lines.flatMapTo(HashSet()) { KoVersionGuard.tokens(it) }

    fun enough(words: Set<String>): Boolean = words.size >= MIN_WORDS

    /** Whether two sheets are one song's words. */
    fun compare(own: Set<String>, original: Set<String>): Verdict {
        val shared = own.count { it in original }
        val smaller = minOf(own.size, original.size)
        return Verdict(
            cover = enough(own) && enough(original) && shared >= smaller * SAME_SONG_SHARE,
            detail = "shared=$shared/${own.size}:${original.size}",
        )
    }

    private fun sameAct(a: Song, b: Song): Boolean = a.videoId == b.videoId ||
        KoTrackMatcher.sharesArtistChannel(KoTrackMatcher.targetOf(a), b) || sameName(a.artist, b.artist)

    private fun sameName(a: String, b: String): Boolean {
        if (KoTrackMatcher.namesTheSameArtist(a, b) || KoTrackMatcher.sharesArtist(a, b)) return true
        // "Hikaru Utada" / "Utada Hikaru", "Lee Seung Chul" / "Lee Seungchul".
        val theirs = KoTrackMatcher.artistNames(b)
        return KoTrackMatcher.artistNames(a).any { name ->
            theirs.any { it.sorted() == name.sorted() || it.joinToString("") == name.joinToString("") }
        }
    }

    /** Any two names written in different scripts may be one act ("에픽하이" / "EPIK HIGH"). */
    private fun notComparable(a: String, b: String): Boolean {
        val theirs = KoTrackMatcher.artistVariants(b)
        return KoTrackMatcher.artistVariants(a).any { name ->
            theirs.any { KoTrackMatcher.artistScriptsDiffer(name, it) }
        }
    }

    /** A same-artist row this high among the title's own rows is a known song of its own. */
    private const val TOP_ROWS = 3

    /** Distinct words per side below which two sheets say nothing. */
    private const val MIN_WORDS = 20

    private const val SAME_SONG_SHARE = 0.6
}
