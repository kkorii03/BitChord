package com.music.bitchord.ko

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPG-speed item 5: a doubted row's words are asked once its sixty-second
 * opening is held, while the rest of the ninety-second decode runs, and the
 * answer is read only where the old order heard the clip.
 */
class KoEarlyHearingTest {

    private fun chunk(rate: Int, seconds: Double) = FloatArray((rate * seconds).toInt()) { 0.25f }

    @Test
    fun `the clip says once that it is full, on the push that fills it`() {
        val clip = KoOpeningClip(2.0)
        val fired = AtomicInteger()
        clip.onFull = { assertSame(clip, it); fired.incrementAndGet() }
        clip.push(chunk(16_000, 1.5), 16_000)
        assertEquals("not full after 1.5 of 2 s", 0, fired.get())
        clip.push(chunk(16_000, 1.0), 16_000)
        assertEquals(1, fired.get())
        val held = clip.wav()
        clip.push(chunk(16_000, 1.0), 16_000)
        assertEquals("a later push does not say it again", 1, fired.get())
        assertEquals(2_000L, clip.durationMs)
        assertArrayEquals("nothing after the opening changes what is sent", held, clip.wav())
    }

    @Test
    fun `asked once, and only that very clip takes the answer`() = runBlocking {
        val asked = AtomicInteger()
        val hearing = KoEarlyHearing(CoroutineScope(coroutineContext + Job())) { _: KoOpeningClip ->
            asked.incrementAndGet()
            "answer"
        }
        val first = KoOpeningClip(1.0)
        val retried = KoOpeningClip(1.0)
        assertNull("nothing started yet", hearing.take(first))
        hearing.start(first)
        hearing.start(retried)
        assertNull("a retried download's clip is heard by the caller itself", hearing.take(retried))
        assertNull(hearing.take(null))
        val answer = hearing.take(first)
        assertNotNull(answer)
        assertEquals("answer", answer!!.await())
        assertEquals(1, asked.get())
        hearing.close()
        assertNull("a closed hearing gives nothing", hearing.take(first))
    }

    @Test
    fun `the verify does not wait for an unread hearing, and its end drops it`() = runBlocking {
        val sent = CompletableDeferred<Unit>()
        lateinit var hearing: KoEarlyHearing<String>
        withTimeout(5_000) {
            coroutineScope {
                hearing = KoEarlyHearing(CoroutineScope(coroutineContext + Job())) { _: KoOpeningClip ->
                    sent.complete(Unit)
                    awaitCancellation()
                }
                hearing.start(KoOpeningClip(1.0))
                sent.await()
                // Returning here (a chroma refusal) must not wait for the upload.
            }
        }
        val clip = KoOpeningClip(1.0)
        hearing.start(clip)
        hearing.close()
        hearing.start(clip)
        assertNull("nothing is started after the verify ended", hearing.take(clip))
    }

    @Test
    fun `only a doubted row heard by Groq is asked early`() {
        assertTrue(KoEarlyHearing.wanted(needsWords = true, hearer = KoVersionGuard.Hearer.GROQ))
        assertFalse("Deepgram is paid per request", KoEarlyHearing.wanted(true, KoVersionGuard.Hearer.DEEPGRAM))
        assertFalse(KoEarlyHearing.wanted(true, KoVersionGuard.Hearer.NONE))
        assertFalse("an undoubted row's hearing stays after the match", KoEarlyHearing.wanted(false, KoVersionGuard.Hearer.GROQ))
    }

    @Test
    fun `the verify reads the early answer only where it heard the clip before`() {
        val root = File(".").canonicalFile
        val verify = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt") }
            .first { it.exists() }
            .readText()
            .replace("\r\n", "\n")
        val outcome = verify.substringAfter("private suspend fun verifyOutcome(")
            .substringBefore("private fun Outcome.verifiedOrNull()")
        assertTrue(outcome.contains("youtubeWords?.takeIf { KoEarlyHearing.wanted(needsWords, hearer) }"))
        assertEquals(
            "both candidate reads tell the early hearing",
            2,
            Regex("""keepOpening = unalignedWords != null, onOpeningFull = onOpeningFull\)""").findAll(outcome).count(),
        )
        // Taken only inside the two old hearing sites: after an accepted chroma match,
        // and a doubted row with no alignment.
        assertEquals(2, Regex("""early\?\.take\(candidate\.opening\)""").findAll(outcome).count())
        val accepted = outcome.substringAfter("val words = if (youtubeWords != null && verdict.accepted) {")
            .substringBefore("} else {")
        assertTrue(accepted.contains("ahead = early?.take(candidate.opening)"))
        assertTrue(outcome.contains("KoVersionGuard.decide(verdict.accepted, needsWords, words)"))
        val finally = outcome.substringAfterLast("} finally {")
        assertTrue("every way out drops an unread answer", finally.contains("earlyHearing?.close()"))
        // The early answer still goes through the same judging lines.
        val heard = verify.substringAfter("private suspend fun heardWords(").substringBefore("return check")
        assertTrue(heard.contains("ahead.await()"))
        assertTrue(heard.contains("if (heard == null || http == null || http !in 200..299)"))
    }
}
