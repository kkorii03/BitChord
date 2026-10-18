package com.music.bitchord.ui.theme

import androidx.compose.ui.text.style.LineBreak

/**
 * Android 13+ honours phrase-level word breaking; older versions ignore the
 * setting and keep their previous behaviour, so this is safe to apply to the
 * whole scale.
 */
internal actual val platformPhraseLineBreak: LineBreak = LineBreak(
    strategy = LineBreak.Strategy.HighQuality,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Phrase,
)
