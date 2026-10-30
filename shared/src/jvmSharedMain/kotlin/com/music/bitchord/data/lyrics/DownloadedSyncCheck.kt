package com.music.bitchord.data.lyrics

/**
 * When a downloaded file's stored lyrics and timing are worth checking again.
 *
 * A download writes the provider's lines and their times into the file once,
 * at save time. If that first answer was wrong — an untimed sheet, a different
 * edit, an older matching rule — every later play shows the same wrong sync.
 * The file is now checked once against its own audio with today's song check,
 * and the stored sync is replaced only when the new answer is better.
 *
 * Pure on purpose: the version and match rules are the part worth testing
 * without a file, a player or a network request.
 */
object DownloadedSyncCheck {

    /** The song-check rules a stored sync was last checked with. */
    const val SYNC_CHECK_VERSION = 1

    /** Below this agreement a stored sync is worth a fresh provider lookup. */
    const val MIN_MATCH_PERCENT = 70

    /** Why a downloaded file is (or is not) being checked on this play. */
    enum class Recheck(val token: String) {
        NONE("none"),
        UNVERIFIED("unverified"),
        UNKNOWN_PROVIDER("unknown-provider"),
        LOW_MATCH("low-match"),
        DONE("done"),
    }

    /** No version, or one from older rules, means the stored sync was never checked this way. */
    fun isUnverified(storedVersion: Int?): Boolean =
        storedVersion == null || storedVersion < SYNC_CHECK_VERSION

    /**
     * The reason to check now, most specific first. A version that already
     * names the current rules is never checked again, even if the old answer
     * was poor: the check has been paid for, and the next play must not buy it
     * again.
     */
    fun recheckReason(
        storedVersion: Int?,
        providerUnknown: Boolean,
        matchPercent: Int?,
    ): Recheck {
        if (!isUnverified(storedVersion)) return Recheck.NONE
        if (providerUnknown) return Recheck.UNKNOWN_PROVIDER
        if (matchPercent != null && matchPercent < MIN_MATCH_PERCENT) return Recheck.LOW_MATCH
        return Recheck.UNVERIFIED
    }

    /**
     * Whether the file's own lyrics are poor enough evidence to ask the lyric
     * providers for a fresh timed sheet, which is then checked the same way.
     */
    fun shouldFetchNewLyrics(providerUnknown: Boolean, matchPercent: Int?): Boolean =
        providerUnknown || (matchPercent != null && matchPercent < MIN_MATCH_PERCENT)

    /**
     * Whether the new answer should replace the stored one. A new answer always
     * wins over an unverified or failed one; otherwise it has to agree more
     * than what is already there. Equal agreement keeps the stored answer, so
     * a replay is a no-op rather than a rewrite.
     */
    fun shouldReplace(
        storedVersion: Int?,
        storedMatchPercent: Int?,
        newMatchPercent: Int?,
    ): Boolean {
        if (newMatchPercent == null) return false
        if (isUnverified(storedVersion)) return true
        val stored = storedMatchPercent ?: return true
        return newMatchPercent > stored
    }

    /** Which lyric sheet a replacement belongs to. */
    enum class Winner { STORED, FRESH }

    /**
     * What to keep after a re-check: no replacement, the file's own lines
     * under [key], or a fresh sheet under its own [key].
     *
     * [key] is null when nothing is being stored — either the stored answer
     * still wins, or there was no new answer to compare. A fresh winner is
     * only a candidate here: the caller still has to save those lines as the
     * song's override and their sync under [key]. If either fails, it must not
     * record [recordVersion], so the next play tries again.
     */
    class Replacement(
        val replace: Boolean,
        val winner: Winner?,
        val key: Int?,
        val recordVersion: Boolean,
    )

    /**
     * Chooses between the file's own lyrics and a fresh provider answer.
     *
     * The comparison is the same one [shouldReplace] makes: the winner is
     * whichever sheet fits the audio better, and it only replaces what is
     * stored when it beats the stored answer. A tie keeps the file's own
     * lines, so replaying a checked file does not rewrite it.
     */
    fun decide(
        storedVersion: Int?,
        existingMatchPercent: Int?,
        existingKey: Int,
        ownMatchPercent: Int?,
        freshMatchPercent: Int?,
        freshKey: Int,
        /**
         * Whether the file's own check actually reached an engine. A
         * "no-answer" (5xx/401/network) did not, so the version must stay
         * unset and the next play may try again. A "no-words"/"no-fit" did
         * run; recording it is what keeps a structural miss from paying for
         * the same decode on every play.
         */
        ownCheckAnswered: Boolean = false,
        /**
         * Whether the file's own answer was actually applied. A match percent
         * is reported even for a refusal (`refused-match`/`refused-shift`),
         * where no fitted sync was produced: that is not a verification, so it
         * must not count as an answer or be stored.
         */
        ownApplied: Boolean = true,
    ): Replacement {
        // A check that heard nothing (no audio, a refusal, no fit) has not
        // verified the file with these rules. Recording the version would make
        // the next play answer NONE and keep an old wrong sync for ever.
        val ownMatch = ownMatchPercent.takeIf { ownApplied }
        val answered = ownCheckAnswered || ownMatch != null || freshMatchPercent != null
        val recordVersion = answered
        val freshWins = freshMatchPercent != null &&
            freshMatchPercent > (ownMatch ?: 0)
        val bestMatch = if (freshWins) freshMatchPercent else ownMatch
        if (!shouldReplace(storedVersion, existingMatchPercent, bestMatch)) {
            return Replacement(replace = false, winner = null, key = null, recordVersion = recordVersion)
        }
        return if (freshWins) {
            Replacement(replace = true, winner = Winner.FRESH, key = freshKey, recordVersion = recordVersion)
        } else {
            Replacement(replace = true, winner = Winner.STORED, key = existingKey, recordVersion = recordVersion)
        }
    }
}
