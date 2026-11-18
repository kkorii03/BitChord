package com.music.bitchord.ko

/**
 * [KO] log 11-18 18:11 Pompeii: whether the data-source open on this thread is a
 * background fill of the playing track's upgrade rather than the player.
 *
 * The resolver records the parked upgrade's source and format when an upgraded
 * item is opened, so the badge and the swap's own "what was playing" capture name
 * it. The mid-song swap's download opens that same item before any swap: it
 * recorded "Private Hi-Res FLAC" over the YouTube stream still playing, the swap
 * captured that as the copy to revert to, and after the length revert the YouTube
 * Opus was labelled as the FLAC source, so every later swap was skipped
 * `current-clock-unknown`. [CacheWriter][androidx.media3.datasource.cache.CacheWriter]
 * opens its source synchronously on the calling thread, so a thread-local marks
 * exactly the fill's opens and never the player's loader thread. Every path that
 * makes such a copy audible records it itself (the swap's adoption, the audition
 * swap, the rate-limit resume).
 */
object KoCacheFill {
    private val filling = ThreadLocal<Boolean>()

    /** Runs [block] (a blocking cache fill) with this thread marked as filling when [marks]. */
    fun <T> run(marks: Boolean, block: () -> T): T {
        if (!marks) return block()
        val was = filling.get()
        filling.set(true)
        try {
            return block()
        } finally {
            if (was == null) filling.remove() else filling.set(was)
        }
    }

    /** Whether the open on this thread belongs to a marked [run]. */
    fun isFilling(): Boolean = filling.get() == true

    /** Whether an upgraded open may record its stream as the one playing. */
    fun recordsServedUpgrade(proving: Boolean): Boolean = !proving && !isFilling()
}
