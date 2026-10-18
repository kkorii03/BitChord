package com.music.bitchord.ui.theme

import androidx.compose.ui.text.style.LineBreak

/**
 * The desktop's `LineBreak` has no phrase mode — `WordBreak` and `Strictness`
 * are Android additions — so paragraph-level breaking is the closest the
 * multiplatform API offers here.
 */
internal actual val platformPhraseLineBreak: LineBreak = LineBreak.Paragraph
