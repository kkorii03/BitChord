package com.music.bitchord.ko

/**
 * [KO] w1120-battery: a download of a file the song cache already holds whole,
 * from the same source row and codec, copies it off disk instead of a second GET.
 *
 * 11-17..11-20 logs: six songs (Super in Love on JioSaavn, Pop, I Just Might,
 * Sucker, Pompeii, Cruel Summer) logged `hq-cache: … fully cached N MB` for the
 * upgrade copy (`#hifi`) and then `fetched N B directly` for the automatic
 * download of the very same byte count, about 185 MB fetched twice. dupdl
 * ([KoPlayersFill]) only takes the player's in-flight plain-key fill.
 *
 * An entry is taken only when every one of these holds: it is whole, it is not
 * a watch copy or a refused row's copy, the source it records
 * (`configId|trackId|codec`, written when it was filled) is the one the download
 * resolved, and its length is exactly the length the download's own probe was
 * told by the server. The caller then checks its container against the file it
 * saves. Anything else: null, and the download fetches as before.
 */
internal object KoSourceCopy {

    class Entry(
        val key: String,
        val servedSource: String?,
        val length: Long,
        val complete: Boolean,
        val refused: Boolean,
    )

    fun pick(entries: List<Entry>, identity: String?, totalBytes: Long?): String? {
        if (identity.isNullOrBlank() || totalBytes == null || totalBytes <= 0L) return null
        return entries.firstOrNull { entry ->
            !entry.key.endsWith("#watch") &&
                entry.complete &&
                !entry.refused &&
                entry.length == totalBytes &&
                entry.servedSource == identity
        }?.key
    }
}
