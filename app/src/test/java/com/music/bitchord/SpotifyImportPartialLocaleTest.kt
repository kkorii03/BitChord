package com.music.bitchord

import com.music.bitchord.ui.components.SUPPORTED_LANGUAGES
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "some songs were not added" notice is the only answer a partial Spotify
 * import gives. Without a translation every non-English build silently falls
 * back to English for it, so the key must exist in each offered language.
 */
class SpotifyImportPartialLocaleTest {

    @Test
    fun everyOfferedLanguageHasThePartialImportNotice() {
        val missing = SUPPORTED_LANGUAGES
            .map { it.tag }
            .filter { it != "en" }
            .filterNot { tag ->
                File(resDir, "values-${qualifier(tag)}/strings.xml")
                    .readText()
                    .contains("name=\"spotify_import_partial\"")
            }

        assertTrue(
            "Missing spotify_import_partial for: $missing",
            missing.isEmpty(),
        )
    }

    private fun qualifier(tag: String): String = when (tag) {
        "zh-CN" -> "zh"
        "zh-TW" -> "zh-rTW"
        else -> tag
    }

    private companion object {
        val resDir: File by lazy {
            val projectRoot = File(".").canonicalFile
            if (File(projectRoot, "app/src/main/res").exists()) {
                File(projectRoot, "app/src/main/res")
            } else {
                File(projectRoot, "src/main/res")
            }
        }
    }
}
