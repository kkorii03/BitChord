package com.music.bitchord.playback.smart

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song

/**
 * The original recording each cover video was found to be a cover of — by the
 * measurement lining the two up, not by a guess — kept so the cover is shown
 * under the record's own title and artist: "Shape of You" / "Ed Sheeran",
 * where the upload was titled "Shape of you(Edsheren) / 오화요Cover".
 *
 * Read synchronously by [com.music.bitchord.data.cover.CoverTitles.read] (it
 * runs while rows are drawn), so it is a small SharedPreferences map, which
 * Android keeps in memory after the first read.
 */
object CoverOriginals {
    private const val PREFS = "cover_originals"
    private const val MAX_KEPT = 3_000
    private const val SEP = '\u0001'

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** (title, artist) of the record behind [videoId], once one has been measured against it. */
    fun get(videoId: String): Pair<String, String>? {
        val raw = prefs?.getString(videoId, null) ?: return null
        val title = raw.substringBefore(SEP)
        val artist = raw.substringAfter(SEP, "")
        return (title to artist).takeIf { title.isNotBlank() }
    }

    /** Remembers [original] as the record [videoId] covers. */
    fun remember(videoId: String, original: Song) {
        val store = prefs ?: return
        val title = cleanTitle(original.title)
        val artist = original.artist.trim()
        if (title.isBlank()) return
        val value = "$title$SEP$artist"
        if (store.getString(videoId, null) == value) return
        val editor = store.edit().putString(videoId, value)
        // Bounded: drop arbitrary old ones past the cap (they come back when measured again).
        val all = store.all
        if (all.size >= MAX_KEPT) all.keys.take(all.size - MAX_KEPT + 1).forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * The song's name without the release's credits: "(feat. …)", "(with …)",
     * "(From "Frozen" / Soundtrack Version)" — what a cover's title leaves out
     * and a listener calls the song.
     */
    internal fun cleanTitle(title: String): String =
        CREDITS.replace(title, "").replace(Regex("\\s{2,}"), " ").trim().ifBlank { title.trim() }

    private val CREDITS = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|with|from|prod\.?)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
}
