package com.music.bitchord.ko

import com.music.bitchord.data.sources.TrackMatcher
import java.util.Locale

/**
 * Korean-fork title rules restored after the upstream v1.9 merge.
 *
 * Kept here so the next merge leaves the upstream TrackMatcher intact except
 * for the one-line hooks marked `// [KO] title-rules`.
 */
object KoTitleRules {

    /**
     * The take marker written for a tribute-style title. Existing markers
     * (`cover`, `karaoke`, `instrumental`) live in TrackMatcher's
     * VERSION_WORDS; these phrases are the ones that were missing there.
     */
    const val TRIBUTE_TAKE = "tribute"

    private val TRIBUTE_TAKE_PATTERNS = listOf(
        Regex("""\btribute\s+to\b"""),
        Regex("""\boriginally\s+performed\s+by\b"""),
        Regex("""\bin\s+the\s+style\s+of\b"""),
        Regex("""\bmade\s+famous\s+by\b"""),
    )

    /** [TRIBUTE_TAKE] when [text] is a tribute/covers-catalogue title, else null. */
    fun tributeTake(text: String): String? {
        val folded = text.lowercase(Locale.ROOT)
        return TRIBUTE_TAKE.takeIf {
            TRIBUTE_TAKE_PATTERNS.any { pattern -> pattern.containsMatchIn(folded) }
        }
    }

    /**
     * Whether an exact-runtime credit disagreement may be accepted because
     * the two credits are written in different scripts. Same-script different
     * artists stay refused: that is the cover/tribute shape, not a localised
     * spelling of the same act.
     */
    fun creditsOverrideAllowed(wantedArtist: String, gotArtist: String): Boolean =
        TrackMatcher.artistScriptsDiffer(wantedArtist, gotArtist)

    /**
     * The first credited artist, after the fork's byline expansion.
     *
     * [TrackMatcher.artistVariants] drops Topic/VEVO channel plumbing and
     * splits aliases, so "ArtistVEVO" searches as "Artist" and a Korean byline
     * ("강현준(Lil tachi) 및 김호진") searches under its first real name.
     */
    fun primaryArtist(artist: String): String =
        TrackMatcher.artistVariants(artist).firstOrNull().orEmpty()

    /**
     * Whether any identity of [wanted] meets any identity of [got].
     *
     * The lists carry a title and its stated translation ("3.여수밤바다 -
     * Yeosu Night Sea"), so an English catalogue row still matches the Korean
     * listing that named both. Kana/romaji is bridged by transliteration; the
     * guarded containment absorbs punctuation differences without letting a
     * three-letter word stand for a whole title.
     */
    fun coresMatch(wanted: List<String>, got: List<String>, containment: Boolean): Boolean {
        for (w in wanted) {
            for (g in got) {
                if (w == g || TrackMatcher.romajiOf(w) == TrackMatcher.romajiOf(g)) return true
                if (containment && w.length > 3 && g.length > 3 &&
                    (w.contains(g) || g.contains(w))
                ) {
                    return true
                }
            }
        }
        return false
    }
}
