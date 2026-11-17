package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsMismatch
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsSource

/**
 * [KO] LYR6: what a downloaded song's saved sheet ("저장된 가사") is for.
 *
 * The saved sheet is the provider's answer from the day of the download (the file's own tags) or
 * a later re-check's pick (a per-song override). It used to come before any lookup, every play:
 * a wrong or badly timed one stayed on screen while its one exact-only check waited on Deepgram
 * (DyDfgMOUjCI, 11-17: 14 s early for over 37 s, while the provider's sheet was corrected in 1.6 s),
 * and the check's recorded version kept it from ever being looked at again.
 *
 * Online, the providers' answer is the truth. The saved sheet is the offline copy, and online it
 * is only the last fallback when every provider misses. The copy follows the latest sheet the
 * listener saw or picked, and is rewritten only when that sheet changed.
 *
 * Pure, so the rules are tested on the JVM.
 */
object KoSavedLyrics {

    /**
     * Whether a downloaded song asks the lyric providers before showing its saved sheet.
     * A preview (the track's length not known yet) cannot ask, so it shows the saved sheet.
     */
    fun providersFirst(localFile: Boolean, offline: Boolean, previewOnly: Boolean): Boolean =
        localFile && !offline && !previewOnly

    /** The provider asked for by name online: the listener's own earlier pick, fetched fresh. */
    fun preselected(kind: LyricsOverrideFile.Kind?, source: LyricsSource?): LyricsSource? =
        source.takeIf { kind == LyricsOverrideFile.Kind.USER }

    /**
     * Whether a listener's own pick, shown from the saved copy, may carry the correction its sheet
     * earned online. Without it a picked sheet came back offline uncorrected (USER overrides on a
     * downloaded song were never adjustable). A video keeps its own treatment.
     */
    fun ownPickTimeable(isVideo: Boolean, timed: Boolean, byHand: Boolean, timedToVideo: Boolean): Boolean =
        !isVideo && timed && byHand && !timedToVideo

    /**
     * Which kind to write the saved copy as once [newLines] settle on screen, or null for no write.
     *
     * [savedLines]/[savedSource]/[savedKind] describe the copy kept now: the override when there
     * is one, else the file's own sheet ([savedKind] null), else nothing. An automatic pick never
     * replaces the listener's pick of another provider; the same sheet is never written twice.
     * A streamed song ([localFile] false) has no offline copy: a pick there only replaces a
     * stored choice that would otherwise come back next play, and starts none.
     */
    fun kindToSave(
        savedLines: List<LyricLine>?,
        savedSource: LyricsSource?,
        savedKind: LyricsOverrideFile.Kind?,
        newLines: List<LyricLine>,
        newSource: LyricsSource?,
        byHand: Boolean,
        localFile: Boolean,
    ): LyricsOverrideFile.Kind? {
        if (newLines.none { it.text.isNotBlank() }) return null
        if (byHand && !localFile && savedKind == null) return null
        val userKept = savedKind == LyricsOverrideFile.Kind.USER
        if (!byHand && userKept && savedSource != newSource) return null
        val kind = if (byHand || userKept) LyricsOverrideFile.Kind.USER else LyricsOverrideFile.Kind.RECHECK
        val sameSheet = savedLines != null && kept(savedLines) == kept(newLines) && savedSource == newSource
        // The file's own sheet needs no override to say the same; a manual pick of it is still a choice.
        val sameKind = savedKind == kind || (savedKind == null && kind == LyricsOverrideFile.Kind.RECHECK)
        return if (sameSheet && sameKind) null else kind
    }

    /**
     * The lines as the saved copy holds them: display-only fields do not survive the file
     * (LyricsOverrideFile), and comparing them would rewrite an unchanged sheet on every play.
     */
    private fun kept(lines: List<LyricLine>): List<LyricLine> = lines.map { line ->
        line.copy(
            timingSource = null,
            wordsEstimated = false,
            background = line.background?.copy(timingSource = null, wordsEstimated = false),
        )
    }

    /** What a provider sheet taking over online does to the check a saved sheet bought. */
    enum class SavedCheck { KEEP, FORGET_VERSION, FORGET_ANSWER }

    /**
     * [syncCheckVersion] other than 0 says the saved-sheet re-check ran for this song.
     * [answerForSavedSheet]: the stored answer is keyed by the saved sheet's lines on the file's
     * clock, so it would be applied to the same lines from a provider. That answer goes, and the
     * provider sheet's own song check derives a new one; an answer for another key (the stream's)
     * is not the saved sheet's and stays. The version goes either way, so the copy saved next is
     * not taken as "already checked".
     */
    fun savedCheck(syncCheckVersion: Int, answerForSavedSheet: Boolean): SavedCheck = when {
        syncCheckVersion == 0 -> SavedCheck.KEEP
        answerForSavedSheet -> SavedCheck.FORGET_ANSWER
        else -> SavedCheck.FORGET_VERSION
    }

    /**
     * [KO] LYR7-C: whether a provider sheet about to be shown online makes the saved sheet's check
     * stale. Only a sheet that will be shown can (a miss shows the saved sheet, whose check then
     * stays). The same lines, by start time and words, are the sheet that check measured on this
     * file, so its answer and version still hold; dropping them bought a new paid check on every
     * online play. Other words, or the same words on other times (their answer key is the text, so
     * it would land on the wrong times), make it stale.
     */
    fun savedCheckStale(savedLines: List<LyricLine>?, shownLines: List<LyricLine>): Boolean =
        savedLines != null && !sameLines(savedLines, shownLines)

    /**
     * [KO] LYR7-B: the listener's own pick ([preselected]) is still the pick in force and the
     * lookup's answer came from another provider: the picked provider missed (a timeout, a lost
     * race, switched off). That answer must not stand in for the pick; the saved copy is shown.
     */
    fun pickMissed(preselected: LyricsSource?, selectedNow: LyricsSource?, answered: LyricsSource): Boolean =
        preselected != null && selectedNow == preselected && answered != preselected

    /**
     * [KO] LYR7-A: an automatic copy the words heard have already proved is another song's. The
     * online refresh saves a provider's sheet before any words are heard; once they reject it,
     * it must not come back as the saved fallback, here or on the lock screen. A listener's own
     * pick is theirs, whatever was heard.
     */
    fun rejectedCopy(videoId: String, stored: LyricsOverrideFile.Stored): Boolean =
        stored.kind == LyricsOverrideFile.Kind.RECHECK &&
            stored.source?.let { LyricsMismatch.isRejected(videoId, it, stored.lines) } == true

    /**
     * The same sheet by what a copy keeps and a rejection is keyed on: each line's start and words.
     * Display-only fields and word stamps aside, so a copy read back from the file compares equal.
     */
    fun sameLines(a: List<LyricLine>?, b: List<LyricLine>?): Boolean =
        a != null && b != null && a.size == b.size &&
            a.indices.all { a[it].timeMs == b[it].timeMs && a[it].text == b[it].text }
}
