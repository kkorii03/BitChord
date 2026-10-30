package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LyricsSource
import java.util.Locale

/**
 * The words the next-song bundle writes for its lyrics and sync steps.
 *
 * "none" has to mean "asked and found nothing". A step the safety switches or
 * the settings kept from running is "off", not a miss: reporting both as
 * "none" made the field logs read as a lyrics failure rate when a large part
 * of it was a deliberately disabled path.
 */
internal object NextPrepLog {

    fun lyrics(source: LyricsSource?, attempted: Boolean): String = when {
        source != null -> source.name.lowercase(Locale.ROOT)
        attempted -> "none"
        else -> "off"
    }

    fun sync(applied: Boolean, attempted: Boolean): String = when {
        applied -> "applied"
        attempted -> "skipped"
        else -> "off"
    }
}
