package com.music.bitchord.ko

/**
 * [KO] herolyr: a sheet found for a song after its lookup had missed.
 *
 * Phone 11-19 oZ4Tr4bINNY: the lyrics screen's lookup missed at its 12 s deadline
 * and showed nothing; 10 s later the translation-ahead lookup (another question,
 * its own deadline) found VIBE's sheet for the same song, and only translated it.
 * Every fresh search reports here. A hit for a video an earlier fresh search
 * missed is offered once to the screen through the repository's upgrades, which
 * the screen takes only for the song still on it and not over a pick by hand.
 * Nothing waits or polls: it rides on lookups that were running anyway.
 */
object KoLateSheet {

    /** Songs remembered as missed; a session holds far fewer misses than this. */
    private const val KEPT = 64

    private val missed = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > KEPT
    }

    /**
     * A fresh search for [videoId] ended, [found] or not. True when this is a
     * sheet for a song an earlier fresh search missed: it should be offered to
     * the screen. Each miss is answered once.
     */
    fun answered(videoId: String, found: Boolean): Boolean {
        if (videoId.isBlank()) return false
        synchronized(missed) {
            if (!found) {
                missed[videoId] = true
                return false
            }
            return missed.remove(videoId) != null
        }
    }

    /**
     * Whether the screen's own lookup, ending in a miss, keeps the sheet a late
     * answer put up while it waited, instead of clearing the screen. Only a miss
     * with no pick by hand, and only a sheet that is showing.
     */
    fun keepsLateSheet(missed: Boolean, pickedByHand: Boolean, lateSheetShown: Boolean): Boolean =
        missed && !pickedByHand && lateSheetShown

    /** For tests: forgets every miss. */
    internal fun clear() = synchronized(missed) { missed.clear() }
}
