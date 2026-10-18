package com.music.bitchord.ui.theme

import androidx.compose.ui.text.style.LineBreak

/**
 * How the app's type wraps: Korean keeps its words whole across a wrap.
 *
 * Phrase-level word breaking is an Android 13+ addition to `LineBreak`, so the
 * rule itself lives in the platform actuals: the phone asks for
 * [LineBreak.WordBreak.Phrase], the desktop falls back to
 * [LineBreak.Paragraph], which is the closest the multiplatform API offers.
 */
val PhraseLineBreak: LineBreak
    get() = platformPhraseLineBreak

internal expect val platformPhraseLineBreak: LineBreak
