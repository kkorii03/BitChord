package com.music.bitchord.ko

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.ContentCountryAlert
import com.music.bitchord.ui.components.GeminiKeyAlert
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Content-country and API-key sheets added by the Korean fork. */
@Composable
internal fun KoForkAlerts(
    hazeState: HazeState,
    scope: CoroutineScope,
    showContentCountry: Boolean,
    onShowContentCountryChange: (Boolean) -> Unit,
    showGeminiKey: Boolean,
    onShowGeminiKeyChange: (Boolean) -> Unit,
    showDeepgramKey: Boolean,
    onShowDeepgramKeyChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    if (showContentCountry) {
        BackHandler { onShowContentCountryChange(false) }
        val contentCountry by AppSettings.contentCountry.collectAsStateWithLifecycle()
        ContentCountryAlert(
            hazeState = hazeState,
            selected = contentCountry,
            onSelect = AppSettings::setContentCountry,
            onDismiss = { onShowContentCountryChange(false) },
        )
    }

    if (showGeminiKey) {
        BackHandler { onShowGeminiKeyChange(false) }
        val savedKey by AppSettings.geminiApiKey.collectAsStateWithLifecycle()
        var keyInput by remember { mutableStateOf(savedKey) }
        GeminiKeyAlert(
            hazeState = hazeState,
            value = keyInput,
            onValueChange = { keyInput = it },
            hasSavedKey = savedKey.isNotBlank(),
            onSave = {
                val key = keyInput.trim()
                if (key.isNotEmpty()) {
                    AppSettings.setGeminiApiKey(key)
                    AppSettings.setGeminiTranslation(true)
                    onShowGeminiKeyChange(false)
                }
            },
            onRemove = {
                AppSettings.setGeminiApiKey("")
                AppSettings.setGeminiTranslation(false)
                onShowGeminiKeyChange(false)
            },
            onDismiss = { onShowGeminiKeyChange(false) },
        )
    }

    if (showDeepgramKey) {
        BackHandler { onShowDeepgramKeyChange(false) }
        val savedKey by AppSettings.deepgramApiKey.collectAsStateWithLifecycle()
        // The key in use, masked like Gemini's, so it is clear one is saved.
        var keyInput by remember { mutableStateOf(savedKey) }
        var keyStatus by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        val checkingText = stringResource(R.string.deepgram_key_checking)
        val badText = stringResource(R.string.deepgram_key_bad)
        val okText = stringResource(R.string.deepgram_key_ok)
        val unverifiedText = stringResource(R.string.deepgram_key_unverified)
        GeminiKeyAlert(
            hazeState = hazeState,
            value = keyInput,
            onValueChange = {
                keyInput = it
                keyStatus = null
            },
            hasSavedKey = savedKey.isNotBlank(),
            onSave = {
                val key = keyInput.trim()
                if (key.isNotEmpty() && !checking) {
                    // Asked of Deepgram before it is kept: a wrong key says so here,
                    // rather than as AI timing quietly falling back to Gemini.
                    checking = true
                    keyStatus = checkingText
                    scope.launch {
                        val accepted = com.music.bitchord.data.lyrics.Deepgram.verify(key)
                        checking = false
                        if (accepted == false) {
                            keyStatus = badText
                        } else {
                            AppSettings.setDeepgramApiKey(key)
                            onShowDeepgramKeyChange(false)
                            android.widget.Toast.makeText(
                                context,
                                if (accepted == true) okText else unverifiedText,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            status = keyStatus,
            busy = checking,
            onRemove = {
                AppSettings.setDeepgramApiKey("")
                onShowDeepgramKeyChange(false)
            },
            onDismiss = { onShowDeepgramKeyChange(false) },
            title = stringResource(R.string.deepgram_api_key),
            description = stringResource(R.string.deepgram_api_key_description),
        )
    }
}
