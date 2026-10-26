package com.music.bitchord.data.sources

import java.util.Locale
import kotlin.math.abs

/**
 * The decision behind the one health check a downloaded file gets when it is
 * played.
 *
 * Kept free of Android types so the rules can be exercised on the JVM. The
 * measurement itself lives in `HqAudioVerify`; this file only turns its answer,
 * the two runtimes and the two sets of names into a verdict and an action.
 *
 * The order matters. A sound comparison that explicitly rejected the file is
 * the strongest evidence, so it wins outright. An accepted comparison is
 * equally strong the other way and stops a marker from condemning a recording
 * the audio proved is the same take. Only when the audio could not answer does
 * the arrangement vocabulary and the runtime difference decide.
 */
object DownloadHealthPolicy {

    /**
     * How far a downloaded file may run from the catalogue track before its
     * length is evidence of another recording.
     *
     * Eight seconds is far outside container rounding and the few seconds an
     * album and a single can differ by, but well inside the intro/outro a
     * music video adds. The sound comparison is what catches a cover of the
     * same length; this is the cheap check for the ones that are obviously not.
     */
    const val DURATION_TOLERANCE_MS = 8_000L

    enum class Verdict(val wire: String) {
        OK("ok"),
        WRONG_RECORDING("wrong-recording"),
        ARRANGEMENT("arrangement"),
        LOWER_QUALITY("lower-quality"),
    }

    enum class Action(val wire: String) {
        NONE("none"),
        STREAM("stream"),
        REDOWNLOAD("redownload"),
        UPGRADE("upgrade"),
    }

    data class Input(
        /** The catalogue song's own title and credit. */
        val songTitle: String,
        val songArtist: String = "",
        /** The runtime the catalogue states, when it states one. */
        val catalogDurationMs: Long? = null,
        /** The file's own runtime, when it could be read. */
        val fileDurationMs: Long? = null,
        /** The source row's title and uploader, when the download recorded them. */
        val sourceTitle: String? = null,
        val sourceArtist: String? = null,
        /** Null when the audio could not be compared (no reference, screen off). */
        val soundAccepted: Boolean? = null,
        /**
         * True when [soundAccepted] came from a stored verdict rather than a
         * fresh decode of the file in hand.
         *
         * A cached answer is proof about the source/track pair, not about the
         * bytes now on disk: a repair or a bad transfer can leave a different
         * recording under the same pair. The runtime and arrangement checks
         * therefore still run for a cached acceptance.
         */
        val soundCached: Boolean = false,
        /** The rung of the file on disk. */
        val currentTier: HqTier? = null,
        /** The best rung a sound comparison has proved for this song. */
        val verifiedTier: HqTier? = null,
        /** False offline, where the file is all there is until a fetch can run. */
        val online: Boolean = true,
    )

    data class Decision(
        val verdict: Verdict,
        val action: Action,
    ) {
        val healthy: Boolean get() = verdict == Verdict.OK
    }

    fun judge(input: Input): Decision {
        if (input.soundAccepted == false) {
            return wrong(input.online)
        }
        // A fresh accepted comparison is proof of the recording; the title
        // markers are only a substitute for the answer the audio already gave.
        // A cached acceptance is not: it proves the pair, not the bytes, so it
        // must not skip the checks that can condemn a file the pair once fitted.
        if ((input.soundAccepted == null || input.soundCached) && isArrangement(input)) {
            return Decision(Verdict.ARRANGEMENT, if (input.online) Action.STREAM else Action.REDOWNLOAD)
        }
        if ((input.soundAccepted == null || input.soundCached) && lengthDiffers(input)) {
            return wrong(input.online)
        }
        val current = input.currentTier
        val verified = input.verifiedTier
        if (current != null && verified != null && verified.rank < current.rank) {
            return Decision(Verdict.LOWER_QUALITY, Action.UPGRADE)
        }
        return Decision(Verdict.OK, Action.NONE)
    }

