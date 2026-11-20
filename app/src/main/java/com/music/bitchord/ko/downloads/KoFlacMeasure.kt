package com.music.bitchord.ko.downloads

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.sources.HqLibraryPolicy
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.download.DownloadedQuality
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import java.io.File
import java.io.InputStream

/**
 * [KO] HQ-DOWN: the depth and rate a saved FLAC really has, read from its
 * STREAMINFO block, and the last check before a verified replacement takes
 * the place of a file.
 *
 * 11-18 16:31 Bones: a saved 24-bit/48 kHz FLAC was about to be replaced by a
 * 16/44.1 copy, because a plain download records only the badge "FLAC" and
 * the badge reads as 16-bit lossless. Nothing compared the two files' own
 * headers before the old one went.
 */
internal object KoFlacMeasure {

    /**
     * STREAMINFO of a file that starts with the FLAC magic, as a lossless
     * format with its depth and rate. Null for anything else (an ALAC/M4A
     * file, an ID3-prefixed FLAC, a short read): unknown, never a downgrade.
     */
    fun parse(head: ByteArray): StreamFormat? {
        if (head.size < HEAD_BYTES) return null
        if (head[0] != 'f'.code.toByte() || head[1] != 'L'.code.toByte() ||
            head[2] != 'a'.code.toByte() || head[3] != 'C'.code.toByte()
        ) return null
        if (head[4].toInt() and 0x7F != TYPE_STREAMINFO) return null
        val length = ((head[5].toInt() and 0xFF) shl 16) or
            ((head[6].toInt() and 0xFF) shl 8) or
            (head[7].toInt() and 0xFF)
        if (length < STREAMINFO_BYTES) return null
        // Payload: min/max block (2+2), min/max frame (3+3), then 20 bits of
        // sample rate, 3 of channels-1 and 5 of bits-per-sample-1.
        val p = 8 + 10
        val rate = ((head[p].toInt() and 0xFF) shl 12) or
            ((head[p + 1].toInt() and 0xFF) shl 4) or
            ((head[p + 2].toInt() and 0xFF) ushr 4)
        val bits = (((head[p + 2].toInt() and 0x01) shl 4) or ((head[p + 3].toInt() and 0xFF) ushr 4)) + 1
        if (rate <= 0 || bits < 4) return null
        return StreamFormat(codec = "flac", sampleRateHz = rate, bitDepth = bits)
    }

    fun read(file: File): StreamFormat? =
        runCatching { file.inputStream().use(::readHead) }.getOrNull()

    fun read(context: Context, uri: Uri): StreamFormat? = runCatching {
        val path = uri.path
        if (uri.scheme == "file" && path != null) {
            File(path).inputStream().use(::readHead)
        } else {
            context.contentResolver.openInputStream(uri)?.use(::readHead)
        }
    }.getOrNull()

    private fun readHead(input: InputStream): StreamFormat? {
        val head = ByteArray(HEAD_BYTES)
        var filled = 0
        while (filled < HEAD_BYTES) {
            val n = input.read(head, filled, HEAD_BYTES - filled)
            if (n < 0) break
            filled += n
        }
        return if (filled == HEAD_BYTES) parse(head) else null
    }

    /**
     * Why [staged] must not replace [old], or null when it may. A staged file
     * that could not be read counts against a lossless [old] only when its
     * badge says it is lossy; an unreadable lossless file is unknown.
     */
    fun refusal(id: String, old: StreamFormat?, staged: StreamFormat?, stagedBadge: String?): String? {
        val candidate = staged ?: DownloadedQuality.formatOf(stagedBadge).takeIf { it.isLossless == false }
        if (!HqQuality.isDowngradeFrom(old, candidate)) return null
        Log.w(TAG, "download: kept $id old=${describe(old)} new=${describe(candidate)}")
        return KEPT_MESSAGE
    }

    /**
     * The final gate of a verified replacement ([hq] non-null): read the file
     * the record names now and the staged bytes, before the commit. A record
     * condemned as the wrong recording is never protected — the right song
     * comes before its quality.
     */
    fun refusedLowerFile(
        context: Context,
        ids: Set<String>,
        staged: Uri,
        stagedBadge: String?,
        hq: HqDownloadSource?,
    ): String? {
        if (hq == null) return null
        val records = Downloads.savedMetadataValue()
        val previous = ids.mapNotNull { records[it] }
        if (previous.isEmpty() || previous.any { it.wrongRecording }) return null
        val oldUri = previous.firstNotNullOfOrNull { meta -> meta.uri.takeIf { it.isNotBlank() } } ?: return null
        if (oldUri == staged.toString()) return null
        val old = read(context, Uri.parse(oldUri)) ?: return null
        return refusal(ids.first(), old, read(context, staged), stagedBadge)
            // [KO] LIBRARY-5: the kept file's measured rung goes into its record, so the
            // pass stops reading it as its badge's 16-bit and asking for the same pair.
            ?.also { keepMeasuredTier(ids, oldUri, HqQuality.tierOf(old)) }
    }

