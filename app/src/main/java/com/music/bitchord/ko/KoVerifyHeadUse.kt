package com.music.bitchord.ko

/**
 * [KO] UPG-8 (first half): how much of a verify head the decoder really used.
 *
 * A verify downloads a head sized from the format's bitrate for 91 seconds
 * (HqVerifyPolicy.bytesToFetch). Whether that head is larger than the decode
 * needs is not known from the logs, so the timing line also says the furthest
 * byte the extractor read and how much audio it decoded. Heads are not
 * resized here; this only measures.
 */
object KoVerifyHeadUse {
    /** ` maxRead=<bytes> audio=<ms>ms`; a figure that was not measured prints `-`. */
    fun suffix(maxRead: Long, audioMs: Long): String =
        " maxRead=${if (maxRead >= 0L) maxRead.toString() else "-"}" +
            " audio=${if (audioMs >= 0L) "${audioMs}ms" else "-"}"
}
