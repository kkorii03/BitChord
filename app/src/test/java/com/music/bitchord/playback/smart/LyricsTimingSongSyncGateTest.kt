package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.SongSyncRetry
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
import kotlinx.coroutines.selects.SelectClause2
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * The 429 quiet deadline is account-wide. It has to be written before the
     * gate is released, otherwise a song waiting on that gate can acquire it
     * and send its own request in the gap.
     */
    @Test
    fun `a 429 publishes its quiet deadline before the gate is released`() = runBlocking {
        val gate = gateField.get(LyricsTiming) as Mutex
        val quietField = LyricsTiming::class.java.getDeclaredField("songQuietUntilMs").apply {
            isAccessible = true
        }
        val observing = ObservingMutex(gate) { quietField.getLong(LyricsTiming) }
        setStaticFinal(gateField, observing)
        quietField.setLong(LyricsTiming, 0L)
        val before = System.currentTimeMillis()
        try {
            val answer = LyricsTiming.listenForSongWith(
                context = context,
                videoId = "quiet-song",
                key = 7,
                audio = ByteArray(16),
                language = "en",
                generation = 0L,
                listen = { _, _ ->
                    Deepgram.Answer(
                        words = null,
                        httpCode = 429,
                        retryAfterMs = SongSyncRetry.MAX_STATED_DELAY_MS,
                    )
                },
            )

            assertNull(answer)
            val published = observing.quietAtUnlock
            assertTrue(published != null && published > before)
        } finally {
            setStaticFinal(gateField, gate)
            quietField.setLong(LyricsTiming, 0L)
        }
    }

    /** [songSyncGate] is a `val`, which Kotlin compiles to a static final field. */
    private fun setStaticFinal(field: java.lang.reflect.Field, value: Any?) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafeClass
            .getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass
            .getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, base, offset, value)
    }
}

/** A gate that reads the quiet deadline at the moment it is unlocked. */
private class ObservingMutex(
    private val delegate: Mutex,
    private val quiet: () -> Long,
) : Mutex {
    @Volatile
    var quietAtUnlock: Long? = null

    override val isLocked: Boolean get() = delegate.isLocked

    override val onLock: SelectClause2<Any?, Mutex> get() = delegate.onLock

    override suspend fun lock(owner: Any?) = delegate.lock(owner)

    override fun tryLock(owner: Any?): Boolean = delegate.tryLock(owner)

    override fun holdsLock(owner: Any): Boolean = delegate.holdsLock(owner)

    override fun unlock(owner: Any?) {
        quietAtUnlock = quiet()
        delegate.unlock(owner)
    }
}
