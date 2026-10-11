package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.ui.screens.CONTENT_COUNTRIES
import com.music.bitchord.ui.screens.countryDisplayName
import dev.chrisbanes.haze.HazeState

/*
 * The fork's own settings alerts, in the same frosted UIAlertController shape
 * as everything else in Settings ([AppLanguageDialog], [ListenBrainzTokenAlert]).
 * They used to be stock Material AlertDialogs with an outlined text field and
 * radio buttons — the only Material-looking boxes left in an otherwise
 * iOS-styled app.
 */

/** How much of the screen the country list may take before it scrolls inside the card. */
private val COUNTRY_LIST_MAX_HEIGHT = 340.dp

/** The content country: one tap applies it and closes, like the app-language picker. */
@Composable
fun ContentCountryAlert(
    hazeState: HazeState,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        AlertHeading(
            title = stringResource(R.string.content_country),
            message = stringResource(R.string.content_country_description),
        )
        LazyColumn(modifier = Modifier.heightIn(max = COUNTRY_LIST_MAX_HEIGHT)) {
            items(CONTENT_COUNTRIES, key = { it }) { code ->
                AlertRule()
                CheckRow(
                    label = countryDisplayName(code),
                    selected = code.equals(selected, ignoreCase = true),
                    onClick = {
                        onSelect(code)
                        onDismiss()
                    },
                )
            }
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/**
 * The Gemini API key. Save turns Gemini translation on with a key and off
 * without one; Remove is offered only once there is a key to remove.
 */
@Composable
fun GeminiKeyAlert(
    hazeState: HazeState,
    value: String,
    onValueChange: (String) -> Unit,
    hasSavedKey: Boolean,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlertTitle(stringResource(R.string.gemini_api_key))
            AlertMessage(
                text = stringResource(R.string.gemini_api_key_description),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            PillTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = stringResource(R.string.gemini_api_key),
                isPassword = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.save),
            emphasised = true,
            onClick = onSave,
            enabled = value.isNotBlank(),
        )
        if (hasSavedKey) {
            AlertRule()
            AlertAction(label = stringResource(R.string.remove), emphasised = false, onClick = onRemove, destructive = true)
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

@Composable
private fun AlertHeading(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 19.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlertTitle(title)
        AlertMessage(text = message, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AlertTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun AlertMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

/** A row of a single-choice list: the label, and a check on the chosen one. */
@Composable
private fun CheckRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(indication = null, interactionSource = interactionSource, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
