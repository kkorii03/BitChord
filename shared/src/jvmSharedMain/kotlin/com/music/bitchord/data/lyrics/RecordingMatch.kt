package com.music.bitchord.data.lyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Whether a catalogue search hit is this recording, shared by the sources that
 * match on a name ([LrcRed], [AmllTtml]) so the rules cannot drift apart.
 *
 * Every test is a requirement rather than a score: a miss is recoverable, the
 * wrong words scrolling in time with the right song is not. The title outside
 * its brackets and any " - " suffix must be the same; the bracket words that
 * name a different recording ([VERSION_WORDS]: live, remix, acoustic…) must
 * agree; at least one credited artist must be shared; and the length must be
 * within [DURATION_TOLERANCE_SECONDS] when both sides know it. A hit with no
 * length at all is refused once the song's own length is known — deliberately
 * for every source, not just iTunes: an unverified edit is the wrong words in
 * time with the right song, and no provider's row is trustworthy enough to
 * waive it.
 */
internal object RecordingMatch {

    private const val DURATION_TOLERANCE_SECONDS = 3.0

    /** One search row, already flattened onto the fields every source has. */
    internal data class Candidate<T>(
        val value: T,
        val title: String?,
        val artist: String?,
        /** Seconds, fractional; null when the row does not state a length. */
        val seconds: Double?,
        /** False for a row that is not usable as a document at all. */
        val usable: Boolean = true,
    )

    fun <T> best(candidates: List<Candidate<T>>, title: String, artist: String, durationMs: Long): T? {
        val wantedTitle = coreOf(title)
        val wantedVersion = versionOf(title)
        val wantedArtists = artistsOf(artist)
        val seconds = durationMs / 1000.0

        fun distance(candidate: Candidate<T>): Double =
            if (durationMs > 0L && candidate.seconds != null) abs(candidate.seconds - seconds) else 0.0

        return candidates
            .filter { candidate ->
                val name = candidate.title ?: return@filter false
                candidate.usable &&
                    coreOf(name) == wantedTitle &&
                    versionOf(name) == wantedVersion &&
                    (wantedArtists.isEmpty() || artistsOf(candidate.artist.orEmpty()).any { it in wantedArtists }) &&
                    // The unknown-length refusal above applies to every source:
                    // a wrong edit can never be repaired by a whole-song shift.
                    (durationMs <= 0L ||
                        (candidate.seconds != null && distance(candidate) <= DURATION_TOLERANCE_SECONDS))
            }
            .minByOrNull(::distance)
            ?.value
    }

    /** "Song (Live) - 2011 Remaster" → "song". */
    private fun coreOf(title: String): String =
        normalized(title.replace(BRACKETED, " ").substringBefore(" - "))
            .ifEmpty { normalized(title) }

    /** The version words in the parts [coreOf] throws away. */
    private fun versionOf(title: String): Set<String> {
        val extras = BRACKETED.findAll(title).map { it.value }.toList() +
            title.substringAfter(" - ", "")
        return extras.flatMap { extra ->
            val words = normalized(extra).split(' ').filter { it.isNotEmpty() }
            // "Album Version" and "Radio Edit" read like a take and are the
            // ordinary release. Treating them as versions made the plain
            // listing of the same recording fail every test below.
            if (words.joinToString("") in NEUTRAL) emptyList() else words.filter { it in VERSION_WORDS }
        }.toSet()
    }

    private fun artistsOf(artist: String): Set<String> =
        artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }.toSet()

    /** Lower case, accents off ("ROSÉ" is "rose"), and only letters and digits. */
    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val WHITESPACE = Regex("""\s+""")
    private val ARTIST_SEPARATORS = Regex(
        """\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** Asides that read like a take but name the ordinary release. */
    private val NEUTRAL = setOf(
        "albumversion", "originalversion", "originalmix", "singleversion",
        "radioversion", "radioedit", "stereoversion", "monoversion",
        "studioversion", "fullversion", "standardversion", "explicitversion",
        "deluxeversion", "originaltrack", "cleanedit", "cleanversion", "cleanmix", "clean",
    )

    /** Bracket words that make it a different recording, not a different label. */
    private val VERSION_WORDS = setOf(
        "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental",
        "karaoke", "cappella", "acapella", "demo", "edit", "version", "cover",
        "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
    )
}
