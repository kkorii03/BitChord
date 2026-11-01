package com.music.bitchord.playback

import com.music.bitchord.data.net.SongVersionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upcoming row must stream the song when its saved file is known to be the
 * video's sound, even when the download has no recorded provenance.
 *
 * The crossfade marks the upcoming row audible, so the current-row repair in
 * SongVersionController never runs for it. Provenance alone is not enough: a
 * legacy file can already be remembered as the video's sound or be longer than
 * the catalogue release.
 */
class SongVersionReplacementEvidenceTest {

    @Test
    fun legacyFileRememberedAsVideoSoundStreamsTheSong() {
        assertTrue(
            SongVersionPolicy.replacementStreamsSong(
                playId = "song",
                soundSourceId = null,
                online = true,
                mvSoundSuspectMs = 4_000L,
            ),
        )
    }

    @Test
    fun zeroDeltaVerdictStillStreamsTheSong() {
        assertTrue(
            SongVersionPolicy.replacementStreamsSong(
                playId = "song",
                soundSourceId = null,
                online = true,
                mvSoundSuspectMs = 0L,
            ),
        )
    }

    @Test
    fun legacyFileLongerThanCatalogueStreamsTheSong() {
        assertTrue(
            SongVersionPolicy.replacementStreamsSong(
                playId = "song",
                soundSourceId = null,
                online = true,
                fileDurationMs = 244_000L,
                catalogDurationMs = 240_000L,
            ),
        )
    }

    @Test
    fun legacyFileWithinSlackStillPlaysItsFile() {
        assertFalse(
            SongVersionPolicy.replacementStreamsSong(
                playId = "song",
                soundSourceId = null,
                online = true,
                fileDurationMs = 242_000L,
                catalogDurationMs = 240_000L,
            ),
        )
    }

    @Test
    fun offlineSuspectedFileStaysAsTheOnlySound() {
        assertFalse(
            SongVersionPolicy.replacementStreamsSong(
                playId = "song",
                soundSourceId = null,
                online = false,
                mvSoundSuspectMs = 4_000L,
            ),
        )
    }

    @Test
    fun recordedOwnProvenanceIsNotGuessedAtByLength() {
        assertFalse(
            SongVersionPolicy.replacementStreamsSong(
                playId = "song",
                soundSourceId = "song",
                online = true,
                fileDurationMs = 244_000L,
                catalogDurationMs = 240_000L,
            ),
        )
    }

    @Test
    fun recordedOtherProvenanceStillStreamsTheSong() {
        assertTrue(
            SongVersionPolicy.replacementStreamsSong(
                playId = "song",
                soundSourceId = "video",
                online = true,
            ),
        )
    }
}
