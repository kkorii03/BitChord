package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.telegram.TelegramAuthStage
import com.music.bitchord.telegram.TelegramChat
import com.music.bitchord.telegram.TelegramClientManager
import com.music.bitchord.telegram.TelegramError

/**
 * Telegram account setup: application credentials, phone/code/2FA login,
 * current login state and logout. Opening this page is what opens the TDLib
 * client; leaving it releases the client again.
 */
@Composable
fun TelegramSettingsScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by TelegramClientManager.state.collectAsStateWithLifecycle()
    var editingCredentials by rememberSaveable { mutableStateOf(false) }
    // Secrets stay in composition only: never in saved instance state.
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var chats by remember { mutableStateOf<List<TelegramChat>>(emptyList()) }
    var loadingChats by remember { mutableStateOf(false) }
    val selectedChats by TelegramClientManager.selectedChats.collectAsStateWithLifecycle()

    LaunchedEffect(state.stage) {
        if (state.stage != TelegramAuthStage.READY) return@LaunchedEffect
        loadingChats = true
        chats = runCatching { TelegramClientManager.chats() }.getOrDefault(emptyList())
        loadingChats = false
    }

    LaunchedEffect(Unit) { TelegramClientManager.acquire("settings") }
    DisposableEffect(Unit) {
        onDispose { TelegramClientManager.release("settings") }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.telegram),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        SettingsGroup(
            header = stringResource(R.string.telegram),
            footer = stringResource(R.string.telegram_privacy),
            topSpacing = 0.dp,
        ) {
            StateRow(state.stage, state.accountName, state.error)
            if (state.error == TelegramError.GENERIC && state.stage != TelegramAuthStage.READY) {
                RowDivider()
                DestructiveRow(
                    label = stringResource(R.string.telegram_reset_session),
                    onClick = { TelegramClientManager.resetSession() },
                )
            }
            if (state.configured && !editingCredentials) {
                RowDivider()
                SettingsRow(
                    title = stringResource(R.string.telegram_change_credentials),
                    subtitle = stringResource(R.string.telegram_credentials_saved),
                    onClick = { editingCredentials = true },
                )
            }
        }

        if (!state.configured || editingCredentials) {
            SettingsGroup(header = stringResource(R.string.telegram_api_credentials)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    OutlinedTextField(
                        value = apiId,
                        onValueChange = { apiId = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.telegram_api_id)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiHash,
                        onValueChange = { apiHash = it },
                        label = { Text(stringResource(R.string.telegram_api_hash)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.telegram_api_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            TelegramClientManager.saveCredentials(apiId, apiHash)
                            editingCredentials = false
                        },
                    ) {
                        Text(stringResource(R.string.telegram_save))
                    }
                }
            }
        }

        when (state.stage) {
            TelegramAuthStage.WAIT_PHONE -> LoginGroup(
                label = stringResource(R.string.telegram_phone),
                value = phone,
                onValueChange = { phone = it },
                keyboardType = KeyboardType.Phone,
                visual = null,
                action = stringResource(R.string.telegram_continue),
                onAction = { TelegramClientManager.submitPhone(phone) },
            )

            TelegramAuthStage.WAIT_CODE -> LoginGroup(
                label = stringResource(R.string.telegram_code),
                value = code,
                onValueChange = { code = it },
                keyboardType = KeyboardType.Number,
                visual = null,
                action = stringResource(R.string.telegram_continue),
                onAction = { TelegramClientManager.submitCode(code) },
            )

            TelegramAuthStage.WAIT_PASSWORD -> LoginGroup(
                label = stringResource(R.string.telegram_password),
                value = password,
                onValueChange = { password = it },
                keyboardType = KeyboardType.Password,
                visual = PasswordVisualTransformation(),
                action = stringResource(R.string.telegram_log_in),
                onAction = { TelegramClientManager.submitPassword(password) },
            )

            TelegramAuthStage.READY -> {
                SettingsGroup(
                    header = stringResource(R.string.telegram_search_chats),
                    footer = stringResource(R.string.telegram_search_chats_footer),
                ) {
                    if (loadingChats) {
                        SettingsRow(
                            title = stringResource(R.string.telegram_chats_loading),
                        )
                    } else if (chats.isEmpty()) {
                        SettingsRow(
                            title = stringResource(R.string.telegram_chats_empty),
                        )
                    } else {
                        chats.forEachIndexed { index, chat ->
                            if (index > 0) RowDivider()
                            SettingsRow(
                                title = chat.title,
                                subtitle = chatKindLabel(chat.kind),
                                trailing = {
                                    Switch(
                                        checked = chat.id in selectedChats,
                                        onCheckedChange = { checked ->
                                            TelegramClientManager.setChatSelected(chat.id, checked)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                SettingsGroup {
                    DestructiveRow(
                        label = stringResource(R.string.telegram_log_out),
                        onClick = { TelegramClientManager.logOut() },
                    )
                }
            }

            else -> Unit
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StateRow(
    stage: TelegramAuthStage,
    accountName: String?,
    error: TelegramError?,
) {
    val text = when {
        error != null -> stringResource(errorText(error))
        stage == TelegramAuthStage.READY && !accountName.isNullOrBlank() -> {
            stringResource(R.string.telegram_connected_as, accountName)
        }
        stage == TelegramAuthStage.READY -> stringResource(R.string.telegram_status_connected)
        stage == TelegramAuthStage.STARTING -> stringResource(R.string.telegram_status_connecting)
        stage == TelegramAuthStage.WAIT_CREDENTIALS -> {
            stringResource(R.string.telegram_status_enter_credentials)
        }
        stage == TelegramAuthStage.WAIT_PHONE -> stringResource(R.string.telegram_status_phone)
        stage == TelegramAuthStage.WAIT_CODE -> stringResource(R.string.telegram_status_code)
        stage == TelegramAuthStage.WAIT_PASSWORD -> stringResource(R.string.telegram_status_password)
        stage == TelegramAuthStage.UNSUPPORTED -> stringResource(R.string.telegram_status_unsupported)
        stage == TelegramAuthStage.CLOSING -> stringResource(R.string.telegram_status_closing)
        else -> stringResource(R.string.not_connected)
    }
    SettingsRow(
        title = stringResource(R.string.telegram_status),
        subtitle = text,
    )
}

@Composable
private fun LoginGroup(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    visual: PasswordVisualTransformation?,
    action: String,
    onAction: () -> Unit,
) {
    SettingsGroup {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = visual ?: androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun errorText(error: TelegramError): Int = when (error) {
    TelegramError.INVALID_CREDENTIALS -> R.string.telegram_error_credentials
    TelegramError.INVALID_PHONE -> R.string.telegram_error_phone
    TelegramError.INVALID_CODE -> R.string.telegram_error_code
    TelegramError.CODE_EXPIRED -> R.string.telegram_error_code_expired
    TelegramError.INVALID_PASSWORD -> R.string.telegram_error_password
    TelegramError.RATE_LIMITED -> R.string.telegram_error_rate_limited
    TelegramError.UNSUPPORTED -> R.string.telegram_error_unsupported
    TelegramError.GENERIC -> R.string.telegram_error_generic
}

@Composable
private fun chatKindLabel(kind: String): String = stringResource(
    when (kind) {
        "channel" -> R.string.telegram_chat_channel
        "group" -> R.string.telegram_chat_group
        "private" -> R.string.telegram_chat_private
        else -> R.string.telegram_chat_other
    },
)
