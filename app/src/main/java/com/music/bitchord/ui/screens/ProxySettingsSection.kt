package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.innertube.ProxyConnectionTest
import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyConfigError
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.ProxyValidation
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * Settings > Account & integrations > Network proxy.
 *
 * The proxy exists for one network: Korea, where music.youtube.com answers
 * without Premium while the rest of YouTube still works. The scope switch is
 * therefore part of the feature rather than a detail — music-only keeps the
 * phone's own connection for pages and media, and "all YouTube" is for when
 * the whole origin, stream downloads included, has to leave from somewhere
 * else.
 *
 * Credentials are never shown back from storage: the password field starts
 * with the saved value only because the settings screen already mirrors the
 * encrypted store in memory; it is never logged or displayed outside the
 * edit dialog.
 */
@Composable
internal fun ProxySettingsSection() {
    val mode by AppSettings.proxyMode.collectAsStateWithLifecycle()
    val host by AppSettings.proxyHost.collectAsStateWithLifecycle()
    val port by AppSettings.proxyPort.collectAsStateWithLifecycle()
    val username by AppSettings.proxyUsername.collectAsStateWithLifecycle()
    val password by AppSettings.proxyPassword.collectAsStateWithLifecycle()
    val proxyScope by AppSettings.proxyScope.collectAsStateWithLifecycle()
    val gateActive by MusicGateFallback.active.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ProxyConnectionTest.Result?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val result = testResult
    val testSubtitle = when {
        testing -> stringResource(R.string.proxy_testing)
        result is ProxyConnectionTest.Result.Ok ->
            stringResource(R.string.proxy_test_ok, result.shelves)
        result is ProxyConnectionTest.Result.Gated -> stringResource(R.string.proxy_test_gated)
        result is ProxyConnectionTest.Result.Failed ->
            result.message?.let { stringResource(R.string.proxy_test_failed, it) }
                ?: stringResource(R.string.proxy_test_no_content)
        else -> stringResource(R.string.proxy_connection_test_subtitle)
    }
    val runTest = {
        if (!testing) {
            testing = true
            testResult = null
            coroutineScope.launch {
                testResult = ProxyConnectionTest.run()
                testing = false
            }
        }
    }

    SettingsGroup(
        header = stringResource(R.string.network_proxy),
        footer = stringResource(R.string.network_proxy_footer),
    ) {
        SettingsRow(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.proxy_type),
            subtitle = stringResource(mode.labelRes()),
            onClick = { editing = true },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.CheckCircle,
            title = stringResource(R.string.proxy_gate_state),
            subtitle = stringResource(
                if (gateActive) R.string.proxy_gate_fallback else R.string.proxy_gate_direct,
            ),
        )

        if (mode != ProxyMode.OFF) {
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Dns,
                title = stringResource(R.string.proxy_server),
                subtitle = if (host.isBlank()) {
                    stringResource(R.string.proxy_server_not_set)
                } else {
                    "$host:$port"
                },
                onClick = { editing = true },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Language,
                title = stringResource(
                    if (proxyScope == ProxyScope.ALL_YOUTUBE) {
                        R.string.proxy_scope_all
                    } else {
                        R.string.proxy_scope_music
                    },
                ),
                subtitle = stringResource(
                    if (proxyScope == ProxyScope.ALL_YOUTUBE) {
                        R.string.proxy_scope_all_subtitle
                    } else {
                        R.string.proxy_scope_music_subtitle
                    },
                ),
                trailing = {
                    Switch(
                        checked = proxyScope == ProxyScope.ALL_YOUTUBE,
                        onCheckedChange = { checked ->
                            AppSettings.setProxyScope(
                                if (checked) ProxyScope.ALL_YOUTUBE else ProxyScope.MUSIC_ONLY,
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.CheckCircle,
                title = stringResource(R.string.proxy_connection_test),
                subtitle = testSubtitle,
                enabled = !testing && host.isNotBlank(),
                trailing = {
                    TextButton(
                        onClick = runTest,
                        enabled = !testing && host.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.proxy_test_action))
                    }
                },
            )
        }
    }

    if (editing) {
        ProxyConfigDialog(
            initial = ProxyConfig(
                mode = mode,
                host = host,
                port = port,
                username = username,
                password = password,
                scope = proxyScope,
            ),
            onDismiss = { editing = false },
            onSave = { config ->
                AppSettings.setProxyConfig(config)
                testResult = null
                editing = false
            },
        )
    }
}

@Composable
private fun ProxyConfigDialog(
    initial: ProxyConfig,
    onDismiss: () -> Unit,
    onSave: (ProxyConfig) -> Unit,
) {
    var mode by remember { mutableStateOf(initial.mode) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.takeIf { it > 0 }?.toString().orEmpty()) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }

    val candidate = ProxyConfig(
        mode = mode,
        host = host,
        port = port.trim().toIntOrNull() ?: 0,
        username = username,
        password = password,
        scope = initial.scope,
    )
    val error = ProxyValidation.validate(candidate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_proxy)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.proxy_type),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ProxyMode.entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { mode = entry }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = mode == entry,
                            onClick = { mode = entry },
                        )
                        Text(stringResource(entry.labelRes()))
                    }
                }

                if (mode != ProxyMode.OFF) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.proxy_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.proxy_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.proxy_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.proxy_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(error.messageRes()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(candidate) },
                enabled = error == null,
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun ProxyMode.labelRes(): Int = when (this) {
    ProxyMode.OFF -> R.string.proxy_off
    ProxyMode.HTTP -> R.string.proxy_http
    ProxyMode.SOCKS5 -> R.string.proxy_socks5
}

private fun ProxyConfigError.messageRes(): Int = when (this) {
    ProxyConfigError.HOST_MISSING -> R.string.proxy_error_host_missing
    ProxyConfigError.HOST_INVALID -> R.string.proxy_error_host_invalid
    ProxyConfigError.PORT_INVALID -> R.string.proxy_error_port_invalid
}
