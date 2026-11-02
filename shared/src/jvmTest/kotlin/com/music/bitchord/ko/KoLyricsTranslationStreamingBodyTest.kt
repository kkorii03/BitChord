package com.music.bitchord.ko

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Buffer

/**
 * 11-02 heap report: a Gemini transcription kept the whole WAV, then a
 * base64 copy of it as one String in the askGemini continuation. The request
 * body must encode the audio straight into the sink and still send exactly
 * the JSON the old whole-String path sent.
 */
class KoLyricsTranslationStreamingBodyTest {

    private val audio = ByteArray(10_000) { (it * 31 and 0xff).toByte() }

    private fun body() =
        """{"contents":[{"parts":[{"inlineData":{"mimeType":"audio/wav","data":"${KoLyricsTranslation.AUDIO_DATA_PLACEHOLDER}"}},{"text":"가사"}]}]}"""

    @Test
    fun streamedAudioBodyMatchesTheOldWholeStringBody() {
        val json = body()
        val request = KoLyricsTranslation.geminiRequestBody(json, audio)
        val sent = Buffer().also { request.writeTo(it) }.readUtf8()
        val expected = json.replace(
            KoLyricsTranslation.AUDIO_DATA_PLACEHOLDER,
            java.util.Base64.getEncoder().encodeToString(audio),
        )
        assertEquals(expected, sent)
        assertEquals(expected.toByteArray(Charsets.UTF_8).size.toLong(), request.contentLength())
    }

    @Test
    fun thePlaceholderNeverReachesTheSender() {
        val request = KoLyricsTranslation.geminiRequestBody(body(), audio)
        val sent = Buffer().also { request.writeTo(it) }.readUtf8()
        assertEquals(false, sent.contains(KoLyricsTranslation.AUDIO_DATA_PLACEHOLDER))
    }

    @Test
    fun aTextOnlyBodyIsSentAsBefore() {
        val json = """{"contents":[{"parts":[{"text":"hello"}]}]}"""
        val request = KoLyricsTranslation.geminiRequestBody(json, null)
        val sent = Buffer().also { request.writeTo(it) }.readUtf8()
        assertEquals(json, sent)
        assertEquals(json.toByteArray(Charsets.UTF_8).size.toLong(), request.contentLength())
    }
}
