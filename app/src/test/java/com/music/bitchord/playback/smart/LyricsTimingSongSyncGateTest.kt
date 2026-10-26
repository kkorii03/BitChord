package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PowerState
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The account-wide Deepgram gate must not be held while a song check decodes
 * its file. Another song's long provider lookup or decode may not stall on it;
 * only the Deepgram call itself is serialised.
 */
class LyricsTimingSongSyncGateTest {

    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var previousKey: String
    private var previousScreenOn = true
    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }
    private val gateField = LyricsTiming::class.java.getDeclaredField("songSyncGate").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-song-gate").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
        previousKey = Deepgram.apiKey
        Deepgram.apiKey = "test-key"
        previousScreenOn = PowerState.screenOn
        PowerState.update(false)
    }

    @After
    fun tearDown() {
        Deepgram.apiKey = previousKey
        PowerState.update(previousScreenOn)
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    @Test
    fun `a file check does not wait for the Deepgram gate`() = runBlocking {
        val gate = gateField.get(LyricsTiming) as Mutex
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            gate.withLock {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()

        val lines = listOf(
            LyricLine(0L, "one"),
            LyricLine(1_000L, "two"),
            LyricLine(2_000L, "three"),
        )
        val song = Song(
            videoId = "missing-file",
            title = "Test Song",
            artist = "Test Artist",
            thumbnailUrl = null,
            isVideo = false,
            localUri = File(directory, "missing.m4a").absolutePath,
        )

        val result = try {
            withTimeout(2_000) {
                LyricsTiming.recheckDownloaded(
                    context = context,
                    song = song,
                    lines = lines,
                    providerUnknown = true,
                    title = song.title,
                    artist = song.artist,
                    durationMs = 180_000L,
                    album = null,
                    alternatives = emptyList(),
                    artistKnown = true,
                    artistAliases = emptyList(),
                )
            }
        } finally {
            release.complete(Unit)
            holder.join()
        }

        assertNull(result)
    }
}
