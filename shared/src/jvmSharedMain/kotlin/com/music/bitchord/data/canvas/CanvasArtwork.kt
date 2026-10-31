package com.music.bitchord.data.canvas

import java.text.Normalizer
import java.util.Locale

/**
 * Which provider a clip came from — read in one place, by
 * [CanvasArtworkPlayer][com.music.bitchord.ui.player.CanvasArtworkPlayer]'s
 * caller, to decide whether the backdrop should keep re-tinting itself off
 * the clip as it loops rather than settling on its first frame. See that
 * call site for why Spotify's Canvas gets the periodic re-tint and the other
 * three don't.
 */
enum class CanvasSource { SPOTIFY, OTHER }

/**
 * A looping video that stands in for a track's cover art — what Spotify calls
 * a Canvas and Apple calls motion artwork.
 *
 * [url] is what the player mounts; [fallbackUrl] is tried once if that errors,
 * which is how the Apple provider hands over a second rendition of the same
 * clip when the preferred one won't decode. The three metadata fields are not
 * decoration: providers search by free text and will happily return the wrong
 * album's clip, so [matches] re-checks the answer against what's playing.
 */
data class CanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: CanvasSource = CanvasSource.OTHER,
    /**
     * The same clip framed 3:4, when the provider has one. Only the phone
     * player takes it (it runs the clip under the whole screen); everywhere
     * else keeps the square [url].
     */
    val portraitUrl: String? = null,
    /**
     * The stretch of [url] to loop, for a clip cut from a whole video — the
     * upload itself, or the song's music video — rather than a made-to-loop
     * canvas. Null plays the whole clip.
     */
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
    /** When [url] stops working (a video stream's link expires); null for one that keeps. */
    val expiresAtMs: Long? = null,
) {
    /** Cut from a video rather than published as motion artwork. */
    val fromVideo: Boolean get() = clipStartMs != null

    /**
     * Whether this clip really belongs to the track we asked about.
     *
     * Title and artists must match exactly once punctuation, case and accents
     * are stripped — a near miss here is a different song by the same artist,
     * which is the one failure mode users notice. The album is only held to
     * that standard when both sides know it: BitChord resolves a track's album
     * asynchronously after the player opens, and a track queued from search
     * may never get one, so requiring it outright would mean no canvas at all
     * for most of a session.
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() || titlesMatch(title, wantTitle)

        val artistOk = artist == null || wantArtist.isBlank() || creditsMatch(wantArtist, artist)

        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            album.normalizeForMatch() == wantAlbum.normalizeForMatch()

        return titleOk && artistOk && albumOk
    }
}

/**
 * Case, accents and punctuation all differ between YouTube Music, Apple and
 * Tidal for the same release — "Beyoncé - CRAZY IN LOVE (feat. JAY-Z)" against
 * "Beyonce Crazy in Love feat Jay Z". Fold all three away before comparing.
 */
fun String.normalizeForMatch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase(Locale.ROOT)
        // Letters and digits of every script survive: Hangul, kana and CJK
        // used to fold to nothing here, which made every Korean or Japanese
        // credit look blank and so never match at all.
        .replace(Regex("[^\\p{L}\\p{N}\\p{M}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Whether two titles name the same song. Exact once normalised, or the same
 * once a featured-artist tag or a remaster note is taken off either side —
 * "Song (feat. X)" on YouTube is "Song" on Apple, and "Song - 2011 Remaster"
 * is the same recording's artwork.
 */
fun titlesMatch(got: String, want: String): Boolean {
    val a = got.normalizeForMatch()
    val b = want.normalizeForMatch()
    if (a.isEmpty() || b.isEmpty()) return got.trim().equals(want.trim(), ignoreCase = true)
    if (a == b) return true
    val coreA = got.coreTitle()
    val coreB = want.coreTitle()
    return coreA.isNotEmpty() && coreA == coreB
}

private fun String.coreTitle(): String =
    replace(FEATURE_TAG, " ").replace(REMASTER_NOTE, " ").normalizeForMatch()

private val FEATURE_TAG = Regex(
    """[(\[]\s*(?:feat\.?|ft\.?|featuring|with|prod\.?)\s[^)\]]*[)\]]""",
    RegexOption.IGNORE_CASE,
)
private val REMASTER_NOTE = Regex(
    """\s[-–]\s[^-–]*remaster[^-–]*$|[(\[][^)\]]*remaster[^)\]]*[)\]]""",
    RegexOption.IGNORE_CASE,
)

/**
 * Whether two credit lines name the same act. Every artist on [want] found on
 * [got] is a match as before; so now is the lead of either side found on the
 * other, because services list collaborators differently — YouTube's
 * "K/DA, (G)I-DLE, Wolftyla" is Apple's "K/DA". A name with an alias in
 * brackets, "아이유 (IU)", answers to either half.
 */
fun creditsMatch(want: String, got: String): Boolean {
    val wanted = creditNames(want)
    val credited = creditNames(got)
    if (wanted.isEmpty() || credited.isEmpty()) return false
    fun same(a: Set<String>, b: Set<String>) = a.any { it in b }
    if (wanted.all { w -> credited.any { same(w, it) } }) return true
    return credited.any { same(wanted.first(), it) } || wanted.any { same(credited.first(), it) }
}

/** Each credited artist as the set of names it answers to. */
private fun creditNames(raw: String): List<Set<String>> =
    raw.split(ARTIST_SEPARATORS).mapNotNull { part ->
        val full = part.normalizeForMatch()
        if (full.isBlank()) return@mapNotNull null
        val outside = part.replace(BRACKETED, " ").normalizeForMatch()
        val inside = BRACKETED.findAll(part).map { it.value.normalizeForMatch() }.toList()
        // Short fragments — the "G" of "(G)I-DLE" — are not names of their own.
        (listOf(full) + (listOf(outside) + inside).filter { it.length >= 2 }).toSet()
    }

private val BRACKETED = Regex("""\([^)]*\)""")

private val ARTIST_SEPARATORS = Regex(
    "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
    RegexOption.IGNORE_CASE,
)