    /**
     * [KO] LIBRARY-5 (review 11-20): the same comparison, made before a byte of a
     * verified replacement ([hq]) is fetched. A FLAC saved before rungs were
     * recorded reads as its badge's 16-bit, so any verified hi-res pair made it a
     * target: tens of MB were downloaded and then refused by [refusedLowerFile]
     * (or replaced a file of the same rung), and nothing corrected the record, so
     * it repeated. Here the old file's own STREAMINFO is read first; when it is
     * already at least the pair's rung the transfer is skipped and the measured
     * rung is recorded. [refusedLowerFile] stays the final gate after the bytes.
     * A record condemned as the wrong recording is never protected.
     */
    fun keptBeforeTransfer(context: Context, ids: Set<String>, hq: HqDownloadSource?): String? {
        if (hq == null) return null
        val records = Downloads.savedMetadataValue()
        val previous = ids.mapNotNull { records[it] }
        if (previous.isEmpty() || previous.any { it.wrongRecording }) return null
        val meta = previous.firstOrNull { it.uri.isNotBlank() } ?: return null
        val old = read(context, Uri.parse(meta.uri)) ?: return null
        return keptBefore(ids, meta, old, hq)
    }

    /** The decision of [keptBeforeTransfer] once the old file's head is read. */
    internal fun keptBefore(
        ids: Set<String>,
        meta: SavedSongMetadata,
        old: StreamFormat,
        hq: HqDownloadSource,
    ): String? {
        val measured = HqQuality.tierOf(old)
        // Decided on what the record will read once the rung is written, so a
        // skip always leaves the song no longer a target (no loop through the pass).
        val reads = DownloadedQuality.tierOf(meta.downloadFormat, measured.name)
        if (!HqLibraryPolicy.hasCopyAtLeastAsGood(reads, hq.tier)) return null
        keepMeasuredTier(ids, meta.uri, measured)
        Log.w(
            TAG,
            "download: kept ${ids.first()} before fetching: file measures ${describe(old)} " +
                "(${measured.name}), pair ${hq.sourceConfigId} claims ${hq.tier.name}",
        )
        return KEPT_BEFORE_MESSAGE
    }

    /**
     * Writes [measured] as the stored rung of every record in [ids] that still
     * names [uri], when that reads higher than the record does now. Never on a
     * wrong-recording record, and the record's pair ids are left alone.
     */
    private fun keepMeasuredTier(ids: Set<String>, uri: String, measured: HqTier) {
        Downloads.record(
            saved = { it },
            meta = { map ->
                var out = map
                for (id in ids) {
                    val current = out[id] ?: continue
                    if (current.uri != uri || current.wrongRecording) continue
                    val now = DownloadedQuality.tierOf(current.downloadFormat, current.hqTier)
                    val then = DownloadedQuality.tierOf(current.downloadFormat, measured.name)
                    if (then.rank >= now.rank) continue
                    out = out + (id to current.copy(hqTier = measured.name))
                }
                out
            },
        )
    }

    /**
     * The measured rung of a plain (non-verified) FLAC download, recorded so
     * the library does not read a 24-bit file as its badge's 16-bit. Null when
     * the header says nothing above what the badge already gives.
     */
    fun measuredTierName(uri: Uri, badge: String?): String? {
        val badgeTier = DownloadedQuality.tierOf(badge)
        if (badge?.trim()?.uppercase(java.util.Locale.ROOT)?.startsWith("FLAC") != true) return null
        val context = Downloads.applicationContext() ?: return null
        val tier = read(context, uri)?.let(HqQuality::tierOf) ?: return null
        return tier.takeIf { it.rank < badgeTier.rank }?.name
    }

    private fun describe(format: StreamFormat?): String =
        format?.let { "${it.bitDepth ?: "?"}/${it.sampleRateHz ?: "?"} ${it.codec ?: "?"}" } ?: "unknown"

    const val KEPT_MESSAGE = "이미 받은 파일보다 음질이 낮아 바꾸지 않았습니다"
    const val KEPT_BEFORE_MESSAGE = "이미 받은 파일의 음질이 같거나 더 높아 바꾸지 않았습니다"

    private const val TAG = "Downloads"
    private const val TYPE_STREAMINFO = 0
    private const val STREAMINFO_BYTES = 34
    private const val HEAD_BYTES = 8 + STREAMINFO_BYTES
}
