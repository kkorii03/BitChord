package com.music.bitchord.ko

import java.util.Collections

/**
 * [KO] OPT2 unverified-refusal
 *
 * Source rows a search offered only on a doubted identity: an artist credit in
 * another script, or the act's own song under a Latin title (see
 * `TrackMatcher.isArtistUnverifiedOriginal` / `isTitleUnverifiedOriginal`).
 * Noted by the search itself, in memory, by `source\u0001track`.
 *
 * Phone 11-16..17: 15 check downloads (189 MB) ended "unmeasured", every one
 * such a row that was another song (Paul Kim's 안녕 for AKMU's, Brown Eyed
 * Girls' Abracadabra for (G)I-DLE's, AKMU's Chantey for 매력있어). The aligner
 * refused each on its figures, a null result stores nothing, and every new app
 * run paid the same head again. For a row whose name was already in doubt, a
 * figures refusal of two full openings is the measured answer "not this
 * recording", so it is stored like any measured mismatch. Nothing is ever
 * accepted on this mark; it only lets a refusal stand.
 */
internal object KoUnverifiedRows {

    private val rows: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > MAX_ROWS
        },
    )

    /** One evening's searches, as [KoVersionGuard]'s own marks. */
    internal const val MAX_ROWS = 1024

    /** The aligner's own words for "measured, and not convincingly the same song". */
    private const val FIGURES_REFUSAL = "refused: matched "

    /** Marks each of [found] from [sourceId] as admitted on a doubted identity. */
    fun <T> note(sourceId: String, found: Collection<T>, trackIdOf: (T) -> String) {
        if (sourceId.isBlank()) return
        found.forEach { row ->
            val track = trackIdOf(row).takeIf { it.isNotBlank() } ?: return@forEach
            val key = key(sourceId, track)
            synchronized(rows) {
                rows.remove(key)
                rows[key] = true
            }
        }
    }

    /** Whether this source row was admitted only on a doubted identity. */
    fun contains(sourceId: String?, trackId: String?): Boolean {
        val source = sourceId?.takeIf { it.isNotBlank() } ?: return false
        val track = trackId?.takeIf { it.isNotBlank() } ?: return false
        return rows[key(source, track)] == true
    }

    /**
     * Whether an aligner that returned nothing has in fact measured a doubted
     * row as another song: the row's name was in doubt, both openings were
     * full, the aligner ran to its end without throwing, and it refused on its
     * figures. A path it could not build, a silent or flat reference, or a short
     * opening say nothing about the row and still store nothing.
     */
    fun refusalStands(
        unverified: Boolean,
        referenceComplete: Boolean,
        candidateComplete: Boolean,
        alignerRan: Boolean,
        alignerVerdict: String?,
    ): Boolean = unverified && referenceComplete && candidateComplete && alignerRan &&
        alignerVerdict?.startsWith(FIGURES_REFUSAL) == true

    /**
     * [KO] unaligned-words. Phone 11-16 21:15, AKMU's 외국인의 고백: a
     * title-unverified row (AKMU's Love Lee, the same 3:00) was fetched whole
     * (19 MB), decoded, and left "unmeasured" with no alignment; nothing was
     * stored, so the walk fetched it again a minute later. When the aligner gives
     * no figures to refuse on, a doubted row whose two openings are full is asked
     * its words instead: different words refuse it like a measured mismatch. The
     * same words, too few words or no hearing change nothing (still unmeasured).
     */
    fun wordsDecide(unverified: Boolean, referenceComplete: Boolean, candidateComplete: Boolean): Boolean =
        unverified && referenceComplete && candidateComplete

    /**
     * The words check with no measured offset ([compare] takes the shift,
     * `candidate = shift + youtube`): tried at [UNALIGNED_SHIFTS_MS] (an intro
     * one copy has and the other lacks), keeping the reading most favourable to
     * the candidate. So DIFFERENT comes back only when no tried shift finds the
     * same words, and UNDECIDED when none could call it either way.
     */
    fun unalignedWords(compare: (shiftMs: Long) -> KoVersionGuard.WordsCheck): KoVersionGuard.WordsCheck {
        val checks = UNALIGNED_SHIFTS_MS.map(compare)
        // Any shift that hears the same words keeps the row: DIFFERENT only when none does.
        checks.filter { it.verdict == KoVersionGuard.Words.SAME }
            .maxByOrNull { it.shared.toDouble() / minOf(it.referenceWords, it.candidateWords).coerceAtLeast(1) }
            ?.let { return it }
        return checks.filter { it.verdict != KoVersionGuard.Words.UNDECIDED }
            .maxByOrNull { it.shared.toDouble() / minOf(it.referenceWords, it.candidateWords).coerceAtLeast(1) }
            ?: checks.first()
    }

    /** Zero first; a 60-second opening still leaves a 36-second stretch at the widest shift. */
    internal val UNALIGNED_SHIFTS_MS = listOf(0L, 5_000L, -5_000L, 10_000L, -10_000L, 15_000L, -15_000L, 20_000L, -20_000L)

    private fun key(sourceId: String, trackId: String) = sourceId + '\u0001' + trackId
}
