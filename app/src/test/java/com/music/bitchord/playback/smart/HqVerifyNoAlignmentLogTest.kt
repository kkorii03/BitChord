package com.music.bitchord.playback.smart

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 11-17 HQ6. Phone 11-17 19:5x, 또 하루 qmAjTFSH5SA: two
 * "match=0% offset=0 -> unmeasured" lines with no reason, because the branch
 * where the aligner returns nothing logged only the empty verdict. The aligner
 * already records why it refused (too short, silent, no path, chance level);
 * the branch must put that on the phone log so the next report can tell a
 * broken reference from a different recording. Pinned on the source because
 * the branch needs a real decoder and network.
 */
class HqVerifyNoAlignmentLogTest {

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
    }

    @Test
    fun `an aligner with no answer logs its refusal reason`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        val branch = verify.substringAfter("if (result == null) {")
            .substringBefore("return@coroutineScope Outcome.Unavailable(transient = true)")
        assertTrue(
            "the aligner-null branch must log ChromaAlignment.lastVerdict",
            branch.contains("no alignment (\${ChromaAlignment.lastVerdict})"),
        )
    }
}
