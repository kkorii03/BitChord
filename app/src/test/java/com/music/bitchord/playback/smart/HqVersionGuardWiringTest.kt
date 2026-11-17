package com.music.bitchord.playback.smart

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 11-17 (lane H leftovers). The pieces below sit inside the verification
 * and the cache's key path, which no unit test can run without a real player
 * cache and network, so their wiring is pinned on the source, as
 * DirectSourceGateTest does for the loader-thread settle.
 *
 *  - The risky mark is read back from the verdict store, so a play after a
 *    restart that reaches the verification without a search still needs words.
 *  - With no Groq key the candidate is heard by Deepgram's Whisper (the model the
 *    song check heard the YouTube copy with), and no gate asks for Groq alone.
 *  - A complete copy whose source row was refused is neither a sibling to fall
 *    back to nor an addon copy worth keeping once the queue lets go of it.
 */
class HqVersionGuardWiringTest {

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
    }

    @Test
    fun `the verification reads the stored risky mark and keeps the search's`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        val outcome = verify.substringAfter("private suspend fun verifyOutcome(")
            .substringBefore("private fun Outcome.verifiedOrNull()")
        assertTrue(outcome.contains("HqVerificationStore.isRisky(context, youtubeId, sourceId, trackId)"))
        assertTrue(outcome.contains("HqVerificationStore.markRisky(context, youtubeId, sourceId, trackId)"))
        // Still read before the stored verdict answers.
        assertTrue(
            outcome.indexOf("HqVerificationStore.isRisky(") <
                outcome.indexOf("HqVerificationStore.get(context, youtubeId, sourceId, trackId)"),
        )
    }

    @Test
    fun `without a Groq key the candidate is heard by Deepgram's Whisper`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        val heard = verify.substringAfter("private suspend fun heardWords(")
            .substringBefore("private fun wordsHearer()")
        assertTrue(heard.contains("Deepgram.listen(wav, youtube.language, Deepgram.MODEL_WHISPER"))
        assertFalse(
            "no words gate may ask for Groq alone",
            verify.contains("GroqWhisper.apiKey.isBlank()") || verify.contains("GroqWhisper.apiKey.isNotBlank()"),
        )
    }

    @Test
    fun `a refused copy is no sibling and no kept addon copy`() {
        val cache = source("playback/AudioCache.kt")
        val sibling = cache.substringAfter("private fun completeSibling(")
            .substringBefore("private fun completeUpgradeRendition(")
        assertEquals(2, Regex("""!isRefusedCopy\(key\)""").findAll(sibling).count())
        val keeps = cache.substringAfter("private fun keepsAddonCopy(")
            .substringBefore("private val koRetention")
        assertTrue(keeps.contains("!isRefusedCopy(key)"))
    }
}
