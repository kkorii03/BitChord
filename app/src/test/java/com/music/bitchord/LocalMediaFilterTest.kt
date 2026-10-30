package com.music.bitchord

import com.music.bitchord.data.LocalMediaRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaFilterTest {

    @Test
    fun rejectsShortAudio() {
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 29_999,
                displayName = "effect.mp3",
                path = "/storage/emulated/0/Music/effect.mp3",
            ),
        )
    }

    @Test
    fun rejectsWaveAndRecorderFiles() {
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "recording.wav",
                path = "/storage/emulated/0/Recordings/recording.wav",
            ),
        )
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "voice-note.mp3",
                path = "/storage/emulated/0/Voice Recorder/voice-note.mp3",
            ),
        )
    }

    @Test
    fun keepsSupportedMusicFiles() {
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 50_000,
                displayName = "Song.flac",
                path = "/storage/emulated/0/Music/Artist/Song.flac",
            ),
        )
    }

    @Test
    fun keepsAudioOnlyMp4AndDsd() {
        for (name in listOf("Song.mp4", "Song.dsf", "Song.dff")) {
            assertTrue(
                name,
                LocalMediaRepository.isEligibleLocalMusic(
                    durationMs = 180_000,
                    displayName = name,
                    path = "/storage/emulated/0/Music/$name",
                ),
            )
        }
    }

    @Test
    fun keepsTracksTheScannerCouldNotTime() {
        // Fragmented-MP4 ALAC scans with duration 0, high-rate DSD with NULL
        // (read back as 0): an unknown length, not a short clip.
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 0,
                displayName = "Starboy.m4a",
                path = "/storage/emulated/0/Music/Starboy.m4a",
            ),
        )
    }

    @Test
    fun `containers media3 has no extractor for stay out`() {
        // Media3 1.11 ships no WMA/ASF or AIFF extractor, so these used to
        // appear in Local Music and then fail on the first play.
        for (name in listOf("song.wma", "mix.aif", "master.aiff")) {
            assertFalse(name, LocalMediaRepository.hasLocalMusicExtension(name, "/storage/emulated/0/Music/$name"))
            assertFalse(
                name,
                LocalMediaRepository.isEligibleLocalMusic(
                    durationMs = 180_000,
                    displayName = name,
                    path = "/storage/emulated/0/Music/$name",
                ),
            )
            assertFalse(name, LocalMediaRepository.isAudioFileName(name))
        }
    }

    @Test
    fun `filter off shows wave and 3gp the music gate hides`() {
        for (name in listOf("Song.wav", "Clip.3gp", "Clip.3gpp")) {
            assertTrue(
                name,
                LocalMediaRepository.hasLocalMusicExtension(
                    displayName = name,
                    path = "/storage/emulated/0/Download/$name",
                ),
            )
        }
    }

    @Test
    fun `filter on hides wave and 3gp even under the music folder`() {
        for (name in listOf("Song.wav", "Clip.3gp", "Clip.3gpp")) {
            assertFalse(
                name,
                LocalMediaRepository.isEligibleLocalMusic(
                    durationMs = 180_000,
                    displayName = name,
                    path = "/storage/emulated/0/Music/$name",
                ),
            )
        }
    }

    @Test
    fun `download folder extras use every playable local container`() {
        for (name in listOf("Song.wav", "Clip.3gp", "Song.mp4", "Song.dsf", "Song.dff")) {
            assertTrue(name, LocalMediaRepository.isAudioFileName(name))
        }
        assertFalse(LocalMediaRepository.isAudioFileName("notes.txt"))
        assertFalse(LocalMediaRepository.isAudioFileName("clip.mkv"))
    }

    @Test
    fun `filter off keeps recordings the music gate would drop`() {
        // "Show non-music audio" off means the folder/duration heuristics are
        // skipped; only the container has to be playable. This is the half of
        // the setting that used to be a no-op.
        assertTrue(
            LocalMediaRepository.hasLocalMusicExtension(
                displayName = "voice-note.m4a",
                path = "/storage/emulated/0/Recordings/voice-note.m4a",
            ),
        )
        assertTrue(
            LocalMediaRepository.hasLocalMusicExtension(
                displayName = "memo.opus",
                path = "/storage/emulated/0/Voice Recorder/memo.opus",
            ),
        )
    }

    @Test
    fun `filter off brings back wav and 3gp audio`() {
        // The subtitle names WAV as something the filter hides, so switching
        // it off has to show a WAV file rather than keep the whole format out.
        assertTrue(
            LocalMediaRepository.hasPlayableAudioExtension(
                displayName = "memo.wav",
                path = "/storage/emulated/0/Recordings/memo.wav",
            ),
        )
        assertTrue(
            LocalMediaRepository.hasPlayableAudioExtension(
                displayName = "clip.3gp",
                path = "/storage/emulated/0/Download/clip.3gp",
            ),
        )
        // The merged container list keeps playable WAV/3GP in the music set;
        // the filter-on path still hides them through nonMusicExtensions.
        assertTrue(
            LocalMediaRepository.hasLocalMusicExtension(
                displayName = "memo.wav",
                path = "/storage/emulated/0/Recordings/memo.wav",
            ),
        )
    }

    @Test
    fun `filter off still refuses containers the app cannot play`() {
        assertFalse(
            LocalMediaRepository.hasLocalMusicExtension(
                displayName = "clip.mkv",
                path = "/storage/emulated/0/Movies/clip.mkv",
            ),
        )
        assertFalse(
            LocalMediaRepository.hasLocalMusicExtension(
                displayName = "notes.txt",
                path = "/storage/emulated/0/Download/notes.txt",
            ),
        )
    }
}
