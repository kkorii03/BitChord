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
 *
 * A *music video* is the one length case that is not a different edit: it
 * carries an intro and an outro the record does not, so it runs longer than
 * the track the catalogue files. See [VIDEO_OVERHANG_SECONDS].
 */
internal object RecordingMatch {

    /** A catalogue row within this of the song's own length is the same recording. */
    private const val DURATION_TOLERANCE_SECONDS = 3.0

    /**
     * How much longer than its catalogue track a music video may run and still
     * be the same words.
     *
     * Only one direction is widened. Three of the ten songs in the 11-05
     * emulator run were refused on length alone, and all three were the same
     * recording by the same artist — Youngblood (230 s video / 203.4 s track),
     * When I Was Your Man (234 s / 213.8 s), 7 rings (185 s / 178.6 s). The
     * video adds material at the ends; it does not drop verses, so a song
     * *longer* than its row is expected. A row longer than the song still has
     * to be within [DURATION_TOLERANCE_SECONDS]: there our audio would be a cut
     * of it, and a cut can be missing verses.
     *
     * The "different edit" case this leaves to the version test is the one the
     * names already carry (live, remix, acoustic…), which the data bears out:
     * every remix, acoustic and live row in that run was caught by
     * [VERSION_WORDS], never by the length.
     *
     * shortcut: 30 s, the largest overhang seen (26.6 s); widen only if a real
     * video of the same recording falls outside it.
     */
    private const val VIDEO_OVERHANG_SECONDS = 30.0

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
        val rules = Rules(title, artist, durationMs)
        return candidates.filter { rules.failures(it).isEmpty() }.minByOrNull { rules.distance(it) }?.value
    }

    /**
     * Why [best] found nothing, for the one line a miss prints.
     *
     * A search that never got an answer and a search whose every row was
     * refused both end in the same null, and the two send a reader looking in
     * opposite places — the network or the matching rules. This names the test
     * the closest row failed so the log can say which it was.
     */
    fun <T> missReason(candidates: List<Candidate<T>>, title: String, artist: String, durationMs: Long): String {
        if (candidates.isEmpty()) return "no-match count=0"
        val rules = Rules(title, artist, durationMs)
        val closest = candidates.minWithOrNull(
            compareBy<Candidate<T>> { rules.failures(it).size }.thenBy { rules.distance(it) },
        )
        val failed = closest?.let { rules.failures(it).firstOrNull() }
            ?: return "no-match count=${candidates.size}"
        return "no-match count=${candidates.size} closest=$failed"
    }

    /** The tests as one set of wanted values, so [best] and [missReason] cannot drift. */
    private class Rules(title: String, artist: String, durationMs: Long) {
        private val wantedTitle = RecordingMatch.coreOf(title)
        private val wantedVersion = RecordingMatch.versionOf(title)
        private val wantedArtists = RecordingMatch.artistsOf(artist)
        private val seconds = durationMs / 1000.0
        private val known = durationMs > 0L

        fun distance(candidate: Candidate<*>): Double =
            if (known && candidate.seconds != null) abs(candidate.seconds - seconds) else 0.0

        /** The tests this row fails, empty when it is this recording. */
        fun failures(candidate: Candidate<*>): List<String> {
            if (!candidate.usable) return listOf("unusable")
            val name = candidate.title ?: return listOf("no-title")
            val out = mutableListOf<String>()
            if (RecordingMatch.coreOf(name) != wantedTitle) out += "core"
            if (RecordingMatch.versionOf(name) != wantedVersion) out += "version"
            if (wantedArtists.isNotEmpty() && RecordingMatch.artistsOf(candidate.artist.orEmpty()).none { it in wantedArtists }) {
                out += "artist"
            }
            if (known) {
                val other = candidate.seconds
                when {
                    // The unknown-length refusal above applies to every source:
                    // a wrong edit can never be repaired by a whole-song shift.
                    other == null -> out += "no-length"
                    other > seconds + DURATION_TOLERANCE_SECONDS -> out += "length"
                    other < seconds - VIDEO_OVERHANG_SECONDS -> out += "length"
                }
            }
            return out
        }
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
