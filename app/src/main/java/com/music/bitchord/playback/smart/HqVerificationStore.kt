package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.sources.HqTier

/**
 * The device's memory of what the sound comparison already decided.
 *
 * A high-quality candidate is expensive to verify: two ninety-second decodes
 * and a chroma alignment. The answer is a property of the pair — this YouTube
 * song version and this source's track — and does not change between plays, so
 * it is kept once and read from here afterwards.
 *
 * A rejection is kept too, and for the same reason: a cover catalogue returns
 * the same wrong row on every search, and paying for the same decode to refuse
 * it again would be the whole cost of the feature with none of its benefit.
 * The key names the source *and* its own track id, because one catalogue can
 * hold both the record and a cover under the same title.
 */
object HqVerificationStore {

    /** What was measured for one pair. */
    data class Verified(
        val offsetMs: Long,
        val rate: Double,
        val matchPercent: Int,
        val tier: HqTier,
        /**
         * The source row's own title and uploader, when the caller had them.
         *
         * Kept with the verdict so a later download can apply the arrangement
         * check to the names the *source* used, not only to the catalogue row
         * the file was filed under. Defaulted for verdicts stored before this.
         */
        val sourceTitle: String? = null,
        val sourceArtist: String? = null,
    )

    /** One accepted pair, with the ids needed to ask for the very same stream again. */
    data class Entry(
        val youtubeId: String,
        val sourceConfigId: String,
        val trackId: String,
        val verified: Verified,
    )

    /**
     * The source a current-playing verdict has to be looked up under.
     *
     * The decoder's own source identity wins when it has one, because it names
     * the stream actually being read. A snapshot without a source config id has
     * not identified its source, so the recorded [com.music.bitchord.playback.StreamChoice] is the fallback
     * rather than a reason to give up and call the clock unknown.
     */
    internal data class SourceIdentity(
        val sourceConfigId: String,
        val trackId: String?,
    )

    internal fun sourceIdentity(
        snapshotSourceConfigId: String?,
        snapshotTrackId: String?,
        choiceSourceConfigId: String?,
        choiceTrackId: String?,
    ): SourceIdentity? {
        if (snapshotSourceConfigId != null) {
            return SourceIdentity(snapshotSourceConfigId, snapshotTrackId)
        }
        val choiceId = choiceSourceConfigId ?: return null
        return SourceIdentity(choiceId, choiceTrackId)
    }

    private const val PREFS = "hq_verification"
    private const val KEY_VERSION = "version"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_REJECTED = "rejected"
    private const val MAX_VERIFIED = 2_000
    private const val MAX_REJECTED = 2_000

    /**
     * Format 1 stored a rejection whenever a comparison did not accept, even
     * when the candidate never produced a sample. Those entries are
     * indistinguishable from a real mismatch in the old file, so the one-time
     * upgrade drops every legacy rejection rather than keeping a wrong no.
     * Accepted pairs are measurements and survive.
     */
    private const val STORE_VERSION = 2

    private class Data(
        val verified: LinkedHashMap<String, Verified> = LinkedHashMap(),
        val rejected: LinkedHashSet<String> = LinkedHashSet(),
    )

    private val lock = Any()
    private var cached: Data? = null

    /**
     * Applies the one-time format cleanup without waiting for a caller to ask
     * for a verdict. Safe to call more than once; the loaded copy answers after
     * the first call.
     */
    fun init(context: Context) {
        synchronized(lock) { data(context) }
    }

    fun get(
        context: Context,
        youtubeId: String,
        sourceConfigId: String?,
        trackId: String?,
    ): Verified? = synchronized(lock) {
        data(context).verified[keyOf(youtubeId, sourceConfigId, trackId)]
    }

    fun put(
        context: Context,
        youtubeId: String,
        sourceConfigId: String?,
        trackId: String?,
        verified: Verified,
    ) {
        synchronized(lock) {
            val data = data(context)
            val key = keyOf(youtubeId, sourceConfigId, trackId)
            data.verified.remove(key)
            data.verified[key] = verified
            while (data.verified.size > MAX_VERIFIED) {
                data.verified.remove(data.verified.keys.first())
            }
            data.rejected.remove(key)
            save(context, data)
        }
    }

    fun isRejected(
        context: Context,
        youtubeId: String,
        sourceConfigId: String?,
        trackId: String?,
    ): Boolean = synchronized(lock) {
        keyOf(youtubeId, sourceConfigId, trackId) in data(context).rejected
    }

    fun reject(
        context: Context,
        youtubeId: String,
        sourceConfigId: String?,
        trackId: String?,
    ) {
        synchronized(lock) {
            val data = data(context)
            val key = keyOf(youtubeId, sourceConfigId, trackId)
            data.verified.remove(key)
            data.rejected.remove(key)
            data.rejected.add(key)
            while (data.rejected.size > MAX_REJECTED) {
                data.rejected.remove(data.rejected.first())
            }
            save(context, data)
        }
    }

