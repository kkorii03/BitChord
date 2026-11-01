package com.music.bitchord.ko

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.ui.screens.Chevron
import com.music.bitchord.ui.screens.LyricsSettingsCatalog
import com.music.bitchord.ui.screens.SettingsGroupScope
import com.music.bitchord.ui.screens.SettingsRow

@Composable
internal fun SettingsGroupScope.KoLyricsEntryRow(onOpenLyrics: () -> Unit) {
    val lyricsTitle = stringResource(R.string.settings_lyrics)
    row(
        lyricsTitle,
        "lyrics", "translation", "lrclib", "musixmatch", "blur",
        "timing", "sync", "gemini", "ai", "deepgram", "prefetch",
        "download", "lock screen", "lockscreen",
        "실시간 가사", "가사 소스", "번역 언어", "AI 가사 번역",
        "가사 자동 번역", "가사 타이밍", "잠금 화면 가사", "가사 싱크",
        *LyricsSettingsCatalog.keys.toTypedArray(),
    ) {
        SettingsRow(
            icon = Icons.AutoMirrored.Rounded.Notes,
            title = lyricsTitle,
            trailing = { Chevron() },
            onClick = onOpenLyrics,
        )
    }
}
