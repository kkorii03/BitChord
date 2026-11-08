package com.music.bitchord.ko

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.download.HqLibraryUpgrade

// Korean-fork Downloads rows for the high-quality library pass, moved out of the
// upstream DownloadManagerSheet.kt so the next upstream merge leaves that file alone.
/**
 * The high-quality pass's one line, shown at the top of the Downloads screen.
 *
 * Drawn from the persisted pass state, so it survives the sheet being closed
 * and the process being restarted. A finished pass that failed something keeps
 * its count and a retry here; otherwise nothing is shown when no pass is
 * running.
 */
@Composable
internal fun HqLibraryProgressLine(modifier: Modifier = Modifier) {
    val progress by HqLibraryUpgrade.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    when {
        progress.failedVisible -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.hq_library_failed, progress.failed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { HqLibraryUpgrade.startReplacementPass(context) }) {
                Text(stringResource(R.string.retry))
            }
        }
        progress.visible -> Text(
            text = if (progress.waiting) {
                stringResource(R.string.hq_library_waiting)
            } else {
                stringResource(
                    R.string.hq_library_progress,
                    progress.done,
                    progress.total,
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }
}

/**
 * The Downloads screen's "모두 고음질로 다시 받기" control.
 *
 * A deliberate one-off run of the replacement pass, independent of the two
 * automatic switches. It goes through [HqLibraryUpgrade.startReplacementPass],
 * which is the same worker and the same verified-pair downloads the automatic
 * pass uses — never a second implementation.
 */
@Composable
internal fun HqLibraryUpgradeAllRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val progress by HqLibraryUpgrade.progress.collectAsStateWithLifecycle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { HqLibraryUpgrade.startReplacementPass(context) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.HighQuality,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.hq_library_upgrade_all),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (progress.visible) {
                    if (progress.waiting) {
                        stringResource(R.string.hq_library_waiting)
                    } else {
                        stringResource(
                            R.string.hq_library_progress,
                            progress.done,
                            progress.total,
                        )
                    }
                } else {
                    stringResource(R.string.hq_library_upgrade_all_subtitle)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