    /** The best rung already verified for [youtubeId], for the tier-change log. */
    fun bestTier(context: Context, youtubeId: String): HqTier? = synchronized(lock) {
        data(context).verified.entries
            .asSequence()
            .filter { it.key.startsWith("$youtubeId\u0001") }
            .map { it.value.tier }
            .minByOrNull { it.rank }
    }

    /**
     * Every accepted pair, with its source and track ids.
     *
     * The automatic library pass needs more than the best rung: it has to ask
     * the same catalogue for the same row, or a fresh search can land on a
     * different recording. Rejected pairs are deliberately absent — they are
     * not candidates, and a pass that tried them would only pay for the same
     * decode again.
     */
    fun entries(context: Context): List<Entry> = synchronized(lock) {
        data(context).verified.entries.mapNotNull { (key, verified) ->
            val parts = key.split('\u0001')
            if (parts.size < 3) return@mapNotNull null
            val youtubeId = parts[0]
            val sourceConfigId = parts[1]
            val trackId = parts[2]
            if (youtubeId.isBlank() || sourceConfigId.isBlank() || trackId.isBlank()) {
                return@mapNotNull null
            }
            Entry(youtubeId, sourceConfigId, trackId, verified)
        }
    }

    /**
     * Forgets every verdict measured against [sourceConfigId].
     *
     * A verdict names the source config and its track id, not the server URL.
     * Editing an addon's address or replacing the whole source list can keep
     * the same id while pointing somewhere else, and reusing the old track id
     * would ask the new catalogue for a recording it never had.
     */
    fun invalidate(context: Context, sourceConfigId: String) {
        if (sourceConfigId.isBlank()) return
        synchronized(lock) {
            val data = data(context)
            val suffix = "\u0001$sourceConfigId\u0001"
            val verified = data.verified.keys.filter { it.contains(suffix) }
            val rejected = data.rejected.filter { it.contains(suffix) }
            if (verified.isEmpty() && rejected.isEmpty()) return
            verified.forEach { data.verified.remove(it) }
            rejected.forEach { data.rejected.remove(it) }
            save(context, data)
        }
    }

    /** Forgets every pair, for a source list that was replaced wholesale. */
    fun clear(context: Context) {
        synchronized(lock) {
            cached = Data()
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_VERSION, STORE_VERSION)
                .remove(KEY_VERIFIED)
                .remove(KEY_REJECTED)
                .apply()
        }
    }

    private fun keyOf(youtubeId: String, sourceConfigId: String?, trackId: String?): String =
        listOf(youtubeId, sourceConfigId.orEmpty(), trackId.orEmpty())
            .joinToString("\u0001")
            .replace('\n', ' ')
            .replace('\t', ' ')

    private fun String.sanitized(): String =
        replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    private fun data(context: Context): Data {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val data = Data()
        prefs.getString(KEY_VERIFIED, null)?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 5) return@forEach
            val offset = parts[1].toLongOrNull() ?: return@forEach
            val rate = parts[2].toDoubleOrNull() ?: return@forEach
            val match = parts[3].toIntOrNull() ?: return@forEach
            val tier = runCatching { HqTier.valueOf(parts[4]) }.getOrNull() ?: return@forEach
            val sourceTitle = parts.getOrNull(5)?.takeIf { it.isNotBlank() }
            val sourceArtist = parts.getOrNull(6)?.takeIf { it.isNotBlank() }
            data.verified[parts[0]] = Verified(offset, rate, match, tier, sourceTitle, sourceArtist)
        }
        val version = prefs.getInt(KEY_VERSION, 1)
        if (version < STORE_VERSION) {
            // Legacy rejections may be failed decodes rather than mismatches;
            // keeping them would hide a good candidate forever. Verified lines
            // above are real measurements and stay.
            prefs.edit()
                .putInt(KEY_VERSION, STORE_VERSION)
                .remove(KEY_REJECTED)
                .apply()
        } else {
            prefs.getString(KEY_REJECTED, null)?.lineSequence()?.forEach { line ->
                if (line.isNotBlank()) data.rejected += line
            }
        }
        cached = data
        return data
    }

    private fun save(context: Context, data: Data) {
        val verified = data.verified.entries.joinToString("\n") { (key, value) ->
            // The source names are appended only when known, so a verdict
            // without them keeps the five-field line older builds wrote.
            val names = if (value.sourceTitle == null && value.sourceArtist == null) {
                emptyList()
            } else {
                listOf(value.sourceTitle.orEmpty().sanitized(), value.sourceArtist.orEmpty().sanitized())
            }
            (listOf(key, value.offsetMs, value.rate, value.matchPercent, value.tier.name) + names)
                .joinToString("\t")
        }
        val rejected = data.rejected.joinToString("\n")
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VERSION, STORE_VERSION)
            .putString(KEY_VERIFIED, verified)
            .putString(KEY_REJECTED, rejected)
            .apply()
    }
}
