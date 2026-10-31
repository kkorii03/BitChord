package com.music.bitchord.ko

import com.music.bitchord.data.sources.TrackMatcher

/**
 * Korean-fork title rules restored after the upstream v1.9 merge.
 *
 * Kept here so the next merge leaves the upstream TrackMatcher intact except
 * for the one-line hooks marked `// [KO] title-rules`.
 */
object KoTitleRules {

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
