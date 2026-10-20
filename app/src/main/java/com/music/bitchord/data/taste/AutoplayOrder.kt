package com.music.bitchord.data.taste

import java.util.Locale

/**
 * Puts an AutoPlay batch in the order a listener expects after a seed track.
 *
 * Ranking alone was not enough: a saved favourite carries a higher prior than
 * the seed's own radio, so the first suggestion after a K-pop track could be an
 * unrelated song from the listener's library. This pass enforces what ranking
 * cannot, independently of the scores:
 *
 *  - the seed's own radio and the collaborative-filtering rows for that seed
 *    fill the first [LEAD_SLOTS] places;
 *  - a favourite (or rediscover row) is never the immediate next track and is
 *    placed at most once every [FAVOURITE_EVERY] places after the lead;
 *  - a local row is only eligible when it shares an artist or script with the
 *    seed, or is itself part of the seed's radio;
 *  - ids already played or queued this session are never returned.
 *
 * Pure Kotlin with no Android dependency, so the ordering can be pinned by the
 * JVM tests in this package.
 */
object AutoplayOrder {

    /** Places after the seed that must come from its own radio or similar rows. */
    const val LEAD_SLOTS = 6

    /** After the lead, at most one favourite is placed per this many tracks. */
    const val FAVOURITE_EVERY = 5

    /** The seed facts the relationship test needs, independent of a [Song]. */
    data class SeedProfile(
        val videoId: String,
        val title: String = "",
        val artistNames: List<String> = emptyList(),
        val artistIds: List<String> = emptyList(),
    )

    /**
     * Reorders [candidates] (already ranked) and stops at [limit].
     *
     * [seedIds] is the set of ids the seed's radio and similar sources offered;
     * [excludedIds] is what this session has already played or queued.
     */
    fun order(
        candidates: List<Candidate>,
        seed: SeedProfile,
        seedIds: Set<String> = emptySet(),
        excludedIds: Set<String> = emptySet(),
        limit: Int,
    ): List<Candidate> {
        if (limit <= 0) return emptyList()

        val seen = HashSet<String>(excludedIds)
        val usable = ArrayList<Candidate>(candidates.size)
        for (candidate in candidates) {
            if (candidate.videoId.isBlank()) continue
            if (candidate.videoId == seed.videoId) continue
            if (seen.add(candidate.videoId)) usable += candidate
        }

        val seedArtistNames = seed.artistNames.mapTo(HashSet(), ::key)
        val seedArtistIds = seed.artistIds.filterTo(HashSet()) { it.isNotBlank() }
        val seedScripts = scripts(seed.title + " " + seed.artistNames.joinToString(" "))

        // Every local favourite is kept out of the general queue: the related
        // ones may be placed only in a favourite slot, and an unrelated one is
        // not offered at all.
        val favouriteRows = usable.filter { isFavourite(it.source) }
        val favourites = favouriteRows.filter { candidate ->
            related(candidate, seedArtistNames, seedArtistIds, seedScripts, seedIds)
        }
        val favouriteIds = favouriteRows.mapTo(HashSet()) { it.videoId }
        val others = usable.filterNot { it.videoId in favouriteIds }

        val out = ArrayList<Candidate>(limit)
        val leadIds = HashSet<String>()
        for (candidate in others) {
            if (out.size >= LEAD_SLOTS || out.size >= limit) break
            if (candidate.source != CandidateSource.RADIO_SEED &&
                candidate.source != CandidateSource.SIMILAR
            ) {
                continue
            }
            out += candidate
            leadIds += candidate.videoId
        }

        // Everything that was not used for the lead, in ranked order. The lead
        // rows skip their turn here rather than appearing twice.
        val rest = ArrayList<Candidate>(others.size)
        for (candidate in others) {
            if (candidate.videoId !in leadIds) rest += candidate
        }

        var favouriteAt = 0
        var restAt = 0
        while (out.size < limit) {
            val favouriteSlot = out.size >= LEAD_SLOTS &&
                (out.size - LEAD_SLOTS) % FAVOURITE_EVERY == 0
            if (favouriteSlot && favouriteAt < favourites.size) {
                out += favourites[favouriteAt++]
            } else if (restAt < rest.size) {
                out += rest[restAt++]
            } else {
                break
            }
        }
        return out
    }

    private fun isFavourite(source: CandidateSource): Boolean =
        source == CandidateSource.FAVOURITE || source == CandidateSource.REDISCOVER

    private fun related(
        candidate: Candidate,
        seedArtistNames: Set<String>,
        seedArtistIds: Set<String>,
        seedScripts: Set<Character.UnicodeScript>,
        seedIds: Set<String>,
    ): Boolean {
        if (candidate.videoId in seedIds) return true

        val names = candidate.artistNames.map(::key).filter { it.isNotEmpty() }
        if (names.any { it in seedArtistNames }) return true
        if (candidate.artistIds.any { it.isNotBlank() && it in seedArtistIds }) return true

        if (seedScripts.isNotEmpty()) {
            val candidateScripts = scripts(
                candidate.title + " " + candidate.artistNames.joinToString(" "),
            )
            if (candidateScripts.any { it in seedScripts }) return true
        }
        return false
    }

    private fun key(name: String): String = name.trim().lowercase(Locale.ROOT)

    /**
     * The non-Latin scripts a row is written in. A K-pop seed and a Hangul
     * favourite share one; an English-language seed and an unrelated Latin
     * favourite share none.
     */
    private fun scripts(text: String): Set<Character.UnicodeScript> {
        val found = HashSet<Character.UnicodeScript>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val script = Character.UnicodeScript.of(codePoint)
            if (script != Character.UnicodeScript.LATIN &&
                script != Character.UnicodeScript.COMMON &&
                script != Character.UnicodeScript.INHERITED &&
                script != Character.UnicodeScript.UNKNOWN
            ) {
                found += script
            }
            index += Character.charCount(codePoint)
        }
        return found
    }
}