    /**
     * Whether a verdict about the file at [inspectedUri] may still be written
     * to the record now.
     *
     * Verification takes seconds, and a repair can commit in that window. The
     * new file is not the one that was inspected, so a flag written by id alone
     * would condemn the replacement. [inspectedVersion] is the file's own
     * length/modified stamp, which catches a same-uri rewrite as well.
     */
    fun shouldMarkWrong(
        currentUri: String?,
        inspectedUri: String,
        alreadyWrong: Boolean = false,
        currentVersion: String? = null,
        inspectedVersion: String? = null,
    ): Boolean =
        !alreadyWrong &&
            currentUri == inspectedUri &&
            (inspectedVersion == null || currentVersion == inspectedVersion)

    private fun wrong(online: Boolean) =
        Decision(Verdict.WRONG_RECORDING, if (online) Action.STREAM else Action.REDOWNLOAD)

    private fun lengthDiffers(input: Input): Boolean {
        val file = input.fileDurationMs?.takeIf { it > 0L } ?: return false
        val catalog = input.catalogDurationMs?.takeIf { it > 0L } ?: return false
        return abs(file - catalog) > DURATION_TOLERANCE_MS
    }

    /**
     * Whether the source row names an arrangement the song itself does not.
     *
     * The comparison is deliberate: "Cover Me" by an artist called Cover Me is
     * not a cover, so a marker only counts when it appears on the source side
     * and not on the song's own side. The vocabulary is the one the catalogue
     * matcher already uses, plus the standalone words a download catalogue
     * puts in an uploader name (`MR`, `inst`, `piano`, `lofi`, `remix`).
     */
    fun isArrangement(input: Input): Boolean =
        isArrangement(
            sourceTitle = input.sourceTitle,
            sourceArtist = input.sourceArtist,
            songTitle = input.songTitle,
            songArtist = input.songArtist,
        )

    fun isArrangement(
        sourceTitle: String?,
        sourceArtist: String?,
        songTitle: String?,
        songArtist: String?,
    ): Boolean {
        val sourceRaw = listOfNotNull(sourceTitle, sourceArtist).joinToString(" ")
        if (sourceRaw.isBlank()) return false
        val songRaw = listOfNotNull(songTitle, songArtist).joinToString(" ")
        val source = sourceRaw.lowercase(Locale.ROOT)
        val song = songRaw.lowercase(Locale.ROOT)
        // `MR` is the Korean shorthand for a backing track, but a bare "Mr"
        // is also an ordinary honorific ("Mr. Big"). Only the uppercase form
        // counts, and only when the song's own names do not use it.
        val uppercaseMr = MR.containsMatchIn(sourceRaw) && !MR.containsMatchIn(songRaw)
        return uppercaseMr ||
            ARRANGEMENT_MARKERS.any { it.containsMatchIn(source) && !it.containsMatchIn(song) }
    }

    private val MR = Regex("""(?<![A-Za-z])MR(?![A-Za-z])""")

    private val ARRANGEMENT_MARKERS = listOf(
        Regex("""\bpiano\b"""),
        Regex("""피아노"""),
        Regex("""\binst(rumental)?\b"""),
        Regex("""\binstrumental\b"""),
        Regex("""\bkaraoke\b"""),
        Regex("""노래방"""),
        Regex("""\b8[- ]?bit\b"""),
        Regex("""\bcover(s|ed)?\b"""),
        Regex("""커버"""),
        Regex("""\blo[- ]?fi\b"""),
        Regex("""\bremix(es|ed)?\b"""),
        Regex("""\brmx\b"""),
        Regex("""originally performed"""),
        Regex("""in the style of"""),
        Regex("""made famous"""),
        Regex("""made popular by"""),
        Regex("""\btribute\b"""),
        Regex("""\bbacking (track|band|vocals?)\b"""),
        Regex("""\b(piano|guitar)\s+(version|cover|instrumental|arrangement)\b"""),
        Regex("""\blullaby\b"""),
        Regex("""\b(chiptune|chip tune)\b"""),
        Regex("""반주"""),
    )
}
